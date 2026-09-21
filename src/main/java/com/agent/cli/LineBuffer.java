package com.agent.cli;

/**
 * 行编辑缓冲区：按“码点”而不是“字节”增删，退格时返回被删字符的显示宽度。
 *
 * <p>这是修复中文退格问题的核心：终端行模式下一个退格只删一个字节，
 * 而这里一个退格删除一个完整字符（中文不会变成乱码或半个字）。
 */
public final class LineBuffer {

    private final StringBuilder text = new StringBuilder();

    /** 追加一个码点（支持 emoji 这类由两个 char 组成的字符）。 */
    public void append(int codePoint) {
        text.appendCodePoint(codePoint);
    }

    /**
     * 删除最后一个码点。
     *
     * @return 被删除字符的显示宽度；缓冲区为空时返回 0
     */
    public int backspace() {
        if (text.isEmpty()) {
            return 0;
        }
        int lastCodePoint = text.codePointBefore(text.length());
        int width = DisplayWidth.of(lastCodePoint);
        text.delete(text.length() - Character.charCount(lastCodePoint), text.length());
        return width;
    }

    public void clear() {
        text.setLength(0);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** 已输入内容占用的终端列数。 */
    public int displayWidth() {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += DisplayWidth.of(codePoint);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    public String text() {
        return text.toString();
    }
}
