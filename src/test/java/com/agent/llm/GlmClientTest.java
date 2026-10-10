package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlmClient 的离线测试：用本地 {@link HttpServer} 冒充 GLM Endpoint。
 *
 * <p>这样可以验证请求构造、鉴权头、状态码处理与响应解析，而不需要真实 API Key，
 * 也不访问外网。
 */
class GlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<HttpExchange> captured = new ArrayList<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    private HttpServer server;
    private String apiUrl;
    private int status = 200;
    private String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v4/chat/completions", exchange -> {
            captured.add(exchange);
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));

            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v4/chat/completions";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldParseFinalAnswerWithUsage() throws Exception {
        responseBody = """
                {
                  "choices": [{"message": {"role": "assistant", "content": "你好，我是 GLM。"}}],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 7}
                }
                """;

        LLMResponse response = new GlmClient("test-key", apiUrl)
                .chat(List.of(Message.user("你好")), List.of());

        assertEquals("你好，我是 GLM。", response.content());
        assertFalse(response.hasToolCalls());
        assertEquals(12, response.promptTokens());
        assertEquals(7, response.completionTokens());
        assertEquals("Bearer test-key", lastAuthorization.get());
    }

    @Test
    void shouldParseToolCallsWhenContentIsNull() throws Exception {
        responseBody = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_abc123",
                        "type": "function",
                        "function": {"name": "read_file", "arguments": "{\\"path\\":\\"pom.xml\\"}"}
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 180, "completion_tokens": 20}
                }
                """;

        LLMResponse response = new GlmClient("test-key", apiUrl)
                .chat(List.of(Message.user("读取 pom.xml")), List.of());

        assertTrue(response.hasToolCalls(), "content=null 时仍必须解析出 tool_calls");
        assertEquals("", response.content());
        assertEquals(1, response.toolCalls().size());
        ToolCall call = response.toolCalls().get(0);
        assertEquals("call_abc123", call.id());
        assertEquals("read_file", call.name());
        assertEquals("{\"path\":\"pom.xml\"}", call.arguments());
    }

    @Test
    void shouldSerializeMessagesAndToolDefinitions() throws Exception {
        responseBody = """
                {"choices": [{"message": {"role": "assistant", "content": "ok"}}]}
                """;

        var registry = new com.agent.tool.ToolRegistry(java.nio.file.Path.of("."));
        var client = new GlmClient("test-key", apiUrl);
        client.chat(
                List.of(
                        Message.system("system prompt"),
                        Message.user("读取 pom.xml"),
                        Message.assistant("", List.of(new ToolCall(
                                "call_1",
                                new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}")))),
                        Message.tool("call_1", "文件内容:\n<project/>")),
                registry.getToolDefinitions());

        JsonNode request = mapper.readTree(lastRequestBody.get());
        assertEquals(GlmClient.DEFAULT_MODEL, request.path("model").asText());

        JsonNode messages = request.path("messages");
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("assistant", messages.get(2).path("role").asText());
        assertEquals("call_1", messages.get(2).path("tool_calls").get(0).path("id").asText());
        assertFalse(messages.get(2).has("tool_call_id"), "assistant 消息不应带 tool_call_id");
        assertEquals("tool", messages.get(3).path("role").asText());
        assertEquals("call_1", messages.get(3).path("tool_call_id").asText());
        assertFalse(messages.get(3).has("tool_calls"), "tool 消息不应带 tool_calls");

        JsonNode tools = request.path("tools");
        assertEquals(7, tools.size());
        assertEquals("function", tools.get(0).path("type").asText());
        assertNotNull(tools.get(0).path("function").path("parameters").path("properties").get("path"));
        assertFalse(lastRequestBody.get().contains("test-key"), "请求体不能包含 API Key");
    }

    @Test
    void shouldTranslateHttpErrors() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"invalid api key\"}}";
        IOException unauthorized = assertThrows(IOException.class, () -> chatOnce());
        assertTrue(unauthorized.getMessage().contains("401"));
        assertTrue(unauthorized.getMessage().contains("鉴权"));
        assertTrue(unauthorized.getMessage().contains("invalid api key"), "应保留响应体便于排查");

        status = 429;
        IOException rateLimited = assertThrows(IOException.class, () -> chatOnce());
        assertTrue(rateLimited.getMessage().contains("429"));

        status = 503;
        IOException serverError = assertThrows(IOException.class, () -> chatOnce());
        assertTrue(serverError.getMessage().contains("503"));
    }

    @Test
    void shouldFailWhenChoicesMissing() {
        responseBody = "{\"error\":\"boom\"}";
        IOException error = assertThrows(IOException.class, () -> chatOnce());
        assertTrue(error.getMessage().contains("choices"));
    }

    @Test
    void shouldRejectBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new GlmClient("   "));
        assertThrows(IllegalArgumentException.class, () -> new GlmClient(null));
    }

    private void chatOnce() throws IOException {
        new GlmClient("test-key", apiUrl).chat(List.of(Message.user("hi")), List.of());
    }
}
