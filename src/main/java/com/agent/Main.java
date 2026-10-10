package com.agent;

import com.agent.cli.ConfigurationWizard;
import com.agent.cli.TerminalLineReader;
import com.agent.config.AgentConfiguration;
import com.agent.config.ConfigurationStore;
import com.agent.llm.GlmClient;
import com.agent.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Path;

/** CLI 接线、首次配置和会话命令；模型协议与工具执行保持独立。 */
public class Main {
    private static final String API_KEY_ENV = "GLM_API_KEY";

    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) System.exit(status);
    }

    private static int run(String[] args) {
        Path projectRoot = Path.of(".");
        boolean configure = false;
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            help(); return 0;
        }
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--root") && i + 1 < args.length) projectRoot = Path.of(args[++i]);
                else if (args[i].equals("--configure")) configure = true;
                else { help(); return 2; }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("项目目录无效。"); return 2;
        }
        ToolRegistry registry;
        try { registry = new ToolRegistry(projectRoot); }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        Path envFile = Path.of(".env");
        ConfigurationStore store = ConfigurationStore.system();
        try (TerminalLineReader reader = TerminalLineReader.create()) {
            AgentConfiguration settings = store.load(envFile);
            boolean invalid = !settings.hasApiKey() || !AgentConfiguration.validModel(settings.model())
                    || !AgentConfiguration.validApiUrl(settings.apiUrl());
            ConfigurationWizard wizard = ConfigurationWizard.forTerminal(reader, store);
            if (configure || invalid) {
                if (!reader.isInteractive()) {
                    System.err.println("GLM_API_KEY 或模型配置缺失/无效。请在交互终端运行程序，按向导完成配置。");
                    System.err.println("脚本模式请提供 GLM_API_KEY 环境变量或有效配置文件；不会将任务输入当作密钥。");
                    return configure ? 2 : 1;
                }
                if (invalid) System.out.println("欢迎使用 Java AI Coding Agent！先完成 GLM 配置，随后即可开始任务。");
                settings = wizard.configure(wizardDefaults(settings), false);
                if (settings == null) return 0;
                if (store.hasOverrides(envFile)) overridesNotice();
                if (configure) return 0;
            }
            Agent agent = createAgent(settings, registry);
            System.out.println("\n项目目录：" + registry.root());
            System.out.println("当前模型：" + settings.model());
            System.out.println("输入自然语言任务，Agent 会自动读写文件、编译或运行 Java 项目。");
            System.out.println("/config 配置密钥与服务；/model 选择模型；/root 路径 切换项目；/help 查看帮助。");
            System.out.println("clear 清空对话历史；exit/quit 退出。\n");
            while (true) {
                String input = reader.readLine("你：");
                if (input == null) break;
                input = input.trim();
                if (input.isEmpty()) continue;
                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit") || input.equals("/exit")) break;
                if (input.equalsIgnoreCase("clear") || input.equals("/clear")) {
                    agent.clearHistory(); System.out.println("历史已清空。\n"); continue;
                }
                if (input.equals("/help")) { help(); continue; }
                if (input.equals("/config") || input.equals("/model")) {
                    if (!reader.isInteractive()) {
                        System.out.println("配置向导需要交互终端；脚本模式请使用 GLM_* 配置。"); continue;
                    }
                    AgentConfiguration selected = wizard.configure(settings, input.equals("/model"));
                    if (selected != null) {
                        settings = selected;
                        agent = createAgent(settings, registry);
                        System.out.println("配置已生效，当前模型：" + settings.model() + "；对话历史已清空。");
                        if (store.hasOverrides(envFile)) overridesNotice();
                    }
                    continue;
                }
                if (input.equals("/root") || input.startsWith("/root ")) {
                    String path = input.substring(5).trim();
                    if (path.isEmpty()) { System.out.println("当前项目目录：" + registry.root() + "；使用 /root 路径 切换。"); continue; }
                    try {
                        ToolRegistry selected = new ToolRegistry(Path.of(unquote(path)));
                        Agent selectedAgent = createAgent(settings, selected);
                        registry = selected;
                        agent = selectedAgent;
                        System.out.println("项目目录：" + registry.root() + "；对话历史已清空。");
                    } catch (IllegalArgumentException e) { System.out.println("无法切换项目：" + e.getMessage()); }
                    continue;
                }
                System.out.println("\nAgent：" + agent.run(input) + "\n");
            }
        } catch (IOException e) {
            System.err.println("配置或终端读取失败，请检查配置文件的访问权限与终端状态。");
            return 1;
        }
        System.out.println("再见！");
        return 0;
    }

    private static AgentConfiguration wizardDefaults(AgentConfiguration settings) {
        return new AgentConfiguration(settings.apiKey(),
                AgentConfiguration.validApiUrl(settings.apiUrl()) ? settings.apiUrl() : GlmClient.DEFAULT_API_URL,
                AgentConfiguration.validModel(settings.model()) ? settings.model() : GlmClient.DEFAULT_MODEL);
    }

    private static Agent createAgent(AgentConfiguration settings, ToolRegistry registry) {
        return new Agent(new GlmClient(settings.apiKey(), settings.apiUrl(), settings.model()), registry);
    }

    private static void overridesNotice() {
        System.out.println("本次选择已生效；下次启动时，当前目录 .env 和 GLM_* 环境变量仍优先于用户配置。");
    }

    private static void help() {
        System.out.println("用法: agent [--root 项目目录] [--configure]");
        System.out.println("首次启动自动引导输入 API Key、选择模型与保存配置；--configure 单独运行配置向导。");
        System.out.println("/config 重新配置；/model 选择模型；/root 路径 切换项目；/help 查看帮助。");
        System.out.println("clear 或 /clear 清空历史；exit、quit 或 /exit 退出。");
        System.out.println("工具: list_files, list_dir, read_file, search_code, write_file, edit_file, execute_command");
        System.out.println("配置优先级：启动目录 .env > GLM_* 环境变量 > 用户配置 > 默认值。");
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
        try {
            String value = ConfigurationStore.readFile(envFile).get(name);
            if (value != null && !value.isBlank()) return value;
        } catch (IOException e) { System.err.println("读取 .env 失败。"); }
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
