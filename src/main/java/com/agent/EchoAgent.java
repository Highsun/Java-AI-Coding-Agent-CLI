package com.agent;

/**
 * Lab 01 的输入到响应逻辑，原样保留以便 Lab01 的测试继续作为回归基线。
 *
 * <p>Lab 03 之后 CLI 的主入口使用 ReAct 版 {@link Agent}；本类不访问终端，
 * 也不参与工具调用。
 */
public class EchoAgent {
    /**
     * Responds to a single user input without performing any I/O.
     *
     * @param input user input; may be {@code null}
     * @return the greeting for blank/hello input, or an echo for other input
     */
    public String respond(String input) {
        if (input == null || input.isBlank()) {
            return "Hello, Agent!";
        }
        if ("hello".equalsIgnoreCase(input.trim())) {
            return "Hello, Agent!";
        }
        return "I heard: " + input;
    }
}
