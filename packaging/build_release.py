#!/usr/bin/env python3
"""Build a versioned working-tree snapshot without changing historical artifacts."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APP_NAME = "Java AI Coding Agent"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def executable_copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    destination.chmod(0o755)


def zip_directory(directory, destination):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(directory.parent))


def usage(version, mac=False):
    start = ("双击 `Java AI Coding Agent.app`，在自动打开的终端中输入任务。默认工作目录为 "
             "`~/JavaCodingAgentWorkspace`，首次启动会创建该目录。也可以将 `.app` 移至“应用程序”文件夹。\n\n"
             "通过终端操作指定项目：\n\n```sh\n./agent --root \"/你的/项目目录\"\n./agent --help\n./agent --version\n```\n\n"
             "如果已将应用移到“应用程序”，命令行入口是：\n\n```sh\n"
             "\"/Applications/Java AI Coding Agent.app/Contents/Resources/bin/agent\" --root \"/你的/项目目录\"\n```\n"
             if mac else
             "需要完整的 JDK 17 或更新版本；如果要让 Agent 编译 Java，JDK 中必须有 `javac`。\n\n"
             "Unix/macOS：\n\n```sh\n./bin/agent --root \"/你的/项目目录\"\n./bin/agent --help\n./bin/agent --version\n```\n\n"
             "Windows：\n\n```bat\nbin\\agent.cmd --root \"C:\\你的项目\"\n```\n\n"
             "也可直接使用 `java -Dfile.encoding=UTF-8 -jar lib/agent-cli.jar --root 项目目录`。\n")
    return f"""# Java AI Coding Agent {version}

{start}
## GLM 配置

首次启动时，CLI 会直接引导输入 API Key（隐藏回显）、选择模型和服务地址，并询问是否保存配置。
按回车可接受默认模型与服务地址；保存后无需重启即可输入任务。密钥可在智谱开放平台的 API Keys 页面获取。
发行包不含真实 API Key，无需先编辑文件或阅读本文才能使用。

所有启动方式统一读取用户配置 `~/.config/java-coding-agent/.env`；设置了 `XDG_CONFIG_HOME` 时，
路径为 `$XDG_CONFIG_HOME/java-coding-agent/.env`。Unix/macOS 保存的配置文件权限为 `600`。
也可仅在本次会话使用配置，或设置 `GLM_API_KEY` 环境变量、使用启动时当前目录的 `.env`。
优先级：当前目录 `.env` > 环境变量 > 用户配置文件 > 默认值。

`GLM_API_URL` 和 `GLM_MODEL` 可在向导中修改；本版默认模型为 `glm-5.2`，也可输入账户可用的自定义模型标识。
`--root` 只指定 Agent 的读写和执行范围，不改变 `.env` 的查找位置。
管道输入不会启动向导，也不会把任务文本误当作密钥；脚本模式应预先提供配置。

## 使用

任务示例：“新建 Hello.java，输出 Hello Agent，编译并运行，然后根据实际输出汇报结果。”
输入 `/config` 重新配置密钥和服务，`/model` 选择模型，`/root 路径` 切换项目，`/help` 查看内置帮助。
`clear` 或 `/clear` 清空对话历史，`exit`、`quit` 或 `/exit` 退出。
也可运行 `agent --configure` 单独配置；取消配置不会覆盖原配置，保存失败时仍可在本次会话使用。
启动器不带 `--root` 时，以终端当前目录为工作目录；双击 Mac 应用采用上文的默认目录。
请为 Agent 选择用于开发或实验的目录，它会按任务实际修改文件和执行受支持的命令。

本程序支持列目录、读文件、搜索代码、新建文件、精确替换、执行 Java/Javac/Maven 命令。
模型回答需要网络和有效 GLM 账户。Mac 版内置 Java 和 Javac；Maven 工程任务仍需要自行安装 Maven，并具备相应工程依赖。

## 会话保存与恢复

输入 `/save 会话名` 手动保存当前历史，输入 `/load 会话名` 并确认后恢复。
会话保存在当前项目的 `.agent/sessions/会话名.json`，名称可使用汉字、英文字母、数字、下划线和连字符，最长 64 个字符。
覆盖已有会话、加载替换历史均需确认；退出、清空、切换模型／配置／项目时，有未保存历史也会提醒。
确认提示 `[y/N]` 只有输入 `y` 或 `yes` 才同意，回车或其他回答取消。

启动时不自动恢复，退出时不自动保存。恢复保留对话和工具结果，不重新执行历史工具，也不还原项目文件。
会话使用当前模型、服务地址、系统提示和项目目录；无效文件会整体验证失败并保留当前历史。
会话文件不复制配置凭证，但用户写入对话的秘密不会自动脱敏。操作外部项目时，本程序不会自动修改该项目的 Git 忽略规则。

## 版本与验证

`release.json` 记录本次构建的源码摘要、Git 基准提交、工作树状态、JDK 和测试结果。
源码归档与 `SHA256SUMS` 随发行目录单独提供。本发行版本 {version} 包含 10.9 Coding Agent 和 10.16 会话保存／恢复功能。
包内 Java 运行时的许可位于应用 `Contents/Resources/runtime/legal/`；依赖组件的许可与声明保留在 JAR 的 `META-INF/` 中。
项目源码的发布授权沿用原仓库约定。

Mac 应用使用本地 ad-hoc 签名，未使用 Developer ID 签名或 Apple 公证。Apple Silicon 和 Intel 需要分别构建。
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "target/releases")
    parser.add_argument("--portable-only", action="store_true")
    parser.add_argument("--online", action="store_true", help="allow Maven to fetch uncached dependencies")
    args = parser.parse_args()
    pom = ET.parse(ROOT / "pom.xml").getroot()
    version = pom.findtext("{http://maven.apache.org/POM/4.0.0}version")
    if not version or any(c not in "0123456789.-abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" for c in version):
        raise SystemExit("Invalid project version")
    output = args.output_dir.resolve() / version
    if output.exists():
        raise SystemExit(f"Refusing to overwrite release directory: {output}; choose another --output-dir")
    output.mkdir(parents=True)
    build_parent = ROOT / "target"
    build_parent.mkdir(exist_ok=True)
    build = Path(tempfile.mkdtemp(prefix="release-build-", dir=build_parent))
    snapshot = build / f"Java-Coding-Agent-{version}-source"
    snapshot.mkdir()
    for name in ("pom.xml", "src", "dev", "README.md", ".env.example", ".gitignore",
                 "AGENTS.md", "CLAUDE.md", "SPEC.md", "packaging"):
        source = ROOT / name
        if source.is_dir():
            shutil.copytree(source, snapshot / name,
                            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".DS_Store"))
        else:
            shutil.copy2(source, snapshot / name)
    source_files = {str(p.relative_to(snapshot)): sha256(p) for p in sorted(snapshot.rglob("*")) if p.is_file()}
    zip_directory(snapshot, output / f"Java-Coding-Agent-{version}-source.zip")
    log = (output / "build.log").open("w", encoding="utf-8")

    def run(command):
        print("Building:", " ".join(map(str, command)), flush=True)
        log.write("$ " + " ".join(map(str, command)) + "\n")
        log.flush()
        subprocess.run(list(map(str, command)), cwd=snapshot, stdout=log, stderr=subprocess.STDOUT, check=True)

    try:
        run(["mvn", *([] if args.online else ["-o"]), "-B", "-f", snapshot / "pom.xml", "package"])
        totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
        reports = snapshot / "target/surefire-reports"
        for report in reports.glob("TEST-*.xml"):
            suite = ET.parse(report).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
        if not totals["tests"] or totals["failures"] or totals["errors"] or totals["skipped"]:
            raise RuntimeError(f"Release requires all tests to pass without skips: {totals}")
        shutil.copytree(reports, output / "test-results")
        jar = snapshot / "target/agent-cli.jar"
        run([sys.executable, snapshot / "dev/acceptance.py", "--jar", jar,
             "--output", output / "acceptance-results.json"])
        run([sys.executable, snapshot / "dev/session_acceptance.py", "--jar", jar,
             "--output", output / "session-acceptance-results.json"])
        coding_results = json.loads((output / "acceptance-results.json").read_text(encoding="utf-8"))
        session_results = json.loads((output / "session-acceptance-results.json").read_text(encoding="utf-8"))
        for results, expected in ((coding_results["results"], 6), (session_results["results"], 15)):
            if len(results) != expected or any(result["status"] != "PASS" for result in results):
                raise RuntimeError("CLI acceptance did not pass all expected scenarios")
        if session_results["jar_sha256"] != sha256(jar):
            raise RuntimeError("Session acceptance tested a different JAR")
        metadata = {
            "name": APP_NAME, "version": version,
            "built_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "git_base_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "git_worktree_status": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True).splitlines(),
            "source": "current working-tree snapshot, including uncommitted files",
            "source_sha256": source_files, "jar_sha256": sha256(jar), "unit_tests": totals,
            "cli_acceptance": {"coding_agent_scenarios": 6, "session_scenarios": 15},
            "platform": platform.platform(), "architecture": platform.machine(),
            "jdk": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip(),
            "api_credentials_included": False,
        }

        def populate(home):
            (home / "lib").mkdir(parents=True)
            (home / "VERSION").write_text(version + "\n", encoding="utf-8")
            shutil.copy2(jar, home / "lib/agent-cli.jar")
            executable_copy(snapshot / "packaging/agent", home / "bin/agent")
            shutil.copy2(snapshot / ".env.example", home / ".env.example")

        portable = build / f"Java-Coding-Agent-{version}-portable"
        populate(portable)
        shutil.copy2(snapshot / "packaging/agent.cmd", portable / "bin/agent.cmd")
        (portable / "使用说明.md").write_text(usage(version), encoding="utf-8")

        mac_distribution = None
        if platform.system() == "Darwin" and not args.portable_only:
            architecture = {"arm64": "arm64", "x86_64": "x64"}[platform.machine()]
            mac_distribution = build / f"Java-Coding-Agent-{version}-macos-{architecture}"
            contents = mac_distribution / f"{APP_NAME}.app/Contents"
            resources = contents / "Resources"
            populate(resources)
            executable_copy(snapshot / "packaging/Start.command", resources / "Start.command")
            java_home = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
            run([java_home / "bin/jlink", "--add-modules",
                 "java.se,jdk.compiler,jdk.zipfs,jdk.crypto.ec,jdk.unsupported,jdk.localedata,jdk.charsets,jdk.jartool",
                 "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2",
                 "--output", resources / "runtime"])
            (contents / "MacOS").mkdir()
            run(["/usr/bin/clang", "-Wall", "-Wextra", "-Werror", "-Os", "-mmacosx-version-min=11.0",
                 snapshot / "packaging/macos-launcher.c", "-o", contents / "MacOS/JavaCodingAgent"])
            with (contents / "Info.plist").open("wb") as stream:
                plistlib.dump({"CFBundleName": APP_NAME, "CFBundleDisplayName": APP_NAME,
                               "CFBundleIdentifier": "com.agent.coding-cli", "CFBundleExecutable": "JavaCodingAgent",
                               "CFBundlePackageType": "APPL", "CFBundleShortVersionString": version,
                               "CFBundleVersion": version, "LSMinimumSystemVersion": "11.0",
                               "NSHighResolutionCapable": True}, stream)
            (contents / "PkgInfo").write_bytes(b"APPL????")
            wrapper = '#!/bin/sh\nset -eu\nHERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)\nexec "$HERE/' + APP_NAME + '.app/Contents/Resources/bin/agent" "$@"\n'
            (mac_distribution / "agent").write_text(wrapper, encoding="utf-8")
            (mac_distribution / "agent").chmod(0o755)
            shutil.copy2(snapshot / ".env.example", mac_distribution / ".env.example")
            (mac_distribution / "使用说明.md").write_text(usage(version, mac=True), encoding="utf-8")
            metadata["bundled_runtime"] = subprocess.check_output(
                [str(resources / "runtime/bin/java"), "-version"], stderr=subprocess.STDOUT, text=True).strip()

        metadata_text = json.dumps(metadata, ensure_ascii=False, indent=2) + "\n"
        (output / "release.json").write_text(metadata_text, encoding="utf-8")
        (portable / "release.json").write_text(metadata_text, encoding="utf-8")
        zip_directory(portable, output / (portable.name + ".zip"))
        shutil.move(str(portable), output / portable.name)
        if mac_distribution:
            resources = mac_distribution / f"{APP_NAME}.app/Contents/Resources"
            (resources / "release.json").write_text(metadata_text, encoding="utf-8")
            (mac_distribution / "release.json").write_text(metadata_text, encoding="utf-8")
            run(["/usr/bin/codesign", "--force", "--deep", "--sign", "-", "--timestamp=none",
                 mac_distribution / f"{APP_NAME}.app"])
            run(["/usr/bin/codesign", "--verify", "--deep", "--strict", mac_distribution / f"{APP_NAME}.app"])
            run(["/usr/bin/ditto", "-c", "-k", "--norsrc", "--keepParent", mac_distribution,
                 output / (mac_distribution.name + ".zip")])
            shutil.move(str(mac_distribution), output / mac_distribution.name)
        sums = [f"{sha256(p)}  {p.name}" for p in sorted(output.glob("*.zip"))]
        (output / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
        print(f"Release built: {output}\nJUnit: {totals}\nVerify: python3 packaging/verify_release.py '{output}'")
    finally:
        log.close()


if __name__ == "__main__":
    main()
