package com.agent;

import com.agent.llm.GlmClient;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端离线验收：用本地假 GLM 服务器串起
 * {@code GlmClient → ToolRegistry → Lab02 File Tool → Agent} 的完整链路。
 *
 * <p>两次验收分别对应实验的 AT03（单工具调用）与 AT04（多步调用），
 * 同时覆盖 AT06（Loop 退出）。
 */
class AgentEndToEndTest {

    private static final PrintStream QUIET = new PrintStream(new ByteArrayOutputStream());

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> script = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();

    private HttpServer server;
    private String apiUrl;

    @BeforeEach
    void startFakeGlm() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v4/chat/completions", exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);

            // 按脚本顺序返回；脚本用尽后重复最后一条（用于观察是否发生多余请求）
            int index = Math.min(requestBodies.size() - 1, script.size() - 1);
            byte[] bytes = script.get(index).getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v4/chat/completions";
    }

    @AfterEach
    void stopFakeGlm() {
        server.stop(0);
    }

    @Test
    void shouldReadPomThenAnswerAt03(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"),
                "<project><artifactId>agent-cli</artifactId></project>", StandardCharsets.UTF_8);
        script.add(toolCallResponse("call_1", "read_file", "{\"path\":\"pom.xml\"}"));
        script.add(textResponse("该项目依赖 JUnit Jupiter、OkHttp、Jackson 与 SLF4J。"));

        String answer = runAgent(projectRoot, "读取 pom.xml，并告诉我项目使用了哪些依赖。");

        assertEquals(2, requestBodies.size(), "应发生一次 Tool Call 后再请求一轮");
        assertTrue(answer.contains("OkHttp"));

        JsonNode messages = messagesOfLastRequest();
        JsonNode assistant = messages.get(messages.size() - 2);
        JsonNode toolResult = messages.get(messages.size() - 1);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
        assertEquals("tool", toolResult.path("role").asText());
        assertEquals("call_1", toolResult.path("tool_call_id").asText());
        assertTrue(toolResult.path("content").asText().contains("agent-cli"),
                "真实文件内容必须作为 Observation 回传");
    }

    @Test
    void shouldSearchThenReadThenAnswerAt04(@TempDir Path projectRoot) throws Exception {
        Path source = Files.createDirectories(projectRoot.resolve("src/main/java/com/agent"));
        Files.writeString(source.resolve("Agent.java"),
                "// TODO 拆分 ReAct 循环\npublic class Agent {}\n", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);

        // 第一轮：搜索 TODO；第二轮：读取命中文件；第三轮：给出最终答案
        script.add(toolCallResponse("call_s", "search_code",
                "{\"keyword\":\"TODO\",\"path\":\".\"}"));
        script.add(toolCallResponse("call_r", "read_file",
                "{\"path\":\"src/main/java/com/agent/Agent.java\"}"));
        script.add(textResponse("TODO 位于 Agent.java 的类注释前，提示拆分 ReAct 循环。"));

        String answer = runAgent(projectRoot, "搜索 TODO，并读取其中一个文件。");

        assertEquals(3, requestBodies.size(), "AT04 至少需要 search_code → read_file 两次调用");
        assertTrue(answer.contains("TODO"));

        List<JsonNode> messages = messageNodes();
        List<String> observationOrder = messages.stream()
                .filter(message -> "tool".equals(message.path("role").asText()))
                .map(message -> message.path("tool_call_id").asText())
                .toList();
        assertEquals(List.of("call_s", "call_r"), observationOrder, "两次调用的结果都要回传");

        String readObservation = messages.get(messages.size() - 1).path("content").asText();
        assertTrue(readObservation.contains("拆分 ReAct 循环"), "第二次 Observation 应是文件内容");
    }

    private String runAgent(Path projectRoot, String userInput) {
        var agent = new Agent(
                new GlmClient("test-key", apiUrl),
                new ToolRegistry(projectRoot),
                QUIET);
        return agent.run(userInput);
    }

    private JsonNode messagesOfLastRequest() throws Exception {
        return mapper.readTree(requestBodies.get(requestBodies.size() - 1)).path("messages");
    }

    /** {@code ArrayNode} 是 {@code Iterable}，这里转成 List 便于使用 Stream 断言。 */
    private List<JsonNode> messageNodes() throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        messagesOfLastRequest().forEach(nodes::add);
        return nodes;
    }

    private static String toolCallResponse(String id, String name, String argumentsJson) {
        String arguments = argumentsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "choices": [{"message": {"role": "assistant", "content": "",
                    "tool_calls": [{"id": "%s", "type": "function",
                      "function": {"name": "%s", "arguments": "%s"}}]}}],
                  "usage": {"prompt_tokens": 100, "completion_tokens": 20}
                }
                """.formatted(id, name, arguments);
    }

    private static String textResponse(String content) {
        return """
                {
                  "choices": [{"message": {"role": "assistant", "content": "%s"}}],
                  "usage": {"prompt_tokens": 300, "completion_tokens": 30}
                }
                """.formatted(content);
    }
}
