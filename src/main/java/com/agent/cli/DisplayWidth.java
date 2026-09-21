package com.agent.cli;

/**
 * 估算一个码点在终端里占用的列数。
 *
 * <p>终端按“列”移动光标，而中文、日文、韩文等字符占两列（East Asian Wide）。
 * 自建行编辑必须知道这个宽度，才能在退格时把光标和字符一起擦干净；
 * 如果按“一个字符一列”处理，宽字符就会留下半个残影。
 *
 * <p>这是一个近似实现：覆盖了常见的宽字符区段，不处理极少数的歧义宽度字符。
 */
public final class DisplayWidth {

    /** 常见宽字符区段（起止均含）。 */
    private static final int[][] WIDE_RANGES = {
            {0x1100, 0x115F},   // 谚文字母
            {0x2E80, 0x303E},   // 中日韩部首、康熙部首等
            {0x3041, 0x33FF},   // 平假名、片假名、中日韩符号与兼容字符
            {0x3400, 0x4DBF},   // 中日韩扩展 A
            {0x4E00, 0x9FFF},   // 中日韩统一表意文字
            {0xA000, 0xA4CF},   // 彝文
            {0xAC00, 0xD7A3},   // 谚文音节
            {0xF900, 0xFAFF},   // 中日韩兼容表意文字
            {0xFE10, 0xFE19},   // 竖排标点
            {0xFE30, 0xFE6F},   // 中日韩兼容形式
            {0xFF00, 0xFF60},   // 全角字符（含全角冒号 U+FF1A）
            {0xFFE0, 0xFFE6},   // 全角符号
            {0x1F300, 0x1F64F}, // 表情符号
            {0x1F900, 0x1F9FF}, // 补充符号
            {0x20000, 0x3FFFD}  // 中日韩扩展 B 及以上
    };

    private DisplayWidth() {
    }

    /**
     * @param codePoint 待测量的码点
     * @return 终端列数：组合字符为 0，宽字符为 2，其余为 1
     */
    public static int of(int codePoint) {
        if (codePoint == 0 || codePoint == 0x200B || isCombining(codePoint)) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /** 组合附加符号不单独占位。 */
    private static boolean isCombining(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isWide(int codePoint) {
        for (int[] range : WIDE_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
