package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListFilesToolTest {
    @Test
    void shouldListRegularFilesRecursivelyAndSortThem(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("z.txt"), "z");
        Files.writeString(dir.resolve("nested/a.txt"), "a");
        Files.createDirectory(dir.resolve("empty"));

        List<String> files = new ListFilesTool().listFiles(dir);

        assertEquals(List.of("nested" + java.io.File.separator + "a.txt", "z.txt"), files);
    }
}
