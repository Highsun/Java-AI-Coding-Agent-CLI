#!/usr/bin/env python3
"""Exercise real CLI onboarding in a PTY with isolated user settings (Unix)."""
import argparse
import errno
import json
import os
from pathlib import Path
import pty
import select
import signal
import subprocess
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def check(condition, message):
    if not condition:
        raise AssertionError(message)


class Terminal:
    def __init__(self, command, cwd, environment):
        self.master, self.slave = pty.openpty()
        self.original = termios.tcgetattr(self.slave)
        # Keep a shell alive after the application exits so macOS doesn't revoke
        # the slave TTY before its restored modes can be inspected.
        wrapper = '"$@"; status=$?; printf "\\n__AGENT_PTY_DONE_%s__\\n" "$status"; IFS= read -r done; exit "$status"'
        self.process = subprocess.Popen(["/bin/sh", "-c", wrapper, "pty-check", *map(str, command)], cwd=cwd, env=environment,
                                        stdin=self.slave, stdout=self.slave, stderr=self.slave,
                                        start_new_session=True)
        self.data = bytearray()
        self.cursor = 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=5)
        os.close(self.master)
        os.close(self.slave)

    @property
    def output(self):
        return self.data.decode("utf-8", errors="replace").replace("\r\n", "\n")

    def pump(self, timeout):
        if select.select([self.master], [], [], timeout)[0]:
            try:
                chunk = os.read(self.master, 65536)
            except OSError as error:
                if error.errno == errno.EIO:
                    return False
                raise
            self.data.extend(chunk)
            return bool(chunk)
        return False

    def expect(self, text, timeout=30):
        marker = text.encode("utf-8")
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            position = self.data.find(marker, self.cursor)
            if position >= 0:
                self.cursor = position + len(marker)
                return
            self.pump(0.05)
            if self.process.poll() is not None:
                self.pump(0)
                if self.data.find(marker, self.cursor) < 0:
                    break
        # Do not include the transcript in failure text: it might contain a secret
        # if the very behavior being tested is broken.
        raise AssertionError(f"Terminal did not reach prompt: {text!r}; exit={self.process.poll()}")

    def send(self, value):
        os.write(self.master, value.encode("utf-8"))

    def finish(self, code=0):
        self.expect(f"__AGENT_PTY_DONE_{code}__")
        restored = termios.tcgetattr(self.slave)
        mask = termios.ECHO | termios.ICANON
        check(restored[3] & mask == self.original[3] & mask, "Terminal echo/canonical mode not restored")
        self.send("\n")
        end = time.monotonic() + 15
        while self.process.poll() is None and time.monotonic() < end:
            self.pump(0.05)
        check(self.process.poll() == code, f"Terminal exit status: {self.process.poll()}, expected {code}")
        while select.select([self.master], [], [], 0.05)[0]:
            if not self.pump(0):
                break


def run_checks(launcher, environment, base, java, jar, start=None):
    """Return evidence; every HOME/config/workspace is inside base, never real HOME."""
    base = Path(base).resolve()
    base.mkdir(parents=True, exist_ok=True)
    results, requests, errors = [], [], []
    secret = "onboarding-private-test-key"
    replacement = "replacement-private-test-key"
    source = 'public class Hello { public static void main(String[] a) { System.out.println("Hello Agent"); }}\n'
    steps = [
        ("write_file", {"path": "Hello.java", "content": source}, "写入成功"),
        ("execute_command", {"command": "javac Hello.java"}, "exitCode=0"),
        ("execute_command", {"command": "java Hello"}, "stdout:\nHello Agent"),
    ]

    class Model(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                requests.append(request)
                check(self.headers.get("Authorization") in ("Bearer " + secret, "Bearer " + replacement), "Authorization missing")
                check(request["model"] in ("onboarding-model", "updated-model"), "Configured model not used")
                messages = request["messages"]
                check(secret not in json.dumps(messages) and replacement not in json.dumps(messages), "Credential in model history")
                observations = [m for m in messages if m["role"] == "tool"]
                calls = [c for m in messages for c in m.get("tool_calls", [])]
                check([m["tool_call_id"] for m in observations] == [c["id"] for c in calls], "Observation order")
                for i, observation in enumerate(observations):
                    check(steps[i][2] in observation["content"], "Tool result mismatch")
                if len(observations) < len(steps):
                    i = len(observations)
                    name, arguments, _ = steps[i]
                    message = {"role": "assistant", "content": None, "tool_calls": [{
                        "id": f"onboarding_{i}", "type": "function", "function": {
                            "name": name, "arguments": json.dumps(arguments)}}]}
                else:
                    message = {"role": "assistant", "content": "引导验收完成：已编译运行 Hello Agent。"}
                response, status = {"choices": [{"message": message}]}, 200
            except Exception as error:
                errors.append(str(error))
                response, status = {"error": "offline test failed"}, 400
            data = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(data)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Model)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    endpoint = f"http://127.0.0.1:{server.server_port}/chat"

    def fresh(name):
        directory = base / name
        cwd = directory / "当前目录 with spaces"
        cwd.mkdir(parents=True)
        env = {k: v for k, v in environment.items() if not k.startswith("GLM_")}
        env.update(HOME=str(directory / "用户目录"), XDG_CONFIG_HOME=str(directory / "配置目录"))
        config = Path(env["XDG_CONFIG_HOME"]) / "java-coding-agent/.env"
        return cwd, env, config

    def record(name, terminal, **extra):
        check(secret not in terminal.output and replacement not in terminal.output, "Credential echoed to terminal")
        results.append(dict(scenario=name, status="PASS", transcript=terminal.output, **extra))

    def setup(terminal, key=secret, model="onboarding-model", save="", keep=False):
        terminal.expect("API Key [回车保留现有密钥]：" if keep else "API Key：")
        terminal.send(key + "\n")
        terminal.expect("选择模型 [1]：")
        terminal.send("2\n")
        terminal.expect("模型名称：")
        terminal.send(model + "\n")
        terminal.expect("服务地址 [")
        terminal.send(endpoint + "\n")
        terminal.expect("保存配置供下次启动使用？[Y/n]：")
        terminal.send(save + "\n")

    try:
        cwd, env, config = fresh("首次配置")
        with Terminal([launcher], cwd, env) as terminal:
            terminal.expect("API Key：")
            check(not (termios.tcgetattr(terminal.slave)[3] & termios.ECHO), "Password echo was not disabled")
            terminal.send("\n")
            terminal.expect("请输入有效密钥")
            terminal.expect("API Key：")
            # Exercise secret-line editing without leaking characters or length.
            terminal.send("discarded\x15" + secret + "x\x7f\n")
            terminal.expect("选择模型 [1]：")
            terminal.send("9\n")
            terminal.expect("请输入 1 或 2")
            terminal.expect("选择模型 [1]：")
            terminal.send("2\n")
            terminal.expect("模型名称：")
            terminal.send("onboarding-model\n")
            terminal.expect("服务地址 [")
            terminal.send("invalid-url\n")
            terminal.expect("请输入完整的")
            terminal.expect("服务地址 [")
            terminal.send(endpoint + "\n")
            terminal.expect("保存配置供下次启动使用？[Y/n]：")
            terminal.send("\n")
            terminal.expect("你：")
            terminal.send("新建 Hello.java，输出 Hello Agent，编译并运行，报告实际结果。\n")
            terminal.expect("引导验收完成：")
            terminal.expect("你：")
            terminal.send("exit\n")
            terminal.finish()
            record("onboarding_hidden_key_save_and_real_tools", terminal, http_requests=len(requests))
        check(config.is_file() and secret in config.read_text(), "Settings not saved or secret editing failed")
        check(config.stat().st_mode & 0o777 == 0o600, "Saved config permissions must be 600")
        check((cwd / "Hello.java").read_text() == source and (cwd / "Hello.class").is_file(), "Real tool chain failed")
        check(len(requests) == 4 and not errors, "First configured task did not complete")

        other = cwd.parent / "另一个项目 with spaces"
        other.mkdir()
        with Terminal([launcher], cwd, env) as terminal:
            terminal.expect("你：")
            check("GLM 配置向导" not in terminal.output, "Saved settings not loaded")
            check("当前模型：onboarding-model" in terminal.output, "Saved model not loaded")
            terminal.send("/model\n")
            terminal.expect("选择模型 [1]：")
            terminal.send("2\n")
            terminal.expect("模型名称：")
            terminal.send("updated-model\n")
            terminal.expect("保存配置供下次启动使用？[Y/n]：")
            terminal.send("y\n")
            terminal.expect("你：")
            # Keeping the old key must work without exposing even a masked suffix.
            terminal.send("/config\n")
            setup(terminal, key="", model="updated-model", keep=True)
            terminal.expect("你：")
            terminal.send('/root "' + str(other) + '"\n')
            terminal.expect("项目目录：" + str(other))
            terminal.expect("你：")
            terminal.send('/root "' + str(other / "missing") + '"\n')
            terminal.expect("无法切换项目：")
            terminal.expect("你：")
            terminal.send("/help\n")
            terminal.expect("用法: agent")
            terminal.expect("你：")
            terminal.send("新建 Hello.java，编译并运行。\n")
            terminal.expect("引导验收完成：")
            terminal.expect("你：")
            terminal.send("/exit\n")
            terminal.finish()
            record("restart_model_config_root_and_help", terminal, http_requests=len(requests) - 4)
        check("GLM_MODEL=updated-model" in config.read_text() and secret in config.read_text(), "Updated config not saved")
        check((other / "Hello.class").is_file() and len(requests) == 8 and not errors, "Root/model switch not used by tools")

        with Terminal([launcher, "--configure"], cwd, env) as terminal:
            setup(terminal, key=replacement, model="updated-model", keep=True)
            terminal.finish()
            check("你：" not in terminal.output, "--configure should not enter a task session")
            record("configure_only_replace_key", terminal)
        check(replacement in config.read_text() and secret not in config.read_text(), "Replacement key not persisted")

        # A direct JAR launch must share the same per-user settings as wrappers.
        with Terminal([java, "-Dfile.encoding=UTF-8", "-jar", jar], cwd, env) as terminal:
            terminal.expect("你：")
            check("GLM 配置向导" not in terminal.output and "当前模型：updated-model" in terminal.output, "Direct JAR config lookup differs")
            terminal.send("exit\n")
            terminal.finish()
            record("direct_jar_reads_user_config", terminal)

        cwd2, env2, config2 = fresh("仅本次使用")
        with Terminal([launcher], cwd2, env2) as terminal:
            setup(terminal, save="n")
            terminal.expect("你：")
            terminal.send("exit\n")
            terminal.finish()
            record("session_only_no_persistent_config", terminal)
        check(not config2.exists(), "Session-only choice wrote config")
        with Terminal([launcher], cwd2, env2) as terminal:
            terminal.expect("API Key：")
            terminal.send("\x04")
            terminal.finish()
            record("first_setup_cancel_restores_terminal", terminal)
        check(not config2.exists(), "Cancelled first setup wrote config")

        original = config.read_bytes()
        with Terminal([launcher], cwd, env) as terminal:
            terminal.expect("你：")
            terminal.send("/config\n")
            terminal.expect("API Key [回车保留现有密钥]：")
            terminal.send("\x04")
            terminal.expect("已取消配置")
            terminal.expect("你：")
            terminal.send("exit\n")
            terminal.finish()
            record("in_session_cancel_preserves_config", terminal)
        check(config.read_bytes() == original, "Cancellation changed saved settings")

        if start is not None:
            cwd3, env3, config3 = fresh("Mac首次启动")
            with Terminal([start], cwd3, env3) as terminal:
                setup(terminal)
                terminal.expect("你：")
                terminal.send("exit\n")
                terminal.expect("按回车关闭此窗口。")
                terminal.send("\n")
                terminal.finish()
                check(str(Path(env3["HOME"]) / "JavaCodingAgentWorkspace") in terminal.output, "Mac default workspace")
                record("mac_start_command_first_setup", terminal)
            check(config3.is_file(), "Mac first setup did not save settings")
        return results
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def main():
    import tempfile
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--start", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    env = {k: v for k, v in os.environ.items() if not k.startswith("GLM_") and
           k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
    with tempfile.TemporaryDirectory(prefix="Agent 引导验收 ") as temp:
        results = run_checks(args.launcher.resolve(), env, temp, args.java.resolve(), args.jar.resolve(),
                             args.start.resolve() if args.start else None)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(results)} PTY checks passed. Evidence: {args.output}")


if __name__ == "__main__":
    main()
