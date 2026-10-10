package com.agent;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 将课堂故障卡变成可重复的离线模型脚本；文件和 javac/java 都是真实执行。 */
class GameScenariosTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private Agent agent(FakeLLMClient fake) {
        return new Agent(fake, new ToolRegistry(root), new PrintStream(new ByteArrayOutputStream()));
    }
    private LLMResponse call(String id, String name, Map<String, Object> args) throws Exception {
        return new LLMResponse("", List.of(new ToolCall(id, new ToolCall.Function(name, mapper.writeValueAsString(args)))), 1, 1);
    }
    private List<Message> observations(Agent agent) {
        return agent.history().stream().filter(m -> m.role().equals("tool")).toList();
    }

    @Test void shouldRecoverFromMissingFileByListingDirectoryThenReadingExistingFile() throws Exception {
        Files.writeString(root.resolve("ActualConfig.java"), "class ActualConfig {}");
        var fake = new FakeLLMClient(List.of(
                call("c1", "read_file", Map.of("path", "Config.java")),
                call("c2", "list_dir", Map.of("path", ".")),
                call("c3", "read_file", Map.of("path", "ActualConfig.java")),
                LLMResponse.text("实际文件名为 ActualConfig.java。")));
        Agent agent = agent(fake);
        assertTrue(agent.run("读取配置类；如果路径不对，请找到实际文件。").contains("ActualConfig"));
        List<Message> results = observations(agent);
        assertTrue(results.get(0).content().startsWith("执行工具失败"));
        assertTrue(results.get(1).content().contains("ActualConfig.java"));
        assertTrue(results.get(2).content().contains("class ActualConfig"));
        assertEquals(List.of("c1", "c2", "c3"), results.stream().map(Message::toolCallId).toList());
        assertEquals(4, fake.requests().size());
    }

    @Test void shouldFeedUnknownToolBackThenExecuteCorrectToolWithoutNameCorrection() throws Exception {
        Files.writeString(root.resolve("Hello.java"), "class Hello {}");
        var fake = new FakeLLMClient(List.of(
                call("c1", "open_file", Map.of("path", "Hello.java")),
                call("c2", "read_file", Map.of("path", "Hello.java")), LLMResponse.text("已读取。")));
        Agent agent = agent(fake);
        agent.run("读取 Hello.java");
        assertTrue(observations(agent).get(0).content().startsWith("未知工具: open_file"));
        assertTrue(observations(agent).get(1).content().contains("class Hello"));
    }

    @Test void shouldStopThirdIdenticalCallAndKeepEveryIdPairedEvenForRemainingBatchCalls() throws Exception {
        var c1 = call("c1", "read_file", Map.of("path", "Hello.java"));
        var c2 = call("c2", "read_file", Map.of("path", "Hello.java"));
        var c3 = call("c3", "read_file", Map.of("path", "Hello.java"));
        var write = call("c4", "write_file", Map.of("path", "never.txt", "content", "never"));
        var fake = new FakeLLMClient(List.of(c1, c2,
                new LLMResponse("", List.of(c3.toolCalls().get(0), write.toolCalls().get(0)), 1, 1)));
        Agent agent = agent(fake);
        assertTrue(agent.run("重复调用陷阱").contains("连续重复调用"));
        assertEquals(3, fake.requests().size());
        assertEquals(4, observations(agent).size());
        assertTrue(observations(agent).get(2).content().contains("安全守卫"));
        assertTrue(observations(agent).get(3).content().contains("安全守卫"));
        assertFalse(Files.exists(root.resolve("never.txt")));
    }

    @Test void shouldRejectDuplicateIdsBeforeAnySideEffectInBatch() throws Exception {
        var first = call("duplicate", "write_file", Map.of("path", "a", "content", "a"));
        var second = call("duplicate", "write_file", Map.of("path", "b", "content", "b"));
        Agent agent = agent(new FakeLLMClient(List.of(new LLMResponse("",
                List.of(first.toolCalls().get(0), second.toolCalls().get(0)), 1, 1))));
        assertTrue(agent.run("创建文件").contains("协议错误"));
        assertFalse(Files.exists(root.resolve("a")));
        assertFalse(Files.exists(root.resolve("b")));
        assertTrue(observations(agent).isEmpty());
    }

    @Test void shouldRejectReusedIdAcrossTurnsButAllowItAfterClear() throws Exception {
        var fake = new FakeLLMClient(List.of(call("c1", "list_dir", Map.of("path", ".")), LLMResponse.text("第一次"),
                call("c1", "write_file", Map.of("path", "bad", "content", "bad")),
                call("c1", "list_dir", Map.of("path", ".")), LLMResponse.text("清空后")));
        Agent agent = agent(fake);
        assertEquals("第一次", agent.run("列目录"));
        assertTrue(agent.run("创建文件").contains("协议错误"));
        assertFalse(Files.exists(root.resolve("bad")));
        agent.clearHistory();
        assertEquals("清空后", agent.run("列目录"));
    }

    @Test void shouldEditCompileRunAndOnlyThenReceiveFinalAnswer() throws Exception {
        Files.writeString(root.resolve("Hello.java"), "public class Hello { public static void main(String[] a) { System.out.println(\"wrong\"); }}");
        var fake = new FakeLLMClient(List.of(
                call("read", "read_file", Map.of("path", "Hello.java")),
                call("edit", "edit_file", Map.of("path", "Hello.java", "old_text", "\"wrong\"", "new_text", "\"Hello Agent\"")),
                call("compile", "execute_command", Map.of("command", "javac Hello.java")),
                call("run", "execute_command", Map.of("command", "java Hello")),
                LLMResponse.text("修改完成，编译通过，运行输出 Hello Agent。")));
        Agent agent = agent(fake);
        agent.run("将 Hello.java 的输出改为 Hello Agent，然后重新编译并运行验证。");
        List<Message> results = observations(agent);
        assertTrue(results.get(1).content().contains("替换成功"));
        assertTrue(results.get(2).content().contains("exitCode=0"));
        assertTrue(results.get(3).content().contains("exitCode=0"));
        assertTrue(results.get(3).content().contains("stdout:\nHello Agent"));
        assertTrue(Files.readString(root.resolve("Hello.java")).contains("Hello Agent"));
        assertEquals(5, fake.requests().size());
        assertEquals(4, fake.requests().get(4).stream().filter(m -> m.role().equals("tool")).count());
    }

    @Test void shouldRecoverFromCompilerErrorByEditingRecompilingAndRunning() throws Exception {
        String broken = "public class Hello { public static void main(String[] a) { System.out.println(\"fixed\") }}";
        List<LLMResponse> script = new ArrayList<>();
        script.add(call("write", "write_file", Map.of("path", "Hello.java", "content", broken)));
        script.add(call("broken", "execute_command", Map.of("command", "javac Hello.java")));
        script.add(call("fix", "edit_file", Map.of("path", "Hello.java", "old_text", "println(\"fixed\")", "new_text", "println(\"fixed\");")));
        script.add(call("compile", "execute_command", Map.of("command", "javac Hello.java")));
        script.add(call("run", "execute_command", Map.of("command", "java Hello")));
        script.add(LLMResponse.text("编译问题已修复，输出 fixed。"));
        Agent agent = agent(new FakeLLMClient(script));
        agent.run("新建 Hello.java，输出 fixed；如编译失败请修复，再运行验证。");
        var results = observations(agent);
        assertFalse(results.get(1).content().contains("exitCode=0"));
        assertTrue(results.get(1).content().contains("stderr:"));
        assertTrue(results.get(3).content().contains("exitCode=0"));
        assertTrue(results.get(4).content().contains("stdout:\nfixed"));
    }

    @Test void shouldKeepFailureObservationAndValidHistoryOnLlmException() throws Exception {
        var fake = call("c1", "read_file", Map.of("path", "missing"));
        int[] rounds = {0};
        Agent agent = new Agent((messages, tools) -> {
            if (rounds[0]++ == 0) return fake;
            throw new java.io.IOException("model unavailable");
        }, new ToolRegistry(root), new PrintStream(new ByteArrayOutputStream()));
        assertTrue(agent.run("读取缺失文件").contains("model unavailable"));
        assertEquals("c1", observations(agent).get(0).toolCallId());
        assertEquals("assistant", agent.history().get(agent.history().size() - 1).role());
    }
}
