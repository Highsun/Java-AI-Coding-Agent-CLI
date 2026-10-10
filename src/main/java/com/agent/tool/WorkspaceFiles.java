package com.agent.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 递归工具共享的遍历规则：不跟随符号链接，不搜索 Git/构建目录及本地密钥。 */
final class WorkspaceFiles {
    private WorkspaceFiles() { }
    static boolean hidden(Path path) {
        String name = path.getFileName().toString();
        return name.equals(".git") || name.equals("target") || name.equals(".env")
                || (name.startsWith(".env.") && !name.equals(".env.example"));
    }

    static List<Path> regularFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("目录不存在: " + root);
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && hidden(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !hidden(file)) result.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.naturalOrder());
        return result;
    }
}
