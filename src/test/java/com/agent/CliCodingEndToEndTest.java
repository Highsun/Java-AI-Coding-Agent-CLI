package com.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 真 CLI 子进程 → HTTP Tool Calling → 文件编辑 → 真编译/运行 → 最终回答与 clear。 */
class CliCodingEndToEndTest {
    @TempDir Path temp;

    @Test void shouldRunNaturalLanguageCodingTaskThroughCliAndClearHistory() throws Exception {
        Path project = Files.createDirectory(temp.resolve("项目 with spaces"));
        Files.writeString(project.resolve("Hello.java"),
                "public class Hello { public static void main(String[] a) { System.out.println(\"wrong\"); }}");
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            int round = requests.size();
            var root = mapper.createObjectNode();
            var message = root.putArray("choices").addObject().putObject("message");
            message.put("role", "assistant");
            if (round <= 4) {
                String name = switch (round) { case 1 -> "read_file"; case 2 -> "edit_file"; default -> "execute_command"; };
                Map<String, String> args = switch (round) {
                    case 1 -> Map.of("path", "Hello.java");
                    case 2 -> Map.of("path", "Hello.java", "old_text", "\"wrong\"", "new_text", "\"Hello Agent\"");
                    case 3 -> Map.of("command", "javac Hello.java");
                    default -> Map.of("command", "java Hello");
                };
                message.putNull("content");
                var call = message.putArray("tool_calls").addObject();
                call.put("id", "cli_" + round).put("type", "function");
                call.putObject("function").put("name", name).put("arguments", mapper.writeValueAsString(args));
            } else {
                message.put("content", round == 5 ? "已修改并编译运行，输出 Hello Agent。" : "清空后你好。");
            }
            byte[] bytes = mapper.writeValueAsBytes(root);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        Process process = null;
        var reader = Executors.newSingleThreadExecutor();
        try {
            String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", classpath, "com.agent.Main", "--root", project.toString())
                    .directory(temp.toFile()).redirectErrorStream(true);
            builder.environment().put("GLM_API_KEY", "offline-test");
            builder.environment().put("GLM_API_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            process = builder.start();
            final Process running = process;
            var outputFuture = reader.submit(() -> new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            process.getOutputStream().write(("将 Hello.java 的输出改为 Hello Agent，重新编译并运行验证。\nclear\n你好\nexit\n")
                    .getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI 应按时退出");
            String output = outputFuture.get(3, TimeUnit.SECONDS);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("执行工具: edit_file"), output);
            assertTrue(output.contains("stdout:\nHello Agent"), output);
            assertTrue(output.contains("历史已清空"));
            assertTrue(Files.readString(project.resolve("Hello.java")).contains("Hello Agent"));
            assertTrue(Files.exists(project.resolve("Hello.class")));
            assertEquals(6, requests.size());
            assertEquals(7, requests.get(0).path("tools").size());
            assertTrue(requests.get(0).path("messages").get(1).path("content").asText().contains("重新编译"));
            List<String> observations = new ArrayList<>();
            requests.get(4).path("messages").forEach(m -> {
                if (m.path("role").asText().equals("tool")) observations.add(m.path("content").asText());
            });
            assertEquals(4, observations.size());
            assertTrue(observations.get(1).contains("替换成功"));
            assertTrue(observations.get(2).contains("exitCode=0"));
            assertTrue(observations.get(3).contains("stdout:\nHello Agent"));
            assertEquals(2, requests.get(5).path("messages").size(), "clear 后仅 system + 新 user");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            reader.shutdownNow();
            server.stop(0);
        }
    }
}
