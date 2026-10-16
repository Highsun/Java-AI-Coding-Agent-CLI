package com.agent.session;

import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SessionCodecTest {
    private final SessionCodec codec = new SessionCodec();
    static final ObjectMapper JSON = new ObjectMapper();

    static List<Message> history() {
        return List.of(Message.user("中文\n\"引号\" 😀"),
                Message.assistant(null, List.of(
                        new ToolCall("a", new ToolCall.Function("read_file", "{bad JSON")),
                        new ToolCall("b", new ToolCall.Function("unknown", null)))),
                Message.tool("a", "执行工具失败：参数错误"), Message.tool("b", "执行工具失败：未知工具"),
                Message.assistant("完成"));
    }

    @Test void roundTripsUnicodeNullArgumentsAndFailedToolHistory() throws Exception {
        assertEquals(history(), codec.decode(codec.encode(history())));
        assertEquals(List.of(), codec.decode(codec.encode(List.of())));
        var root = JSON.readTree(codec.encode(history()));
        assertEquals(2, root.size());
        assertFalse(new String(codec.encode(history()), StandardCharsets.UTF_8).contains("system"));
    }

    static Stream<String> invalidDocuments() throws Exception {
        String valid = new String(new SessionCodec().encode(history()), StandardCharsets.UTF_8);
        var bad = new java.util.ArrayList<String>();
        bad.add("{"); bad.add("[]"); bad.add("null"); bad.add("{}");
        bad.add(valid + " {}"); bad.add(valid.replace("\"version\":1", "\"version\":1,\"version\":1"));
        bad.add(valid.replace("\"version\":1", "\"version\":2"));
        bad.add(valid.replace("\"version\":1", "\"version\":1.0"));
        bad.add(valid.replace("\"version\":1", "\"version\":4294967297"));
        bad.add(valid.replace("\"role\":\"user\"", "\"role\":\"system\""));
        bad.add(valid.replace("\"role\":\"user\"", "\"role\":8"));
        bad.add(valid.replace("\"role\":\"user\",", ""));
        bad.add(valid.replace("\"role\":\"user\"", "\"role\":\"user\",\"extra\":0"));
        bad.add(valid.replace("\"id\":\"b\"", "\"id\":\"a\""));
        bad.add(valid.replace("\"id\":\"b\"", "\"id\":\" \""));
        bad.add(valid.replace("\"arguments\":null", "\"arguments\":{}"));
        bad.add(valid.replace("\"toolCallId\":\"a\"", "\"toolCallId\":\"b\""));
        bad.add(valid.replace("\"toolCallId\":\"a\"", "\"toolCallId\":null"));
        ObjectNode missingResult = (ObjectNode) JSON.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingResult.get("messages")).remove(2);
        bad.add(missingResult.toString());
        bad.add("{\"version\":1,\"messages\":[{\"role\":\"tool\",\"content\":\"x\",\"toolCalls\":null,\"toolCallId\":\"orphan\"}]}");
        bad.add(valid.replace("\"name\":\"unknown\"", "\"name\":\"unknown\",\"extra\":0"));
        return bad.stream();
    }

    @ParameterizedTest @MethodSource("invalidDocuments")
    void rejectsInvalidStructureAsAWhole(String json) {
        assertThrows(IOException.class, () -> codec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void rejectsInvalidUtf8AndAllowsExactSizeButNotOneMoreByte() throws Exception {
        assertThrows(IOException.class, () -> codec.decode(new byte[]{(byte) 0xc3, 0x28}));
        byte[] base = codec.encode(List.of());
        byte[] padded = new byte[SessionCodec.MAX_BYTES];
        java.util.Arrays.fill(padded, (byte) ' ');
        System.arraycopy(base, 0, padded, 0, base.length);
        assertEquals(List.of(), codec.decode(padded));
        assertThrows(IOException.class, () -> codec.decode(java.util.Arrays.copyOf(padded, padded.length + 1)));
        int overhead = codec.encode(List.of(Message.user(""))).length;
        String body = "x".repeat(SessionCodec.MAX_BYTES - overhead);
        assertEquals(SessionCodec.MAX_BYTES, codec.encode(List.of(Message.user(body))).length);
        assertThrows(IOException.class, () -> codec.encode(List.of(Message.user(body + "x"))));
    }

    @Test void rejectsInvalidHistoryBeforeSaving() {
        assertThrows(IOException.class, () -> codec.encode(List.of(Message.system("old"))));
        assertThrows(IOException.class, () -> codec.encode(List.of(Message.assistant(null))));
        assertThrows(IOException.class, () -> codec.encode(history().subList(0, 2)));
        assertThrows(IOException.class, () -> codec.encode(List.of(new Message("user", "x", List.of(), null))));
    }
}
