#!/usr/bin/env python3
"""Build and run device checks with an isolated Debug profile and local fixtures."""
import argparse
from contextlib import contextmanager, ExitStack
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'net.conw.unit.debug'
PROFILE_BACKUP = '.unit-test-backup'
PROFILE_DIRS = ('databases', 'files', 'shared_prefs', 'app_webview', 'app_webview_incognito',
                'cache', 'code_cache', 'no_backup')
PROXY_PORTS = (8780, 8880, 8882)


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, check=True, **kwargs)


def select_device(serial):
    if not shutil.which('adb'):
        raise RuntimeError('Add Android SDK platform-tools to PATH')
    if not serial:
        output = run(['adb', 'devices'], capture_output=True, text=True).stdout
        devices = [line.split()[0] for line in output.splitlines()[1:]
                   if len(line.split()) > 1 and line.split()[1] == 'device']
        if len(devices) != 1:
            raise RuntimeError('Connect one device or select it with --device SERIAL')
        serial = devices[0]
    os.environ['UNIT_DEVICE'] = os.environ['ANDROID_SERIAL'] = serial
    from device import ADB, adb
    ADB[:] = ['adb', '-s', serial]
    if adb('get-state').strip() != 'device':
        raise RuntimeError('The selected device is unavailable')
    return adb


def app_shell(adb, script):
    return adb('shell', 'run-as', PACKAGE, 'sh', '-c', shlex.quote(script))


def restore_profile(adb):
    adb('shell', 'am', 'force-stop', PACKAGE)
    directories = ' '.join(PROFILE_DIRS)
    app_shell(adb, f'''set -e
        test -d {PROFILE_BACKUP}
        for directory in {directories}; do
            if test -f {PROFILE_BACKUP}/ready || test -e {PROFILE_BACKUP}/"$directory"; then
                rm -rf "$directory"
            fi
            if test -e {PROFILE_BACKUP}/"$directory"; then
                mv {PROFILE_BACKUP}/"$directory" .
            fi
        done
        rm -rf {PROFILE_BACKUP}
    ''')


@contextmanager
def fixture_profile(adb):
    check_profile(adb)
    from browser import downloads
    adb('shell', 'am', 'force-stop', PACKAGE)
    directories = ' '.join(PROFILE_DIRS)
    started = prepared = False
    try:
        app_shell(adb, f'mkdir {PROFILE_BACKUP}')
        started = True
        app_shell(adb, f'''set -e
            for directory in {directories}; do
                if test -e "$directory"; then mv "$directory" {PROFILE_BACKUP}/; fi
            done
            touch {PROFILE_BACKUP}/ready
        ''')
        prepared = True
        yield
    finally:
        if started:
            try:
                adb('shell', 'am', 'force-stop', PACKAGE)
                if prepared:
                    cleanup_downloads(adb, downloads)
            finally:
                restore_profile(adb)


def check_profile(adb):
    from browser import downloads, private_pid
    if private_pid():
        raise RuntimeError('Close the Debug incognito session before running device checks')
    existing = app_shell(adb, f'if test -e {PROFILE_BACKUP}; then echo unfinished; fi').strip()
    if existing:
        raise RuntimeError('Restore the unfinished test profile with python3 tests/run.py --restore')
    if any(row['state'] in ('QUEUED', 'RUNNING') for row in downloads_if_present(adb, downloads)):
        raise RuntimeError('Finish Debug downloads before running device checks')


def downloads_if_present(adb, read):
    present = app_shell(adb, 'if test -f databases/unit.db; then echo present; fi').strip()
    return read() if present else []


def cleanup_downloads(adb, read):
    rows = downloads_if_present(adb, read)
    if not rows:
        return
    from browser import remove_download, wait_activity
    adb('shell', 'am', 'start', '-n', PACKAGE + '/net.conw.unit.BrowserActivity',
        '--ez', 'show_downloads', 'true')
    wait_activity('.BrowserActivity ')
    for row in rows:
        remove_download(row)


@contextmanager
def fixtures(webpage, proxies):
    with ExitStack() as stack:
        servers = []
        if webpage:
            from http.server import ThreadingHTTPServer
            from server import Handler
            site = ThreadingHTTPServer(('127.0.0.1', 8765), Handler)
            servers.append(site)
            stack.callback(site.server_close)
        if proxies:
            from proxy_server import create_servers
            for server in create_servers():
                servers.append(server)
                stack.callback(server.server_close)
        for server in servers:
            threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .1}, daemon=True).start()
            stack.callback(server.shutdown)
        yield


@contextmanager
def mappings(adb, ports, inspect_webview):
    with ExitStack() as stack:
        stack.callback(adb, 'shell', 'rm', '-f', '/data/local/tmp/unit-ui.xml')
        existing = dict(line.split()[-2:] for line in adb('reverse', '--list').splitlines() if line.strip())
        for port in ports:
            address = 'tcp:' + str(port)
            if address in existing:
                if existing[address] != address:
                    raise RuntimeError('ADB reverse port is already used: ' + address)
                continue
            adb('reverse', '--no-rebind', address, address)
            stack.callback(adb, 'reverse', '--remove', address)
        if inspect_webview:
            for key in ('UNIT_CDP_PORT', 'UNIT_CDP_PRIVATE_PORT'):
                port = adb('forward', 'tcp:0', 'localabstract:unit_test').strip()
                os.environ[key] = port
                stack.callback(adb, 'forward', '--remove', 'tcp:' + port)
        yield


@contextmanager
def permissions(adb, sdk):
    selected = (['android.permission.POST_NOTIFICATIONS'] if sdk >= 33 else
                ['android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE']
                if sdk == 28 else [])
    original = adb('shell', 'dumpsys', 'package', PACKAGE)
    missing = [permission for permission in selected
               if permission + ': granted=true' not in original]
    try:
        for permission in missing:
            adb('shell', 'pm', 'grant', PACKAGE, permission)
        yield
    finally:
        for permission in missing:
            adb('shell', 'pm', 'revoke', PACKAGE, permission)


def instrumentation(adb, suite):
    classes = {
        'core': ['net.conw.unit.CoreTest'],
        'downloads': ['net.conw.unit.DownloadTest'],
        'all': ['net.conw.unit.CoreTest', 'net.conw.unit.DownloadTest'],
    }[suite]
    command = ['adb', '-s', os.environ['UNIT_DEVICE'], 'shell', 'am', 'instrument', '-w', '-r',
               '-e', 'class', ','.join(classes)]
    if suite in ('downloads', 'all'):
        command += ['-e', 'fixtures', 'true']
    command.append(PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
    try:
        output = run(command, capture_output=True, text=True, timeout=360).stdout
    except subprocess.TimeoutExpired as error:
        if error.stdout:
            print(error.stdout.decode(errors='replace') if isinstance(error.stdout, bytes) else error.stdout,
                  flush=True)
        raise
    print(output, flush=True)
    expected = re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)', output)
    summary = re.search(r'OK \(([1-9]\d*) tests?\)', output)
    if (not expected or not summary or expected.group(1) != summary.group(1)
            or re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', output)
            or 'INSTRUMENTATION_CODE: -1' not in output):
        raise RuntimeError('Device tests failed or did not complete all discovered checks')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', default=os.environ.get('UNIT_DEVICE') or os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--suite', choices=('all', 'core', 'downloads', 'ui'), default='all')
    parser.add_argument('--restore', action='store_true', help='Restore a Debug profile left by an interrupted run')
    args = parser.parse_args()
    adb = select_device(args.device)
    if args.restore:
        restore_profile(adb)
        print('Debug profile restored.', flush=True)
        return
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    if sdk < 28:
        raise RuntimeError('Device checks require Android 9 or newer')
    ui = args.suite in ('all', 'ui')
    if ui:
        if not shutil.which('node'):
            raise RuntimeError('UI checks require Node.js 22 or newer')
        run(['node', '-e', 'if (typeof WebSocket !== "function") process.exit(1)'])
    installed = adb('shell', 'pm', 'list', 'packages', '--user', '0', PACKAGE).splitlines()
    from device import require_app_access
    if 'package:' + PACKAGE in installed:
        require_app_access()
        check_profile(adb)
    gradle = [str(ROOT / 'gradlew')]
    if os.name == 'nt':
        gradle = ['cmd', '/c', str(ROOT / 'gradlew.bat')]
    run(gradle + [':app:assembleDebug', ':app:assembleDebugAndroidTest'], timeout=900)
    for path in ('app/build/outputs/apk/debug/app-debug.apk',
                 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        adb('install', '--no-incremental', '-r', '-d', str(ROOT / path))
    require_app_access()
    proxies = args.suite in ('downloads', 'all', 'ui')
    ports = ([8765] if ui else []) + (list(PROXY_PORTS) if proxies else [])
    with fixtures(ui, proxies), mappings(adb, ports, ui), permissions(adb, sdk), fixture_profile(adb):
        if args.suite != 'ui':
            instrumentation(adb, args.suite)
        if ui:
            for name in ('core_flow.py', 'download_lifecycle.py'):
                run([sys.executable, str(ROOT / 'tests' / name)], timeout=600)
    print('All selected checks passed; fixtures and Debug profile restored.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print('Device checks interrupted.', file=sys.stderr)
        sys.exit(130)
