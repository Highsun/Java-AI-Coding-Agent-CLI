package com.agent.tool;

/** 执行员：只执行注册员已经查找、验证的工具；如实将异常转换为失败结果。 */
public final class ToolExecutor {
    private final ToolRegistry registry;
    public ToolExecutor(ToolRegistry registry) { this.registry = registry; }

    public ToolResult execute(String name, String arguments) {
        try {
            ToolRegistry.Invocation invocation = registry.resolve(name, arguments);
            return invocation.tool().execute(invocation.arguments(), registry.guard());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.failure(message.startsWith("未知工具") ? message : "执行工具失败: " + message);
        }
    }
}
