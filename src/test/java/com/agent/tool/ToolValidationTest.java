package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ToolValidationTest {
    @TempDir Path root;

    @Test void shouldRejectMissingNullWrongTypesAndExtraFieldsBeforeSideEffects() {
        var executor = new ToolExecutor(new ToolRegistry(root));
        for (String json : new String[]{"{}", "[]", "null", "{\"path\":null,\"content\":\"x\"}",
                "{\"path\":42,\"content\":\"x\"}", "{\"path\":\"x\",\"content\":[]}",
                "{\"path\":\"x\",\"content\":\"x\",\"overwrite\":\"true\"}",
                "{\"path\":\"x\",\"content\":\"x\",\"extra\":true}",
                "{\"path\":\"x\",\"path\":\"y\",\"content\":\"x\"}",
                "{\"path\":\"x\",\"content\":\"x\"} {}"}) {
            assertFalse(executor.execute("write_file", json).success(), json);
            assertFalse(Files.exists(root.resolve("x")));
        }
    }

    @Test void shouldPreserveBooleanAndIntegerTypesAndRejectDuplicateRegistration() throws Exception {
        var registry = new ToolRegistry(root);
        assertTrue(registry.resolve("write_file", "{\"path\":\"x\",\"content\":\"x\",\"overwrite\":true}")
                .arguments().get("overwrite").isBoolean());
        assertEquals(2, registry.resolve("execute_command", "{\"command\":\"java -version\",\"timeout_seconds\":2}")
                .arguments().get("timeout_seconds").intValue());
        assertFalse(new ToolExecutor(registry).execute("execute_command", "{\"command\":\"java -version\",\"timeout_seconds\":1.5}").success());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new ReadFileTool()));
    }

    @Test void shouldReportUnknownToolWithoutSilentlyCorrectingItsName() {
        var result = new ToolExecutor(new ToolRegistry(root)).execute("open_file", "{\"path\":\"x\"}");
        assertFalse(result.success());
        assertTrue(result.output().startsWith("未知工具: open_file"));
    }
}
