#!/usr/bin/env python3
"""离线会话验收：真实 JAR、跨进程恢复、管道和 Unix PTY，不使用真实配置。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'packaging'))
from verify_onboarding import Terminal, check


def wire(messages):
    result = []
    for message in messages:
        item = {'role': message['role']}
        if message['content'] is not None:
            item['content'] = message['content']
        if message['toolCalls'] is not None:
            item['tool_calls'] = [dict(id=c['id'], type='function', function=c['function']) for c in message['toolCalls']]
        if message['toolCallId'] is not None:
            item['tool_call_id'] = message['toolCallId']
        result.append(item)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path(__file__).resolve().parents[1] / 'target/agent-cli.jar')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'target/session-acceptance-results.json')
    args = parser.parse_args()
    jar = args.jar.resolve()
    check(jar.is_file(), '先重新打包 JAR')
    requests, errors, results = [], [], []

    class Model(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(request)
                check(self.headers.get('Authorization') == 'Bearer offline-session-test', 'unexpected credential')
                check(len(request['tools']) == 7, 'session operations must not become model tools')
                history = request['messages']
                check(sum(m['role'] == 'system' for m in history) == 1, 'system prompt duplicated')
                last = history[-1]
                if last['role'] == 'user' and last['content'] == '工具任务':
                    calls = []
                    for name in ('data.txt', 'missing.txt'):
                        calls.append(dict(id=f'read_{len(requests)}_{len(calls)}', type='function',
                                          function=dict(name='read_file', arguments=json.dumps({'path': name}))))
                    message = dict(role='assistant', content=None, tool_calls=calls)
                else:
                    message = dict(role='assistant', content='离线回答完成')
                payload, status = {'choices': [{'message': message}]}, 200
            except Exception as error:
                errors.append(str(error))
                payload, status = {'error': 'local test failed'}, 400
            data = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(data)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Model)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix='Java会话验收 ') as temp:
            base = Path(temp).resolve()
            project = base / '项目 A with spaces'
            other = base / '项目 B with spaces'
            project.mkdir(); other.mkdir()
            (project / 'data.txt').write_text('实际工具结果', encoding='utf-8')
            env = {k: v for k, v in os.environ.items() if not k.startswith('GLM_') and
                   k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
            env.update(HOME=str(base / 'home'), XDG_CONFIG_HOME=str(base / 'config'),
                       GLM_API_KEY='offline-session-test', GLM_MODEL='session-model',
                       GLM_API_URL=f'http://127.0.0.1:{server.server_port}/chat')
            command = ['java', '-Dfile.encoding=UTF-8', '-jar', str(jar), '--root', str(project)]

            def record(name, output, start):
                check(not errors, str(errors))
                results.append(dict(scenario=name, status='PASS', transcript=output,
                                    requests=requests[start:]))

            def pipe(name, text, model=None):
                start = len(requests)
                configured = dict(env)
                if model:
                    configured['GLM_MODEL'] = model
                process = subprocess.run(command, input=text, text=True, encoding='utf-8',
                                         cwd=base, env=configured, capture_output=True, timeout=30)
                check(process.returncode == 0, process.stdout + process.stderr)
                record(name, process.stdout + process.stderr, start)
                return process.stdout, requests[start:]

            output, req = pipe('save_multitool_history', '工具任务\n/save 实验_01\nexit\n')
            check(len(req) == 2, 'save must not call model')
            session = project / '.agent/sessions/实验_01.json'
            saved = json.loads(session.read_text())
            check(len(saved['messages']) == 5, 'lost tool messages')
            check('实际工具结果' in saved['messages'][2]['content'], 'missing real observation')
            check('失败' in saved['messages'][3]['content'], 'lost failure observation')
            check('offline-session-test' not in session.read_text(), 'credential persisted')
            check(not (base / '.agent').exists(), 'sessions incorrectly use startup directory')
            before_bytes = session.read_bytes()

            output, req = pipe('startup_does_not_restore', '新问题\nexit\ny\n')
            check(len(req) == 1 and len(req[0]['messages']) == 2, 'startup auto restored')
            output, req = pipe('restore_with_current_model_and_continue', '/load 实验_01\ny\n接着说\nexit\ny\n', 'new-model')
            check(len(req) == 1, 'load replayed tools or called model')
            check(req[0]['model'] == 'new-model', 'load changed model')
            check(req[0]['messages'][1:-1] == wire(saved['messages']), 'restored request mismatch')
            check(req[0]['messages'][-1]['content'] == '接着说', 'lost new input')
            check(session.read_bytes() == before_bytes, 'load modified file')

            output, req = pipe('cancel_overwrite_and_restore',
                               '保留当前\n/save 实验_01\nn\n/load 实验_01\nn\n继续当前\nexit\ny\n')
            check(len(req) == 2 and len(req[1]['messages']) == 4, 'cancel changed history')
            check(req[1]['messages'][1]['content'] == '保留当前', 'old history lost')
            check(session.read_bytes() == before_bytes, 'cancel overwrote file')
            output, req = pipe('confirm_overwrite', '新版本\n/save 实验_01\ny\nexit\n')
            check(len(req) == 1 and json.loads(session.read_text())['messages'][0]['content'] == '新版本', 'overwrite failed')

            (project / '.agent/sessions/bad.json').write_text('{bad private data', encoding='utf-8')
            output, req = pipe('invalid_load_and_commands_preserve_history',
                               '当前\n/load bad\n/load missing\n/save a b\n/SAVE x\n继续\nexit\ny\n')
            check(len(req) == 2 and len(req[1]['messages']) == 4, 'invalid command reached model or lost history')
            check('private data' not in output, 'parser exposed file contents')

            output, req = pipe('cancel_discard_and_clear', '原始\nclear\nn\nexit\nn\n继续\nclear\ny\n全新\nexit\ny\n')
            check([len(r['messages']) for r in req] == [2, 4, 2], 'discard/clear history mismatch')
            output, req = pipe('switch_project_cancel_invalid_and_success',
                               f'原项目\n/root "{other}"\nn\n继续原项目\n/root "{other / "missing"}"\ny\n仍在原项目\n'
                               f'/root "{other}"\ny\n/load 实验_01\n新项目\nexit\ny\n')
            check([len(r['messages']) for r in req] == [2, 4, 6, 2], 'project switch changed history at wrong time')
            check('当前项目找不到该会话' in output, 'loaded another project session')
            check(not (other / '.agent').exists(), 'missing load created directory')
            for name, text in [('pipe_eof_main', '未保存\n'), ('pipe_eof_confirm', '未保存\nexit\n')]:
                output, req = pipe(name, text)
                check('尚未保存' in output and len(req) == 1, 'EOF silently discarded or looped')

            # Exercise model/config changes and both EOF/Ctrl+C in the real raw terminal.
            start = len(requests)
            with Terminal(command, base, env) as terminal:
                terminal.expect('你：'); terminal.send('保留模型历史\n'); terminal.expect('你：')
                for action in ('/model', '/config'):
                    terminal.send(action + '\n'); terminal.expect('[y/N]：'); terminal.send('n\n'); terminal.expect('你：')
                terminal.send('确认保留\n'); terminal.expect('你：')
                terminal.send('/model\n'); terminal.expect('[y/N]：'); terminal.send('y\n')
                terminal.expect('选择模型 [1]：'); terminal.send('\x04'); terminal.expect('你：')
                terminal.send('向导取消后继续\n'); terminal.expect('你：')
                terminal.send('/model\n'); terminal.expect('[y/N]：'); terminal.send('y\n')
                terminal.expect('选择模型 [1]：'); terminal.send('2\n')
                terminal.expect('模型名称：'); terminal.send('updated-model\n')
                terminal.expect('保存配置供下次启动使用？[Y/n]：'); terminal.send('n\n'); terminal.expect('你：')
                terminal.send('新模型\n'); terminal.expect('你：')
                terminal.send('/save pty_saved\n'); terminal.expect('你：')
                terminal.send('/config\n'); terminal.expect('API Key [回车保留现有密钥]：'); terminal.send('\n')
                terminal.expect('选择模型 [1]：'); terminal.send('\n')
                terminal.expect('服务地址 ['); terminal.send('\n')
                terminal.expect('保存配置供下次启动使用？[Y/n]：'); terminal.send('n\n'); terminal.expect('你：')
                terminal.send('/load pty_saved\n'); terminal.expect('[y/N]：'); terminal.send('yes\n'); terminal.expect('你：')
                terminal.send('恢复后当前模型\n'); terminal.expect('你：')
                terminal.send('exit\n'); terminal.expect('[y/N]：'); terminal.send('y\n'); terminal.finish()
                check([len(r['messages']) for r in requests[start:]] == [2, 4, 6, 2, 4], 'PTY history changes incorrect')
                check(all(r['model'] == 'updated-model' for r in requests[start + 3:]), 'load or config changed current model')
                record('pty_model_config_cancel_switch_and_load', terminal.output, start)

            for control, label in [('\x03', 'ctrl_c'), ('\x04', 'ctrl_d')]:
                for prompt in ('main', 'confirmation'):
                    start = len(requests)
                    with Terminal(command, base, env) as terminal:
                        terminal.expect('你：'); terminal.send('终止前未保存\n'); terminal.expect('你：')
                        if prompt == 'confirmation':
                            terminal.send('/load 实验_01\n'); terminal.expect('[y/N]：')
                        terminal.send(control); terminal.finish()
                        check('尚未保存' in terminal.output, 'terminal ended without unsaved notice')
                        check(len(requests) == start + 1, 'terminal confirmation called model')
                        record(f'pty_{label}_{prompt}', terminal.output, start)

            check(not (base / 'config/java-coding-agent/.env').exists(), 'session/config-only choices wrote real settings')
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=2)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    evidence = dict(jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), passed=len(results), results=results)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{len(results)} session CLI/PTY scenarios passed. Evidence: {args.output}')


if __name__ == '__main__':
    main()
