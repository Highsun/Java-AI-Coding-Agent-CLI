package com.agent.tool;

import com.agent.llm.LLMClient;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.stream.Collectors;

/** 游戏中的 list_dir：只列当前目录一层，保留独立的递归 list_files。 */
public final class ListDirTool implements Tool {
    @Override public LLMClient.ToolDef definition() {
        return ToolSchemas.define("list_dir", "列出目录直接子项，目录以 / 结尾，不递归。文件找不到时可先列目录。",
                ToolSchemas.text("path", "相对项目根目录的目录路径；根目录用 ."));
    }

    @Override public ToolResult execute(JsonNode args, SafetyGuard guard) throws IOException {
        var dir = guard.resolve(args.get("path").textValue());
        if (!Files.isDirectory(dir)) throw new IOException("目录不存在: " + args.get("path").textValue());
        try (var files = Files.list(dir)) {
            return ToolResult.ok("目录清单:\n" + files.filter(p -> !WorkspaceFiles.hidden(p) && !Files.isSymbolicLink(p))
                    .map(p -> p.getFileName() + (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                    .sorted().collect(Collectors.joining("\n")));
        }
    }
}
