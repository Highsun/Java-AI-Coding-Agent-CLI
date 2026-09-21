package com.agent.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 显示宽度计算的测试：中文占两列是修好退格问题的前提。 */
class DisplayWidthTest {

    @Test
    void shouldTreatHalfwidthAsciiAsOneColumn() {
        assertEquals(1, DisplayWidth.of('a'));
        assertEquals(1, DisplayWidth.of('1'));
    }

    @Test
    void shouldTreatChineseAndFullwidthAsTwoColumns() {
        assertEquals(2, DisplayWidth.of('你'));
        assertEquals(2, DisplayWidth.of('好'));
        assertEquals(2, DisplayWidth.of('：'));
    }

    @Test
    void shouldTreatEmojiAsTwoColumns() {
        assertEquals(2, DisplayWidth.of(0x1F600));
    }

    @Test
    void shouldTreatCombiningMarkAsZeroWidth() {
        assertEquals(0, DisplayWidth.of(0x0301));
    }
}
