# Java AI Coding Agent 发行构建

这是当前课程项目的 v0.1.1 发行流程。版本号来自 `pom.xml`；Lab 04 是课程阶段，不是发行版本号。

在项目根目录执行：

```sh
python3 packaging/build_release.py
```

需要 Python 3.11 或更新版本、Maven、完整的 JDK 17，以及已缓存的 Maven 依赖。默认离线构建；首次获取依赖可指定 `--online`。在 macOS 上还需要 Xcode Command Line Tools，生成当前架构的 `.app`；其他系统只生成通用包，也可显式指定 `--portable-only`。

脚本在 `target/` 中复制当前工作树的 `pom.xml`、`src/`、`dev/`、README 和打包脚本，再执行测试与 Maven Shade 打包。包括尚未提交的当前源码，不包括 `.env`、`.git` 或原有 `target/`。不会覆盖原有 `target/agent-cli.jar`。已有同版本输出目录时停止；另选 `--output-dir` 可重新构建。

产物位于 `target/releases/0.1.1/`：

- `Java-Coding-Agent-0.1.1-macos-arm64.zip`：本机架构的独立 Mac 应用，内置 Java 17 运行时和 `javac`。
- `Java-Coding-Agent-0.1.1-portable.zip`：包含全部应用依赖的 JAR、Unix/Windows 启动器，需要外部 JDK 17 或更新版本。
- `Java-Coding-Agent-0.1.1-source.zip`：本次构建的源码快照。
- `release.json`、`SHA256SUMS`、`build.log`、`test-results/`：版本、源码哈希、产物校验与构建测试记录。

解压后的应用可移动到其他目录使用。首次启动在终端中引导输入密钥（隐藏回显）、选择模型与保存配置，随后直接进入任务界面。
输入 `/config`、`/model`、`/root 路径`、`/help` 可继续配置和查看帮助，无需手工编辑文件。
默认配置位置与工作目录独立，发行包不会携带真实 GLM 密钥。`使用说明.md` 是补充说明。

构建后验证：

```sh
python3 packaging/verify_release.py target/releases/0.1.1
```

该验证会将 ZIP 解压到带有中文及空格的临时目录，检查签名和启动器，通过本地 HTTP 模型走真实文件读写、编译、执行及错误恢复链路；不消耗 GLM API 额度。
同时通过伪终端验证首次配置、隐藏输入与编辑、保存权限、再次启动、会话命令、取消和仅本次使用，Mac 版还验证 `Start.command` 首次启动流程。
所有测试使用临时 HOME 和配置目录，不读写真实用户配置。结果写入发行目录的 `verification.json`。真实 GLM 调用沿用原有客户端。

macOS 包采用本地 ad-hoc 签名，不含 Apple Developer ID 或公证；供本机及课程交付使用。从网络下载至其他 Mac 时，系统可能要求在“隐私与安全性”中允许打开。该包仅适配构建机架构；Intel Mac 应在 Intel JDK 上重新构建。Agent 调用 Maven 仍需另行安装 Maven。
