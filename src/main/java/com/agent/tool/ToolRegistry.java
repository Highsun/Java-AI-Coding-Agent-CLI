package com.agent.tool;

import com.agent.llm.LLMClient;
import com.agent.safety.SafetyGuard;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 注册员：名称查找与严格参数验证。不纠正工具名，不做模型决策，不执行 I/O。 */
public class ToolRegistry {
    public static final String LIST_FILES = "list_files";
    public static final String READ_FILE = "read_file";
    public static final String SEARCH_CODE = "search_code";
    public static final String LIST_DIR = "list_dir";
    public static final String WRITE_FILE = "write_file";
    public static final String EDIT_FILE = "edit_file";
    public static final String EXECUTE_COMMAND = "execute_command";

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SafetyGuard guard;

    public record Invocation(Tool tool, JsonNode arguments) { }
    public ToolRegistry() { this(Path.of(".")); }

    public ToolRegistry(Path root) {
        guard = new SafetyGuard(root);
        register(new ListFilesTool());
        register(new ReadFileTool());
        register(new SearchCodeTool());
        register(new ListDirTool());
        register(new WriteFileTool());
        register(new EditFileTool());
        register(new ExecuteCommandTool());
    }

    public void register(Tool tool) {
        String name = tool.definition().name();
        if (tools.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("工具已注册: " + name);
    }

    public Path root() { return guard.root(); }
    public SafetyGuard guard() { return guard; }
    public Set<String> toolNames() { return Set.copyOf(tools.keySet()); }
    public List<LLMClient.ToolDef> getToolDefinitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public Invocation resolve(String name, String json) throws IOException {
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("未知工具: " + name
                + "（可用工具: " + String.join(", ", tools.keySet()) + "）");
        JsonNode args = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        ToolSchemas.validate(args, tool.definition().parameters());
        return new Invocation(tool, args);
    }

    /** 保留 Lab03 的文本入口，统一委托执行员，旧调用方可继续使用。 */
    public String execute(String name, String json) {
        return new ToolExecutor(this).execute(name, json).output();
    }
}
