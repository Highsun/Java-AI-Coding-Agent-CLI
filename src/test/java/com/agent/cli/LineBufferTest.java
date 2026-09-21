package com.agent.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 行编辑缓冲区的测试：一次退格必须删掉一个完整字符，而不是一个字节。 */
class LineBufferTest {

    private static void append(LineBuffer buffer, String text) {
        text.codePoints().forEach(buffer::append);
    }

    @Test
    void shouldDeleteOneChineseCharacterPerBackspace() {
        LineBuffer buffer = new LineBuffer();
        append(buffer, "你好");

        int width = buffer.backspace();

        assertEquals(2, width, "汉字占两列，擦除时要按两列处理");
        assertEquals("你", buffer.text());
    }

    @Test
    void shouldDoNothingWhenBackspaceOnEmptyBuffer() {
        LineBuffer buffer = new LineBuffer();

        assertEquals(0, buffer.backspace());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void shouldDeleteEmojiAsOneCharacter() {
        LineBuffer buffer = new LineBuffer();
        append(buffer, "ok😀");

        int width = buffer.backspace();

        assertEquals(2, width);
        assertEquals("ok", buffer.text(), "emoji 由两个 char 组成，应整体删除");
    }

    @Test
    void shouldNotProduceBrokenCharactersWhenDeletingToEmpty() {
        LineBuffer buffer = new LineBuffer();
        append(buffer, "中文abc");

        for (int i = 0; i < 5; i++) {
            buffer.backspace();
        }

        assertTrue(buffer.isEmpty(), "按字符删除不会留下半个汉字或替换字符");
        assertEquals("", buffer.text());
    }

    @Test
    void shouldMeasureTotalDisplayWidth() {
        LineBuffer buffer = new LineBuffer();
        append(buffer, "a你好：");

        assertEquals(7, buffer.displayWidth(), "1 + 2 + 2 + 2");
    }
}
