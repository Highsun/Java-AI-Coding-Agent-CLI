package com.agent;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ReAct 控制逻辑的离线测试：使用 FakeLLMClient，不联网。 */
class AgentTest {

    /** 过程输出重定向到缓冲区，保持测试日志干净。 */
    private static final PrintStream QUIET = new PrintStream(new ByteArrayOutputStream());

    private final ToolRegistry registry = new ToolRegistry(Path.of("."));

    @Test
    void shouldExitOnFinalAnswer() {
        var fake = new FakeLLMClient(List.of(
                new LLMResponse("你好，我是 Agent。", null, 10, 5)));

        var agent = new Agent(fake, registry, QUIET);

        assertEquals("你好，我是 Agent。", agent.run("hi"));
        assertEquals(1, fake.requests().size());
    }

    @Test
    void shouldLoopOnToolCallAndFeedObservationBack(@TempDir Path tempDir) {
        var call = new ToolCall(
                "call_1",
                new ToolCall.Function("list_files", "{\"path\":\".\"}"));
        var fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call), 100, 20),
                new LLMResponse("已列出文件。", null, 200, 10)));

        var agent = new Agent(fake, new ToolRegistry(tempDir), QUIET);
        String answer = agent.run("列出当前目录");

        assertTrue(answer.contains("已列出文件"), "最终回答应来自第二轮");
        assertEquals(2, fake.requests().size(), "有 Tool Call 时必须再请求一轮");

        List<Message> secondRequest = fake.requests().get(1);
        assertTrue(secondRequest.stream().anyMatch(message ->
                        "tool".equals(message.role()) && "call_1".equals(message.toolCallId())),
                "tool_call_id=call_1 的 Observation 必须进入下一轮请求");
        assertTrue(secondRequest.stream().anyMatch(message ->
                        "assistant".equals(message.role()) && message.hasToolCalls()),
                "发起调用的 assistant 消息不能遗漏，否则 API 会报配对错误");
    }

    @Test
    void shouldNotCrashOnUnknownTool() {
        var call = new ToolCall(
                "call_1",
                new ToolCall.Function("no_such_tool", "{}"));
        var fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call), 50, 5),
                new LLMResponse("抱歉，该工具不存在。", null, 80, 10)));

        var agent = new Agent(fake, registry, QUIET);
        assertTrue(agent.run("调用不存在的工具").contains("不存在"));
    }

    @Test
    void shouldFeedToolFailureBackAsObservation(@TempDir Path tempDir) {
        var call = new ToolCall(
                "call_9",
                new ToolCall.Function("read_file", "{\"path\":\"missing.txt\"}"));
        var fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call), 10, 5),
                new LLMResponse("该文件不存在。", null, 20, 5)));

        var agent = new Agent(fake, new ToolRegistry(tempDir), QUIET);
        agent.run("读取不存在的文件");

        String observation = fake.requests().get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(Message::content)
                .findFirst()
                .orElse("");
        assertTrue(observation.startsWith("执行工具失败"), "工具失败也要作为 Observation 回传");
    }

    @Test
    void shouldExecuteMultipleToolCallsInOneRound(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("a.txt"), "hello");
        var first = new ToolCall("call_a", new ToolCall.Function("list_files", "{\"path\":\".\"}"));
        var second = new ToolCall("call_b", new ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"));
        var fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(first, second), 30, 10),
                new LLMResponse("完成。", null, 40, 5)));

        new Agent(fake, new ToolRegistry(tempDir), QUIET).run("列出并读取");

        List<String> toolIds = fake.requests().get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(Message::toolCallId)
                .toList();
        assertEquals(List.of("call_a", "call_b"), toolIds, "多个调用都要按顺序回传结果");
    }

    @Test
    void shouldStopAtMaxIterations() {
        var call = new ToolCall("call_x", new ToolCall.Function("list_files", "{\"path\":\".\"}"));
        var fake = new FakeLLMClient(
                List.of(new LLMResponse("", List.of(call), 1, 1)), true);

        var agent = new Agent(fake, registry, QUIET);
        String answer = agent.run("无限调用");

        assertTrue(answer.contains("最大推理轮数"), "安全阀必须生效");
        assertEquals(Agent.MAX_ITERATIONS, fake.requests().size());
    }

    @Test
    void shouldClearHistoryKeepingOnlySystemPrompt() {
        var fake = new FakeLLMClient(List.of(
                new LLMResponse("第一次回答。", null, 1, 1),
                new LLMResponse("第二次回答。", null, 1, 1)));
        var agent = new Agent(fake, registry, QUIET);

        agent.run("第一个问题");
        agent.clearHistory();
        agent.run("第二个问题");

        List<Message> afterClear = fake.requests().get(1);
        assertEquals(2, afterClear.size(), "clear 后应只剩 system + 新的 user");
        assertEquals(Message.ROLE_SYSTEM, afterClear.get(0).role());
        assertEquals(Message.ROLE_USER, afterClear.get(1).role());
        assertEquals("第二个问题", afterClear.get(1).content());
    }

    @Test
    void shouldTruncateOverlongObservation(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("big.txt"),
                "x".repeat(Agent.MAX_OBSERVATION_CHARS + 500));
        var call = new ToolCall("call_big", new ToolCall.Function("read_file", "{\"path\":\"big.txt\"}"));
        var fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call), 1, 1),
                new LLMResponse("已读取。", null, 1, 1)));

        new Agent(fake, new ToolRegistry(tempDir), QUIET).run("读取大文件");

        String observation = fake.requests().get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(Message::content)
                .findFirst()
                .orElse("");
        assertTrue(observation.contains("已截断"), "超长 Observation 应被截断");
        assertFalse(observation.length() > Agent.MAX_OBSERVATION_CHARS + 200);
    }

    @Test
    void shouldSendSystemPromptAndToolDefinitionsOnFirstRequest() {
        var fake = new FakeLLMClient(List.of(new LLMResponse("好的。", null, 1, 1)));

        new Agent(fake, registry, QUIET).run("你好");

        assertEquals(Message.ROLE_SYSTEM, fake.requests().get(0).get(0).role());
        assertEquals(3, fake.toolRequests().get(0).size(), "三个 File Tool 都应注册给模型");
    }
}
