package com.agent.tool;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 同一份 Schema 用于向模型声明参数，以及在本地验证参数。 */
public final class ToolSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolSchemas() { }
    public record Param(String name, String type, String description, boolean required) { }

    public static LLMClient.ToolDef define(String name, String description, Param... params) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (Param param : params) {
            var property = properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());
            if (param.required()) required.add(param.name());
        }
        schema.put("additionalProperties", false);
        return new LLMClient.ToolDef(name, description, schema);
    }

    public static Param text(String name, String description) {
        return new Param(name, "string", description, true);
    }

    static void validate(JsonNode args, JsonNode schema) {
        if (args == null || !args.isObject()) throw new IllegalArgumentException("参数必须是 JSON 对象");
        for (JsonNode name : schema.path("required")) {
            if (!args.has(name.asText())) throw new IllegalArgumentException("缺少必填参数: " + name.asText());
        }
        var fields = args.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode property = schema.path("properties").get(entry.getKey());
            if (property == null) throw new IllegalArgumentException("未知参数: " + entry.getKey());
            boolean valid = switch (property.path("type").asText()) {
                case "string" -> entry.getValue().isTextual();
                case "integer" -> entry.getValue().isIntegralNumber() && entry.getValue().canConvertToInt();
                case "boolean" -> entry.getValue().isBoolean();
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("参数类型错误: " + entry.getKey()
                    + " 应为 " + property.path("type").asText());
        }
    }
}
