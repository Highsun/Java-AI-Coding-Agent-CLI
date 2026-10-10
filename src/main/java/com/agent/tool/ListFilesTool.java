package com.agent.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import com.agent.llm.LLMClient;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;

/** Recursively lists regular files below a directory. */
public class ListFilesTool implements Tool {
    @Override public LLMClient.ToolDef definition() {
        return ToolSchemas.define("list_files", "递归列出项目文件，忽略 .git、target、本地密钥及符号链接。",
                ToolSchemas.text("path", "相对项目根目录的目录路径；根目录用 ."));
    }

    @Override public ToolResult execute(JsonNode args, SafetyGuard guard) throws IOException {
        return ToolResult.ok("文件列表:\n" + String.join("\n", listFiles(guard.resolve(args.get("path").textValue()))));
    }
    /**
     * Returns sorted paths relative to {@code root}.
     *
     * @param root directory to traverse
     * @return relative paths for all regular files below {@code root}
     * @throws IOException if the directory cannot be traversed
     */
    public List<String> listFiles(Path root) throws IOException {
        return WorkspaceFiles.regularFiles(root).stream().map(root::relativize).map(Path::toString).sorted().toList();
    }
}
