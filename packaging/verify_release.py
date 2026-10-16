#!/usr/bin/env python3
"""Verify extracted release archives and run real tools against an offline model."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import zipfile
from verify_onboarding import run_checks as onboarding_checks

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def run_case(command, environment, workspace, scenario):
    requests, errors = [], []
    steps = scenario["steps"]
    for name, content in scenario["seed"].items():
        (workspace / name).write_text(content, encoding="utf-8")

    class Model(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                requests.append(request)
                check(len(request["tools"]) == 7, "missing tool schemas")
                history = request["messages"]
                check(history[1]["content"] == scenario["prompt"], "task changed")
                observations = [m for m in history if m["role"] == "tool"]
                calls = [c for m in history for c in m.get("tool_calls", [])]
                check([m["tool_call_id"] for m in observations] == [c["id"] for c in calls], "tool IDs/order")
                for index, observation in enumerate(observations):
                    check(steps[index]["expected"] in observation["content"], observation["content"])
                if len(observations) < len(steps):
                    index = len(observations)
                    action = steps[index]
                    message = {"role": "assistant", "content": None, "tool_calls": [{
                        "id": f"call_{index+1}", "type": "function", "function": {
                            "name": action["name"], "arguments": json.dumps(action["args"], ensure_ascii=False)}}]}
                else:
                    message = {"role": "assistant", "content": "验收完成：所有预期 Observation 已收到。"}
                body, status = {"choices": [{"message": message}]}, 200
            except Exception as error:
                errors.append(str(error))
                body, status = {"error": str(error)}, 400
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(data)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Model)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    env = dict(environment, GLM_API_KEY="offline-dummy", GLM_API_URL=f"http://127.0.0.1:{server.server_port}/chat")
    # Read the endpoint from the user's config in one case, and a workspace .env
    # in another. No real credential or service is used in any test.
    if scenario["name"] == "create_compile_run":
        config = Path(env["XDG_CONFIG_HOME"]) / "java-coding-agent/.env"
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text('\ufeff GLM_API_KEY="offline-dummy"\nGLM_API_URL="' + env.pop("GLM_API_URL") +
                          '"\nGLM_MODEL="release-test"\nIGNORED=$(touch should-not-exist)\n', encoding="utf-8")
        env.pop("GLM_API_KEY")
    elif scenario["name"] == "read_edit_compile_run":
        (workspace / ".env").write_text("GLM_API_KEY=offline-dummy\nGLM_API_URL=" + env["GLM_API_URL"] + "\n", encoding="utf-8")
        env["GLM_API_URL"] = "http://127.0.0.1:1/should-be-overridden"
    try:
        completed = subprocess.run([str(command), "--root", str(workspace)], cwd=workspace, env=env,
                                   input=scenario["prompt"] + "\nexit\ny\n", text=True,
                                   capture_output=True, encoding="utf-8", timeout=60)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        config = Path(env["XDG_CONFIG_HOME"]) / "java-coding-agent/.env"
        if config.exists():
            config.unlink()
    output = completed.stdout + completed.stderr
    check(completed.returncode == 0, output)
    check(not errors, "\n".join(errors))
    check(f"项目目录：{workspace}" in output, "--root did not select requested workspace")
    check(not (workspace / "should-not-exist").exists(), "config must never be executed as shell")
    if scenario.get("stopped"):
        check(len(requests) == 3 and "安全守卫停止" in output, output)
    else:
        check(len(requests) == len(steps) + 1 and "验收完成：" in output, output)
    if "expected_file" in scenario:
        check((workspace / "Hello.java").read_text(encoding="utf-8") == scenario["expected_file"], "source mismatch")
        check((workspace / "Hello.class").is_file(), "compiler did not produce class")
    return {"scenario": scenario["name"], "status": "PASS", "requests": len(requests), "transcript": output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("release", type=Path)
    args = parser.parse_args()
    release = args.release.resolve()
    metadata = json.loads((release / "release.json").read_text(encoding="utf-8"))
    spec = importlib.util.spec_from_file_location("acceptance_fixtures", ROOT / "dev/acceptance.py")
    fixtures = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures)
    results = []
    for line in (release / "SHA256SUMS").read_text().splitlines():
        expected, name = line.split("  ", 1)
        with (release / name).open("rb") as stream:
            check(hashlib.file_digest(stream, "sha256").hexdigest() == expected, f"archive checksum: {name}")
        with zipfile.ZipFile(release / name) as archive:
            check(archive.testzip() is None, f"corrupt archive: {name}")
            for member in archive.namelist():
                check(not ({".env", ".git", ".DS_Store", "__pycache__"} & set(Path(member).parts)),
                      f"unexpected private/generated file in archive: {member}")
    java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip() if os.uname().sysname == "Darwin" else os.environ.get("JAVA_HOME", "")
    with tempfile.TemporaryDirectory(prefix="Agent 发行验收 ") as temp:
        base = Path(temp).resolve()
        for archive in sorted(release.glob("*.zip")):
            if archive.name.endswith("-source.zip"):
                continue
            destination = base / archive.stem
            destination.mkdir()
            if os.uname().sysname == "Darwin":
                subprocess.run(["/usr/bin/ditto", "-x", "-k", str(archive), str(destination)], check=True)
            else:
                with zipfile.ZipFile(archive) as zipped:
                    zipped.extractall(destination)
                    for member in zipped.infolist():
                        mode = member.external_attr >> 16
                        if mode:
                            (destination / member.filename).chmod(mode)
            distribution = destination / archive.stem
            mac = "-macos-" in archive.name
            app = distribution / "Java AI Coding Agent.app"
            home = app / "Contents/Resources" if mac else distribution
            launcher = distribution / "agent" if mac else distribution / "bin/agent"
            check(hashlib.sha256((home / "lib/agent-cli.jar").read_bytes()).hexdigest() == metadata["jar_sha256"], "JAR hash")
            env = {k: v for k, v in os.environ.items() if not k.startswith("GLM_") and
                   k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS", "MAVEN_ARGS")}
            user = base / (archive.stem + " 用户目录")
            user.mkdir()
            env.update(HOME=str(user), XDG_CONFIG_HOME=str(user / "config"),
                       PATH="/usr/bin:/bin", JAVA_HOME="/nonexistent" if mac else java_home)
            cwd = base / (archive.stem + " 当前目录")
            cwd.mkdir()
            def invoke(arguments, input="", launch=launcher):
                return subprocess.run([str(launch), *arguments], cwd=cwd, env=env, input=input,
                                      capture_output=True, text=True, encoding="utf-8", timeout=30)
            check(invoke(["--version"]).stdout.strip() == f"Java AI Coding Agent {metadata['version']}", "version")
            help_result = invoke(["--help"])
            check(help_result.returncode == 0 and "execute_command" in help_result.stdout, "help without key")
            missing = invoke([])
            check(missing.returncode == 1 and "GLM_API_KEY" in missing.stderr, "missing key exit status")
            check(invoke(["--root", str(cwd / "missing")]).returncode == 2, "invalid root exit status")
            env["GLM_API_KEY"] = "offline-dummy"
            normal = invoke([], "exit\n")
            check(normal.returncode == 0 and f"项目目录：{cwd}" in normal.stdout,
                  "default cwd or exit: " + normal.stdout + normal.stderr)
            del env["GLM_API_KEY"]
            if mac:
                subprocess.run(["/usr/bin/codesign", "--verify", "--deep", "--strict", str(app)], check=True)
                native = invoke(["--check"], launch=app / "Contents/MacOS/JavaCodingAgent")
                check(native.returncode == 0 and Path(native.stdout.strip()).resolve() == (home / "Start.command").resolve(), "native Finder entry")
                env["GLM_API_KEY"] = "offline-dummy"
                doubleclick = invoke([], "exit\n\n", launch=home / "Start.command")
                check(doubleclick.returncode == 0 and str(user / "JavaCodingAgentWorkspace") in doubleclick.stdout,
                      "double-click terminal script or workspace")
                del env["GLM_API_KEY"]
                check((home / "runtime/bin/javac").is_file(), "bundled compiler missing")
            for scenario in fixtures.scenarios():
                workspace = base / (archive.stem + " 实验 " + scenario["name"])
                workspace.mkdir()
                result = run_case(launcher, env, workspace, scenario)
                results.append(dict(result, distribution=archive.stem))
                print(f"PASS {archive.stem}: {scenario['name']}", flush=True)
            terminal_results = onboarding_checks(launcher, env, base / (archive.stem + " 引导实验"),
                                                 home / "runtime/bin/java" if mac else Path(java_home) / "bin/java",
                                                 home / "lib/agent-cli.jar", home / "Start.command" if mac else None)
            results.extend(dict(result, distribution=archive.stem) for result in terminal_results)
            print(f"PASS {archive.stem}: {len(terminal_results)} real PTY onboarding checks", flush=True)
            results.append({"distribution": archive.stem, "scenario": "launchers_config_paths_signature", "status": "PASS"})
    report = {"version": metadata["version"], "model": "local scripted HTTP model; real file/Java tools",
              "gui_launch_tested": False, "note": "Native entry path and Start.command tested; Finder/Terminal GUI not driven.",
              "results": results}
    (release / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(results)} release checks passed. Evidence: {release / 'verification.json'}")


if __name__ == "__main__":
    main()
