package com.agent;

import com.agent.llm.GlmClient;
import com.agent.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * CLI 接线：加载配置、创建对象、处理终端交互。
 *
 * <p>这里不出现任何 LLM 协议或文件工具逻辑，主流程只是
 * {@code GlmClient + ToolRegistry + Agent} 的组合。
 */
public class Main {

    private static final String API_KEY_ENV = "GLM_API_KEY";

    public static void main(String[] args) {
        String apiKey = loadApiKey(Path.of(".env"));
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：未找到 " + API_KEY_ENV);
            System.err.println("请在项目根目录的 .env 或环境变量中设置 " + API_KEY_ENV);
            System.exit(1);
        }

        GlmClient llm = new GlmClient(apiKey);
        ToolRegistry registry = new ToolRegistry(Path.of("."));
        Agent agent = new Agent(llm, registry);

        System.out.println("输入问题，Agent 会自动决定是否调用文件工具；");
        System.out.println("clear 清空对话历史；exit/quit 退出。\n");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("你：");
                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();
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
     * <p>必须从项目根目录运行，因为 {@code .env} 按当前工作目录寻找。
     * 读取时主动去除 Windows 可能写入的 BOM 与首尾空白，并兼容带引号的写法。
     *
     * @param envFile {@code .env} 路径
     * @return API Key，未配置时返回 {@code null}
     */
    static String loadApiKey(Path envFile) {
        if (Files.isRegularFile(envFile)) {
            try (BufferedReader reader = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.replace("\uFEFF", "").trim();
                    if (line.startsWith(API_KEY_ENV + "=")) {
                        return unquote(line.substring((API_KEY_ENV + "=").length()).trim());
                    }
                }
            } catch (IOException e) {
                System.err.println("读取 .env 失败: " + e.getMessage());
            }
        }
        return System.getenv(API_KEY_ENV);
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
