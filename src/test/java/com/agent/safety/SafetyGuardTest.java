package com.agent.safety;

import com.agent.llm.ToolCall;
import com.agent.tool.ToolExecutor;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SafetyGuardTest {
    @TempDir Path temp;

    @Test void shouldBlockPathTraversalSymlinkAndMissingFileUnderOutsideSymlink() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret"), "secret");
        Files.createSymbolicLink(root.resolve("link"), outside);
        var guard = new SafetyGuard(root);
        assertThrows(java.io.IOException.class, () -> guard.resolve("../outside/secret"));
        assertThrows(java.io.IOException.class, () -> guard.resolve("link/secret"));
        assertThrows(java.io.IOException.class, () -> guard.resolve("link/missing/new.txt"));
        var result = new ToolExecutor(new ToolRegistry(root)).execute("write_file",
                "{\"path\":\"link/new.txt\",\"content\":\"bad\"}");
        assertFalse(result.success());
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    @Test void shouldProtectSecretsEvenThroughInsideSymlinkAndSkipThemInTraversal() throws Exception {
        Files.writeString(temp.resolve(".env"), "TOKEN=private");
        Files.writeString(temp.resolve(".env.example"), "placeholder");
        Files.createDirectories(temp.resolve(".git"));
        Files.writeString(temp.resolve(".git/config"), "private");
        Files.createSymbolicLink(temp.resolve("alias"), temp.resolve(".env"));
        var executor = new ToolExecutor(new ToolRegistry(temp));
        for (String path : new String[]{".env", ".env.local", ".git/config", "alias"}) {
            assertFalse(executor.execute("read_file", "{\"path\":\"" + path + "\"}").success());
        }
        String listing = executor.execute("list_files", "{\"path\":\".\"}").output();
        assertTrue(listing.contains(".env.example"));
        assertFalse(listing.contains(".git"));
        assertFalse(listing.contains("alias"));
        assertFalse(executor.execute("search_code", "{\"keyword\":\"private\",\"path\":\".\"}").output().contains("TOKEN"));
    }

    @Test void shouldCompareRepeatedCallsWithoutDependingOnJsonKeyOrderAndResetPerTurn() {
        var guard = new SafetyGuard(temp);
        assertTrue(guard.allow(call("1", "{\"path\":\".\",\"keyword\":\"x\"}")));
        assertTrue(guard.allow(call("2", "{\"keyword\":\"x\",\"path\":\".\"}")));
        assertFalse(guard.allow(call("3", "{\"path\":\".\",\"keyword\":\"x\"}")));
        guard.beginTurn();
        assertTrue(guard.allow(call("4", "{\"path\":\".\",\"keyword\":\"x\"}")));
    }

    private ToolCall call(String id, String args) {
        return new ToolCall(id, new ToolCall.Function("search_code", args));
    }
}
