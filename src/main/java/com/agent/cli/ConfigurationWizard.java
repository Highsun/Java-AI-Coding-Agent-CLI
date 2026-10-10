package com.agent.cli;

import com.agent.config.AgentConfiguration;
import com.agent.config.ConfigurationStore;
import java.io.IOException;
import java.io.PrintStream;

/** Interactive setup; uses the task reader so buffered input is never lost. */
public final class ConfigurationWizard {
    public interface Input {
        String line(String prompt) throws IOException;
        String secret(String prompt) throws IOException;
    }

    private final Input input;
    private final PrintStream out;
    private final ConfigurationStore store;

    public ConfigurationWizard(Input input, PrintStream out, ConfigurationStore store) {
        this.input = input; this.out = out; this.store = store;
    }

    public static ConfigurationWizard forTerminal(TerminalLineReader reader, ConfigurationStore store) {
        return new ConfigurationWizard(new Input() {
            public String line(String prompt) throws IOException { return reader.readLine(prompt); }
            public String secret(String prompt) throws IOException { return reader.readSecretLine(prompt); }
        }, System.out, store);
    }

    /** null means cancelled; the caller keeps its previous session config. */
    public AgentConfiguration configure(AgentConfiguration current, boolean modelOnly) throws IOException {
        out.println(modelOnly ? "\n选择模型（空行时 Ctrl-D 取消，Ctrl-C 退出程序）" : "\nGLM 配置向导（空行时 Ctrl-D 取消，Ctrl-C 退出程序）");
        String key = current.apiKey();
        if (!modelOnly) {
            out.println("API Key 输入时不会显示；可在智谱开放平台的 API Keys 页面获取。");
            while (true) {
                String value = input.secret(current.hasApiKey() ? "API Key [回车保留现有密钥]：" : "API Key：");
                if (value == null) return cancel();
                value = value.trim();
                if (value.isEmpty() && current.hasApiKey()) break;
                if (new AgentConfiguration(value, current.apiUrl(), current.model()).hasApiKey()) { key = value; break; }
                out.println("请输入有效密钥，不能留空、包含空白或使用示例占位符。");
            }
        }
        String model = chooseModel(current.model());
        if (model == null) return cancel();
        String url = current.apiUrl();
        if (!modelOnly) {
            while (true) {
                String value = input.line("服务地址 [" + url + "]：");
                if (value == null) return cancel();
                value = value.trim().isEmpty() ? url : value.trim();
                if (AgentConfiguration.validApiUrl(value)) { url = value; break; }
                out.println("请输入完整的 http:// 或 https:// 服务地址。");
            }
        }
        AgentConfiguration selected = new AgentConfiguration(key, url, model);
        while (true) {
            String answer = input.line("保存配置供下次启动使用？[Y/n]：");
            if (answer == null) return cancel();
            answer = answer.trim();
            if (answer.equalsIgnoreCase("n") || answer.equalsIgnoreCase("no")) {
                out.println("配置仅在本次会话使用。");
                return selected;
            }
            if (!answer.isEmpty() && !answer.equalsIgnoreCase("y") && !answer.equalsIgnoreCase("yes")) {
                out.println("请输入 y 或 n。"); continue;
            }
            try {
                store.save(selected);
                out.println("已保存配置：" + store.userFile());
            } catch (IOException e) {
                out.println("无法保存配置，本次会话仍可继续。请检查配置目录的写入权限。");
            }
            return selected;
        }
    }

    private AgentConfiguration cancel() {
        out.println("已取消配置。");
        return null;
    }

    private String chooseModel(String current) throws IOException {
        out.println("1. " + current + "（当前默认）\n2. 自定义模型名称");
        while (true) {
            String choice = input.line("选择模型 [1]：");
            if (choice == null) return null;
            choice = choice.trim();
            if (choice.isEmpty() || choice.equals("1")) return current;
            if (choice.equals("2")) {
                String model = input.line("模型名称：");
                if (model == null) return null;
                if (AgentConfiguration.validModel(model.trim())) return model.trim();
                out.println("模型名称不能为空，只能包含字母、数字及 . _ : / -。");
            } else {
                out.println("请输入 1 或 2。");
            }
        }
    }
}
