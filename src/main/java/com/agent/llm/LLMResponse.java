package com.agent.llm;

import java.util.List;

/**
 * 一次模型调用的结果。
 *
 * <p>{@link #hasToolCalls()} 是 ReAct 循环唯一的继续/退出判据：
 * 有工具调用就执行后回传，没有就把 {@code content} 当作最终回答。
 */
public record LLMResponse(
        String content,
        List<ToolCall> toolCalls,
        int promptTokens,
        int completionTokens) {

    public LLMResponse {
        if (toolCalls != null) {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    public static LLMResponse text(String content) {
        return new LLMResponse(content, null, 0, 0);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
