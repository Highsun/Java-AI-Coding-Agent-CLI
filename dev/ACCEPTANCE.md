# CLI 验收用例与执行指令

本文件是可执行测试的使用说明，不是实验报告。环境要求：JDK 17+、Maven、Python 3（只用标准库）。

在 `code/` 执行：

```bash
mvn test
mvn -q -DskipTests package
python3 dev/acceptance.py
```

依赖已缓存时可使用 `mvn -o -B test` 与 `mvn -o -B -DskipTests package`。HTTP 测试需要允许监听 `127.0.0.1`，不访问外网。验收脚本使用新建临时目录，结束后清理，不修改真实业务项目。指定其他 JAR/证据路径可用 `--jar` / `--output`。

## 打包 CLI 的六组自然语言输入

| 用例 | 仿真 Prompt | 脚本模型选择的动作 | 验收条件 |
|---|---|---|---|
| 新建编译运行 | 新建 Hello.java，输出 Hello Agent，编译并运行，报告实际结果。 | write_file → javac → java | 真实文件内容正确，class 存在，两条命令 exitCode=0，运行 stdout=Hello Agent，结果回传后才最终回答 |
| 读取修改验证 | 读取 Hello.java，把输出改成 Hello Agent，然后重新编译、运行验证。 | read_file → edit_file → javac → java | 唯一原文替换成功，重新编译和实际运行正确，4 条结果按 ID 回传 |
| 文件不存在 | 读取 Config.java；若不存在，请先列目录，再读取实际配置文件。 | read_file(失败) → list_dir → read_file | 失败如实回传，目录返回 ActualConfig.java，读到实际类内容 |
| 未知工具 | 读取 Hello.java，若工具名错误，请根据可用工具修正调用。 | open_file(失败) → read_file | 未知名称不被注册员偷偷替换；模型收到错误后发出新调用 |
| 重复陷阱 | 故障挑战：无论结果如何，连续三次读取 Hello.java。 | read_file × 3 | 第三次不执行；守卫提示未完成；不再请求模型，也没有伪造最终成功 |
| 编译恢复 | 新建并运行 Hello.java，输出 Hello Agent；遇到编译错误后修复，再编译运行。 | write_file → javac(失败) → edit_file → javac → java | 首次真实编译 exitCode=1，错误回传；补分号后重新编译成功并运行得到正确输出 |

`dev/acceptance.py` 校验所有 HTTP 请求带 7 个工具定义、每个 assistant 调用都有匹配的 tool 结果，且每条结果满足场景预期。证据文件是 `target/acceptance-results.json`，包含 Prompt、模型请求数、回传 Observation 和 CLI 完整输出。

## JUnit 额外覆盖

- `GameScenariosTest`：课堂故障恢复、重复批次后的所有结果配对、全批重复 ID 拒绝、跨对话 ID/clear、编译修复、LLM 失败后的历史一致性。
- `CliCodingEndToEndTest`：启动真实 Main 子进程，以包含空格和中文的 `--root` 路径操作项目，完整编辑/编译/运行，clear 后新请求只剩 system + user。
- `EditingToolsTest`：UTF-8 新建/读取、显式覆盖、唯一匹配、缺失/空/未匹配/多处匹配不写入、尺寸上限、目录层级。
- `ToolValidationTest`：缺失/null/错误类型/未知字段/重复 JSON 字段/非对象/尾随 JSON 拒绝，保留 boolean/integer 类型。
- `SafetyGuardTest`：根目录穿越、符号链接越界、密钥路径别名保护、遍历排除、重复参数顺序归一化及新任务重置。
- `ExecuteCommandToolTest`：真实 javac/java、工作目录、带空格参数、stdout/stderr、非零退出、超时及子进程清理、大输出排空、命令策略限制。
- 旧 Lab 01～03 及中文行编辑回归测试继续执行；工具数量断言与空参数行为随新严格协议更新。

## 真实 GLM 人工验收

运行 `java -jar target/agent-cli.jar --root /absolute/path/to/trusted-test-project`，输入上表前两个 Prompt。需要本机 `.env` 中的有效 Key 和模型服务网络。核对终端执行轨迹与项目实际文件，再独立执行 `javac Hello.java` 和 `java Hello` 检查结果。脚本模型离线验收不会证明真实 GLM 一定采用相同动作或生成正确代码。
