"""The sandbox container's front door: one POST per runPython call.

    POST /run    {"code": str, "timeoutSeconds": int, "files": {"attachments/x.docx": base64, ...}}
              -> {"exitCode": int, "stdout": str, "stderr": str, "timedOut": bool, "files": {...outputs...}}
    GET  /health -> 200

Each run gets a fresh directory on the /work tmpfs, holding only the files the request carried, and
runner.py (the same file Desktop runs) executes the program there as a child process. Nothing is
kept between runs. The container itself has no network route out, no volumes and a read-only
system (docker-compose.yml), so this process is not what keeps the code in - it only moves files.

The bearer token is checked because the container is on a network the app shares; a request
without it is refused, and a container started with no token refuses everything.
This file is identical in apps/app/deploy/sandbox/ and apps/app/deploy-homeserver/sandbox/.
"""
import base64
import hmac
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get('SANDBOX_TOKEN', '')
MAX_BODY = 150 * 1024 * 1024
MAX_FILE = 25 * 1024 * 1024
MAX_TOTAL = 100 * 1024 * 1024
MAX_STREAM = 8000
# Two programs at once at most; a third waits. Memory is the container's limit, shared by all runs.
SLOTS = threading.BoundedSemaphore(2)
RUNNER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runner.py')
# The compose file mounts a tmpfs here; overridable so the server can be tried outside the container.
WORK = os.environ.get('SANDBOX_WORK', '/work')


def tail(text):
    return text if len(text) <= MAX_STREAM else '...(truncated)\n' + text[-MAX_STREAM:]


def run(request):
    code = request.get('code') or ''
    timeout = max(1, min(int(request.get('timeoutSeconds') or 50), 120))
    root = tempfile.mkdtemp(prefix='run-', dir=WORK)
    try:
        for folder in ('attachments', 'outputs', '.run'):
            os.makedirs(os.path.join(root, folder))
        for rel, data in (request.get('files') or {}).items():
            target = os.path.normpath(os.path.join(root, rel))
            if not (target.startswith(os.path.join(root, 'attachments') + os.sep)
                    or target.startswith(os.path.join(root, 'outputs') + os.sep)):
                raise ValueError(f'input outside attachments/ or outputs/: {rel}')
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, 'wb') as f:
                f.write(base64.b64decode(data))
        with open(os.path.join(root, '.run', 'main.py'), 'w', encoding='utf-8') as f:
            f.write(code)

        env = {'PATH': os.environ.get('PATH', ''), 'PYTHONIOENCODING': 'utf-8',
               'PYTHONDONTWRITEBYTECODE': '1', 'HOME': root}
        if 'SYSTEMROOT' in os.environ:  # Windows: Python does not start without it
            env['SYSTEMROOT'] = os.environ['SYSTEMROOT']
        try:
            p = subprocess.run([sys.executable, '-I', RUNNER, root], cwd=root, env=env,
                               capture_output=True, text=True, timeout=timeout)
            exit_code, stdout, stderr, timed_out = p.returncode, p.stdout, p.stderr, False
        except subprocess.TimeoutExpired as e:
            exit_code, timed_out = -1, True
            stdout = e.stdout.decode('utf-8', 'replace') if isinstance(e.stdout, bytes) else (e.stdout or '')
            stderr = e.stderr.decode('utf-8', 'replace') if isinstance(e.stderr, bytes) else (e.stderr or '')

        outputs, total = {}, 0
        out_dir = os.path.join(root, 'outputs')
        for dirpath, _, names in os.walk(out_dir):
            for name in sorted(names):
                path = os.path.join(dirpath, name)
                if os.path.islink(path) or not os.path.isfile(path):
                    continue
                size = os.path.getsize(path)
                if size > MAX_FILE or total + size > MAX_TOTAL:
                    continue
                total += size
                rel = os.path.relpath(path, root).replace(os.sep, '/')
                with open(path, 'rb') as f:
                    outputs[rel] = base64.b64encode(f.read()).decode('ascii')
        return {'exitCode': exit_code, 'stdout': tail(stdout), 'stderr': tail(stderr),
                'timedOut': timed_out, 'files': outputs}
    finally:
        shutil.rmtree(root, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    def _reply(self, status, body):
        data = json.dumps(body).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            self._reply(200, {'ok': True})
        else:
            self._reply(404, {'error': 'not found'})

    def do_POST(self):
        if self.path != '/run':
            return self._reply(404, {'error': 'not found'})
        auth = self.headers.get('Authorization', '')
        if not TOKEN or not hmac.compare_digest(auth, 'Bearer ' + TOKEN):
            return self._reply(401, {'error': 'unauthorized'})
        length = int(self.headers.get('Content-Length') or 0)
        if length <= 0 or length > MAX_BODY:
            return self._reply(413, {'error': 'request too large'})
        try:
            request = json.loads(self.rfile.read(length))
        except ValueError:
            return self._reply(400, {'error': 'not JSON'})
        with SLOTS:
            try:
                self._reply(200, run(request))
            except ValueError as e:
                self._reply(400, {'error': str(e)})

    def log_message(self, fmt, *args):  # one line per request, no bodies
        print('%s %s' % (self.command, self.path), flush=True)


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
