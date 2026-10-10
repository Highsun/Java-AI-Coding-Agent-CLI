package com.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 在同目录暂存后替换，避免写到一半留下坏文件，并保留已有文件权限。 */
final class AtomicTextFiles {
    static final int MAX_BYTES = 1_048_576;
    private AtomicTextFiles() { }

    static void write(Path path, String content, boolean overwrite) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("写入内容超过 1 MiB 限制");
        Path temp = Files.createTempFile(path.getParent(), ".agent-", ".tmp");
        try {
            if (Files.exists(path) && Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(path));
            }
            Files.write(temp, bytes);
            if (!overwrite) {
                // 无 REPLACE_EXISTING：即使检查后别的进程新建了目标，也不能覆盖它。
                Files.move(temp, path);
            } else {
                try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            }
        } finally { Files.deleteIfExists(temp); }
    }
}
