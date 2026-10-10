package com.agent.safety;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 直接创建进程，不使用 shell。限定 Java 开发命令；这不是对生成代码的 OS 隔离。 */
public final class CommandPolicy {
    private static final Set<String> MAVEN_ARGS = Set.of("-o", "-q", "-B", "-DskipTests",
            "validate", "compile", "test", "package", "verify", "clean");
    private CommandPolicy() { }

    public static List<String> parse(String command, SafetyGuard guard) throws IOException {
        if (command == null || command.isBlank()) throw new IOException("命令不能为空");
        if (command.chars().anyMatch(c -> ";&|><`$\n\r".indexOf(c) >= 0)) {
            throw new IOException("不支持 shell 操作符、重定向或变量展开，请每次执行一条命令");
        }
        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) throw new IOException("命令不能为空");
        String executable = tokens.get(0);
        if (!Set.of("java", "javac", "mvn").contains(executable)) {
            throw new IOException("命令不在允许列表: " + executable + "（允许 java、javac、mvn）");
        }
        if (executable.equals("mvn")) {
            for (String token : tokens.subList(1, tokens.size())) {
                if (!MAVEN_ARGS.contains(token)) throw new IOException("不支持的 Maven 参数或目标: " + token);
            }
        } else {
            for (int i = 1; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (token.startsWith("@") || token.startsWith("-J") || token.startsWith("-D")
                        || token.startsWith("-javaagent") || token.startsWith("-agent")
                        || token.startsWith("-processor") || token.startsWith("--")) {
                    if (!Set.of("--version", "--release", "--class-path").contains(token)) {
                        throw new IOException("不支持的命令参数: " + token);
                    }
                }
                if (Set.of("-cp", "-classpath", "--class-path").contains(token)) {
                    if (++i >= tokens.size()) throw new IOException("缺少 classpath 参数");
                    for (String path : tokens.get(i).split(java.io.File.pathSeparator, -1)) {
                        guard.resolve(path.isEmpty() ? "." : path);
                    }
                } else if (token.contains("/") || token.contains("\\") || token.startsWith(".")
                        || token.endsWith(".java") || token.endsWith(".jar")) {
                    guard.resolve(token);
                }
            }
            tokens.set(0, Path.of(System.getProperty("java.home"), "bin", executable).toString());
            if (executable.equals("javac")) tokens.add(1, "-proc:none");
        }
        return List.copyOf(tokens);
    }

    private static List<String> tokenize(String text) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                else token.append(ch);
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                started = true;
            } else if (Character.isWhitespace(ch)) {
                if (started) { result.add(token.toString()); token.setLength(0); started = false; }
            } else { token.append(ch); started = true; }
        }
        if (quote != 0) throw new IOException("命令引号未闭合");
        if (started) result.add(token.toString());
        return result;
    }
}
