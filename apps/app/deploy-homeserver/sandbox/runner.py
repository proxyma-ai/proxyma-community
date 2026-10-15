"""Runs one piece of model-written Python for the runPython tool.

    python runner.py <run-dir>

<run-dir> holds the program at .run/main.py, the conversation's uploads in attachments/ and its
produced files in outputs/. The program runs with <run-dir> as its working directory.

The SAME file runs on Desktop, as a child of the app, and inside the sandbox container on Home
Server and Enterprise Server (sandbox_server.py). The audit hook below is the boundary on Desktop,
the user's own machine. In the container it is defence in depth: that container has no network,
no volumes and a read-only system, so a program that got past the hook would still find nothing
worth reaching.

WHAT THE HOOK ALLOWS
- Reading: the run directory, the Python installation (stdlib and site-packages) and system fonts.
- Writing, renaming, deleting: inside outputs/ and .run/ only - never those folders themselves, which
  a program could otherwise replace with a link to anywhere.
- Nothing else: no sockets, no subprocesses (by subprocess, os, _winapi or _posixsubprocess), no
  links, no native libraries loaded by hand (ctypes), no registry, no walking the heap for the
  objects taken out of reach. An attempt raises PermissionError naming what was refused.

Python audit hooks are not a sandbox against code written to escape them. They keep a model's
mistakes - and instructions injected into a document it read - inside the run directory.
"""
import os
import sys


def _real(p):
    return os.path.normcase(os.path.realpath(os.path.abspath(p)))


RUN = _real(sys.argv[1])
os.chdir(RUN)
WORK = os.path.join(RUN, '.run')
TMP = os.path.join(WORK, 'tmp')
os.makedirs(TMP, exist_ok=True)
os.makedirs(os.path.join(RUN, 'outputs'), exist_ok=True)
# Libraries that cache or scratch-write go to the run's own folder, never the user's profile.
os.environ['TMPDIR'] = os.environ['TEMP'] = os.environ['TMP'] = TMP
os.environ['MPLCONFIGDIR'] = os.path.join(WORK, 'mpl')
os.environ['MPLBACKEND'] = 'Agg'
import tempfile  # noqa: E402 - after TMPDIR is set
tempfile.tempdir = TMP

WRITE_ROOTS = [_real(os.path.join(RUN, 'outputs')), _real(WORK)]
READ_ROOTS = [RUN] + WRITE_ROOTS
for p in {sys.prefix, sys.base_prefix, sys.exec_prefix, os.path.dirname(sys.executable)} | set(sys.path):
    if p and os.path.isdir(p):
        READ_ROOTS.append(_real(p))
for fonts in (os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts'),
              '/usr/share/fonts', '/usr/local/share/fonts', '/etc/fonts'):
    if os.path.isdir(fonts):
        READ_ROOTS.append(_real(fonts))


def _under(path, roots):
    try:
        p = _real(os.fsdecode(path))
    except (TypeError, ValueError):
        return False
    return any(p == r or p.startswith(r + os.sep) for r in roots)


def _inside(path, roots):
    """Strictly inside a root: the root itself may not be removed, renamed or replaced."""
    try:
        p = _real(os.fsdecode(path))
    except (TypeError, ValueError):
        return False
    return any(p.startswith(r + os.sep) for r in roots)


WRITE_FLAGS = os.O_WRONLY | os.O_RDWR | os.O_APPEND | os.O_CREAT | os.O_TRUNC
REFUSED = (
    'socket.connect', 'socket.bind', 'socket.getaddrinfo', 'socket.gethostbyname', 'socket.sendto',
    'socket.sendmsg', 'subprocess.Popen', 'os.system', 'os.exec', 'os.posix_spawn', 'os.spawn',
    'os.fork', 'os.forkpty', 'os.startfile', 'os.kill', 'os.killpg', 'ctypes.dlopen', 'ctypes.dlsym',
    'ctypes.cdata', 'winreg.OpenKey', 'winreg.CreateKey', 'webbrowser.open', 'pty.spawn',
    # Windows' own process, file, pipe, mapping and junction calls, each its own event and none of
    # them one of the above: _winapi.CreateProcess started any program, _winapi.CreateFile opened
    # any file and _winapi.CreateJunction pointed a folder anywhere.
    '_winapi.CreateProcess', '_winapi.CreateFile', '_winapi.CreateJunction', '_winapi.CreateNamedPipe',
    '_winapi.CreatePipe', '_winapi.OpenProcess', '_winapi.TerminateProcess', '_winapi.OpenFileMapping',
    # Links, of either kind: a link inside outputs/ is read back by the app as an output.
    'os.symlink', 'os.link',
    # The heap, from which a module or function taken out of reach could be picked back up.
    'gc.get_objects', 'gc.get_referrers', 'gc.get_referents',
)
# A copy reads one path and writes another - on Windows through CopyFile2, which opens neither
# through Python's open(), so the 'open' rule never sees them.
COPIES = ('shutil.copyfile', 'shutil.copytree')
PATH_WRITES = {
    'os.remove': 1, 'os.unlink': 1, 'os.rmdir': 1, 'os.mkdir': 1, 'os.chmod': 1, 'os.truncate': 1,
    'os.utime': 1, 'shutil.rmtree': 1, 'os.rename': 2,
}


def _hook(event, args):
    if event == 'open':
        path, mode, flags = args
        if isinstance(path, int) or path is None:
            return
        writes = (mode is not None and any(c in str(mode) for c in 'wax+')) or \
                 (mode is None and flags is not None and flags & WRITE_FLAGS)
        if writes:
            if not _under(path, WRITE_ROOTS):
                raise PermissionError(f'Writing outside outputs/ is not allowed: {os.fsdecode(path)}')
        elif not _under(path, READ_ROOTS):
            raise PermissionError(f'Reading outside the workspace is not allowed: {os.fsdecode(path)}')
    elif event in PATH_WRITES:
        for path in args[:PATH_WRITES[event]]:
            if isinstance(path, (str, bytes, os.PathLike)) and not _inside(path, WRITE_ROOTS):
                raise PermissionError(f'{event} outside outputs/ is not allowed: {os.fsdecode(path)}')
    elif event in COPIES:
        src, dst = args[0], args[1]
        if not _under(src, READ_ROOTS):
            raise PermissionError(f'Reading outside the workspace is not allowed: {os.fsdecode(src)}')
        if not _inside(dst, WRITE_ROOTS):
            raise PermissionError(f'Writing outside outputs/ is not allowed: {os.fsdecode(dst)}')
    elif event == 'import' and args and args[0] == '_posixsubprocess':
        raise PermissionError('_posixsubprocess is not allowed in this sandbox')
    elif event.startswith(REFUSED):
        raise PermissionError(f'{event} is not allowed in this sandbox')


# The office stack is imported BEFORE the hook goes in. numpy - and so pandas, openpyxl and
# matplotlib, which import it - loads kernel32 through ctypes at import time on Windows, and a hook
# that refuses ctypes would refuse numpy. Importing them first keeps ctypes refused for the model's
# own code, which is where it matters: ctypes reaches every Windows API, the hook's whole boundary.
for _module in ('numpy', 'pandas', 'openpyxl', 'docx', 'pptx', 'PIL.Image', 'matplotlib',
                'matplotlib.pyplot', 'xlsxwriter'):
    try:
        __import__(_module)
    except Exception:  # noqa: BLE001 - absent or broken, the program's own import will say so
        pass

# _posixsubprocess.fork_exec starts a process and raises NO audit event of its own. It is taken out
# of reach before the hook goes in - out of sys.modules, off the subprocess module that imported it -
# and importing it again is refused by the hook.
sys.modules.pop('_posixsubprocess', None)
if 'subprocess' in sys.modules and hasattr(sys.modules['subprocess'], '_fork_exec'):
    def _no_fork_exec(*_args, **_kwargs):
        raise PermissionError('subprocesses are not allowed in this sandbox')
    sys.modules['subprocess']._fork_exec = _no_fork_exec

sys.addaudithook(_hook)

source = os.path.join(WORK, 'main.py')
with open(source, encoding='utf-8') as f:
    code = compile(f.read(), 'main.py', 'exec')
exec(code, {'__name__': '__main__', '__file__': 'main.py'})
