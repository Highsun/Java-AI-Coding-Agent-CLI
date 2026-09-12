package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Reads UTF-8 text files. */
public class ReadFileTool {
    /**
     * Reads the complete contents of a regular file as UTF-8 text.
     *
     * @param path file to read
     * @return file contents
     * @throws IOException if the path does not identify a readable regular file
     */
    public String readFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException("不是文件或不存在: " + path);
        }
        return Files.readString(path);
    }
}
