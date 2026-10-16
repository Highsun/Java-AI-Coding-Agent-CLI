package com.agent.cli;

import com.agent.Agent;
import com.agent.llm.*;
import com.agent.session.SessionStore;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionCommandsTest {
    @TempDir Path root;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes);
    final FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("answer", null, 0, 0)), true);
    Agent agent() { return new Agent(fake, new ToolRegistry(root), out); }

    @ParameterizedTest @ValueSource(strings = {"y", "yes", "Y", " YES ", "n", "", "invalid"})
    void confirmsExactlyOnceAndNeverSendsAnswerToModel(String answer) throws Exception {
        var a = agent(); a.run("one");
        int[] reads = {0};
        var commands = new SessionCommands(prompt -> { reads[0]++; return answer; }, out);
        assertEquals(Set.of("y", "yes").contains(answer.strip().toLowerCase(Locale.ROOT)), commands.allowDiscard(a, "退出"));
        assertEquals(1, reads[0]); assertEquals(1, fake.requests().size());
        assertEquals(3, a.history().size()); assertTrue(a.hasUnsavedHistory());
    }

    @Test void cancelsOverwriteAndLoadThenReplacesOnlyAfterConfirmation() throws Exception {
        var a = agent(); a.run("saved");
        var answers = new ArrayDeque<>(List.of("n", "n", "yes", "y"));
        var commands = new SessionCommands(prompt -> answers.removeFirst(), out);
        commands.handle("/save s", a, root);
        assertFalse(a.hasUnsavedHistory());
        byte[] original = Files.readAllBytes(new SessionStore(root).path("s"));
        a.run("unsaved"); var current = a.history();
        commands.handle("/save s", a, root);
        assertArrayEquals(original, Files.readAllBytes(new SessionStore(root).path("s")));
        assertTrue(a.hasUnsavedHistory());
        commands.handle("/load s", a, root);
        assertEquals(current, a.history()); assertTrue(a.hasUnsavedHistory());
        commands.handle("/load s", a, root);
        assertEquals(3, a.history().size()); assertFalse(a.hasUnsavedHistory());
        a.run("replacement");
        commands.handle("/save s", a, root);
        assertEquals(a.sessionHistory(), new SessionStore(root).load("s"));
        assertFalse(a.hasUnsavedHistory());
        assertEquals(3, fake.requests().size()); assertTrue(answers.isEmpty());
    }

    @Test void invalidLoadAndSaveRetainHistoryAndDoNotAskForConfirmation() throws Exception {
        var a = agent(); a.run("keep"); var old = a.history();
        var commands = new SessionCommands(prompt -> { fail("无效文件不应要求确认"); return null; }, out);
        commands.handle("/save", a, root);
        commands.handle("/save a b", a, root);
        commands.handle("/load missing", a, root);
        assertFalse(Files.exists(root.resolve(".agent")));
        var store = new SessionStore(root); store.save("bad", List.of(), false);
        Files.writeString(store.path("bad"), "{bad private text");
        commands.handle("/load bad", a, root);
        assertEquals(old, a.history()); assertTrue(a.hasUnsavedHistory());
        assertFalse(bytes.toString().contains("private text"));
        Files.createDirectory(store.path("directory"));
        commands.handle("/save directory", a, root);
        assertEquals(old, a.history()); assertTrue(a.hasUnsavedHistory());
    }

    @Test void eofCancelsAndStopsWhileCleanStateNeedsNoDiscardPrompt() throws Exception {
        var a = agent();
        var commands = new SessionCommands(prompt -> null, out);
        assertTrue(commands.allowDiscard(a, "退出")); assertFalse(commands.ended());
        a.run("unsaved");
        assertFalse(commands.allowDiscard(a, "退出")); assertTrue(commands.ended());
        commands.warnOnEnd(a);
        assertTrue(bytes.toString().contains("未自动保存"));
        assertFalse(Files.exists(root.resolve(".agent")));
    }
}
