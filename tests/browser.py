import json
import atexit
import os
import shlex
import sqlite3
import subprocess
import tempfile
import time
from pathlib import Path

from device import ADB, PACKAGE, adb, nodes, press_node, tap, tap_popup_item

FORWARDS = {}
OWNED_FORWARDS = set()


def close_forwards():
    for port in OWNED_FORWARDS:
        subprocess.run(ADB + ['forward', '--remove', 'tcp:' + port],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


atexit.register(close_forwards)


def field(name):
    return next(n for n in nodes() if n.get('resource-id', '').endswith('/' + name))


def edit(name, value):
    if name.startswith('proxy_'):
        keyboard_back()
    node = field(name)
    press_node(node)
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    if node.get('text'):
        adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * len(node.get('text'))))
    if value:
        adb('shell', 'input', 'text', shlex.quote(value.replace(' ', '%s')))


def keyboard_back():
    if 'mInputShown=true' in adb('shell', 'dumpsys', 'input_method'):
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def page(expression, private=False, url=None):
    name = PACKAGE + (':incognito' if private else '')
    pid = ''
    deadline = time.monotonic() + 8
    while not pid and time.monotonic() < deadline:
        pid = subprocess.run(ADB + ['shell', 'pidof', name], capture_output=True, text=True).stdout.strip()
        if not pid:
            time.sleep(.2)
    assert pid, 'WebView process missing'
    previous = FORWARDS.get(private)
    if previous is None or previous[1] != pid:
        port = (previous[0] if previous else
                os.environ.get('UNIT_CDP_PRIVATE_PORT' if private else 'UNIT_CDP_PORT', '0'))
        assigned = adb('forward', 'tcp:' + port, 'localabstract:webview_devtools_remote_' + pid).strip()
        if port == '0':
            port = assigned
            OWNED_FORWARDS.add(port)
        FORWARDS[private] = (port, pid)
    port = FORWARDS[private][0]
    result = subprocess.check_output(['node', str(Path(__file__).with_name('cdp.mjs')), port, expression]
                                     + ([url] if url else []), text=True)
    value = json.loads(result)
    assert 'exceptionDetails' not in value, value
    return value['result'].get('value')


def wait_page(expression, private=False, url=None):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            if page(expression, private, url):
                return
        except subprocess.CalledProcessError:
            pass
        time.sleep(.2)
    raise AssertionError('Page state did not settle: ' + expression)


def navigate(address, private=False):
    previous = page('performance.timeOrigin', private)
    tap('address')
    edit('address_input', address)
    tap('Go')
    wait_page('location.href === ' + json.dumps(address) +
              ' && performance.timeOrigin !== ' + str(previous), private, address)


def private_pid():
    return subprocess.run(ADB + ['shell', 'pidof', PACKAGE + ':incognito'],
                          capture_output=True, text=True).stdout.strip()


def wait_activity(component):
    for _ in range(30):
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        if any(('topResumedActivity=' in line or 'mResumedActivity:' in line) and PACKAGE + '/' in line and
               component in line for line in activities.splitlines()):
            return
        time.sleep(.2)
    raise AssertionError('Browser activity did not become active: ' + component)


def open_incognito():
    assert not private_pid(), 'Close the incognito session before running this check'
    tap('tabs')
    group = next(n for n in nodes() if n.get('text') == 'Incognito' and
                 n.get('class') == 'android.widget.TextView' and n.get('clickable') == 'true')
    press_node(group)
    wait_activity('.BrowserActivity ')
    assert not private_pid(), 'Group preview started an incognito process'
    tap('New tab')
    wait_activity('.IncognitoActivity ')


def configure_proxy():
    tap('More'); tap('Proxy')
    tap('proxy_type'); tap_popup_item(0, 3)
    tap('Authentication')
    edit('proxy_host', '127.0.0.1')
    edit('proxy_port', '8880')
    edit('proxy_username', 'unit')
    edit('proxy_password', 'proxy-pass')
    keyboard_back()
    if field('proxy_enabled').get('checked') != 'true':
        tap('proxy_enabled')
    tap('Apply')


def toggle_proxy(enabled):
    tap('More'); tap('Proxy')
    switch = field('proxy_enabled')
    if (switch.get('checked') == 'true') != enabled:
        press_node(switch)
    tap('Apply')


def downloads():
    with tempfile.TemporaryDirectory(prefix='unit-download-db-') as directory:
        for suffix in ('', '-wal', '-shm'):
            result = subprocess.run(ADB + ['exec-out', 'run-as', PACKAGE, 'cat',
                'databases/unit.db' + suffix], capture_output=True)
            if result.returncode == 0 and (suffix or result.stdout.startswith(b'SQLite format 3\0')):
                Path(directory, 'unit.db' + suffix).write_bytes(result.stdout)
        assert Path(directory, 'unit.db').is_file(), 'Start the disposable app before inspecting downloads'
        with sqlite3.connect(Path(directory, 'unit.db')) as db:
            db.row_factory = sqlite3.Row
            return [dict(row) for row in db.execute('SELECT * FROM downloads ORDER BY id')]


def remove_download(item):
    row = next((row for row in downloads() if row['id'] == item['id']), None)
    if row is None:
        return
    tap(row['name'])
    if row['state'] in ('QUEUED', 'RUNNING'):
        tap('Cancel')
    else:
        tap('Delete'); tap('Also delete file'); tap('Remove')
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if all(current['id'] != row['id'] for current in downloads()):
            return
        time.sleep(.1)
    raise AssertionError('Download record was not removed: ' + row['name'])
