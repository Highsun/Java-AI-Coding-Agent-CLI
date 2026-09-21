package com.agent.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 非终端（管道输入）时的回退行为测试，保证脚本与冒烟测试不受行编辑改动影响。 */
class TerminalLineReaderTest {

    private static TerminalLineReader readerFor(String input, ByteArrayOutputStream output) {
        return TerminalLineReader.create(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @Test
    void shouldReadLinesWhenInputIsNotATerminal() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TerminalLineReader reader = readerFor("你好\nclear\n", output)) {
            assertFalse(reader.isInteractive(), "管道输入不应启用原始模式");
            assertEquals("你好", reader.readLine("你："));
            assertEquals("clear", reader.readLine("你："));
            assertNull(reader.readLine("你："), "输入耗尽后应返回 null 表示结束");
        }
        assertEquals("你：你：你：", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldKeepUtf8CharactersIntact() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TerminalLineReader reader = readerFor("读取 pom.xml 并概括依赖\n", output)) {
            assertEquals("读取 pom.xml 并概括依赖", reader.readLine("你："));
        }
    }
}
