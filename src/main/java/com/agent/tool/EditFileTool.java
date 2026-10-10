package com.agent.tool;

import com.agent.llm.LLMClient;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;

/** 游戏中的精确编辑：原文必须恰好出现一次，否则完全不写入。 */
public final class EditFileTool implements Tool {
    @Override public LLMClient.ToolDef definition() {
        return ToolSchemas.define("edit_file", "替换现有 UTF-8 文件中的唯一一处原文。未匹配或多处匹配时不修改文件。",
                ToolSchemas.text("path", "相对项目根目录的文件路径"),
                ToolSchemas.text("old_text", "已读取的非空原文，必须恰好出现一次"),
                ToolSchemas.text("new_text", "替换文本，可为空字符串"));
    }

    @Override public ToolResult execute(JsonNode args, SafetyGuard guard) throws IOException {
        var path = guard.resolve(args.get("path").textValue());
        if (!Files.isRegularFile(path)) throw new IOException("文件不存在: " + args.get("path").textValue());
        if (Files.size(path) > AtomicTextFiles.MAX_BYTES) throw new IOException("文件超过 1 MiB 编辑限制");
        String old = args.get("old_text").textValue();
        if (old.isEmpty()) throw new IOException("old_text 不能为空");
        String content = Files.readString(path);
        int first = content.indexOf(old);
        if (first < 0) throw new IOException("原文未匹配，文件未修改");
        if (content.indexOf(old, first + 1) >= 0) throw new IOException("原文多处匹配，文件未修改");
        String updated = content.substring(0, first) + args.get("new_text").textValue()
                + content.substring(first + old.length());
        AtomicTextFiles.write(path, updated, true);
        return ToolResult.ok("替换成功: " + args.get("path").textValue()
                + "；替换次数=1；旧编译结果不能证明当前源码正确，请重新编译并运行。");
    }
}
