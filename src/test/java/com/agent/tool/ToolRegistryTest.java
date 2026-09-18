package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ToolRegistry 的适配、Schema 与安全边界测试。 */
class ToolRegistryTest {

    @Test
    void shouldRegisterThreeFileToolsWithSchema(@TempDir Path tempDir) {
        var registry = new ToolRegistry(tempDir);
        var definitions = registry.getToolDefinitions();

        assertEquals(3, definitions.size());
        assertEquals(
                java.util.Set.of("list_files", "read_file", "search_code"),
                registry.toolNames());

        var readFile = definitions.stream()
                .filter(def -> "read_file".equals(def.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("object", readFile.parameters().path("type").asText());
        assertTrue(readFile.parameters().path("properties").has("path"));
        assertEquals("path", readFile.parameters().path("required").get(0).asText());
        assertFalse(readFile.description().isBlank(), "工具描述不能为空，否则模型不知道何时调用");
    }

    @Test
    void shouldExecuteReadFileReusingLab02Tool(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        var registry = new ToolRegistry(tempDir);

        String result = registry.execute("read_file", "{\"path\":\"pom.xml\"}");

        assertTrue(result.startsWith("文件内容"));
        assertTrue(result.contains("<project/>"));
    }

    @Test
    void shouldExecuteListAndSearch(@TempDir Path tempDir) throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(nested.resolve("App.java"), "class App { // TODO fix\n", StandardCharsets.UTF_8);
        var registry = new ToolRegistry(tempDir);

        String listing = registry.execute("list_files", "{\"path\":\".\"}");
        assertTrue(listing.contains("src/main/App.java"));

        String search = registry.execute(
                "search_code", "{\"keyword\":\"TODO\",\"path\":\".\"}");
        assertTrue(search.contains("src/main/App.java:1"));
    }

    @Test
    void shouldReportMissingKeywordAsObservation(@TempDir Path tempDir) {
        var registry = new ToolRegistry(tempDir);

        String result = registry.execute("search_code", "{\"keyword\":\"NOTHING\",\"path\":\".\"}");

        assertTrue(result.contains("未找到"));
    }

    @Test
    void shouldReturnErrorForUnknownTool(@TempDir Path tempDir) {
        var registry = new ToolRegistry(tempDir);

        String result = registry.execute("no_such_tool", "{}");

        assertTrue(result.startsWith("未知工具"));
        assertTrue(result.contains("read_file"), "错误信息应列出可用工具");
    }

    @Test
    void shouldReturnErrorForInvalidArgumentsJson(@TempDir Path tempDir) {
        var registry = new ToolRegistry(tempDir);

        String result = registry.execute("read_file", "not-json");

        assertTrue(result.startsWith("执行工具失败"));
    }

    @Test
    void shouldRejectPathOutsideRoot(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(tempDir.resolve("secret.txt"), "top secret", StandardCharsets.UTF_8);
        var registry = new ToolRegistry(root);

        String escapeByRelative = registry.execute("read_file", "{\"path\":\"../secret.txt\"}");
        String escapeByAbsolute = registry.execute(
                "read_file", "{\"path\":\"" + tempDir.resolve("secret.txt") + "\"}");

        assertTrue(escapeByRelative.contains("路径越界"));
        assertTrue(escapeByAbsolute.contains("路径越界"));
    }

    @Test
    void shouldTreatBlankArgumentsAsEmpty(@TempDir Path tempDir) {
        var registry = new ToolRegistry(tempDir);

        assertTrue(registry.execute("list_files", "").startsWith("文件列表"));
    }
}
