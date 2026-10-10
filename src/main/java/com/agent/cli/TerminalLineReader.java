package com.agent.cli;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PushbackReader;
import java.nio.charset.Charset;

/**
 * 自带行编辑的终端读取器：解决中文输入时“一个汉字要按两次退格、删到开头还会残留半个字”的问题。
 *
 * <p>问题的根源在终端本身：默认的规范模式（canonical mode）下，退格由终端驱动处理，
 * 它按字节删除，而一个汉字在 UTF-8 中占 3 个字节；回显又按“1 字节 = 1 列”擦除，
 * 汉字却占 2 列。两者对不上，就会出现“多按几次才删掉一个字”和删不干净的残影。
 *
 * <p>解决办法是把行编辑收回到程序里：进入原始模式（`stty -icanon -echo`）后自己读取按键、
 * 自己回显、自己擦除，删除时按 {@link LineBuffer} 的“码点 + 显示宽度”处理，
 * 因此中文、emoji 都能一次退格删掉一个完整字符。
 *
 * <p>当标准输入不是终端时（例如 `printf ... | java -jar ...`），自动回退为按行读取，
 * 保证管道输入、脚本和测试的行为不变。退出时会恢复终端设置。
 */
public final class TerminalLineReader implements Closeable {

    private static final int ESC = 0x1B;
    private static final int BACKSPACE_DEL = 0x7F;
    private static final int BACKSPACE_BS = 0x08;
    private static final int CTRL_C = 0x03;
    private static final int CTRL_D = 0x04;
    private static final int CTRL_U = 0x15;
    private static final int LINE_FEED = 0x0A;
    private static final int CARRIAGE_RETURN = 0x0D;

    private final PushbackReader rawInput;
    private final BufferedReader canonicalInput;
    private final PrintStream out;
    private final boolean raw;
    private final Console console;

    private Thread restoreHook;
    private boolean swallowLineFeed;
    private boolean closed;

    private TerminalLineReader(PushbackReader rawInput, BufferedReader canonicalInput,
            PrintStream out, boolean raw, Console console) {
        this.rawInput = rawInput;
        this.canonicalInput = canonicalInput;
        this.out = out;
        this.raw = raw;
        this.console = console;
    }

    /** 创建读取器：标准输入是终端时启用自建行编辑，否则按行读取。 */
    public static TerminalLineReader create() {
        return create(System.in, System.out);
    }

    static TerminalLineReader create(InputStream stdin, PrintStream stdout) {
        Charset charset = Charset.defaultCharset();
        Console console = System.console();
        boolean terminal = console != null;

        if (terminal && !System.getProperty("os.name").startsWith("Windows")
                && setStty("-icanon", "-echo", "min", "1", "time", "0")) {
            PushbackReader input = new PushbackReader(new InputStreamReader(stdin, charset), 8);
            TerminalLineReader reader = new TerminalLineReader(input, null, stdout, true, null);
            reader.restoreHook = new Thread(TerminalLineReader::restoreStty, "terminal-restore");
            Runtime.getRuntime().addShutdownHook(reader.restoreHook);
            return reader;
        }
        return new TerminalLineReader(
                null, new BufferedReader(new InputStreamReader(stdin, charset)), stdout, false, console);
    }

    /** @return 是否启用了自建行编辑（终端模式） */
    public boolean isInteractive() {
        return raw || console != null;
    }

    /**
     * 读取一行输入，并负责回显与行编辑。
     *
     * @param prompt 提示符
     * @return 输入内容；遇到 EOF 或 Ctrl-C / Ctrl-D 时返回 {@code null}
     */
    public String readLine(String prompt) throws IOException {
        return readLine(prompt, false);
    }

    /** Read a credential through the same buffer, with no echo or masked length. */
    public String readSecretLine(String prompt) throws IOException {
        if (!raw) {
            if (console == null) throw new IOException("需要可关闭回显的交互终端才能输入 API Key。");
            char[] password = console.readPassword("%s", prompt);
            if (password == null) return null;
            try { return new String(password); }
            finally { java.util.Arrays.fill(password, '\0'); }
        }
        return readLine(prompt, true);
    }

    private String readLine(String prompt, boolean secret) throws IOException {
        if (!raw) {
            if (console != null) return console.readLine("%s", prompt);
            out.print(prompt);
            out.flush();
            return canonicalInput.readLine();
        }

        LineBuffer buffer = new LineBuffer();
        out.print(prompt);
        out.flush();

        while (true) {
            int ch = rawInput.read();
            if (swallowLineFeed) {
                swallowLineFeed = false;
                if (ch == LINE_FEED) {
                    continue;
                }
            }
            if (ch < 0) {
                out.println();
                return buffer.isEmpty() ? null : buffer.text();
            }
            if (ch == LINE_FEED || ch == CARRIAGE_RETURN) {
                swallowLineFeed = ch == CARRIAGE_RETURN;
                out.println();
                return buffer.text();
            }
            if (ch == BACKSPACE_DEL || ch == BACKSPACE_BS) {
                int width = buffer.backspace();
                if (!secret) eraseColumns(width);
                continue;
            }
            if (ch == CTRL_C) {
                out.println("^C");
                return null;
            }
            if (ch == CTRL_D) {
                if (buffer.isEmpty()) {
                    out.println();
                    return null;
                }
                continue;
            }
            if (ch == CTRL_U) {
                int width = buffer.displayWidth();
                buffer.clear();
                if (!secret) eraseColumns(width);
                continue;
            }
            if (ch == ESC) {
                skipEscapeSequence();
                continue;
            }

            int codePoint = readCodePoint(ch);
            if (codePoint < 0x20 || codePoint == BACKSPACE_DEL) {
                continue;
            }
            buffer.append(codePoint);
            if (!secret) {
                out.print(new String(Character.toChars(codePoint)));
                out.flush();
            }
        }
    }

    /** 擦除 {@code width} 列：先退格，再覆盖空格，最后把光标移回原处。 */
    private void eraseColumns(int width) {
        for (int i = 0; i < width; i++) {
            out.print('\b');
        }
        for (int i = 0; i < width; i++) {
            out.print(' ');
        }
        for (int i = 0; i < width; i++) {
            out.print('\b');
        }
        out.flush();
    }

    /** 组合代理对（emoji 等）；不是完整代理对时把多读的字符放回。 */
    private int readCodePoint(int first) throws IOException {
        char high = (char) first;
        if (!Character.isHighSurrogate(high)) {
            return high;
        }
        int next = rawInput.read();
        if (next < 0) {
            return high;
        }
        char low = (char) next;
        if (Character.isLowSurrogate(low)) {
            return Character.toCodePoint(high, low);
        }
        rawInput.unread(next);
        return high;
    }

    /** 忽略方向键、Home/End 等转义序列，避免它们被当成正文插入。 */
    private void skipEscapeSequence() throws IOException {
        int next = rawInput.read();
        if (next != '[' && next != 'O') {
            return;
        }
        int ch;
        while ((ch = rawInput.read()) >= 0) {
            if (ch >= 0x40 && ch <= 0x7E) {
                return;
            }
        }
    }

    @Override
    public void close() {
        if (closed || !raw) {
            return;
        }
        closed = true;
        restoreStty();
        if (restoreHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(restoreHook);
            } catch (IllegalStateException ignored) {
                // JVM 已在关闭过程中，钩子无需移除
            }
        }
    }

    private static boolean setStty(String... options) {
        String[] command = new String[options.length + 1];
        command[0] = "stty";
        System.arraycopy(options, 0, command, 1, options.length);
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 恢复终端的规范模式与回显，避免程序退出后终端“坏掉”。 */
    private static void restoreStty() {
        setStty("icanon", "echo", "min", "1", "time", "0");
    }
}
