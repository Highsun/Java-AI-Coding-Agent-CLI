# Java AI Coding Agent CLI

一个持续演进的 Java AI Coding Agent CLI 项目。

## 项目状态

当前已完成 Lab 01～03、10.9 Coding Agent，并新增 10.16 的会话保存与恢复。下文旧阶段内容保留历史记录；最新行为见本节与 [SPEC.md](SPEC.md)，实际测试记录见 [会话验收记录](dev/SESSION_ACCEPTANCE.md)。

| 版本 | 实验 | 新增能力 | 当前状态 |
|---|---|---|---|
| V1 | Lab 01：Java Project Bootstrap | Maven 工程、Java CLI、Agent 逻辑、JUnit 测试、Git 版本点 | 已完成 |
| V2 | Lab 02：File Tools | 递归列文件、读取文本、搜索代码、文件工具测试与 Demo | 已完成 |
| V3 | Lab 03：LLM Client + ReAct Agent + Tool Calling | GLM Client、ToolRegistry、Tool Calling、ReAct Loop、离线 Fake 测试、可执行 fat jar | 已完成 |
| V4 | 10.9：完整 Coding Agent | 统一 Tool、严格参数校验、文件写入/编辑、真实命令执行、调用记录与安全守卫 | 已完成 |
| V5 | 10.16：会话保存与恢复 | 手动保存/恢复、完整工具历史、丢弃提醒、严格校验、离线验收 | 已完成 |

## 10.16：会话保存与恢复（V5）

会话文件位于**当前项目**的 `.agent/sessions/`，与 CLI 启动目录和 API 配置保存位置分离。可用 `--root` 或 `/root` 选择项目。启动时不自动恢复，完成对话及退出时不自动保存。

```text
你：读取 Hello.java 并解释它的作用
你：/save 课堂练习_01
你：exit

再次启动后：
你：/load 课堂练习_01
加载会话并替换当前历史？[y/N]：y
你：继续解释刚才的方法
```

- `/save <会话名>`：保存内存历史；同名文件覆盖前确认。名称为 1～64 个汉字、英文字母、数字、`_` 或 `-`，不含扩展名、空格或路径。
- `/load <会话名>`：先完整校验，再询问是否替换当前历史；恢复后使用当前系统提示、模型和项目目录，保留工具调用、结果及关联 ID，不重新执行历史工具。
- 确认只接受 `y`/`yes`（忽略大小写和两侧空白）；空行、`n` 或其他输入取消。管道输入须显式提供确认行。
- 有未保存对话时，退出、清空、切换模型／配置／项目之前会提醒；可取消后手动保存。EOF 或 Ctrl+C 结束程序时提醒但不自动保存。
- 文件损坏、缺少字段、工具结果配对错误或大小超过 16 MiB 时拒绝加载，保留当前历史。写入采用同目录临时文件，覆盖采用原子替换；失败不会假报保存成功。
- 会话采用 UTF-8 JSON v1，不保存配置凭证。对话正文按原样保存，用户主动输入的秘密不会自动脱敏。会话目录从文件工具访问、列举和搜索中隔离；命令进程不因此获得操作系统沙箱。

开发指令见 [AGENTS.md](AGENTS.md)，Claude Code 项目入口见 [CLAUDE.md](CLAUDE.md)。三轮访谈和行为约定见 [SPEC.md](SPEC.md)。

离线验证（不使用真实 API Key，不调用真实模型）：

```bash
mvn test
mvn -q -DskipTests package
python3 dev/acceptance.py
python3 dev/session_acceptance.py
```

会话脚本包含 Unix PTY 验证，适用于 macOS/Linux；JUnit 与原有管道验收不依赖 PTY。证据位于 `target/session-acceptance-results.json`，含被测 JAR 的 SHA-256、CLI 输出及本地假模型收到的请求。

## 环境要求

- JDK 17 或更高版本
- Maven 3.9+
- Git
- 网络（仅首次构建需要下载 OkHttp / Jackson / slf4j；离线测试不需要网络）

项目通过 Maven 的 `maven.compiler.release=17` 保持 Java 17 编译目标。Lab 01 和 Lab 02 只使用 JDK 内置文件 API；Lab 03 新增 OkHttp（HTTP）与 Jackson（JSON）两个第三方运行时依赖，并用 Maven Shade 插件打包成可执行 fat jar。

## 首次启动与配置

v0.1.1 起，首次启动 CLI 时会在终端中引导输入 API Key（隐藏回显）、选择模型和服务地址，并询问是否保存。
配置完成后直接进入任务界面，无需编辑文件。保存位置为 `~/.config/java-coding-agent/.env`（设置 `XDG_CONFIG_HOME` 时使用该目录）；Unix/macOS 文件权限为 `600`。
进入后可使用 `/config` 重新配置、`/model` 选择模型、`/root 路径` 切换项目、`/help` 查看帮助；`--configure` 单独运行向导。
管道输入不启用向导，需预先提供配置。所有启动方式的优先级一致：启动目录 `.env` > 环境变量 > 用户配置 > 默认值。
独立 Mac 应用与通用发行包的构建和验收见 [packaging/README.md](packaging/README.md)。

### 手动配置（兼容 Lab 03）

在项目根目录（包含 `pom.xml` 的 `code/`）执行：

```bash
cp .env.example .env
# 编辑 .env，把 your-glm-api-key 换成真实 Key
```

也可以临时使用环境变量：

```bash
export GLM_API_KEY="你的真实Key"
```

另外两个可选配置项（默认值即真实 GLM 服务，通常不需要设置）：

| 配置项 | 默认值 | 用途 |
|---|---|---|
| `GLM_API_URL` | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | 把 Endpoint 指向本地假服务器，做离线端到端验收 |
| `GLM_MODEL` | `glm-5.2` | 换成控制台实际可用的模型标识 |

`.env` 已被 `.gitignore` 忽略。

## 快速开始

在项目根目录（包含 `pom.xml` 的 `code/`）执行：

```bash
# 运行全部测试（离线，不需要 API Key）
mvn test

# 打包可执行 fat jar
mvn -q -DskipTests package

# 运行 CLI：首次启动自动引导；已有项目 .env 仍按当前目录查找
java -jar target/agent-cli.jar

# 操作其他 Java 项目（配置仍从启动目录的 .env 读取）
java -jar target/agent-cli.jar --root /absolute/path/to/project

# 验收打包后的 CLI：本地脚本模型 + 真实文件、javac/java
python3 dev/acceptance.py

# Lab 02 的文件工具 Demo 仍然可用
java -cp target/classes:target/agent-cli.jar com.agent.tool.FileToolsDemo README.md TODO
```

全部测试都是离线测试，不需要 API Key，也不访问外网。发行构建在 `release.json` 中记录本次测试数量与结果。

### 没有 API Key 时如何验收 CLI

`dev/MockGlmServer.java` 是一个只用 JDK 自带 `HttpServer` 写成的假 GLM Endpoint（复用 fat jar 里的 Jackson 解析请求），可以完整走一遍 Chat Completions + Tool Calling 协议：

```bash
# 终端 1：启动假 GLM
java -cp target/agent-cli.jar dev/MockGlmServer.java 8099

# 终端 2：把 CLI 指向假 GLM
GLM_API_KEY=dummy GLM_API_URL=http://127.0.0.1:8099/v4/chat/completions \
  java -jar target/agent-cli.jar
```

它可以复现“直接回答 / 单工具调用 / 多步调用 / 工具失败 / clear”五种场景，用于检查 AT02～AT07 的接线是否正常；协议本身的正确性由 `GlmClientTest` 与 `AgentEndToEndTest` 保证。

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

让 Agent 获得读取和搜索项目代码的基础能力。Lab 02 阶段保留了不同的方法签名；V4 已引入统一 `Tool` 接口，同时保留这些原有方法供 Demo 和回归测试使用。

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
| Tool 安全边界 | V3 的根目录检查已迁移为 `SafetyGuard.resolve`，V4 增加符号链接及私密文件检查 |

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

### 中文输入与退格（行编辑）

默认情况下，终端的行编辑由操作系统按**字节**处理：一个汉字在 UTF-8 中占 3 个字节、在屏幕上占 2 列，于是会出现“一个汉字要按两三次退格才删掉”和“删到开头还残留半个字”的现象。本项目的 CLI 不再依赖终端自带的编辑能力，而是自己做行编辑：

| 类 | 作用 |
|---|---|
| `TerminalLineReader` | 进入原始模式（`stty -icanon -echo`）后逐字符读取、自己回显与擦除；退出时自动恢复终端设置 |
| `LineBuffer` | 按码点增删，一次退格删除一个完整字符（含 emoji） |
| `DisplayWidth` | 按显示宽度计算擦除列数，汉字/全角字符按 2 列处理 |

该行为只在标准输入是终端时启用；`printf ... | java -jar ...` 这类管道输入会自动回退为按行读取，脚本与冒烟测试不受影响。支持 Ctrl+C（退出）、Ctrl+D（空行时退出）、Ctrl+U（清空当前行），方向键等转义序列会被忽略而不会插入乱码。

### 测试

Lab 03 的测试全部离线，不需要 API Key，也不访问外网：

| 测试类 | 覆盖内容 |
|---|---|
| `AgentTest` | 最终回答直接退出、Tool Call 触发第二轮、Observation 回传、未知工具、工具失败回传、同轮多调用、`MAX_ITERATIONS` 安全阀、`clearHistory`、超长 Observation 截断、首轮请求带 system + 3 个工具定义 |
| `AgentEndToEndTest` | 本地假 GLM 服务器 + 真实 `ReadFileTool` / `SearchCodeTool` 的完整链路（AT03、AT04、AT06） |
| `GlmClientTest` | 本地 `HttpServer` 验证 Bearer 鉴权、messages/tools 序列化、`content=null` 仍解析 `tool_calls`、usage 解析、401/429/5xx/缺少 choices 错误、空 Key 拒绝、请求体不含 Key |
| `ToolRegistryTest` | 工具注册与 Schema、复用 Lab02 工具、未知工具、非法 JSON、路径越界拒绝；V4 将空参数改为严格拒绝 |
| `MainTest` | `.env` 读取、BOM 与引号处理、文件缺失时回退 |
| `EchoAgentTest` | Lab 01 行为回归（5 个原有用例） |

### AT01～AT07 对照

真机验收状态：2026-09-20 使用真实 GLM API Key（模型 `glm-5.2`）跑通 AT01～AT07，共 10 次请求、4 次工具调用，输入 22758 / 输出 1797 tokens。

| 编号 | 验收项 | 本项目中的验证方式 |
|---|---|---|
| AT01 | GLM 连通 | 已用真实 Key 验证 `content` 非空；协议细节另由 `GlmClientTest.shouldParseFinalAnswerWithUsage` 覆盖 |
| AT02 | 直接回答 | `AgentTest.shouldExitOnFinalAnswer` |
| AT03 | 单工具调用 | `AgentEndToEndTest.shouldReadPomThenAnswer`（`read_file` → 最终答案） |
| AT04 | 多步调用 | `AgentEndToEndTest.shouldSearchThenReadThenAnswerAt04`（`search_code → read_file`）；`AgentTest.shouldExecuteMultipleToolCallsInOneRound` 覆盖同轮多调用 |
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

## 10.9：完整 Coding Agent（V4）

当前数据流：

```text
用户自然语言 → Main → Agent → LLMClient/GlmClient
                          ↓ assistant(tool_calls)
                   ObservationRecorder 检查 ID 并保存调用
                          ↓
                   SafetyGuard 检查重复调用
                          ↓
                   ToolExecutor → ToolRegistry 查找与验证 → Tool 执行
                          ↓
                   ObservationRecorder 保存 tool(tool_call_id)
                          ↓
                   完整历史回传模型 → 后续工具或最终回答
```

### 课堂角色对应模块

| 角色 | 对应实现 | 职责 |
|---|---|---|
| User | CLI 使用者 / `Main` 的 REPL | 输入自然语言任务，核对答案和实际结果 |
| Agent Controller | `Agent` | 组织模型请求和工具执行，控制循环及退出 |
| LLM | `LLMClient` / `GlmClient` | 根据消息历史和工具定义选择动作或最终回答 |
| Tool Registry | `ToolRegistry` / `ToolSchemas` | 查名称，检查必填字段、类型、未知字段、重复 JSON 字段；不默默改名或强转类型 |
| Tool Executor | `ToolExecutor` / 各 `Tool` 实现 | 执行真实文件 I/O 或进程，返回 `ToolResult` |
| Observation Recorder | `ObservationRecorder` | 执行前检查全批调用 ID，保存 assistant 调用和所有成功/失败的 tool 结果 |
| Safety Guard | `SafetyGuard` / `CommandPolicy`，配合 `Agent` 与命令工具 | 路径/密钥保护、重复调用、迭代上限、命令限制及超时清理 |

### 当前工具协议

| 工具名 | 参数 | 行为 |
|---|---|---|
| `list_files` | `path: string` | 递归列文件 |
| `list_dir` | `path: string` | 仅列一层目录 |
| `read_file` | `path: string` | 读取当前 UTF-8 文本 |
| `search_code` | `keyword: string`, `path: string` | 字面关键字搜索，返回文件/行号/内容 |
| `write_file` | `path: string`, `content: string`, `overwrite?: boolean` | 新建文件及父目录；默认拒绝覆盖，完整覆盖需显式 `true` |
| `edit_file` | `path: string`, `old_text: string`, `new_text: string` | 原文必须非空且恰好匹配一处；失败时文件不变 |
| `execute_command` | `command: string`, `timeout_seconds?: integer` | 在项目根目录执行 Java 开发命令，返回 `exitCode`、`timedOut`、stdout、stderr |

参数与结果不再统一强转成字符串；`Tool` 接口保留原始 `JsonNode` 参数，执行结果由 `ToolResult(success, output)` 表达。Schema 是本地校验和模型声明的共同来源。

### 命令、路径与循环边界

- 命令通过 `ProcessBuilder` 直接启动，允许 `java`、`javac`、`mvn`。支持引号包围的空格参数，不支持 shell、管道、重定向、变量展开、通配符和响应文件。
- Maven 允许 `validate/compile/test/package/verify/clean` 与 `-o/-q/-B/-DskipTests`。Java 工具固定使用当前 JDK，javac 禁用自动注解处理。
- 命令默认 30 秒，可设为 1～120 秒；超时终止进程及已观察到的子进程。stdin 为 EOF；stdout/stderr 分开并行读取，各保留前 2048 字节，继续排空剩余输出防止阻塞。
- 文件工具限制项目根目录，检查符号链接的真实目标，拒绝 `.git`、`.env` 和私密 `.env.*`（允许 `.env.example`）。递归遍历还跳过 `target` 和符号链接。读写/编辑文本上限 1 MiB；搜索跳过超过该大小的文件。
- 同一任务中第三次连续相同工具及参数被拒绝并结束任务；不同策略仍可继续。最多 10 轮。调用 ID 在未清空的整个对话中必须唯一，非法批次在任何动作前拒绝。
- 修改成功只证明文件已修改。系统提示要求重新编译和运行/测试，以工具结果作为完成证据；模型最终回答本身不是自动正确性证明。

这些是应用层策略，**不是操作系统沙箱**。运行 Java 程序或 Maven 插件具有启动 CLI 用户的系统权限，文件工具的目录限制不会隔离进程内的文件访问。命令进程不继承 `GLM_*` 凭证或 Java/Maven 注入选项。用于自己的可信项目；外部代码的系统隔离需要容器等额外执行环境。

### 运行和验收

从 `code/` 启动（原有 `.env` 读取规则保持不变，文件优先于环境变量）：

```bash
mvn test
mvn -q -DskipTests package
java -jar target/agent-cli.jar --root /absolute/path/to/project
```

示例输入：

```text
新建 Hello.java，输出 Hello Agent，然后编译运行并告诉我实际结果。
读取 Hello.java，将输出改为 Hello Java Agent，重新编译并运行验证。
clear
exit
```

全部 79 个 JUnit 用例离线运行，包括真 CLI 子进程、本地 HTTP 协议、真实编译/运行、故障恢复、精确编辑、路径检查、超时子进程清理及大输出测试。独立 JAR 验收与仿真 Prompt/通过条件见 `dev/ACCEPTANCE.md`：

```bash
python3 dev/acceptance.py
```

该脚本跑六组临时项目，以脚本模型替代真实 GLM 推理，文件工具和 Java 命令均真实执行；每轮检查 ID 配对和 Observation 后才给出下一步。结果与 CLI 记录保存到 `target/acceptance-results.json`，不使用 API Key、不消耗 Token。这验证协议和执行链路，不代替真实模型的任务决策能力评估。

## Lab 03 架构记录

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

## Lab 03 目录记录

```text
.
├── .env.example
├── .gitignore
├── README.md
├── pom.xml
├── dev
│   └── MockGlmServer.java          离线验收用的假 GLM Endpoint
└── src
    ├── main/java/com/agent
    │   ├── Agent.java
    │   ├── EchoAgent.java
    │   ├── Main.java
    │   ├── cli
    │   │   ├── DisplayWidth.java
    │   │   ├── LineBuffer.java
    │   │   └── TerminalLineReader.java
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
        ├── cli
        │   ├── DisplayWidthTest.java
        │   ├── LineBufferTest.java
        │   └── TerminalLineReaderTest.java
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
lab03b: offline e2e acceptance and debug exercise   # Lab 03-b 验收与调试实验
```
