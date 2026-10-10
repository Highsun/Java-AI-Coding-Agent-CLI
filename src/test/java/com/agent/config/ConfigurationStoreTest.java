package com.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationStoreTest {
    @TempDir Path temp;

    @Test void shouldResolveEachFieldByPrecedence() throws Exception {
        Path user = temp.resolve("user/.env"); Files.createDirectories(user.getParent());
        Files.writeString(user, "GLM_API_KEY=user-key\nGLM_MODEL=user-model\nGLM_API_URL=https://user.example/chat\n");
        Path local = temp.resolve(".env"); Files.writeString(local, "GLM_MODEL=project-model\n");
        var store = new ConfigurationStore(user, Map.of("GLM_API_KEY", "environment-key"));
        var config = store.load(local);
        assertEquals("environment-key", config.apiKey());
        assertEquals("project-model", config.model());
        assertEquals("https://user.example/chat", config.apiUrl());
        assertTrue(store.hasOverrides(local));
    }

    @Test void shouldReadBomQuotesAndIgnoreUnrecognizedExecutableText() throws Exception {
        Path user = temp.resolve(".env");
        Files.writeString(user, "\ufeff GLM_API_KEY='test-key'\nGLM_MODEL=\"test-model\"\n"
                + "IGNORED=$(touch never-execute)\n");
        var config = new ConfigurationStore(user, Map.of()).load(temp.resolve("missing"));
        assertEquals("test-key", config.apiKey()); assertEquals("test-model", config.model());
        assertFalse(Files.exists(temp.resolve("never-execute")));
    }

    @Test void shouldPersistPrivateConfigAndReloadWithoutEnvironment() throws Exception {
        Path user = temp.resolve("用户目录 with spaces/.env");
        var store = new ConfigurationStore(user, Map.of());
        var expected = new AgentConfiguration("test-secret", "http://127.0.0.1:9000/chat", "custom-model");
        store.save(expected);
        assertEquals(expected, store.load(temp.resolve("absent")));
        if (Files.getFileStore(user).supportsFileAttributeView("posix"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(user));
        assertFalse(store.hasOverrides(temp.resolve("absent")));
    }

    @Test void shouldReplaceConfigWithoutLeavingTemporaryCredentials() throws Exception {
        Path user = temp.resolve(".env"); var store = new ConfigurationStore(user, Map.of());
        store.save(new AgentConfiguration("old-secret", "https://example.com/chat", "old-model"));
        var updated = new AgentConfiguration("new-secret", "https://example.com/chat", "new-model");
        store.save(updated);
        assertEquals(updated, store.load(temp.resolve("missing")));
        assertFalse(Files.readString(user).contains("old-secret"));
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }

    @Test void shouldRejectInvalidConfigWithoutChangingExistingFile() throws Exception {
        Path user = temp.resolve(".env"); Files.writeString(user, "original");
        var store = new ConfigurationStore(user, Map.of());
        assertThrows(IllegalArgumentException.class, () -> store.save(
                new AgentConfiguration("secret\nGLM_MODEL=injected", "https://example.com/chat", "model")));
        assertEquals("original", Files.readString(user));
    }

    @Test void shouldRejectSymlinkDestination() throws Exception {
        if (!Files.getFileStore(temp).supportsFileAttributeView("posix")) return;
        Path target = temp.resolve("other"); Files.writeString(target, "original");
        Path link = Files.createSymbolicLink(temp.resolve(".env"), target);
        assertThrows(java.io.IOException.class, () -> new ConfigurationStore(link, Map.of()).save(
                new AgentConfiguration("test-key", "https://example.com/chat", "model")));
        assertEquals("original", Files.readString(target));
    }

    @Test void shouldRedactCredentialsAndRejectPlaceholders() {
        assertFalse(new AgentConfiguration("your-glm-api-key", "", "").hasApiKey());
        assertFalse(new AgentConfiguration("abc\u0000def", "", "").hasApiKey());
        assertFalse(new AgentConfiguration("abc\"def", "", "").hasApiKey());
        var valid = new AgentConfiguration("private-test-key", "https://example.com/chat", "model");
        assertTrue(valid.hasApiKey()); assertFalse(valid.toString().contains("private-test-key"));
    }

    @Test void shouldValidateModelsAndEndpointsBeforePersisting() {
        assertTrue(AgentConfiguration.validModel("provider/custom-model:1"));
        assertFalse(AgentConfiguration.validModel("model\nGLM_API_KEY=x"));
        assertTrue(AgentConfiguration.validApiUrl("https://example.com/chat"));
        assertTrue(AgentConfiguration.validApiUrl("http://127.0.0.1:1234/chat"));
        assertFalse(AgentConfiguration.validApiUrl("file:///tmp/secret"));
        assertFalse(AgentConfiguration.validApiUrl("https://key@example.com/chat"));
        assertFalse(AgentConfiguration.validApiUrl("https://example.com/chat#fragment"));
    }
}
