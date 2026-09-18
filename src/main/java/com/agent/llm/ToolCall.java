package com.agent.llm;

/**
 * 模型表达的“调用哪个工具、传什么参数”的意图。
 *
 * <p>{@code arguments} 是包含 JSON 的字符串（例如 {@code {"path":"pom.xml"}}），
 * 由 {@code ToolRegistry} 再解析一次。
 */
public record ToolCall(String id, Function function) {

    /** function 字段：工具名 + 原始 JSON 参数字符串。 */
    public record Function(String name, String arguments) {
    }

    public String name() {
        return function == null ? null : function.name();
    }

    public String arguments() {
        return function == null ? null : function.arguments();
    }
}
