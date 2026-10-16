package com.agent.session;

import com.agent.llm.Message;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 本地会话文件存储；不读取配置、不访问模型、不执行历史内容。 */
public final class SessionStore {
    // 可注入实际文件操作，确定性验证权限失败、写入失败及提交冲突。
    interface FileIO {
        default InputStream open(Path file) throws IOException {
            return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        }
        default void write(Path file, byte[] bytes) throws IOException { Files.write(file, bytes); }
        default void commit(Path temp, Path file, boolean overwrite) throws IOException {
            if (overwrite) Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp, file); // 不允许替换刚出现的同名文件。
        }
    }
    private final Path root;
    private final FileIO io;
    private final SessionCodec codec = new SessionCodec();

    public SessionStore(Path root) throws IOException { this(root, new FileIO() { }); }
    SessionStore(Path root, FileIO io) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IOException("项目根目录不是目录");
        this.io = io;
    }

    public Path path(String name) throws IOException {
        if (name == null || name.codePointCount(0, name.length()) < 1
                || name.codePointCount(0, name.length()) > 64
                || !name.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                    || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '-')) {
            throw new IOException("会话名须为 1～64 个汉字、英文字母、数字、下划线或连字符，不带扩展名");
        }
        Path file = root.resolve(".agent").resolve("sessions").resolve(name + ".json");
        check(file);
        return file;
    }

    public boolean exists(String name) throws IOException { return Files.exists(path(name), LinkOption.NOFOLLOW_LINKS); }

    public List<Message> load(String name) throws IOException {
        Path file = path(name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("当前项目找不到该会话");
        try (InputStream input = io.open(file)) {
            return codec.decode(input.readNBytes(SessionCodec.MAX_BYTES + 1));
        }
    }

    public Path save(String name, List<Message> messages, boolean overwrite) throws IOException {
        Path file = path(name);
        byte[] bytes = codec.encode(messages); // 校验失败时不创建目录。
        Files.createDirectories(file.getParent());
        check(file);
        Path temp = Files.createTempFile(file.getParent(), ".session-", ".tmp");
        try {
            io.write(temp, bytes);
            check(file);
            io.commit(temp, file, overwrite);
            return file;
        } finally { Files.deleteIfExists(temp); }
    }

    private void check(Path file) throws IOException {
        Path path = root;
        for (Path component : root.relativize(file)) {
            path = path.resolve(component);
            if (Files.isSymbolicLink(path)) throw new IOException("会话路径不允许符号链接");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                boolean valid = path.equals(file) ? Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                if (!valid) throw new IOException("会话路径包含非普通文件或无效目录");
            }
        }
    }
}
