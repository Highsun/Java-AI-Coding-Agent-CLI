# 10.16 会话功能开发与验收记录

执行日期：2026-10-16（Asia/Shanghai）。

## 1. 实现结果

依据 [SPEC.md](../SPEC.md) 1.0 和 [AGENTS.md](../AGENTS.md)，完成以下功能：

- `/save`、`/load` 本地命令及帮助，当前项目下的命名会话文件。
- UTF-8 JSON v1 完整历史往返，工具调用与结果校验、16 MiB 大小边界。
- 加载前验证、替换确认、覆盖确认和未保存历史提醒；恢复不触发模型或历史工具。
- 当前系统提示、模型及项目目录保持规则；保存文件不包含配置凭证字段。
- 文件失败保护、符号链接拒绝、会话目录的文件工具隔离及本仓库 Git 忽略规则。
- EOF、Ctrl+C、Ctrl+D 交互与终端状态恢复；旧 CLI 回归脚本的确认输入适配。

实现职责：`SessionCodec` 编解码和协议校验，`SessionStore` 文件保存与加载，`SessionCommands` 命令和确认，`Agent` 历史与未保存状态，`Main` 接线。没有新增第三方依赖或模型工具。

本次同时补齐 [CLAUDE.md](../CLAUDE.md)，但实际开发工具为 Codex，没有声称执行过 Claude Code 的 `/memory` 检查。

## 2. 环境与版本

| 项目 | 实际值 |
|---|---|
| 基线提交 | `936c950241dadad59dc58617d9c2b20be33c7390` |
| 实现版本 | 以上提交加本次未提交工作区变更；未创建 Git 提交 |
| Maven 项目版本 | `0.1.1`，本次未生成新发行版本 |
| JDK | Eclipse Temurin 17.0.17 |
| Maven | 3.9.16 |
| 系统 | macOS 27.0.1，aarch64 |
| 模型环境 | 假凭证、预编排假模型或本机 HTTP 服务；未使用真实模型服务 |
| 数据环境 | 临时项目和临时用户配置；测试结束清理，未修改真实用户配置 |

被测 `target/agent-cli.jar` 的 SHA-256：

```text
d43e9363fa0dea2ad9c37bb8528cb86a0aa53a039d4938eca07226b3cb67eddb
```

机器可读摘要：[session-test-summary.json](../target/session-test-summary.json)。该摘要标识本次构建；今后修改源码并重新构建后，应重新运行验收和更新对应证据。

## 3. 实际执行结果

| 验证 | 结果 | 证据 |
|---|---|---|
| 开发前 `mvn -o -B test` | 94 个测试通过，0 失败、0 错误、0 跳过 | [基线日志](../target/session-baseline.log) |
| 实现后 `mvn -o -B test` | 139 个测试通过，0 失败、0 错误、0 跳过；比基线增加 45 个执行用例 | [测试日志](../target/session-tests.log)、`target/surefire-reports/` |
| `mvn -o -B -DskipTests package` | BUILD SUCCESS，生成新的可执行 JAR | [构建日志](../target/session-package.log) |
| `python3 dev/acceptance.py` | 6/6 既有 Coding Agent 场景通过 | [原有 CLI 证据](../target/acceptance-results.json) |
| `python3 dev/session_acceptance.py` | 15/15 会话 CLI／PTY 场景通过 | [会话 CLI 证据](../target/session-acceptance-results.json) |
| 现有 `packaging.verify_onboarding.run_checks` 对当前 JAR 执行 | 7/7 首次配置及配置切换 PTY 场景通过；使用临时启动器，没有生成发行包 | [配置回归证据](../target/onboarding-current-results.json) |
| `git check-ignore --no-index .agent/sessions/验收.json` | 命中本仓库忽略规则 | `.gitignore` 中的 `/.agent/sessions/` |
| Git 差异、文档链接及示例检查 | 通过 | 本次工作区检查 |

最初在受限沙箱内运行基线时，本机 HTTP 监听被拒绝，部分真实 Java 子进程断言失败；获准在允许这些测试能力的环境重跑后，原有 94 个用例全部通过。没有因此删除、跳过或放宽测试。

离线 HTTP 验收需要监听 `127.0.0.1`；“离线”表示不依赖外部模型服务，而不是禁止使用本地网络栈。Maven 的 `-o` 表示使用已缓存依赖。

## 4. 规格逐项验收映射

以下 25 项均有本次通过的自动化证据。JUnit 用例断言结构与数据，CLI／PTY 脚本补充真实进程及交互层验证；不是只依据终端成功提示判断。

| 规格编号 | 主要验证位置 | 核对内容 | 结果 |
|---|---|---|---|
| AT-01 | `SessionStoreTest`；`save_multitool_history`、`startup_does_not_restore` | 手动保存、启动不恢复、保存根目录与启动目录分离 | 通过 |
| AT-02 | `SessionCodecTest.roundTripsUnicodeNullArgumentsAndFailedToolHistory` | 中文、换行、引号、emoji、消息顺序逐项相等 | 通过 |
| AT-03 | `AgentSessionTest.restoresMultiToolHistoryWithTruncationAndContinuesWithoutReplay`；`save_multitool_history` | 同轮多个工具及成功／失败结果配对 | 通过 |
| AT-04 | 上述 `AgentSessionTest` | 实际截断的 Observation 保留，不替换为短预览 | 通过 |
| AT-05 | `AgentSessionTest.retainsCurrentSystemPromptAndRejectsInvalidRestoreAtomically`；`SessionCodecTest` | 当前系统提示唯一，不从会话接受旧 system 消息 | 通过 |
| AT-06 | `SessionCommandsTest.cancelsOverwriteAndLoadThenReplacesOnlyAfterConfirmation`；`cancel_overwrite_and_restore` | 取消不改历史，确认后替换而非合并 | 通过 |
| AT-07 | 上述 `SessionCommandsTest`；`confirm_overwrite` | 取消保留文件字节，确认保存新历史 | 通过 |
| AT-08 | `SessionCodecTest.rejectsInvalidStructureAsAWhole`；`invalid_load_and_commands_preserve_history` | 损坏、缺字段及配对错误不部分加载，CLI 可继续 | 通过 |
| AT-09 | `restore_with_current_model_and_continue`；`pty_model_config_cancel_switch_and_load` | 当前模型保持，假凭证未落入会话，当前项目保持 | 通过 |
| AT-10 | `AgentSessionTest`；`restore_with_current_model_and_continue` | 加载无模型请求／工具重放，下一次请求确有恢复历史 | 通过 |
| AT-11 | `cancel_discard_and_clear`、`switch_project_cancel_invalid_and_success`、`pty_model_config_cancel_switch_and_load` | 退出／清空／模型／项目操作均能取消，历史保留 | 通过 |
| AT-12 | 上述 CLI／PTY 场景 | 确认后执行原操作，清空或新 Agent 状态正确，不自动保存 | 通过 |
| AT-13 | `switch_project_cancel_invalid_and_success` | 项目 B 不读取 A 的会话，失败不建目录、不切回 A | 通过 |
| AT-14 | `AgentSessionTest.restoredCallIdsAreStillProtected` | 恢复的调用 ID 参与重复检查，重复写入工具未执行 | 通过 |
| AT-15 | 原有 JUnit、6 组 Coding Agent 及 7 组配置 PTY 回归 | 文件工具、编译运行、错误恢复、安全守卫、配置与中文输入流程 | 通过 |
| PT-01 | `SessionStoreTest`；`save_multitool_history`；`SessionCodecTest` | 中文会话名、真实跨进程恢复、空历史 | 通过 |
| PT-02 | `SessionStoreTest.validatesNamesAndFileKinds`、`rejectsSymlinksAtEveryLevelIncludingInternalTargets` | 非法名称、64/65 码点边界、内外部及各层符号链接 | 通过 |
| PT-03 | `SessionCommandsTest.confirmsExactlyOnceAndNeverSendsAnswerToModel`；管道及 PTY 场景 | y/yes/Y、否定、空行及其他文本；不消耗后续任务为多次确认 | 通过 |
| PT-04 | `AgentSessionTest`、`SessionCommandsTest`、项目及模型切换场景 | 未保存状态表、LLM 失败、操作取消／失败、向导取消 | 通过 |
| PT-05 | `pipe_eof_main`、`pipe_eof_confirm`、四组 `pty_ctrl_*`、配置取消场景 | EOF／Ctrl+C／Ctrl+D，不默认同意、不自动保存，终端模式恢复 | 通过 |
| PT-06 | `SessionStoreTest.preservesOldBytesAndCleansTempsForWriteAndCommitFailures`、`doesNotOverwriteFileCreatedDuringCommit` | 失败注入、原子替换不支持、新文件冲突、临时文件清理 | 通过 |
| PT-07 | `SessionCodecTest`；`SessionStoreTest.reportsReadDenialAndLimitsActualStreamBytes` | 严格结构校验、UTF-8、实际读取限制、恰好 16 MiB 和多 1 字节的读写边界 | 通过 |
| PT-08 | `SessionIsolationTest`；Git 忽略检查 | 目录及链接别名直接访问拒绝、搜索排除、其他 `.agent` 文件仍可读 | 通过 |
| PT-09 | `SessionCodecTest.roundTripsUnicodeNullArgumentsAndFailedToolHistory` | 原始非法参数字符串／null、未知工具的失败历史可恢复 | 通过 |
| PT-10 | `SessionStoreTest`；`SessionCommandsTest.invalidLoadAndSaveRetainHistoryAndDoNotAskForConfirmation` | 缺失、非普通文件、确定性读写权限失败，不改旧历史 | 通过 |

## 5. 本次技术判断与边界

以下是实现中可以从代码和测试复核的技术结论，不代替用户的个人学习心得：

- 工具结果配对是恢复上下文的必要结构。只保存可见聊天文字会丢失模型调用协议，因此使用消息记录并校验配对。
- 加载应先验证再替换。即使文件中的前半部分有效，也不能先修改现有历史再处理后半部分错误。
- 确认输入必须与任务输入共用读取器。多个 stdin 读取器可能预读对方的数据；真实管道和 PTY 测试覆盖了这条边界。
- 未保存状态在成功保存／加载后清除，失败和取消保持；明确区分“文件操作成功”和“用户有意放弃历史”。
- Ctrl+C 在 Unix 终端由程序处理后才能输出未保存提醒。退出时恢复 ISIG、回显和规范模式，并以 PTY 实测。
- JSON 文件保留工具参数原文，不把过去失败的调用强行改写成合法参数；恢复历史与重试工具是不同操作。

验证范围为本机 macOS、JDK 17 和本地假模型；没有验证 Windows 终端行为、真实 GLM 的任务决策质量或跨文件系统的崩溃恢复。本版不提供多进程会话合并或断电持久性保证。没有打包或更新上级发行目录。

课程材料尚需用户补充至少一条采纳／否定 AI 建议的个人理由及个人学习反思。已有三轮真实访谈保存在 SPEC 中；本次开发交付没有将缺失内容编造为完整实验报告，也未生成最终提交 PDF／Word。
