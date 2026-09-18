package com.agent.tool;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Lab02 中签名各异的 File Tools 适配为统一协议：
 *
 * <pre>
 * 工具名 + JSON 参数 ──► ToolRegistry ──► Lab02 Tool ──► 字符串结果
 * </pre>
 *
 * <p>这是“够用就好”的过渡设计：优点是快速统一三个工具；局限是
 * {@code Map<String,String>} 会丢失数字、布尔值、数组等参数类型，后续 Lab04
 * 会重构为 {@code Tool} 接口 + 正式 {@code ToolRegistry}/{@code ToolExecutor}。
 *
 * <p>工具失败时返回错误字符串而不是抛异常：错误字符串同样是 Observation，
 * 模型可以据此修改参数或向用户说明失败，Agent 不会因此崩溃。
 *
 * <p>安全边界（挑战任务 3）：所有路径都相对项目根目录解析，越界访问被拒绝。
 */
public class ToolRegistry {

    public static final String LIST_FILES = "list_files";
    public static final String READ_FILE = "read_file";
    public static final String SEARCH_CODE = "search_code";

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root;

    /** 注册表条目：对外协议（名称 + 描述 + JSON Schema）与执行逻辑。 */
    public record ToolEntry(
            String name,
            String description,
            JsonNode parameters,
            ToolHandler handler) {
    }

    /** 统一执行入口：拿到已解析的字符串参数，返回可回传的文本结果。 */
    public interface ToolHandler {
        String execute(Map<String, String> args) throws Exception;
    }

    /** JSON Schema 中的单个参数描述。 */
    public record Param(
            String name,
            String type,
            String description,
            boolean required) {
    }

    /** 以当前工作目录作为可访问根目录。 */
    public ToolRegistry() {
        this(Path.of("."));
    }

    /** 指定可访问根目录：工具只能读取该目录（含子目录）下的文件。 */
    public ToolRegistry(Path root) {
        this.root = root.toAbsolutePath().normalize();
        registerFileTools();
    }

    public Path root() {
        return root;
    }

    public Set<String> toolNames() {
        return Set.copyOf(tools.keySet());
    }

    /** 供 {@code LLMClient.chat} 使用的工具定义（Tool Calling 协议第一段）。 */
    public List<LLMClient.ToolDef> getToolDefinitions() {
        return tools.values().stream()
                .map(tool -> new LLMClient.ToolDef(
                        tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    /**
     * 按名称执行工具，并把一切失败转换为可回传的文本。
     *
     * @param toolName      模型给出的工具名
     * @param argumentsJson 模型给出的原始 JSON 参数字符串
     * @return 工具结果或错误说明，永不返回 {@code null}
     */
    public String execute(String toolName, String argumentsJson) {
        ToolEntry tool = tools.get(toolName);
        if (tool == null) {
            return "未知工具: " + toolName + "（可用工具: " + String.join(", ", tools.keySet()) + "）";
        }

        try {
            Map<String, String> args = parseArguments(argumentsJson);
            String result = tool.handler().execute(args);
            return result == null ? "" : result;
        } catch (Exception e) {
            return "执行工具失败: " + e.getMessage();
        }
    }

    /**
     * 把参数字符串解析为 {@code Map<String,String>}。
     *
     * <p>刻意保留 {@code asText()} 的类型损失（数字/布尔/数组都变成字符串），
     * 因为当前三个 File Tools 只接受字符串参数；这是 Lab04 要替换的过渡点。
     */
    Map<String, String> parseArguments(String argumentsJson) throws IOException {
        Map<String, String> args = new LinkedHashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return args;
        }
        JsonNode node = mapper.readTree(argumentsJson);
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    args.put(entry.getKey(), entry.getValue().asText()));
        }
        return args;
    }

    /**
     * 把模型给出的路径解析到根目录之内。
     *
     * @throws IOException 路径越界时抛出，由 {@link #execute} 转为 Observation
     */
    Path resolveInRoot(String rawPath) throws IOException {
        String value = (rawPath == null || rawPath.isBlank()) ? "." : rawPath;
        Path candidate = Path.of(value);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("路径越界，拒绝访问项目根目录之外: " + rawPath);
        }
        return resolved;
    }

    private JsonNode createParameters(Param... params) {
        ObjectNode rootNode = mapper.createObjectNode();
        rootNode.put("type", "object");
        ObjectNode properties = rootNode.putObject("properties");
        ArrayNode required = rootNode.putArray("required");

        for (Param param : params) {
            ObjectNode property = properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        rootNode.put("additionalProperties", false);
        return rootNode;
    }

    private void registerFileTools() {
        var lister = new ListFilesTool();
        var reader = new ReadFileTool();
        var searcher = new SearchCodeTool();

        tools.put(LIST_FILES, new ToolEntry(
                LIST_FILES,
                "递归列出指定目录下的文件，返回相对路径列表。需要了解项目结构时使用。",
                createParameters(
                        new Param("path", "string", "目录路径，相对项目根目录；用 . 表示根目录", true)),
                args -> "文件列表:\n" + String.join("\n",
                        lister.listFiles(resolveInRoot(args.get("path"))))));

        tools.put(READ_FILE, new ToolEntry(
                READ_FILE,
                "读取指定文本文件的内容。需要查看某个文件的实现时使用。",
                createParameters(
                        new Param("path", "string", "文件路径，相对项目根目录", true)),
                args -> "文件内容（" + args.get("path") + "）:\n"
                        + reader.readFile(resolveInRoot(args.get("path")))));

        tools.put(SEARCH_CODE, new ToolEntry(
                SEARCH_CODE,
                "在指定目录中搜索包含关键字的代码行，返回相对文件路径、行号和内容。",
                createParameters(
                        new Param("keyword", "string", "搜索关键字", true),
                        new Param("path", "string", "搜索目录，相对项目根目录；用 . 表示根目录", true)),
                args -> {
                    var matches = searcher.searchCode(
                            args.get("keyword"), resolveInRoot(args.get("path")));
                    if (matches.isEmpty()) {
                        return "搜索结果: 未找到包含 " + args.get("keyword") + " 的行";
                    }
                    StringBuilder result = new StringBuilder("搜索结果:\n");
                    matches.forEach(match -> result
                            .append(match.file()).append(":")
                            .append(match.line()).append(": ")
                            .append(match.content()).append("\n"));
                    return result.toString();
                }));
    }
}
