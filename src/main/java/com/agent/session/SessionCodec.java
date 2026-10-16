package com.agent.session;

import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 版本化会话协议。先完整校验，再交付不可变的非系统历史。 */
public final class SessionCodec {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public byte[] encode(List<Message> messages) throws IOException {
        validate(messages);
        var root = MAPPER.createObjectNode().put("version", 1);
        root.set("messages", MAPPER.valueToTree(messages));
        var bytes = new ByteArrayOutputStream();
        // 在写入过程中限制大小，而不是分配整个超大 JSON 后才检查。
        try (var limited = new OutputStream() {
            private int count;
            @Override public void write(int value) throws IOException {
                reserve(1); bytes.write(value);
            }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                reserve(length); bytes.write(data, offset, length);
            }
            private void reserve(int length) throws IOException {
                if (length > MAX_BYTES - count) throw new IOException("会话文件超过 16 MiB 限制");
                count += length;
            }
        }) {
            MAPPER.writeValue(limited, root);
        }
        return bytes.toByteArray();
    }

    public List<Message> decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("会话文件超过 16 MiB 限制");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("会话文件不是有效的 UTF-8", e); }
        JsonNode root;
        try { root = MAPPER.readTree(text); }
        catch (IOException e) { throw new IOException("会话 JSON 损坏、字段重复或包含多余内容", e); }
        fields(root, "version", "messages");
        JsonNode version = root.get("version");
        require(version.isIntegralNumber() && version.canConvertToInt() && version.intValue() == 1,
                "不支持的会话格式版本");
        return parseMessages(root.get("messages"));
    }

    public static void validate(List<Message> messages) throws IOException {
        if (messages == null) throw new IOException("会话消息不能为空");
        parseMessages(MAPPER.valueToTree(messages));
    }

    private static List<Message> parseMessages(JsonNode array) throws IOException {
        require(array != null && array.isArray(), "messages 必须为数组");
        List<Message> messages = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (JsonNode node : array) {
            fields(node, "role", "content", "toolCalls", "toolCallId");
            String role = nonBlank(node.get("role"), "role");
            require(Set.of("user", "assistant", "tool").contains(role), "不支持的消息角色");
            boolean tool = role.equals("tool");
            require(pending.isEmpty() ? !tool : tool, "工具调用与结果不完整或顺序错误");
            JsonNode callsNode = node.get("toolCalls");
            List<ToolCall> calls = null;
            if (!callsNode.isNull()) {
                require(role.equals("assistant") && callsNode.isArray() && !callsNode.isEmpty(),
                        "toolCalls 只能是助手消息中的非空数组");
                calls = new ArrayList<>();
                for (JsonNode call : callsNode) {
                    fields(call, "id", "function");
                    String id = nonBlank(call.get("id"), "工具调用 ID");
                    require(ids.add(id), "工具调用 ID 重复");
                    JsonNode function = call.get("function");
                    fields(function, "name", "arguments");
                    String name = nonBlank(function.get("name"), "工具名称");
                    String arguments = nullableText(function.get("arguments"), "arguments");
                    calls.add(new ToolCall(id, new ToolCall.Function(name, arguments)));
                    pending.addLast(id);
                }
            }
            String content = nullableText(node.get("content"), "content");
            require(content != null || calls != null, "没有工具调用的消息正文必须为字符串");
            String toolId = nullableText(node.get("toolCallId"), "toolCallId");
            if (tool) {
                require(toolId != null && !toolId.isBlank() && toolId.equals(pending.removeFirst()),
                        "工具结果 ID 与调用不匹配");
            } else require(toolId == null, "非工具消息的 toolCallId 必须为 null");
            messages.add(new Message(role, content, calls, toolId));
        }
        require(pending.isEmpty(), "工具调用缺少结果");
        return List.copyOf(messages);
    }

    private static void fields(JsonNode node, String... expected) throws IOException {
        require(node != null && node.isObject(), "会话结构必须为对象");
        require(node.size() == expected.length, "会话缺少字段或包含未知字段");
        for (String field : expected) require(node.has(field), "会话缺少字段：" + field);
    }

    private static String nullableText(JsonNode node, String field) throws IOException {
        require(node != null && (node.isNull() || node.isTextual()), field + " 必须为字符串或 null");
        return node.isNull() ? null : node.textValue();
    }

    private static String nonBlank(JsonNode node, String field) throws IOException {
        String value = nullableText(node, field);
        require(value != null && !value.isBlank(), field + " 不能为空");
        return value;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
