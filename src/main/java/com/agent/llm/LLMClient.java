package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;

/**
 * Agent 依赖的抽象：把“和模型对话”与“用哪个模型服务”解耦。
 *
 * <p>依赖倒置带来两个好处：测试可以换成 {@code FakeLLMClient} 离线跑，
 * 未来也可以替换成其他兼容 Chat Completions 协议的模型。
 */
public interface LLMClient {

    /** 发送给模型的工具定义（Tool Calling 协议的第一段）。 */
    record ToolDef(String name, String description, JsonNode parameters) {
    }

    /**
     * 发送完整历史与工具定义，返回模型的下一步决策。
     *
     * @param messages 完整 Message History（含 system/user/assistant/tool）
     * @param tools    可用工具定义；可为空表示不使用工具
     * @throws IOException 网络错误或非 2xx 响应
     */
    LLMResponse chat(List<Message> messages, List<ToolDef> tools) throws IOException;
}
