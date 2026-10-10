package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.agent.llm.LLMClient;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;

/** Searches text lines in files below a directory. */
public class SearchCodeTool implements Tool {
    @Override public LLMClient.ToolDef definition() {
        return ToolSchemas.define("search_code", "按字面关键字搜索代码行，返回相对文件路径、行号、内容。",
                ToolSchemas.text("keyword", "非空搜索关键字"),
                ToolSchemas.text("path", "相对项目根目录的目录路径；根目录用 ."));
    }

    @Override public ToolResult execute(JsonNode args, SafetyGuard guard) throws IOException {
        String keyword = args.get("keyword").textValue();
        if (keyword.isEmpty()) throw new IOException("搜索关键字不能为空");
        var matches = searchCode(keyword, guard.resolve(args.get("path").textValue()));
        if (matches.isEmpty()) return ToolResult.ok("搜索结果: 未找到包含 " + keyword + " 的行");
        StringBuilder result = new StringBuilder("搜索结果:\n");
        matches.forEach(match -> result.append(match.file()).append(':').append(match.line())
                .append(": ").append(match.content()).append('\n'));
        return ToolResult.ok(result.toString());
    }
    /** A single keyword match with its relative file and one-based line number. */
    public record Match(String file, int line, String content) {
    }

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".class", ".png", ".jpg", ".jpeg", ".gif", ".jar", ".zip", ".DS_Store");

    /**
     * Searches recursively for lines containing {@code keyword}.
     * Files with known binary extensions and files that cannot be read as text
     * are skipped so one problematic file does not abort the whole search.
     *
     * @param keyword literal text to find
     * @param root directory to traverse
     * @return matches in traversal order
     * @throws IOException if the directory itself cannot be traversed
     */
    public List<Match> searchCode(String keyword, Path root) throws IOException {
        List<Match> matches = new ArrayList<>();
        for (Path path : WorkspaceFiles.regularFiles(root)) {
            if (!isIgnored(path)) searchInFile(keyword, root, path, matches);
        }
        return matches;
    }

    private boolean isIgnored(Path path) {
        String name = path.getFileName().toString();
        return IGNORED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private void searchInFile(String keyword, Path root, Path path, List<Match> matches) {
        try {
            if (Files.size(path) > AtomicTextFiles.MAX_BYTES) return;
            List<String> lines = Files.readAllLines(path);
            for (int index = 0; index < lines.size(); index++) {
                String content = lines.get(index);
                if (content.contains(keyword)) {
                    matches.add(new Match(root.relativize(path).toString(), index + 1, content.trim()));
                }
            }
        } catch (IOException ignored) {
            // A single unreadable file should not prevent other files from being searched.
        }
    }
}
