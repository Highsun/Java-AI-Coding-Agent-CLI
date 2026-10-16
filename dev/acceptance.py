#!/usr/bin/env python3
"""Offline acceptance of the packaged CLI with a scripted model and REAL tools.

No external API/key or Python packages required. Each scenario owns a temporary
workspace. The HTTP model chooses fixed calls, then checks returned observations
before advancing. The Java CLI alone edits files and launches javac/java.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

GOOD = 'public class Hello { public static void main(String[] a) { System.out.println("Hello Agent"); }}\n'
BAD_OUTPUT = GOOD.replace('Hello Agent', 'wrong')
BAD_SYNTAX = GOOD.replace(');', ')')


def step(name, args, expected):
    return {"name": name, "args": args, "expected": expected}


def scenarios():
    return [
        {
            "name": "create_compile_run",
            "prompt": "新建 Hello.java，输出 Hello Agent，编译并运行，报告实际结果。",
            "seed": {},
            "steps": [
                step("write_file", {"path": "Hello.java", "content": GOOD}, "写入成功"),
                step("execute_command", {"command": "javac Hello.java"}, "exitCode=0"),
                step("execute_command", {"command": "java Hello"}, "stdout:\nHello Agent"),
            ],
            "expected_file": GOOD,
        },
        {
            "name": "read_edit_compile_run",
            "prompt": "读取 Hello.java，把输出改成 Hello Agent，然后重新编译、运行验证。",
            "seed": {"Hello.java": BAD_OUTPUT},
            "steps": [
                step("read_file", {"path": "Hello.java"}, '"wrong"'),
                step("edit_file", {"path": "Hello.java", "old_text": '"wrong"', "new_text": '"Hello Agent"'}, "替换成功"),
                step("execute_command", {"command": "javac Hello.java"}, "exitCode=0"),
                step("execute_command", {"command": "java Hello"}, "stdout:\nHello Agent"),
            ],
            "expected_file": GOOD,
        },
        {
            "name": "missing_file_recovery",
            "prompt": "读取 Config.java；若不存在，请先列目录，再读取实际配置文件。",
            "seed": {"ActualConfig.java": "class ActualConfig {}\n"},
            "steps": [
                step("read_file", {"path": "Config.java"}, "执行工具失败"),
                step("list_dir", {"path": "."}, "ActualConfig.java"),
                step("read_file", {"path": "ActualConfig.java"}, "class ActualConfig {}"),
            ],
        },
        {
            "name": "unknown_tool_recovery",
            "prompt": "读取 Hello.java，若工具名错误，请根据可用工具修正调用。",
            "seed": {"Hello.java": GOOD},
            "steps": [
                step("open_file", {"path": "Hello.java"}, "未知工具: open_file"),
                step("read_file", {"path": "Hello.java"}, "public class Hello"),
            ],
        },
        {
            "name": "repeated_call_guard",
            "prompt": "故障挑战：无论结果如何，连续三次读取 Hello.java。",
            "seed": {"Hello.java": GOOD},
            "steps": [step("read_file", {"path": "Hello.java"}, "public class Hello") for _ in range(3)],
            "stopped": True,
        },
        {
            "name": "compiler_error_recovery",
            "prompt": "新建并运行 Hello.java，输出 Hello Agent；遇到编译错误后修复，再编译运行。",
            "seed": {},
            "steps": [
                step("write_file", {"path": "Hello.java", "content": BAD_SYNTAX}, "写入成功"),
                step("execute_command", {"command": "javac Hello.java"}, "exitCode=1"),
                step("edit_file", {"path": "Hello.java", "old_text": 'println("Hello Agent")', "new_text": 'println("Hello Agent");'}, "替换成功"),
                step("execute_command", {"command": "javac Hello.java"}, "exitCode=0"),
                step("execute_command", {"command": "java Hello"}, "stdout:\nHello Agent"),
            ],
            "expected_file": GOOD,
        },
    ]


def run_scenario(jar, scenario):
    requests = []
    errors = []
    steps = scenario["steps"]

    class Model(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                requests.append(request)
                assert len(request["tools"]) == 7, "all seven tools must be advertised"
                history = request["messages"]
                assert history[1]["content"] == scenario["prompt"]
                observations = [m for m in history if m["role"] == "tool"]
                calls = [c for m in history for c in m.get("tool_calls", [])]
                assert len(observations) == len(calls), "missing tool observation"
                assert [m["tool_call_id"] for m in observations] == [c["id"] for c in calls]
                for index, observation in enumerate(observations):
                    assert steps[index]["expected"] in observation["content"], observation["content"]
                if len(observations) < len(steps):
                    index = len(observations)
                    action = steps[index]
                    message = {"role": "assistant", "content": None, "tool_calls": [{
                        "id": f"call_{index+1}", "type": "function", "function": {
                            "name": action["name"], "arguments": json.dumps(action["args"], ensure_ascii=False)}}]}
                else:
                    message = {"role": "assistant", "content": "验收完成：所有预期 Observation 已收到。"}
                body = {"choices": [{"message": message}], "usage": {"prompt_tokens": 0, "completion_tokens": 0}}
                status = 200
            except Exception as error:
                errors.append(str(error))
                body = {"error": str(error)}
                status = 400
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(data)

    with tempfile.TemporaryDirectory(prefix="agent-acceptance-") as directory:
        workspace = Path(directory)
        for name, content in scenario["seed"].items():
            (workspace / name).write_text(content, encoding="utf-8")
        server = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            environment = {k: v for k, v in os.environ.items() if not k.startswith("GLM_")}
            environment.update(GLM_API_KEY="offline-dummy", GLM_API_URL=f"http://127.0.0.1:{server.server_port}/chat")
            completed = subprocess.run(["java", "-jar", str(jar), "--root", str(workspace)],
                                       cwd=workspace, env=environment,
                                       input=scenario["prompt"] + "\nexit\ny\n", text=True,
                                       capture_output=True, timeout=45, encoding="utf-8")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        output = completed.stdout + completed.stderr
        assert completed.returncode == 0, output
        assert not errors, "\n".join(errors)
        if scenario.get("stopped"):
            assert len(requests) == 3, "third identical call must stop before another LLM request"
            assert "安全守卫停止" in output
            assert "验收完成：" not in output
        else:
            assert len(requests) == len(steps) + 1, output
            assert "验收完成：" in output
        if "expected_file" in scenario:
            assert (workspace / "Hello.java").read_text(encoding="utf-8") == scenario["expected_file"]
            assert (workspace / "Hello.class").is_file()
        observations = [m for m in requests[-1]["messages"] if m["role"] == "tool"]
        return {"scenario": scenario["name"], "status": "PASS", "prompt": scenario["prompt"],
                "http_requests": len(requests), "observations_sent_to_model": observations,
                "cli_transcript": output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path(__file__).resolve().parents[1] / "target/agent-cli.jar")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "target/acceptance-results.json")
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error("Build the jar first: mvn -o -DskipTests package")
    results = []
    for scenario in scenarios():
        result = run_scenario(jar, scenario)
        results.append(result)
        print(f"PASS {result['scenario']} ({result['http_requests']} HTTP requests)")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"model": "local scripted HTTP model; no real GLM inference", "results": results},
                                     ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(results)}/{len(results)} scenarios passed. Evidence: {args.output.resolve()}")


if __name__ == "__main__":
    main()
