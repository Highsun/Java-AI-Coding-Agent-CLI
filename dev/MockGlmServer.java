import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 离线验收用的假 GLM Endpoint：不联网、不需要真实 API Key，
 * 但完整走一遍 Chat Completions + Tool Calling 协议，用来验收 AT01～AT07。
 *
 * <p>启动（源码模式，classpath 借用 fat jar 里的 Jackson）：
 *
 * <pre>
 * java -cp target/agent-cli.jar dev/MockGlmServer.java 8099
 * </pre>
 *
 * <p>再另开一个终端运行 CLI：
 *
 * <pre>
 * GLM_API_KEY=dummy \
 * GLM_API_URL=http://127.0.0.1:8099/v4/chat/completions \
 * java -jar target/agent-cli.jar
 * </pre>
 *
 * <p>判定规则（脚本化，模拟一个听话的模型），只看历史的最后一条消息：
 * <ol>
 *   <li>最后一条是 tool 结果且来自 read_file → 给出最终答案；结果是错误信息时说明失败原因；</li>
 *   <li>最后一条是 tool 结果且来自 search_code → 读取第一个命中文件；没有命中就给出最终答案；</li>
 *   <li>最后一条是 user 输入 → 按关键词选择 read_file / search_code / 直接回答。</li>
 * </ol>
 *
 * <p>最终答案会附带当前上下文消息数，方便在终端里直接观察历史增长与 {@code clear} 的效果。
 */
public final class MockGlmServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int requestCount;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8099;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v4/chat/completions", exchange -> {
            String response;
            try {
                String requestBody = new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                response = handle(requestBody);
            } catch (Exception e) {
                // 假服务器只用于本地验收，出错时直接把原因写回响应体，方便排查
                response = "{\"error\":\"" + e.getClass().getSimpleName() + ": "
                        + e.getMessage() + "\"}";
                System.err.println("[mock] 处理失败: " + e);
            }

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        System.out.println("[mock] 假 GLM 已启动: http://127.0.0.1:" + port + "/v4/chat/completions");
    }

    private static String handle(String requestBody) throws Exception {
        JsonNode root = MAPPER.readTree(requestBody);
        ArrayNode messages = (ArrayNode) root.path("messages");
        int index = ++requestCount;

        String lastRole = messages.isEmpty()
                ? "-" : messages.get(messages.size() - 1).path("role").asText();
        System.err.printf("[mock] 请求 #%d：messages=%d，最后一条角色=%s%n",
                index, messages.size(), lastRole);

        String visibleHistory = contextHint(messages);
        JsonNode last = messages.isEmpty() ? null : messages.get(messages.size() - 1);

        // 1) 上一条是工具结果：基于这次 Observation 给出最终答案，或继续下一步调用
        if (last != null && "tool".equals(last.path("role").asText())) {
            String observation = last.path("content").asText();
            String toolName = lastCallName(messages, messages.size() - 1);

            if (observation.startsWith("执行工具失败") || observation.startsWith("未知工具")) {
                return textResponse("工具 " + toolName + " 调用失败：" + observation
                        + "。已把失败原因告知用户，会话没有中断。" + visibleHistory);
            }
            if (toolName.equals("search_code")) {
                String hit = firstHitPath(observation);
                if (hit != null) {
                    return toolCallResponse("call_read_" + index, "read_file",
                            "{\"path\":\"" + hit + "\"}");
                }
                return textResponse("没有搜索到相关内容，无法进一步读取文件。" + visibleHistory);
            }
            return textResponse("读取完成，" + toolName + " 返回的内容摘要："
                    + summarize(observation) + visibleHistory);
        }

        // 2) 用户刚提出问题：按用户输入决定是否调用工具
        String userInput = lastUserContent(messages);
        if (userInput.contains("pom.xml") || userInput.contains("依赖")) {
            return toolCallResponse("call_read_pom_" + index, "read_file", "{\"path\":\"pom.xml\"}");
        }
        if (userInput.contains("TODO") || userInput.contains("搜索")) {
            return toolCallResponse("call_search_" + index, "search_code",
                    "{\"keyword\":\"TODO\",\"path\":\".\"}");
        }
        if (userInput.contains("不存在") || userInput.contains("missing")) {
            return toolCallResponse("call_read_missing_" + index, "read_file",
                    "{\"path\":\"no-such-file.txt\"}");
        }
        return textResponse("你好！我是 Java 编程助手 Agent，可以直接回答，也可以读取和搜索你的项目文件。"
                + visibleHistory);
    }

    /** 找到某条 tool 消息所对应的工具名：向前找最近一条带 tool_calls 的 assistant 消息。 */
    private static String lastCallName(ArrayNode messages, int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            JsonNode calls = messages.get(i).path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                return calls.get(calls.size() - 1).path("function").path("name").asText();
            }
        }
        return "tool";
    }

    private static String lastUserContent(ArrayNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).path("role").asText())) {
                return messages.get(i).path("content").asText();
            }
        }
        return "";
    }

    /** 从 search_code 的 Observation 里取第一条命中的相对路径。 */
    private static String firstHitPath(String observation) {
        // Observation 每行格式为「相对路径:行号: 内容」
        var matcher = java.util.regex.Pattern
                .compile("^(.+?):(\\d+): ", java.util.regex.Pattern.MULTILINE)
                .matcher(observation);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String summarize(String text) {
        String flat = text.replace("\n", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    private static String contextHint(ArrayNode messages) {
        return "（本次请求上下文消息数：" + messages.size() + "）";
    }

    private static String toolCallResponse(String id, String name, String arguments) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("role", "assistant");
        message.put("content", "");

        ArrayNode calls = message.putArray("tool_calls");
        ObjectNode call = calls.addObject();
        call.put("id", id);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", name);
        function.put("arguments", arguments);

        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 120);
        usage.put("completion_tokens", 24);
        return root.toString();
    }

    private static String textResponse(String content) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("role", "assistant");
        message.put("content", content);

        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 260);
        usage.put("completion_tokens", 40);
        return root.toString();
    }
}
