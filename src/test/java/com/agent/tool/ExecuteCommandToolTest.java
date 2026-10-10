package com.agent.tool;

import com.agent.safety.CommandPolicy;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExecuteCommandToolTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolResult run(String command, int seconds) throws Exception {
        return new ToolExecutor(new ToolRegistry(root)).execute("execute_command",
                mapper.writeValueAsString(Map.of("command", command, "timeout_seconds", seconds)));
    }

    @Test void shouldCompileAndRunRealJavaWithCwdStdoutStderrAndExitCode() throws Exception {
        Files.writeString(root.resolve("Hello.java"), """
                public class Hello { public static void main(String[] args) {
                    System.out.println("你好 Agent " + args[0]);
                    System.err.println("stderr-marker");
                    System.out.println(java.nio.file.Path.of("").toAbsolutePath());
                }}
                """);
        assertTrue(run("javac Hello.java", 10).success());
        ToolResult result = run("java Hello '带 空格'", 10);
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("exitCode=0"));
        assertTrue(result.output().contains("你好 Agent 带 空格"));
        assertTrue(result.output().contains("stderr-marker"));
        assertTrue(result.output().contains(root.toRealPath().toString()));
    }

    @Test void shouldReportCompilerFailureAndNonzeroExitAsFailure() throws Exception {
        Files.writeString(root.resolve("Broken.java"), "public class Broken { broken }");
        ToolResult compile = run("javac Broken.java", 10);
        assertFalse(compile.success());
        assertTrue(compile.output().contains("stderr:"));
        assertFalse(Files.exists(root.resolve("Broken.class")));
        Files.writeString(root.resolve("Fail.java"), "public class Fail { public static void main(String[] a) { System.exit(7); }}");
        assertTrue(run("javac Fail.java", 10).success());
        ToolResult failure = run("java Fail", 10);
        assertFalse(failure.success());
        assertTrue(failure.output().contains("exitCode=7"));
    }

    @Test void shouldKillTimeoutProcessAndItsChild() throws Exception {
        Files.writeString(root.resolve("Slow.java"), """
                public class Slow { public static void main(String[] a) throws Exception {
                    if (a.length > 0) { Thread.sleep(30000); return; }
                    Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "Slow", "child").start();
                    java.nio.file.Files.writeString(java.nio.file.Path.of("child.pid"), Long.toString(child.pid()));
                    Thread.sleep(30000);
                }}
                """);
        assertTrue(run("javac Slow.java", 10).success());
        long started = System.nanoTime();
        ToolResult result = run("java Slow", 1);
        assertFalse(result.success());
        assertTrue(result.output().contains("timedOut=true"), result.output());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 6);
        long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
        for (int i = 0; i < 20 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(50);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "超时不能留下子进程");
    }

    @Test void shouldDrainLargeStdoutAndStderrWithoutDeadlock() throws Exception {
        Files.writeString(root.resolve("Loud.java"), "public class Loud { public static void main(String[] a) {"
                + " for (int i=0;i<50000;i++) { System.out.println(\"stdout\"); System.err.println(\"stderr\"); } }}");
        assertTrue(run("javac Loud.java", 10).success());
        ToolResult result = run("java Loud", 10);
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("命令输出已截断"));
        assertTrue(result.output().length() < 5000);
    }

    @Test void shouldRejectShellUnsupportedExecutableTraversalAndInvalidTimeoutBeforeExecution() throws Exception {
        for (String command : new String[]{"sh -c echo", "rm a", "javac A.java; java A", "java A > out",
                "java A | cat", "java $(whoami)", "javac ../Outside.java", "java -cp .. Hello", "java 'Hello",
                "mvn -f ../pom.xml test", "java @args"}) {
            assertFalse(run(command, 2).success(), command);
        }
        assertFalse(run("java -version", 0).success());
        assertFalse(run("java -version", 121).success());
        assertFalse(Files.exists(root.resolve("out")));
    }

    @Test void shouldPreserveQuotedPathAndPassMavenArgumentsWithoutShell() throws Exception {
        var guard = new SafetyGuard(root);
        var parsed = CommandPolicy.parse("javac 'my dir/Hello.java'", guard);
        assertEquals("my dir/Hello.java", parsed.get(2));
        assertEquals(java.util.List.of("mvn", "-o", "-q", "test"), CommandPolicy.parse("mvn -o -q test", guard));
    }
}
