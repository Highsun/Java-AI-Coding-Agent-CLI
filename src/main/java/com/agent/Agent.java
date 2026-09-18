package com.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent：维护 Message History，并控制“模型决策 → 工具执行 → 结果回传”的循环。
 *
 * <pre>
 * LLMResponse
 *    ├── hasToolCalls() == true
 *    │      └── 保存 assistant 调用 → 执行工具 → 回传 tool 消息 → 下一轮
 *    └── hasToolCalls() == false
 *           └── 保存并返回最终回答 → 退出
 * </pre>
 *
 * <p>三个退出路径：模型给出最终回答、达到 {@link #MAX_ITERATIONS}、LLM 调用异常。
 */
public class Agent {

    /** 安全阀：避免工具持续失败时模型反复用相同参数形成死循环。 */
    public static final int MAX_ITERATIONS = 10;

    /** 单条 Observation 进入上下文的最大长度（选做任务 1），避免上下文被大文件撑爆。 */
    static final int MAX_OBSERVATION_CHARS = 8_000;

    /** 终端只显示结果摘要，完整结果仍然回传给模型。 */
    private static final int TRACE_PREVIEW_CHARS = 200;

    private static final String TRUNCATION_NOTICE =
            "\n...[结果过长已截断，如需完整内容请缩小范围后重新调用工具]";

    static final String SYSTEM_PROMPT = """
        你是一个 Java 编程助手 Agent，可以读取和搜索用户的项目代码。

        可用工具：
        1. list_files：列出目录下的文件；
        2. read_file：读取文件内容；
        3. search_code：搜索代码。

        需要了解项目代码时，请调用工具，不要凭空猜测文件内容。
        获得工具结果后，再基于结果继续完成任务。
        工具返回失败信息时，请根据错误调整参数或向用户说明原因。
        请用中文回复用户。
        """;

    private final LLMClient llm;
    private final ToolRegistry registry;
    private final PrintStream trace;
    private final List<Message> history = new ArrayList<>();

    public Agent(LLMClient llm, ToolRegistry registry) {
        this(llm, registry, System.out);
    }

    /** 测试用构造器：把过程输出重定向到缓冲区，保持测试日志干净。 */
    public Agent(LLMClient llm, ToolRegistry registry, PrintStream trace) {
        this.llm = llm;
        this.registry = registry;
        this.trace = trace;
        history.add(Message.system(SYSTEM_PROMPT));
    }

    /**
     * 执行一次完整的 ReAct 循环。
     *
     * @param userInput 用户输入
     * @return 最终回答，或循环无法完成时的说明
     */
    public String run(String userInput) {
        history.add(Message.user(userInput));

        for (int round = 1; round <= MAX_ITERATIONS; round++) {
            trace.println("\n思考中……第 " + round + " 轮");

            LLMResponse response;
            try {
                response = llm.chat(history, registry.getToolDefinitions());
            } catch (Exception e) {
                return "LLM 调用失败: " + e.getMessage();
            }

            trace.printf("Token：输入=%d，输出=%d%n",
                    response.promptTokens(), response.completionTokens());

            if (!response.hasToolCalls()) {
                String answer = response.content() == null ? "" : response.content();
                history.add(Message.assistant(answer));
                return answer;
            }

            // 先保存带 tool_calls 的 assistant 消息，再回传结果，
            // 否则模型看不到“自己上轮调用了什么”，API 会报消息角色/配对错误。
            history.add(Message.assistant(response.content(), response.toolCalls()));

            for (ToolCall call : response.toolCalls()) {
                trace.println("执行工具: " + call.name());
                trace.println("参数: " + call.arguments());

                String observation = limit(registry.execute(call.name(), call.arguments()));
                trace.println("结果: " + preview(observation));

                history.add(Message.tool(call.id(), observation));
            }
        }

        return "达到最大推理轮数限制（" + MAX_ITERATIONS + "）";
    }

    /** 清空历史，只保留 system 消息（CLI 的 {@code clear} 命令）。 */
    public void clearHistory() {
        history.clear();
        history.add(Message.system(SYSTEM_PROMPT));
    }

    /** 当前历史的只读快照，便于测试与排查。 */
    public List<Message> history() {
        return List.copyOf(history);
    }

    private static String limit(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_OBSERVATION_CHARS
                ? text
                : text.substring(0, MAX_OBSERVATION_CHARS) + TRUNCATION_NOTICE;
    }

    private static String preview(String text) {
        if (text == null || text.length() <= TRACE_PREVIEW_CHARS) {
            return text;
        }
        return text.substring(0, TRACE_PREVIEW_CHARS) + "...";
    }
}
