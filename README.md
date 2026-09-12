# agent-cli

Lab 01 的 Java 17 Maven 命令行项目。程序从标准输入逐行读取内容，并通过 `Agent` 生成响应后写入标准输出。

## 环境

- JDK 17+
- Maven 3.9+

## 构建与测试

```bash
mvn test
```

## 运行

```bash
java -cp target/classes com.agent.Main
```

启动 CLI 模式后，从标准输入逐行输入内容：

- 输入 `hello`、大小写混合形式或空白内容时，输出 `Hello, Agent!`
- 其他输入输出 `I heard: <input>`
- 输入 `quit` 退出 CLI

## 目录结构

```text
.
├── README.md
├── pom.xml
└── src
    ├── main
    │   └── java
    │       └── com
    │           └── agent
            │               ├── Agent.java  # 输入到响应的纯逻辑
            │               ├── Main.java   # stdin/stdout 接线
            │               └── tool
            │                   ├── FileToolsDemo.java
            │                   ├── ListFilesTool.java
            │                   ├── ReadFileTool.java
            │                   └── SearchCodeTool.java
    └── test
        └── java
            └── com
                └── agent
                    ├── AgentTest.java
                    └── tool
                        ├── ListFilesToolTest.java
                        ├── ReadFileToolTest.java
                        └── SearchCodeToolTest.java
```

## Lab 02 文件工具

`com.agent.tool` 包提供三个独立的文件工具：

- `ListFilesTool.listFiles(Path)`：递归列出目录下的普通文件，返回排序后的相对路径列表。
- `ReadFileTool.readFile(Path)`：按 UTF-8 读取普通文本文件；路径不存在或不是文件时抛出 `NoSuchFileException`。
- `SearchCodeTool.searchCode(String, Path)`：递归搜索包含关键字的文本行，返回文件、行号和内容；跳过常见二进制扩展，单个文件读取失败时继续搜索。

运行测试：

```bash
mvn test
```

运行文件工具演示：

```bash
mvn -q -DskipTests compile
java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO
```

Demo 会先列出当前项目文件，然后读取指定文件，最后搜索指定关键字。
