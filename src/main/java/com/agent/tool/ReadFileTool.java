package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import com.agent.llm.LLMClient;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;

/** Reads UTF-8 text files. */
public class ReadFileTool implements Tool {
    @Override public LLMClient.ToolDef definition() {
        return ToolSchemas.define("read_file", "读取项目内当前 UTF-8 文本文件，最多 1 MiB。",
                ToolSchemas.text("path", "相对项目根目录的文件路径"));
    }

    @Override public ToolResult execute(JsonNode args, SafetyGuard guard) throws IOException {
        Path path = guard.resolve(args.get("path").textValue());
        if (Files.isRegularFile(path) && Files.size(path) > AtomicTextFiles.MAX_BYTES) {
            throw new IOException("文件超过 1 MiB 读取限制");
        }
        return ToolResult.ok("文件内容（" + args.get("path").textValue() + "）:\n" + readFile(path));
    }
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
