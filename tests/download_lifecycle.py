#!/usr/bin/env python3
import shlex
import subprocess
import time
from urllib.parse import unquote, urlsplit

from device import ADB, PACKAGE, adb, require_app_access, tap
from browser import configure_proxy, downloads, navigate, page, remove_download, toggle_proxy

URL = 'http://localhost:8780/page'


def file_exists(uri):
    source = urlsplit(uri)
    if source.scheme == 'content':
        output = adb('shell', 'content', 'query', '--uri', shlex.quote(uri), '--projection', '_id').strip()
        if output == 'No result found.':
            return False
        if not output.startswith('Row: '):
            raise RuntimeError('Unable to check the download URI: ' + output)
        result = subprocess.run(ADB + ['exec-out', 'content', 'read', '--uri', shlex.quote(uri)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=120)
        if result.returncode or result.stderr.strip():
            raise RuntimeError('Unable to read the download file: ' + result.stderr)
        return True
    path = unquote(source.path)
    script = 'test -e ' + shlex.quote(path)
    result = subprocess.run(ADB + ['shell', 'run-as', PACKAGE, 'sh', '-c', shlex.quote(script)],
                            capture_output=True, text=True, timeout=120)
    if result.returncode not in (0, 1) or result.stderr.strip():
        raise RuntimeError('Unable to check the download file: ' + path + '\n' + result.stderr)
    return result.returncode == 0


if __name__ == '__main__':
    require_app_access()
    assert not downloads(), 'Use the empty disposable fixture download list'
    adb('shell', 'am', 'start', '-n', PACKAGE + '/net.conw.unit.BrowserActivity')
    navigate(URL)
    page("document.querySelector('a[href=\"/slow.bin\"]').click()")
    tap('Download')
    deadline = time.monotonic() + 4
    while time.monotonic() < deadline:
        rows = downloads()
        if rows and rows[-1]['bytes'] > 0:
            break
        time.sleep(.05)
    else:
        raise AssertionError('The UI did not start a streaming download')
    original = rows[-1]
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-n', PACKAGE + '/net.conw.unit.BrowserActivity')
    for _ in range(20):
        failed = downloads()[-1]
        if failed['state'] == 'FAILED':
            break
        time.sleep(.1)
    assert failed['id'] == original['id'] and failed['state'] == 'FAILED' and failed['uri'] is None, failed
    assert 'Interrupted' in failed['error']
    print('PASS: Interrupted download recovery discards the partial file', flush=True)

    configure_proxy()
    navigate(URL)
    assert 'PROXY_OK' in page('document.body.innerText')
    tap('More'); tap('Downloads'); tap(failed['name']); tap('Retry')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        rows = downloads()
        if rows and rows[-1]['state'] in ('COMPLETED', 'FAILED'):
            break
        time.sleep(.2)
    else:
        raise AssertionError('The background retry did not complete')
    row = rows[-1]
    assert row['id'] != original['id'] and row['state'] == 'COMPLETED', row
    assert row['bytes'] == row['total'] == 8 * 1024 * 1024 and row['uri']
    print('PASS: Retry creates a new task and completes in the background through the current proxy', flush=True)

    uri = row['uri']
    assert file_exists(uri), 'The download file is missing: ' + uri

    adb('shell', 'am', 'start', '-n', PACKAGE + '/net.conw.unit.BrowserActivity')
    tap('More'); tap('Downloads')
    remove_download(row)
    for _ in range(40):
        if not downloads() and not file_exists(uri):
            break
        time.sleep(.1)
    assert not downloads(), 'Download records remain'
    assert not file_exists(uri), 'The download file remains: ' + uri
    tap('Close Downloads')
    toggle_proxy(False)
    navigate('https://unit.invalid/')
    print('PASS: Delete removes the download records and file', flush=True)
