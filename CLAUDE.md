# Java AI Coding Agent 项目指令

本文件位于含 `pom.xml` 的项目根目录，按 10 月 16 日任务书提供 Claude Code 的项目工作指令入口。任务书要求可通过 Claude Code 的 `/memory` 检查项目指令；本次实际开发由 Codex 完成，没有声称已在 Claude Code 中运行或验证加载。

## 开始工作前

1. 阅读 [AGENTS.md](AGENTS.md)，遵循其中的项目协作、架构与验证约束。
2. **开发会话功能前必须显式读取 [SPEC.md](SPEC.md)**，按 FR、D、AT、PT 编号实现和验收。SPEC 是功能规格，不因其文件名而自动成为项目指令。
3. 查看 Git 状态及相关代码，保留用户已有改动。用户仅要求阅读或文档时不开始实现；用户授权开发后按已确认规格推进，不重复询问。

`AGENTS.md` 为 Codex 的项目指令入口；本文件为 Claude Code 的项目指令入口；`SPEC.md` 定义功能目标、边界及验收。共同工程约束以 `AGENTS.md` 为依据，本文不维护第二套会话规格。用户最新明确指令优先，行为调整须同步到规格和测试。

## 架构约束

- 保持 Java 17 和现有 Maven 结构，优先复用 Jackson、JUnit 等已有依赖。
- `Main` 与 `cli` 负责输入、命令、确认及接线；`Agent` 负责 ReAct 循环和历史；`llm` 负责模型协议；`session` 负责会话编解码及文件读写。
- `ToolRegistry` 负责工具注册及参数校验，`ToolExecutor` 执行工具；`ObservationRecorder` 保留工具调用与结果配对，`SafetyGuard` 保持路径和循环边界。
- 保存与加载是本地命令，不通过模型执行；加载先完整验证再替换历史，不重放工具，不切换当前模型或项目，不持久化配置凭证。
- 文件工具对会话目录的保护不能被描述为操作系统沙箱；不削弱原有密钥、路径、命令和超时限制。

## 构建与验证

在项目根目录执行：

```bash
mvn test
mvn -q -DskipTests package
python3 dev/acceptance.py
python3 dev/session_acceptance.py
```

依赖已缓存时，Maven 可加 `-o -B`。测试使用临时项目、假凭证、本地 HTTP 服务；会话脚本的 PTY 部分在 macOS/Linux 执行。真实模型验证须有任务依据，不用真实用户配置作为测试数据。

规格的 AT 与 PT 均须验收；断言文件、消息、模型请求及失败后的状态，不能只检查成功文字。基线和实际结果见 [dev/SESSION_ACCEPTANCE.md](dev/SESSION_ACCEPTANCE.md)，详细证据位于 `target/`。

只改文档时做文档检查即可。完成代码修改后执行相应测试和 CLI 回归，报告真实结果及限制；不伪造用户访谈理由、个人反思或测试通过记录，不擅自提交 Git 或生成发行包。
