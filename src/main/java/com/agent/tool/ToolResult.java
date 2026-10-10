package com.agent.tool;

/** 执行员交给记录员的真实结果。失败与成功使用相同的回传通道。 */
public record ToolResult(boolean success, String output) {
    public ToolResult { if (output == null) output = ""; }
    public static ToolResult ok(String output) { return new ToolResult(true, output); }
    public static ToolResult failure(String output) { return new ToolResult(false, output); }
}
