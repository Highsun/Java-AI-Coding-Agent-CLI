package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadFileToolTest {
    @Test
    void shouldReadUtf8Text(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("README.md");
        Files.writeString(file, "你好，Agent!\nhello");

        assertEquals("你好，Agent!\nhello", new ReadFileTool().readFile(file));
    }

    @Test
    void shouldThrowWhenFileIsMissing(@TempDir Path dir) {
        Path missing = dir.resolve("not-exist.txt");

        assertThrows(NoSuchFileException.class, () -> new ReadFileTool().readFile(missing));
    }

    @Test
    void shouldThrowWhenPathIsDirectory(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class, () -> new ReadFileTool().readFile(dir));
    }
}
