package com.agent;

import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.tool.ToolResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 记录员：在执行前检查调用 ID；结果只做截断，不将失败改写为成功。 */
public final class ObservationRecorder {
    private final List<Message> history;
    public ObservationRecorder(List<Message> history) { this.history = history; }

    public void beginCalls(LLMResponse response) {
        Set<String> ids = new HashSet<>();
        history.stream().filter(Message::hasToolCalls)
                .flatMap(message -> message.toolCalls().stream()).forEach(call -> ids.add(call.id()));
        for (var call : response.toolCalls()) {
            if (call.id() == null || call.id().isBlank() || !ids.add(call.id())) {
                throw new IllegalArgumentException("工具调用 ID 缺失或重复: " + call.id());
            }
            if (call.name() == null || call.name().isBlank()) {
                throw new IllegalArgumentException("工具调用名称不能为空");
            }
        }
        // 全批验证完再入历史和执行，防止半批执行后才发现协议错误。
        history.add(Message.assistant(response.content(), response.toolCalls()));
    }

    public void record(String id, ToolResult result) {
        String text = result.output();
        if (text.length() > Agent.MAX_OBSERVATION_CHARS) {
            int end = Agent.MAX_OBSERVATION_CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = text.substring(0, end) + "\n...[结果过长已截断，如需完整内容请缩小范围后重新调用工具]";
        }
        history.add(Message.tool(id, text));
    }
}
