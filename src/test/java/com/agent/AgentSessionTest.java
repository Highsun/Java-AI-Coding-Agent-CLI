package com.agent;

import com.agent.llm.*;
import com.agent.session.*;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentSessionTest {
    @TempDir Path root;
    private static final PrintStream QUIET = new PrintStream(OutputStream.nullOutputStream());
    private Agent agent(LLMClient client) { return new Agent(client, new ToolRegistry(root), QUIET); }

    @Test void restoresMultiToolHistoryWithTruncationAndContinuesWithoutReplay() throws Exception {
        Files.writeString(root.resolve("big.txt"), "x".repeat(9000));
        var fake = new FakeLLMClient(List.of(
                new LLMResponse(null, List.of(
                        new ToolCall("read_1", new ToolCall.Function("read_file", "{\"path\":\"big.txt\"}")),
                        new ToolCall("read_2", new ToolCall.Function("read_file", "{\"path\":\"missing\"}"))), 1, 1),
                new LLMResponse("最终回答", null, 1, 1)));
        var first = agent(fake);
        assertFalse(first.hasUnsavedHistory());
        first.run("中文 😀\n读取文件");
        assertTrue(first.hasUnsavedHistory());
        var store = new SessionStore(root);
        store.save("s", first.sessionHistory(), false); first.markSaved();
        assertFalse(first.hasUnsavedHistory());
        assertTrue(first.sessionHistory().get(2).content().contains("已截断"));
        assertTrue(first.sessionHistory().get(3).content().contains("失败"));
        var next = new FakeLLMClient(List.of(new LLMResponse("继续回答", null, 1, 1)));
        var restored = agent(next);
        restored.restoreHistory(store.load("s"));
        assertEquals(first.history(), restored.history());
        assertEquals(0, next.requests().size());
        assertFalse(restored.hasUnsavedHistory());
        restored.run("继续");
        var request = next.requests().get(0);
        assertEquals(first.history(), request.subList(0, request.size() - 1));
        assertEquals(Message.user("继续"), request.get(request.size() - 1));
        assertTrue(restored.hasUnsavedHistory());
    }

    @Test void retainsCurrentSystemPromptAndRejectsInvalidRestoreAtomically() throws Exception {
        var a = agent(new FakeLLMClient(List.of(new LLMResponse("answer", null, 0, 0))));
        a.run("before");
        var old = a.history();
        assertThrows(IOException.class, () -> a.restoreHistory(List.of(Message.tool("orphan", "x"))));
        assertEquals(old, a.history()); assertTrue(a.hasUnsavedHistory());
        a.restoreHistory(List.of(Message.user("restored"), Message.assistant("response")));
        assertEquals(Message.system(Agent.SYSTEM_PROMPT), a.history().get(0));
        assertEquals(1, a.history().stream().filter(m -> m.role().equals("system")).count());
        assertFalse(a.hasUnsavedHistory());
        a.clearHistory(); assertEquals(1, a.history().size()); assertFalse(a.hasUnsavedHistory());
    }

    @Test void restoredCallIdsAreStillProtected() throws Exception {
        var call = new ToolCall("saved_call_1", new ToolCall.Function("write_file", "{\"path\":\"bad.txt\",\"content\":\"bad\"}"));
        var a = agent(new FakeLLMClient(List.of(new LLMResponse(null, List.of(call), 1, 1))));
        a.restoreHistory(List.of(Message.assistant(null, List.of(call)), Message.tool(call.id(), "旧结果")));
        assertTrue(a.run("repeat").contains("协议错误"));
        assertFalse(Files.exists(root.resolve("bad.txt")));
    }

    @Test void llmFailureStillLeavesUnsavedHistory() {
        var a = agent((messages, tools) -> { throw new IOException("offline error"); });
        a.run("test");
        assertTrue(a.hasUnsavedHistory());
        assertEquals(3, a.history().size());
    }
}
