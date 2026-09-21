package com.agent;

import com.agent.llm.GlmClient;
import com.agent.cli.TerminalLineReader;
import com.agent.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI 接线：加载配置、创建对象、处理终端交互。
 *
 * <p>
 * 这里不出现任何 LLM 协议或文件工具逻辑，主流程只是
 * {@code GlmClient + ToolRegistry + Agent} 的组合。
 */
public class Main {

    private static final String API_KEY_ENV = "GLM_API_KEY";
    private static final String API_URL_ENV = "GLM_API_URL";
    private static final String MODEL_ENV = "GLM_MODEL";

    public static void main(String[] args) {
        Path envFile = Path.of(".env");
        String apiKey = loadApiKey(envFile);
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：未找到 " + API_KEY_ENV);
            System.err.println("请在项目根目录的 .env 或环境变量中设置 " + API_KEY_ENV);
            System.exit(1);
        }

        String apiUrl = loadSetting(envFile, API_URL_ENV, GlmClient.DEFAULT_API_URL);
        String model = loadSetting(envFile, MODEL_ENV, GlmClient.DEFAULT_MODEL);

        GlmClient llm = new GlmClient(apiKey, apiUrl, model);
        ToolRegistry registry = new ToolRegistry(Path.of("."));
        Agent agent = new Agent(llm, registry);

        System.out.println("输入问题，Agent 会自动决定是否调用文件工具；");
        System.out.println("clear 清空对话历史；exit/quit 退出。\n");

        // 中文退格由 TerminalLineReader 自己处理：终端按字节删除会让一个汉字需要多次退格，
        // 并留下擦不掉的残影；这里改为按字符 + 显示宽度编辑（非终端输入自动回退为按行读取）。
        try (TerminalLineReader reader = TerminalLineReader.create()) {
            while (true) {
                String input;
                try {
                    input = reader.readLine("你：");
                } catch (IOException e) {
                    System.err.println("读取输入失败: " + e.getMessage());
                    break;
                }
                if (input == null) {
                    break;
                }

                input = input.trim();
                if (input.isEmpty()) {
                    continue;
                }
                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    break;
                }
                if (input.equalsIgnoreCase("clear")) {
                    agent.clearHistory();
                    System.out.println("历史已清空。\n");
                    continue;
                }

                System.out.println("\nAgent：" + agent.run(input) + "\n");
            }
        }

        System.out.println("再见！");
    }

    /**
     * 先读工作目录下的 {@code .env}，再回退到环境变量。
     *
     * <p>
     * 必须从项目根目录运行，因为 {@code .env} 按当前工作目录寻找。
     * 读取时主动去除 Windows 可能写入的 BOM 与首尾空白，并兼容带引号的写法。
     *
     * @param envFile {@code .env} 路径
     * @return API Key，未配置时返回 {@code null}
     */
    static String loadApiKey(Path envFile) {
        return loadSetting(envFile, API_KEY_ENV, null);
    }

    /**
     * 读取一个配置项：优先 {@code .env}，其次环境变量，最后默认值。
     *
     * <p>
     * {@code GLM_API_URL} 与 {@code GLM_MODEL} 主要用于离线验收——
     * 把 Endpoint 指向本地假服务器，就可以在没有真实 Key 的情况下走完整个 CLI 流程。
     *
     * @param envFile  项目根目录下的 {@code .env}
     * @param name     配置项名称，例如 {@code GLM_API_KEY}
     * @param fallback 两者都未配置时的默认值，可为 {@code null}
     * @return 配置值
     */
    static String loadSetting(Path envFile, String name, String fallback) {
        if (Files.isRegularFile(envFile)) {
            try (BufferedReader reader = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.replace("\uFEFF", "").trim();
                    if (line.startsWith(name + "=")) {
                        String value = unquote(line.substring((name + "=").length()).trim());
                        if (!value.isBlank()) {
                            return value;
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("读取 .env 失败: " + e.getMessage());
            }
        }
        String fromEnvironment = System.getenv(name);
        return (fromEnvironment == null || fromEnvironment.isBlank()) ? fallback : fromEnvironment;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
