package com.agent.tool;

import com.agent.llm.LLMClient;
import com.agent.safety.CommandPolicy;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 真实执行 javac/java/mvn，收集 stdout/stderr/退出码，超时清理进程树。 */
public final class ExecuteCommandTool implements Tool {
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MAX_TIMEOUT_SECONDS = 120;
    public static final int MAX_OUTPUT_BYTES = 2048;

    @Override public LLMClient.ToolDef definition() {
        return ToolSchemas.define("execute_command", "在项目根目录执行一条 java、javac 或 mvn 命令，返回真实 exitCode/stdout/stderr。"
                        + "不使用 shell，不支持管道、重定向、通配符。编译失败应修复后再编译；超时会终止进程。",
                ToolSchemas.text("command", "例如 javac Hello.java、java Hello、mvn -o test；含空格的参数用引号包围"),
                new ToolSchemas.Param("timeout_seconds", "integer", "1～120 秒，默认 30", false));
    }

    @Override public ToolResult execute(JsonNode args, SafetyGuard guard) throws Exception {
        int timeout = args.path("timeout_seconds").asInt(DEFAULT_TIMEOUT_SECONDS);
        if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS) throw new IOException("timeout_seconds 必须在 1～120 之间");
        var command = CommandPolicy.parse(args.get("command").textValue(), guard);
        ProcessBuilder builder = new ProcessBuilder(command).directory(guard.root().toFile());
        // 编译/运行程序不需要继承模型凭证；避免 JVM/Maven 的环境注入改变命令。
        builder.environment().keySet().removeIf(key -> key.startsWith("GLM_") || Set.of(
                "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS", "MAVEN_ARGS").contains(key));
        Process process = builder.start();
        var readers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "command-output");
            thread.setDaemon(true);
            return thread;
        });
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        try {
            process.getOutputStream().close(); // 非交互命令：stdin EOF，避免等待输入。
            Future<String> stdout = readers.submit(() -> capture(process.getInputStream()));
            Future<String> stderr = readers.submit(() -> capture(process.getErrorStream()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            boolean finished = false;
            while (System.nanoTime() < deadline) {
                process.descendants().forEach(descendants::add);
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) { finished = true; break; }
            }
            if (!finished) terminate(process, descendants);
            // 不允许任务留下后台子进程持有输出管道。
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            String out = stdout.get(3, TimeUnit.SECONDS);
            String err = stderr.get(3, TimeUnit.SECONDS);
            String exit = finished ? Integer.toString(process.exitValue()) : "timeout";
            String output = "exitCode=" + exit + "\ntimedOut=" + !finished
                    + "\nstdout:\n" + out + "\nstderr:\n" + err;
            return new ToolResult(finished && process.exitValue() == 0, output);
        } finally {
            terminate(process, descendants);
            readers.shutdownNow();
        }
    }

    private static String capture(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            boolean truncated = false;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                int retained = Math.min(count, MAX_OUTPUT_BYTES - bytes.size());
                bytes.write(buffer, 0, retained);
                truncated |= retained < count;
            }
            return bytes.toString(StandardCharsets.UTF_8)
                    + (truncated ? "\n...[命令输出已截断]" : "");
        }
    }

    private static void terminate(Process process, Set<ProcessHandle> descendants) {
        process.descendants().forEach(descendants::add);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }
}
