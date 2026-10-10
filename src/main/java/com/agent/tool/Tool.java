package com.agent.tool;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.agent.safety.SafetyGuard;

/** 工具只描述能力并执行动作；不选择下一步，也不维护对话历史。 */
public interface Tool {
    LLMClient.ToolDef definition();
    ToolResult execute(JsonNode arguments, SafetyGuard guard) throws Exception;
}
