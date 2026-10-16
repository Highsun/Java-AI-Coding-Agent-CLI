package com.agent.safety;

import com.agent.llm.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.TreeSet;

/** 守卫：项目路径/私密文件边界，以及每次任务的连续重复调用上限。不是操作系统沙箱。 */
public final class SafetyGuard {
    public static final int MAX_IDENTICAL_CALLS = 2;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path root;
    private String lastSignature;
    private int repeats;

    public SafetyGuard(Path root) {
        try { this.root = root.toRealPath(); }
        catch (IOException e) { throw new IllegalArgumentException("项目目录不存在: " + root, e); }
        if (!Files.isDirectory(this.root)) throw new IllegalArgumentException("项目根路径不是目录");
    }

    public Path root() { return root; }

    /** 校验已有路径或最近存在的祖先，阻止通过符号链接访问根目录外的文件。 */
    public Path resolve(String raw) throws IOException {
        if (raw == null || raw.isBlank()) throw new IOException("路径不能为空");
        Path input = Path.of(raw);
        Path candidate = (input.isAbsolute() ? input : root.resolve(input)).normalize();
        checkBoundary(candidate, raw);
        Path ancestor = candidate;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        Path real = ancestor.toRealPath();
        checkBoundary(real, raw);
        Path resolved = real.resolve(ancestor.relativize(candidate));
        checkBoundary(resolved, raw);
        return resolved;
    }

    private void checkBoundary(Path path, String raw) throws IOException {
        if (!path.startsWith(root)) throw new IOException("路径越界，拒绝访问项目根目录之外: " + raw);
        if (isSessionPath(path)) throw new IOException("会话目录仅允许通过 /save 和 /load 管理");
        for (Path part : root.relativize(path)) {
            String name = part.toString();
            if (name.equals(".git") || name.equals(".env")
                    || (name.startsWith(".env.") && !name.equals(".env.example"))) {
                throw new IOException("受保护路径，拒绝访问: " + raw);
            }
        }
    }

    public static boolean isSessionPath(Path path) {
        for (int i = 0; i + 1 < path.getNameCount(); i++) {
            if (path.getName(i).toString().equals(".agent")
                    && path.getName(i + 1).toString().equals("sessions")) return true;
        }
        return false;
    }

    public void beginTurn() { lastSignature = null; repeats = 0; }

    /** 第三次连续相同调用不再执行；JSON 对象字段顺序不影响判定。 */
    public boolean allow(ToolCall call) {
        String arguments = call.arguments();
        try { arguments = canonical(MAPPER.readTree(arguments == null ? "{}" : arguments)).toString(); }
        catch (Exception ignored) { /* 非法 JSON 交给注册员报告，仍计入重复调用。 */ }
        String signature = call.name() + "\n" + arguments;
        repeats = signature.equals(lastSignature) ? repeats + 1 : 1;
        lastSignature = signature;
        return repeats <= MAX_IDENTICAL_CALLS;
    }

    private static JsonNode canonical(JsonNode node) {
        if (node != null && node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> sorted.set(name, canonical(node.get(name))));
            return sorted;
        }
        return node == null ? MAPPER.nullNode() : node;
    }
}
