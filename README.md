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
mvn -q -DskipTests compile
echo "hello" | java -cp target/classes com.agent.Main
```

预期输出：

```text
Hello, Agent!
```

也可以交互式运行 `java -cp target/classes com.agent.Main`。输入 `quit` 可退出。

## 目录结构

```text
src/main/java/com/agent/Agent.java   # 输入到响应的纯逻辑
src/main/java/com/agent/Main.java    # stdin/stdout 接线
src/test/java/com/agent/AgentTest.java
```
