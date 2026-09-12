package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Searches text lines in files below a directory. */
public class SearchCodeTool {
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
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!isIgnored(path)) {
                    searchInFile(keyword, root, path, matches);
                }
            }
        }
        return matches;
    }

    private boolean isIgnored(Path path) {
        String name = path.getFileName().toString();
        return IGNORED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private void searchInFile(String keyword, Path root, Path path, List<Match> matches) {
        try {
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
