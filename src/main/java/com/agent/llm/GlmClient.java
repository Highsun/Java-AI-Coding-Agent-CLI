package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用 OkHttp + Jackson 调用智谱 GLM Chat Completions 接口。
 *
 * <p>职责边界：只做 HTTP 通信与协议转换，不执行本地工具、不控制 ReAct 循环。
 *
 * <p>阅读顺序按四步：把 {@link Message} 转成请求 JSON → 把工具定义放入 {@code tools}
 * → 发送带 Bearer Token 的 HTTP POST → 把响应解析成 {@link LLMResponse}。
 */
public class GlmClient implements LLMClient {

    public static final String DEFAULT_API_URL =
            "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    public static final String DEFAULT_MODEL = "glm-5.2";

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_ERROR_BODY_CHARS = 500;

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    /** 生产用法：默认 Endpoint 与默认模型。 */
    public GlmClient(String apiKey) {
        this(apiKey, DEFAULT_API_URL, DEFAULT_MODEL, new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build());
    }

    /** 测试用法：把 Endpoint 指向本地假服务器。 */
    public GlmClient(String apiKey, String apiUrl) {
        this(apiKey, apiUrl, DEFAULT_MODEL, new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build());
    }

    /** 完整构造器：允许替换模型标识与 HTTP 客户端。 */
    public GlmClient(String apiKey, String apiUrl, String model, OkHttpClient httpClient) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("GLM_API_KEY 不能为空");
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("GLM API URL 不能为空");
        }
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.httpClient = httpClient;
        this.mapper = new ObjectMapper();
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDef> tools)
            throws IOException {
        String requestJson = buildRequestJson(messages, tools);

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();

            if (!response.isSuccessful()) {
                throw new IOException(describeHttpError(response.code(), responseBody));
            }
            return parseResponse(responseBody);
        }
    }

    /** 构造请求 JSON。包级可见，便于测试直接断言请求体。 */
    String buildRequestJson(List<Message> messages, List<ToolDef> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message message : messages) {
            messagesArray.add(toMessageNode(message));
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (ToolDef tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.parameters());
            }
        }
        return requestBody.toString();
    }

    private ObjectNode toMessageNode(Message message) {
        ObjectNode messageNode = mapper.createObjectNode();
        messageNode.put("role", message.role());

        if (message.content() != null) {
            messageNode.put("content", message.content());
        }
        if (message.hasToolCalls()) {
            ArrayNode calls = messageNode.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.id());
                callNode.put("type", "function");
                ObjectNode function = callNode.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
            }
        }
        if (message.toolCallId() != null) {
            messageNode.put("tool_call_id", message.toolCallId());
        }
        return messageNode;
    }

    /** 解析响应 JSON。包级可见，便于测试直接喂样例响应。 */
    LLMResponse parseResponse(String responseBody) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (IOException e) {
            throw new IOException("GLM 响应不是合法 JSON: " + truncate(responseBody), e);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("响应中缺少 choices: " + truncate(responseBody));
        }

        JsonNode message = choices.get(0).path("message");

        // content 可能为 null（纯 Tool Call 响应），此时按空串处理，但 tool_calls 仍要解析。
        JsonNode contentNode = message.path("content");
        String content = contentNode.isMissingNode() || contentNode.isNull()
                ? "" : contentNode.asText("");

        List<ToolCall> toolCalls = null;
        JsonNode callsNode = message.path("tool_calls");
        if (callsNode.isArray() && !callsNode.isEmpty()) {
            toolCalls = new ArrayList<>();
            for (JsonNode callNode : callsNode) {
                JsonNode function = callNode.path("function");
                toolCalls.add(new ToolCall(
                        callNode.path("id").asText(),
                        new ToolCall.Function(
                                function.path("name").asText(),
                                function.path("arguments").asText("{}"))));
            }
        }

        JsonNode usage = root.path("usage");
        return new LLMResponse(
                content,
                toolCalls,
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    /**
     * 把 HTTP 状态码翻译成可诊断的错误信息：401/403 鉴权、429 限流/额度、5xx 服务端异常。
     * 错误信息保留状态码与响应体，便于按 FAQ 13.6 排查。
     */
    private String describeHttpError(int statusCode, String body) {
        String hint;
        if (statusCode == 401 || statusCode == 403) {
            hint = "鉴权失败：请检查 GLM_API_KEY 是否有效、是否带 Bearer 前缀";
        } else if (statusCode == 429) {
            hint = "请求过于频繁或额度不足，请稍后重试";
        } else if (statusCode >= 500) {
            hint = "GLM 服务端暂时异常，可稍后重试";
        } else if (statusCode == 400) {
            hint = "请求被拒绝：请检查 messages/tools 的 JSON 结构是否合法";
        } else {
            hint = "请求失败";
        }
        return "GLM HTTP " + statusCode + "（" + hint + "）: " + truncate(body);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_ERROR_BODY_CHARS
                ? text
                : text.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }
}
