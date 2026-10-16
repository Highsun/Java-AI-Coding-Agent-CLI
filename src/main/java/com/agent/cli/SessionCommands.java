package com.agent.cli;

import com.agent.Agent;
import com.agent.session.SessionStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/** 会话命令及丢弃确认共享同一输入流；EOF 是结束信号，不是默认同意。 */
public final class SessionCommands {
    @FunctionalInterface public interface Input { String read(String prompt) throws IOException; }
    private final Input input;
    private final PrintStream out;
    private boolean ended;

    public SessionCommands(Input input, PrintStream out) { this.input = input; this.out = out; }
    public boolean ended() { return ended; }

    public boolean handle(String line, Agent agent, Path root) throws IOException {
        String[] words = line.strip().split("\\s+", 2);
        if (!words[0].equals("/save") && !words[0].equals("/load")) return false;
        if (words.length != 2 || words[1].isBlank()) {
            out.println("用法：" + words[0] + " <会话名>"); return true;
        }
        String name = words[1].strip();
        try {
            SessionStore store = new SessionStore(root);
            Path file = store.path(name);
            if (words[0].equals("/save")) {
                boolean exists = store.exists(name);
                if (exists && !confirm("覆盖会话「" + name + "」(" + file + ")？")) return true;
                store.save(name, agent.sessionHistory(), exists);
                agent.markSaved();
                out.println("会话已保存：" + file);
            } else {
                var messages = store.load(name);
                String warning = agent.hasUnsavedHistory() ? "当前有未保存历史。" : "";
                if (!confirm(warning + "加载会话「" + name + "」(" + file + ") 并替换当前历史？")) return true;
                agent.restoreHistory(messages);
                out.println("会话已恢复：" + file);
            }
        } catch (IOException e) {
            // 解析器异常可能含原始正文，只显示本地受控说明；文件系统异常不输出内容。
            String reason = e instanceof java.nio.file.FileSystemException
                    ? "请检查文件、目录及访问权限" : e.getMessage();
            out.println("会话操作失败：" + reason);
        }
        return true;
    }

    public boolean allowDiscard(Agent agent, String action) throws IOException {
        return !agent.hasUnsavedHistory() || confirm("当前有未保存历史，放弃并" + action + "？");
    }

    public void warnOnEnd(Agent agent) {
        if (agent.hasUnsavedHistory()) out.println("输入已结束：当前对话尚未保存，本次未自动保存。");
    }

    private boolean confirm(String prompt) throws IOException {
        String answer = input.read(prompt + " [y/N]：");
        if (answer == null) ended = true;
        boolean yes = answer != null && (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes"));
        if (!yes) out.println("已取消。");
        return yes;
    }
}
