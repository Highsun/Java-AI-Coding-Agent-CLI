package com.agent.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * 用预先编排的响应替代真实模型：不联网、不消耗 Token，
 * 也不会因模型输出波动导致测试不稳定。
 *
 * <p>同时记录每次收到的完整 Message History，便于断言 Observation 是否正确回传。
 */
public class FakeLLMClient implements LLMClient {

    private final Queue<LLMResponse> script;
    private final List<List<Message>> requests = new ArrayList<>();
    private final List<List<ToolDef>> toolRequests = new ArrayList<>();
    private final boolean repeatLast;
    private LLMResponse last;

    /** 顺序返回 {@code responses}；脚本用尽后返回“无更多响应”文本。 */
    public FakeLLMClient(List<LLMResponse> responses) {
        this(responses, false);
    }

    /**
     * @param repeatLast {@code true} 时最后一次响应会被反复返回，
     *                   用于验证 {@code MAX_ITERATIONS} 安全阀
     */
    public FakeLLMClient(List<LLMResponse> responses, boolean repeatLast) {
        this.script = new ArrayDeque<>(responses);
        this.repeatLast = repeatLast;
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDef> tools) {
        requests.add(List.copyOf(messages));
        toolRequests.add(tools == null ? List.of() : List.copyOf(tools));

        if (!script.isEmpty()) {
            last = script.remove();
            return last;
        }
        if (repeatLast && last != null) {
            return last;
        }
        return new LLMResponse("（无更多响应）", null, 0, 0);
    }

    /** 每次请求收到的历史快照，按调用顺序排列。 */
    public List<List<Message>> requests() {
        return List.copyOf(requests);
    }

    /** 每次请求收到的工具定义。 */
    public List<List<ToolDef>> toolRequests() {
        return List.copyOf(toolRequests);
    }
}
