package com.agent.tool;

import com.agent.llm.LLMClient;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;

/** 新建文本文件；覆盖已有文件必须显式传 overwrite=true。 */
public final class WriteFileTool implements Tool {
    @Override public LLMClient.ToolDef definition() {
        return ToolSchemas.define("write_file", "写入 UTF-8 文件并创建父目录。默认只新建；覆盖需显式 overwrite=true，局部修改用 edit_file。",
                ToolSchemas.text("path", "相对项目根目录的文件路径"),
                ToolSchemas.text("content", "完整文件内容，可为空字符串"),
                new ToolSchemas.Param("overwrite", "boolean", "是否允许覆盖已有文件，默认 false", false));
    }

    @Override public ToolResult execute(JsonNode args, SafetyGuard guard) throws IOException {
        var path = guard.resolve(args.get("path").textValue());
        if (Files.exists(path) && (!Files.isRegularFile(path) || !args.path("overwrite").asBoolean(false))) {
            throw new IOException("文件已存在，未授权覆盖或不是普通文件: " + args.get("path").textValue());
        }
        // 内容先验证，失败时不创建任何目录。
        String content = args.get("content").textValue();
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > AtomicTextFiles.MAX_BYTES) {
            throw new IOException("写入内容超过 1 MiB 限制");
        }
        Files.createDirectories(path.getParent());
        AtomicTextFiles.write(path, content, args.path("overwrite").asBoolean(false));
        return ToolResult.ok("写入成功: " + args.get("path").textValue()
                + "；尚未编译或运行，请调用 execute_command 验证。");
    }
}
