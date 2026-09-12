# Java AI Coding Agent CLI

一个持续演进的 Java AI Coding Agent CLI 项目。

## 项目状态

当前已完成 Lab 01 和 Lab 02，版本能力从“可运行的 CLI”扩展到“能够读取和搜索项目文件的工具集”。后续 Lab 应继续沿用本 README 的版本记录方式，并只补充新增能力。

| 版本 | 实验 | 新增能力 | 当前状态 |
|---|---|---|---|
| V1 | Lab 01：Java Project Bootstrap | Maven 工程、Java CLI、Agent 逻辑、JUnit 测试、Git 版本点 | 已完成 |
| V2 | Lab 02：File Tools | 递归列文件、读取文本、搜索代码、文件工具测试与 Demo | 已完成 |

## 环境要求

- JDK 17 或更高版本
- Maven 3.9+
- Git

项目通过 Maven 的 `maven.compiler.release=17` 保持 Java 17 编译目标。Lab 01 和 Lab 02 均只使用 JDK 内置文件 API，没有新增第三方运行时依赖。

## 快速开始

在项目根目录（包含 `pom.xml` 的 `code/`）执行：

```bash
# 编译并运行全部测试
mvn test

# 仅编译主代码
mvn -q -DskipTests compile
```


预期测试结果：当前共 12 个测试，Failures 和 Errors 均为 0，Maven 输出 `BUILD SUCCESS`。

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
- `Agent`：只负责输入到响应的纯逻辑，不访问终端。
- `Main`：负责 stdin/stdout 接线，逐行读取输入。
- `AgentTest`：验证问候、大小写和首尾空白、普通输入、空白和 `null`。
- `.gitignore`：忽略 `target/`、IDE 配置和系统文件。

### CLI 行为

启动：

```bash
java -cp target/classes com.agent.Main
```

输入行为：

- `hello`、大小写混合形式或纯空白：输出 `Hello, Agent!`
- 其他输入：输出 `I heard: <input>`
- `quit`：退出 CLI，不输出响应

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

## 当前架构

```text
Main
 |
 +--> Agent                 Lab 01：CLI 响应逻辑
 |
 +--> com.agent.tool        Lab 02：独立文件工具
       +--> ListFilesTool   -> List<String>
       +--> ReadFileTool    -> String
       +--> SearchCodeTool  -> List<Match>
       +--> FileToolsDemo
```

## 目录结构

```text
.
├── .gitignore
├── README.md
├── pom.xml
└── src
    ├── main/java/com/agent
    │   ├── Agent.java
    │   ├── Main.java
    │   └── tool
    │       ├── FileToolsDemo.java
    │       ├── ListFilesTool.java
    │       ├── ReadFileTool.java
    │       └── SearchCodeTool.java
    └── test/java/com/agent
        ├── AgentTest.java
        └── tool
            ├── ListFilesToolTest.java
            ├── ReadFileToolTest.java
            └── SearchCodeToolTest.java
```

构建产物位于 `target/`，由 `.gitignore` 忽略。

## Git 检查点

当前项目的主要提交：

```text
f725b9a bootstrap project   # Lab 01 / V1
75c0173 lab02: file tools   # Lab 02 / V2
```
