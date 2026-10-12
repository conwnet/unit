#!/usr/bin/env python3
import re
import shlex
import subprocess
import time
from urllib.parse import urlsplit, unquote

from browser import downloads, navigate, open_incognito, page, private_pid, remove_download, wait_activity, wait_page
from device import ADB, PACKAGE, adb, app_shell, bounds, nodes, require_app_access, tap
from server import PICTURE

BASE = 'http://localhost:8765'
IMAGES = BASE + '/images'


def long_press(selector, private=False):
    point = page('(()=>{const e=document.querySelector(' + repr(selector) +
                 ');e.scrollIntoView({block:"center"});const r=e.getBoundingClientRect();'
                 'return {x:r.left+r.width/2,y:r.top+r.height/2,scale:devicePixelRatio}})()', private)
    views = [bounds(n) for n in nodes() if n.get('class') == 'android.webkit.WebView']
    left, top, _, _ = max(views, key=lambda b: (b[2]-b[0])*(b[3]-b[1]))
    x, y = str(round(left + point['x'] * point['scale'])), str(round(top + point['y'] * point['scale']))
    adb('shell', 'input', 'swipe', x, y, x, y, '800')


def save_image(private=False):
    previous = {row['id'] for row in downloads()}
    wait_page('document.images[0].complete && document.images[0].naturalWidth > 0', private)
    long_press('#linked', private)
    tap('Save image')
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if any(n.get('resource-id') == 'android:id/button1' and n.get('enabled') == 'true'
               and n.get('text') == 'Download' for n in nodes()):
            break
        time.sleep(.1)
    else:
        raise AssertionError('The download confirmation button did not appear')
    tap('Download')
    for _ in range(50):
        added = [row for row in downloads() if row['id'] not in previous]
        if added and added[-1]['state'] in ('COMPLETED', 'FAILED'):
            break
        time.sleep(.1)
    assert added and added[-1]['state'] == 'COMPLETED', added
    row = added[-1]
    assert bool(row['private']) == private, row
    if not private:
        assert row['url'] == BASE + '/protected-image' and row['mime'] == 'image/png', row
        assert re.fullmatch(r'protected-image(?: \(\d+\))?\.png', row['name']), row
        uri = urlsplit(row['uri'])
        command = (['run-as', PACKAGE, 'cat', shlex.quote(unquote(uri.path))] if uri.scheme == 'file'
                   else ['content', 'read', '--uri', shlex.quote(row['uri'])])
        data = subprocess.check_output(ADB + ['exec-out'] + command)
        assert data == PICTURE, 'Downloaded bytes differ'
    tap('More'); tap('Downloads')
    remove_download(row)
    tap('Close Downloads')


def assert_private_closed():
    for _ in range(30):
        if not private_pid():
            break
        time.sleep(.2)
    assert not private_pid(), 'Incognito process is still running'
    for path in ('app_webview_incognito', 'cache/WebView_incognito', 'cache/webview_incognito'):
        remaining = app_shell(f'if test -e {path}; then echo present; fi').strip()
        assert not remaining, 'Incognito data remains: ' + path


if __name__ == '__main__':
    require_app_access()
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    assert sdk >= 28, 'UI checks require Android 9 or newer'
    assert not private_pid(), 'Close the incognito session before running this check'
    adb('shell', 'am', 'start', '-n', PACKAGE + '/net.conw.unit.BrowserActivity')
    wait_activity('.BrowserActivity ')
    assert page('location.href') == 'https://unit.invalid/'
    assert not downloads(), 'Use an empty Debug download list'
    tap('tabs')
    assert sum(bool(re.fullmatch(r'Close tab \d+', n.get('content-desc', ''))) for n in nodes()) == 1
    tap('Close Tabs')
    navigate(BASE + '/')
    navigate(BASE + '/second')
    tap('Back'); wait_page('location.pathname === "/"')
    tap('Forward'); wait_page('location.pathname === "/second"')
    previous = page('performance.timeOrigin')
    tap('More'); tap('Reload')
    wait_page('performance.timeOrigin !== ' + str(previous))
    tap('Back'); wait_page('location.pathname === "/"')
    print('PASS: Home, navigation, Back, Forward and Reload', flush=True)

    navigate(IMAGES)
    save_image()
    print('PASS: Image downloads retain filename, original bytes and browser credentials', flush=True)

    original = page('performance.timeOrigin')
    long_press('#linked')
    tap('Open in new tab')
    wait_page('location.href === ' + repr(BASE + '/second'))
    tap('tabs'); tap('Close tab 2'); tap('Close Tabs')
    assert page('location.href') == IMAGES and page('performance.timeOrigin') == original
    page("document.cookie='unit_test_profile=normal;path=/';localStorage.setItem('unit_test_profile','normal')")
    print('PASS: Links activate a new tab; returning preserves the original document', flush=True)

    open_incognito()
    navigate(BASE + '/', True)
    assert page('({cookie:document.cookie,storage:localStorage.getItem("unit_test_profile")})', True) == {'cookie': '', 'storage': None}
    page("document.cookie='unit_test_profile=private;path=/';localStorage.setItem('unit_test_profile','private')", True)
    navigate(IMAGES, True)
    save_image(True)
    tap('tabs'); tap('Close all'); tap('Close')
    assert_private_closed()
    wait_activity('.BrowserActivity ')
    assert page('performance.timeOrigin') == original
    assert 'unit_test_profile=normal' in page('document.cookie')
    assert page('localStorage.getItem("unit_test_profile")') == 'normal'
    page("document.cookie='unit_test_profile=;path=/;max-age=0';document.cookie='image_session=;path=/;max-age=0';localStorage.removeItem('unit_test_profile')")
    print('PASS: Incognito isolation, image downloads, cleanup and normal window restoration', flush=True)

    open_incognito()
    navigate(BASE + '/', True)
    assert page('document.cookie', True) == ''
    tap('More'); tap('Exit')
    assert_private_closed()
    for _ in range(30):
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        if not any('Hist #' in line and PACKAGE + '/' in line for line in activities.splitlines()):
            break
        time.sleep(.2)
    else:
        raise AssertionError('Browser windows remain after Exit')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/net.conw.unit.BrowserActivity')
    wait_page('location.href === ' + repr(IMAGES))
    assert not downloads()
    navigate('https://unit.invalid/')
    print('PASS: Exit cleans the private session and reopening restores normal tabs', flush=True)
