package com.agent.cli;

import com.agent.config.AgentConfiguration;
import com.agent.config.ConfigurationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationWizardTest {
    @TempDir Path temp;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private ConfigurationWizard wizard(ConfigurationStore store, String... answers) {
        var queue = new LinkedList<>(Arrays.asList(answers));
        return new ConfigurationWizard(new ConfigurationWizard.Input() {
            public String line(String prompt) { return queue.removeFirst(); }
            public String secret(String prompt) { return queue.removeFirst(); }
        }, new PrintStream(output, true, StandardCharsets.UTF_8), store);
    }

    @Test void shouldGuideFirstSetupSaveAndNeverPrintKey() throws Exception {
        var store = new ConfigurationStore(temp.resolve("user/.env"), Map.of());
        var config = wizard(store, "private-secret", "", "", "").configure(AgentConfiguration.defaults(), false);
        assertNotNull(config); assertEquals("private-secret", config.apiKey());
        assertEquals(config, store.load(temp.resolve("missing")));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("private-secret"));
    }

    @Test void shouldRetryBlankKeyAndInvalidChoicesThenUseSessionOnly() throws Exception {
        var store = new ConfigurationStore(temp.resolve("user/.env"), Map.of());
        var config = wizard(store, "", "your-glm-api-key", "valid-secret", "9", "2", "", "2", "custom-model",
                "bad-url", "http://127.0.0.1:9000/chat", "maybe", "n").configure(AgentConfiguration.defaults(), false);
        assertEquals("custom-model", config.model());
        assertEquals("http://127.0.0.1:9000/chat", config.apiUrl());
        assertFalse(Files.exists(store.userFile()));
    }

    @Test void shouldKeepExistingKeyOnEmptyInput() throws Exception {
        var store = new ConfigurationStore(temp.resolve("user/.env"), Map.of());
        var current = new AgentConfiguration("existing-secret", "https://example.com/chat", "existing-model");
        var configured = wizard(store, "", "", "", "n").configure(current, false);
        assertEquals(current, configured);
    }

    @Test void shouldChangeModelWithoutPromptingForKeyOrEndpoint() throws Exception {
        var store = new ConfigurationStore(temp.resolve("user/.env"), Map.of());
        var current = new AgentConfiguration("existing-secret", "https://example.com/chat", "old-model");
        var configured = wizard(store, "2", "new-model", "y").configure(current, true);
        assertEquals("existing-secret", configured.apiKey()); assertEquals("new-model", configured.model());
        assertEquals(configured, store.load(temp.resolve("missing")));
    }

    @Test void shouldCancelWithoutOverwritingExistingConfig() throws Exception {
        var store = new ConfigurationStore(temp.resolve("user/.env"), Map.of());
        var current = new AgentConfiguration("existing-secret", "https://example.com/chat", "old-model");
        store.save(current);
        assertNull(wizard(store, "new-secret", (String)null).configure(current, false));
        assertEquals(current, store.load(temp.resolve("missing")));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("new-secret"));
    }

    @Test void shouldContinueSessionWhenSavingFails() throws Exception {
        Path file = temp.resolve("not-a-directory"); Files.writeString(file, "untouched");
        var store = new ConfigurationStore(file.resolve(".env"), Map.of());
        var config = wizard(store, "test-key", "", "", "").configure(AgentConfiguration.defaults(), false);
        assertNotNull(config); assertTrue(config.hasApiKey());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("本次会话仍可继续"));
        assertEquals("untouched", Files.readString(file));
    }
}
