package com.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EditingToolsTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolResult execute(String name, Map<String, Object> args) throws Exception {
        return new ToolExecutor(new ToolRegistry(root)).execute(name, mapper.writeValueAsString(args));
    }

    @Test void shouldCreateUtf8FileAndParentDirectoriesThenReadActualContent() throws Exception {
        assertTrue(execute("write_file", Map.of("path", "src/你好.txt", "content", "中文😀\n")).success());
        assertEquals("中文😀\n", Files.readString(root.resolve("src/你好.txt")));
        assertTrue(execute("read_file", Map.of("path", "src/你好.txt")).output().contains("中文😀"));
    }

    @Test void shouldRequireExplicitOverwriteAndPreserveOriginalOnRefusal() throws Exception {
        Files.writeString(root.resolve("a.txt"), "original");
        assertFalse(execute("write_file", Map.of("path", "a.txt", "content", "new")).success());
        assertEquals("original", Files.readString(root.resolve("a.txt")));
        assertTrue(execute("write_file", Map.of("path", "a.txt", "content", "", "overwrite", true)).success());
        assertEquals("", Files.readString(root.resolve("a.txt")));
    }

    @Test void shouldReplaceExactlyOneMatchAndPreserveOtherText() throws Exception {
        Files.writeString(root.resolve("Hello.java"), "prefix 你好 suffix\n");
        assertTrue(execute("edit_file", Map.of("path", "Hello.java", "old_text", "你好", "new_text", "世界")).success());
        assertEquals("prefix 世界 suffix\n", Files.readString(root.resolve("Hello.java")));
        try (var files = Files.list(root)) { assertEquals(1, files.count(), "不能留下暂存文件"); }
    }

    @Test void shouldRejectMissingEmptyAbsentAndAmbiguousOriginalWithoutWriting() throws Exception {
        Files.writeString(root.resolve("a.txt"), "aaa");
        for (String old : new String[]{"", "missing", "aa"}) {
            assertFalse(execute("edit_file", Map.of("path", "a.txt", "old_text", old, "new_text", "new")).success());
            assertEquals("aaa", Files.readString(root.resolve("a.txt")));
        }
        assertFalse(execute("edit_file", Map.of("path", "missing", "old_text", "a", "new_text", "b")).success());
    }

    @Test void shouldRejectOversizedWriteWithoutCreatingDirectories() throws Exception {
        assertFalse(execute("write_file", Map.of("path", "nested/big", "content", "x".repeat(1_048_577))).success());
        assertFalse(Files.exists(root.resolve("nested")));
    }

    @Test void shouldListOneLevelSeparatelyFromRecursiveFiles() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {}");
        String direct = execute("list_dir", Map.of("path", ".")).output();
        assertTrue(direct.contains("src/"));
        assertFalse(direct.contains("A.java"));
        assertTrue(execute("list_files", Map.of("path", ".")).output().contains("src/A.java"));
        assertFalse(execute("list_dir", Map.of("path", "missing")).success());
    }
}
