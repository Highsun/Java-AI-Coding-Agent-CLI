package com.agent.tool;

import com.agent.llm.Message;
import com.agent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionIsolationTest {
    @TempDir Path root;
    @Test void protectsSessionFilesAndAliasesButKeepsOtherAgentFilesVisible() throws Exception {
        Path file = new SessionStore(root).save("s", List.of(Message.user("secret_marker")), false);
        Files.writeString(root.resolve(".agent/notes.txt"), "ordinary_marker");
        var registry = new ToolRegistry(root);
        String listing = registry.execute("list_files", "{\"path\":\".\"}");
        assertFalse(listing.contains("s.json")); assertTrue(listing.contains("notes.txt"));
        assertFalse(registry.execute("list_dir", "{\"path\":\".agent\"}").contains("sessions"));
        assertFalse(registry.execute("search_code", "{\"path\":\".\",\"keyword\":\"secret_marker\"}").contains("s.json"));
        assertTrue(registry.execute("read_file", "{\"path\":\".agent/notes.txt\"}").contains("ordinary_marker"));
        Files.createSymbolicLink(root.resolve("alias.json"), file);
        Files.createSymbolicLink(root.resolve("alias-dir"), file.getParent());
        for (String path : List.of(".agent/sessions/s.json", "alias.json")) {
            assertTrue(registry.execute("read_file", "{\"path\":\"" + path + "\"}").startsWith("执行工具失败"));
            assertTrue(registry.execute("write_file", "{\"path\":\"" + path + "\",\"content\":\"x\",\"overwrite\":true}").startsWith("执行工具失败"));
            assertTrue(registry.execute("edit_file", "{\"path\":\"" + path + "\",\"old_text\":\"secret_marker\",\"new_text\":\"x\"}").startsWith("执行工具失败"));
        }
        for (String path : List.of(".agent/sessions", "alias-dir")) {
            for (String tool : List.of("list_files", "list_dir", "search_code")) {
                String args = "{\"path\":\"" + path + "\"" + (tool.equals("search_code") ? ",\"keyword\":\"secret_marker\"" : "") + "}";
                assertTrue(registry.execute(tool, args).startsWith("执行工具失败"));
            }
        }
        assertEquals(List.of(Message.user("secret_marker")), new SessionStore(root).load("s"));
    }
}
