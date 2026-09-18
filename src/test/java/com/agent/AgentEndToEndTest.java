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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端离线验收：用本地假 GLM 服务器串起
 * {@code GlmClient → ToolRegistry → ReadFileTool → Agent} 的完整链路。
 *
 * <p>第一次响应要求调用 {@code read_file}，第二次响应基于 Observation 给出最终答案，
 * 对应 AT03 “单工具调用”与 AT06 “Loop 退出”。
 */
class AgentEndToEndTest {

    private static final PrintStream QUIET = new PrintStream(new ByteArrayOutputStream());

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requestBodies = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    private HttpServer server;
    private String apiUrl;

    @BeforeEach
    void startFakeGlm() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v4/chat/completions", exchange -> {
            requestBodies.add(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String body = callCount.getAndIncrement() == 0
                    ? """
                    {
                      "choices": [{"message": {"role": "assistant", "content": "",
                        "tool_calls": [{"id": "call_1", "type": "function",
                          "function": {"name": "read_file", "arguments": "{\\"path\\":\\"pom.xml\\"}"}}]}}],
                      "usage": {"prompt_tokens": 100, "completion_tokens": 20}
                    }
                    """
                    : """
                    {
                      "choices": [{"message": {"role": "assistant",
                        "content": "该项目依赖 JUnit Jupiter、OkHttp、Jackson 与 SLF4J。"}}],
                      "usage": {"prompt_tokens": 300, "completion_tokens": 30}
                    }
                    """;

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
    void shouldReadPomThenAnswer(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"),
                "<project><artifactId>agent-cli</artifactId></project>", StandardCharsets.UTF_8);

        var agent = new Agent(
                new GlmClient("test-key", apiUrl),
                new ToolRegistry(projectRoot),
                QUIET);

        String answer = agent.run("读取 pom.xml，并告诉我项目使用了哪些依赖。");

        assertEquals(2, callCount.get(), "应发生一次 Tool Call 后再请求一轮");
        assertTrue(answer.contains("OkHttp"));

        // 第二次请求必须携带完整历史：assistant(tool_calls) + tool(tool_call_id)
        JsonNode secondRequest = mapper.readTree(requestBodies.get(1));
        JsonNode messages = secondRequest.path("messages");
        JsonNode assistant = messages.get(messages.size() - 2);
        JsonNode toolResult = messages.get(messages.size() - 1);

        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
        assertEquals("tool", toolResult.path("role").asText());
        assertEquals("call_1", toolResult.path("tool_call_id").asText());
        assertTrue(toolResult.path("content").asText().contains("agent-cli"),
                "真实文件内容必须作为 Observation 回传");
    }
}
