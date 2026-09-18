# Java AI Coding Agent CLI

一个持续演进的 Java AI Coding Agent CLI 项目。

## 项目状态

当前已完成 Lab 01、Lab 02 和 Lab 03。版本能力从“可运行的 CLI”，到“能够读取和搜索项目文件的工具集”，再到“由大模型自主决定何时调用工具的 ReAct Agent”。后续 Lab 应继续沿用本 README 的版本记录方式，并只补充新增能力。

| 版本 | 实验 | 新增能力 | 当前状态 |
|---|---|---|---|
| V1 | Lab 01：Java Project Bootstrap | Maven 工程、Java CLI、Agent 逻辑、JUnit 测试、Git 版本点 | 已完成 |
| V2 | Lab 02：File Tools | 递归列文件、读取文本、搜索代码、文件工具测试与 Demo | 已完成 |
| V3 | Lab 03：LLM Client + ReAct Agent + Tool Calling | GLM Client、ToolRegistry、Tool Calling、ReAct Loop、离线 Fake 测试、可执行 fat jar | 已完成 |

## 环境要求

- JDK 17 或更高版本
- Maven 3.9+
- Git
- 网络（仅首次构建需要下载 OkHttp / Jackson / slf4j；离线测试不需要网络）

项目通过 Maven 的 `maven.compiler.release=17` 保持 Java 17 编译目标。Lab 01 和 Lab 02 只使用 JDK 内置文件 API；Lab 03 新增 OkHttp（HTTP）与 Jackson（JSON）两个第三方运行时依赖，并用 Maven Shade 插件打包成可执行 fat jar。

## 配置 GLM API Key（Lab 03 起）

在项目根目录（包含 `pom.xml` 的 `code/`）执行：

```bash
cp .env.example .env
# 编辑 .env，把 your-glm-api-key 换成真实 Key
```

也可以临时使用环境变量：

```bash
export GLM_API_KEY="你的真实Key"
```

`.env` 已被 `.gitignore` 忽略。不要把真实 Key 写进源码、截图或实验报告，更不要提交到 Git。

## 快速开始

在项目根目录（包含 `pom.xml` 的 `code/`）执行：

```bash
# 运行全部测试（39 个，离线，不需要 API Key）
mvn test

# 打包可执行 fat jar
mvn -q -DskipTests package

# 运行 CLI：必须从项目根目录运行，因为 .env 按当前工作目录查找
java -jar target/agent-cli.jar

# Lab 02 的文件工具 Demo 仍然可用
java -cp target/classes:target/agent-cli.jar com.agent.tool.FileToolsDemo README.md TODO
```

预期测试结果：当前共 39 个测试，Failures 和 Errors 均为 0，Maven 输出 `BUILD SUCCESS`。全部测试都是离线测试，不需要 API Key，也不访问外网。

如果本机 Maven 默认仓库不可写，可以将依赖缓存切换到临时目录：

```bash
mvn -Dmaven.repo.local=/tmp/agent-cli-m2 test
```

## Lab 01：Java Project Bootstrap（V1）

### 目标

建立标准 Maven Java 17 工程，并完成“输入 → 处理 → 输出”的最小 CLI 闭环：

```text
stdin -> Main -> Agent.respond -> stdout
```

### 实现内容

- `pom.xml`：项目坐标 `com.agent:agent-cli:0.1.0`、Java 17、JUnit Jupiter 5.10.2。
- `EchoAgent`：只负责输入到响应的纯逻辑，不访问终端（Lab 01 时类名为 `Agent`；Lab 03 起 `Agent` 改为 ReAct Agent，这段逻辑与它的 5 个测试整体迁移到 `EchoAgent` / `EchoAgentTest`，继续作为回归基线）。
- `Main`：负责 stdin/stdout 接线，逐行读取输入。
- `EchoAgentTest`：验证问候、大小写和首尾空白、普通输入、空白和 `null`。
- `.gitignore`：忽略 `target/`、IDE 配置和系统文件。

### CLI 行为

启动：

```bash
java -jar target/agent-cli.jar
```

输入行为：

- Lab 01 的 `EchoAgent` 行为：`hello`、大小写混合形式或纯空白输出 `Hello, Agent!`，其他输入输出 `I heard: <input>`；
- Lab 03 起 CLI 主流程改为对话式：输入问题交给 ReAct Agent，`clear` 清空历史，`exit`/`quit` 退出。

Lab 01 的工程检查点是：项目能通过 Maven 测试，CLI 能从 stdin 读取并向 stdout 输出，并且已有 Git 提交作为可回退版本。

## Lab 02：File Tools（V2）

### 目标

让 Agent 获得读取和搜索项目代码的基础能力。三个工具目前保持独立类和不同返回类型，这是有意保留的技术债，后续 Lab 可据此抽象统一 `Tool` 接口。

```text
项目文件系统
      ^
      |
ListFilesTool / ReadFileTool / SearchCodeTool
```

### 工具清单

| 类 | 方法 | 返回值 | 行为 |
|---|---|---|---|
| `ListFilesTool` | `listFiles(Path root)` | `List<String>` | 递归列出普通文件，返回排序后的相对路径 |
| `ReadFileTool` | `readFile(Path path)` | `String` | 按 UTF-8 读取普通文件；路径不存在或不是文件时抛出 `NoSuchFileException` |
| `SearchCodeTool` | `searchCode(String keyword, Path root)` | `List<Match>` | 递归搜索包含关键字的行，返回文件、行号和内容 |
| `FileToolsDemo` | `main(String[] args)` | `void` | 从命令行演示三个工具 |

`SearchCodeTool` 会跳过常见二进制扩展（如 `.class`、`.png`、`.jar`），单个文件读取失败时继续搜索其他文件。`Match` 使用 Java 17 `record` 表示。

### Demo

```bash
mvn -q -DskipTests compile
java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO
```

Demo 依次执行：

1. 列出当前项目的前 20 个文件；
2. 读取并打印 `README.md`；
3. 搜索 `TODO`，输出命中文件、行号和内容。

### 测试

Lab 02 的测试位于 `src/test/java/com/agent/tool/`，使用 JUnit 5 `@TempDir` 创建隔离测试目录，不依赖真实项目结构：

- `ListFilesToolTest`：递归、普通文件过滤和排序；
- `ReadFileToolTest`：UTF-8 读取、缺失文件、目录路径异常；
- `SearchCodeToolTest`：嵌套目录、行号和内容、二进制扩展跳过。

## Lab 03：LLM Client + ReAct Agent + Tool Calling（V3）

### 目标

把 Lab 02 中需要用户直接操作的 File Tools，升级为由大模型自主选择和调用工具的 Agent：

```text
用户 ──► Agent ──► GLM ──Tool Calling──► File Tools
 ▲                                              │
 └─────────────── 最终回答 ◄── Observation ─────┘
```

主线只有一句话：**模型负责决策，Java 程序负责执行，执行结果必须回传给模型。**

### 整体架构

```text
Agent
├── LLMClient                 （接口：依赖倒置，可替换实现）
│   └── GlmClient ──HTTP/JSON──► GLM Chat Completions
├── ToolRegistry
│   ├── list_files  ──► ListFilesTool
│   ├── read_file   ──► ReadFileTool
│   └── search_code ──► SearchCodeTool
└── Message History
    └── system / user / assistant / tool
```

| 组件 | 核心职责 | 不应该负责 |
|---|---|---|
| `GlmClient` | HTTP 通信与协议转换 | 执行本地工具、控制 ReAct 循环 |
| `ToolRegistry` | 描述、查找、执行工具 | 决定下一步调用哪个工具 |
| `Agent` | 维护历史并控制循环 | 直接实现读写文件逻辑 |
| `Main` | 创建对象、接线、处理 CLI | 实现业务规则 |

### 新增类清单

| 类 | 位置 | 作用 |
|---|---|---|
| `Message` | `com.agent.llm` | 一条历史消息：`role` + `content` + `toolCalls`/`toolCallId`（二者互斥），并提供静态工厂方法 |
| `ToolCall` | `com.agent.llm` | 模型表达的工具调用意图：`id`、工具名、JSON 参数字符串 |
| `LLMResponse` | `com.agent.llm` | 模型响应：`content`、`toolCalls`、token 用量；`hasToolCalls()` 是循环唯一分支判据 |
| `LLMClient` | `com.agent.llm` | 接口 + `ToolDef` 工具定义，隔离 Agent 与具体模型服务 |
| `GlmClient` | `com.agent.llm` | OkHttp + Jackson 实现 Chat Completions：拼接请求、Bearer 鉴权、解析响应、翻译 HTTP 错误 |
| `ToolRegistry` | `com.agent.tool` | 把三个 File Tool 适配为 `execute(name, jsonArgs) -> String`，生成 JSON Schema，并限制根目录边界 |
| `Agent` | `com.agent` | ReAct Loop：`LLMResponse → 工具执行 → Observation 回传 → 下一轮` |
| `EchoAgent` | `com.agent` | Lab 01 的输入/响应逻辑，保留回归测试 |
| `FakeLLMClient` | `com.agent.llm`（test） | 预编排响应，离线测试非确定性的 Agent 控制逻辑 |

### ReAct Loop 实现要点

1. `Agent` 依赖 `LLMClient` 接口而不是 `GlmClient` 具体类，因此测试可换成 `FakeLLMClient`；
2. 有 Tool Call 时历史必须按固定顺序增长，`assistant(tool_calls)` 一定不能省略：

   ```text
   user
   assistant(tool_calls: call_1)
   tool(tool_call_id: call_1)
   assistant(final answer)
   ```

3. 一次响应可能包含多个 `tool_calls`，按返回顺序逐个执行并全部回传；
4. 工具失败不抛异常，而是返回错误字符串——错误字符串同样是 Observation，模型可据此修正参数或向用户解释；
5. 三个退出路径：模型给出最终回答、达到 `MAX_ITERATIONS = 10`、LLM 调用抛异常。

### 选做/挑战任务落地情况

| 任务 | 实现位置 |
|---|---|
| 限制工具结果长度 | `Agent.MAX_OBSERVATION_CHARS = 8000`，超长 Observation 截断后仍回传并附加截断提示 |
| 完善 JSON Schema | `ToolRegistry.createParameters` 支持描述、`required` 与 `additionalProperties=false` |
| `clear` 清空历史 | `Agent.clearHistory()` + `Main` 的 `clear` 命令（AT07） |
| 区分 HTTP 错误 | `GlmClient.describeHttpError`：401/403 鉴权、429 限流、5xx 服务端异常、400 请求结构 |
| 多工具调用 | `Agent.run()` 遍历 `response.toolCalls()`，测试覆盖同一轮的两次调用 |
| Tool 安全边界 | `ToolRegistry.resolveInRoot`：路径一律相对项目根目录解析，越界（如 `../secret.txt`）被拒绝 |

### 运行

```bash
# 1. 配置 Key（见上文“配置 GLM API Key”）
# 2. 打包并运行
mvn -q -DskipTests package
java -jar target/agent-cli.jar
```

CLI 交互示例：

```text
你：你好
你：读取 pom.xml 并告诉我用了哪些依赖
你：搜索 TODO，并读取其中一个文件
你：clear
你：exit
```

需要项目内容时会先打印 `执行工具: read_file`、`参数: {"path":"pom.xml"}` 和结果摘要，再输出基于 Observation 的最终回答。

### 测试

Lab 03 的测试全部离线，不需要 API Key，也不访问外网：

| 测试类 | 覆盖内容 |
|---|---|
| `AgentTest` | 最终回答直接退出、Tool Call 触发第二轮、Observation 回传、未知工具、工具失败回传、同轮多调用、`MAX_ITERATIONS` 安全阀、`clearHistory`、超长 Observation 截断、首轮请求带 system + 3 个工具定义 |
| `AgentEndToEndTest` | 本地假 GLM 服务器 + 真实 `ReadFileTool` 的完整链路（AT03/AT06） |
| `GlmClientTest` | 本地 `HttpServer` 验证 Bearer 鉴权、messages/tools 序列化、`content=null` 仍解析 `tool_calls`、usage 解析、401/429/5xx/缺少 choices 错误、空 Key 拒绝、请求体不含 Key |
| `ToolRegistryTest` | 三个工具注册与 Schema、复用 Lab02 工具、未知工具、非法 JSON、路径越界拒绝、空参数 |
| `MainTest` | `.env` 读取、BOM 与引号处理、文件缺失时回退 |
| `EchoAgentTest` | Lab 01 行为回归（5 个原有用例） |

### AT01～AT07 对照

| 编号 | 验收项 | 本项目中的验证方式 |
|---|---|---|
| AT01 | GLM 连通 | 配置真实 Key 后运行 CLI 输入“你好”，或 `GlmClientTest.shouldParseFinalAnswerWithUsage` 验证离线协议 |
| AT02 | 直接回答 | `AgentTest.shouldExitOnFinalAnswer` |
| AT03 | 单工具调用 | `AgentEndToEndTest.shouldReadPomThenAnswer`（`read_file` → 最终答案） |
| AT04 | 多步调用 | CLI 输入“搜索 TODO，并读取其中一个文件”；`AgentTest.shouldExecuteMultipleToolCallsInOneRound` 覆盖同轮多调用 |
| AT05 | Tool 失败 | `AgentTest.shouldFeedToolFailureBackAsObservation`（错误作为 Observation 继续） |
| AT06 | Loop 退出 | `AgentTest.shouldLoopOnToolCallAndFeedObservationBack`、`AgentTest.shouldStopAtMaxIterations` |
| AT07 | 历史清空 | `AgentTest.shouldClearHistoryKeepingOnlySystemPrompt` + CLI `clear` 命令 |

### AI 辅助开发记录（摘要）

| AI Task | 本项目的实际做法 |
|---|---|
| 01 实现 `GlmClient` | 生成初版后人工补充：非 2xx 统一抛 `IOException` 并保留状态码与响应体、`content=null` 时仍解析 `tool_calls`、错误体长度上限、Endpoint 可注入以便离线测试 |
| 02 实现 `ToolRegistry` | 复用 Lab 02 的三个工具而非重写；保留 `asText()` 的类型损失作为已知技术债，并在注释中说明是 Lab04 的替换点；补充路径越界与非法 JSON 处理 |
| 03 实现 ReAct Loop | 逐行核对 `MAX_ITERATIONS`、`assistant(tool_calls)` 先入历史、每个 `tool` 消息带正确 `tool_call_id`、工具失败仍回传、无 Tool Call 时退出 |
| 04 Debug | 故障复现方式：把 `call.id()` 写成工具名后，`GlmClientTest.shouldSerializeMessagesAndToolDefinitions` 与 `AgentEndToEndTest` 的 `tool_call_id` 断言会立即失败，据此定位并修复 |

## 当前架构

```text
Main
 |
 +--> Agent                          Lab 03：ReAct 循环 + Message History
 |     +--> LLMClient (接口)
 |     |     └--> GlmClient ──HTTP/JSON──► GLM
 |     +--> ToolRegistry
 |           +--> list_files  -> ListFilesTool
 |           +--> read_file   -> ReadFileTool
 |           +--> search_code -> SearchCodeTool
 |
 +--> EchoAgent                      Lab 01：CLI 响应逻辑（回归基线）

com.agent.tool（Lab 02 独立文件工具，未修改）
```

## 目录结构

```text
.
├── .env.example
├── .gitignore
├── README.md
├── pom.xml
└── src
    ├── main/java/com/agent
    │   ├── Agent.java
    │   ├── EchoAgent.java
    │   ├── Main.java
    │   ├── llm
    │   │   ├── GlmClient.java
    │   │   ├── LLMClient.java
    │   │   ├── LLMResponse.java
    │   │   ├── Message.java
    │   │   └── ToolCall.java
    │   └── tool
    │       ├── FileToolsDemo.java
    │       ├── ListFilesTool.java
    │       ├── ReadFileTool.java
    │       ├── SearchCodeTool.java
    │       └── ToolRegistry.java
    └── test/java/com/agent
        ├── AgentEndToEndTest.java
        ├── AgentTest.java
        ├── EchoAgentTest.java
        ├── MainTest.java
        ├── llm
        │   ├── FakeLLMClient.java
        │   └── GlmClientTest.java
        └── tool
            ├── ListFilesToolTest.java
            ├── ReadFileToolTest.java
            ├── SearchCodeToolTest.java
            └── ToolRegistryTest.java
```

构建产物位于 `target/`，由 `.gitignore` 忽略。`target/agent-cli.jar` 是可执行 fat jar（含 OkHttp / Jackson）。

`.env` 存放本地 API Key，同样被 `.gitignore` 忽略；仓库只保留占位文件 `.env.example`。

## Git 检查点

当前项目的主要提交：

```text
f725b9a bootstrap project          # Lab 01 / V1
75c0173 lab02: file tools          # Lab 02 / V2
87b53b2 docs: organize lab progress
lab03: add glm client and react agent   # Lab 03 / V3（哈希以 git log 为准）
```
