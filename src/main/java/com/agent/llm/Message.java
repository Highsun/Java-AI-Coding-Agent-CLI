package com.agent.llm;

import java.util.List;

/**
 * One entry of the Agent 的工作记忆（Message History）。
 *
 * <p>{@code toolCalls} 与 {@code toolCallId} 互斥：
 * 只有 {@code assistant} 发起工具调用时 {@code toolCalls} 非空，
 * 只有 {@code tool} 回传执行结果时 {@code toolCallId} 非空。
 * 静态工厂方法把合法的角色与字段组合集中在一处，调用方不必反复写四个构造参数。
 */
public record Message(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public Message {
        if (toolCalls != null) {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    public static Message system(String content) {
        return new Message(ROLE_SYSTEM, content, null, null);
    }

    public static Message user(String content) {
        return new Message(ROLE_USER, content, null, null);
    }

    public static Message assistant(String content) {
        return new Message(ROLE_ASSISTANT, content, null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(ROLE_ASSISTANT, content, toolCalls, null);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message(ROLE_TOOL, content, null, toolCallId);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
