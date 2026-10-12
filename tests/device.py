#!/usr/bin/env python3
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'net.conw.unit.debug'
ADB = ['adb'] + (['-s', os.environ['UNIT_DEVICE']] if os.environ.get('UNIT_DEVICE') else [])


def adb(*args):
    return subprocess.check_output(ADB + list(args), text=True,
                                   timeout=300 if args[0] == 'install' else 120)


def app_shell(script):
    return adb('shell', 'run-as', PACKAGE, 'sh', '-c', shlex.quote(script))


def require_app_access():
    try:
        app_shell('pwd')
    except subprocess.CalledProcessError as error:
        raise RuntimeError('The device must support run-as access to the Debug application data directory') from error


def nodes():
    for _ in range(3):
        output = adb('shell', 'uiautomator', 'dump', '/data/local/tmp/unit-ui.xml')
        if 'dumped to' in output:
            elements = list(ET.fromstring(adb('shell', 'cat', '/data/local/tmp/unit-ui.xml')).iter('node'))
            if not any(node.get('package') == PACKAGE for node in elements):
                raise RuntimeError('The Debug application is not in the foreground')
            return elements
        time.sleep(.3)
    raise RuntimeError('UI dump failed; refusing stale coordinates: ' + output)


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))


def matches(label, node):
    return (label.casefold() in ((node.get('text') or '').casefold(),
                                (node.get('content-desc') or '').casefold())
            or node.get('resource-id', '').endswith('/' + label))


def press_node(node):
    left, top, right, bottom = bounds(node)
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))


def tap(label, reveal=True):
    elements = nodes()
    palette = any(n.get('resource-id', '').endswith('/menu_panel') for n in elements)
    matched = [n for n in elements if matches(label, n)]
    sheet = next((n for n in elements if n.get('resource-id', '').endswith('/sheet')), None)
    if sheet is not None:
        left, top, right, bottom = bounds(sheet)
        def inside(node):
            x1, y1, x2, y2 = bounds(node)
            return left <= (x1 + x2) // 2 <= right and top <= (y1 + y2) // 2 <= bottom
        matched = [n for n in matched if inside(n)]
    if palette:
        native_items = [n for n in matched if not n.get('resource-id') and
                        n.get('clickable') == 'true' and n.get('class') in
                        ('android.widget.LinearLayout', 'android.widget.TextView', 'android.widget.FrameLayout')]
        if native_items:
            matched = native_items
    matched.sort(key=lambda n: (n.get('enabled') != 'true', n.get('clickable') != 'true',
                 bool(n.get('resource-id')) if palette else not n.get('resource-id', '').startswith(PACKAGE + ':')))
    matched = [n for n in matched if (lambda b: b[2] > b[0] and b[3] > b[1])(bounds(n))]
    if matched:
        press_node(matched[0])
        print('Tapped', label)
        return
    if reveal and palette:
        pages = [n for n in elements if (n.get('content-desc') or '').startswith('Menu page ')]
        for page in pages:
            press_node(page)
            time.sleep(.2)
            try:
                return tap(label, False)
            except SystemExit:
                pass
    if reveal and label in ['More', 'address', 'tabs', 'Back', 'Forward']:
        bubble = next((n for n in elements if n.get('content-desc') == 'Show toolbar'), None)
        if bubble is not None:
            left, top, right, bottom = bounds(bubble)
            press_node(bubble)
            if right - left < (bottom - top) * .6:
                press_node(bubble)
            return tap(label, False)
    raise SystemExit('Not found: ' + label)


def tap_popup_item(index, count):
    assert 0 <= index < count, (index, count)
    nodes()  # Wait for the keyboard and popup layout to settle.
    windows = re.split(r'(?=  Window #)', adb('shell', 'dumpsys', 'window', 'windows'))
    popups = [window for window in windows
              if 'PopupWindow:' in window.split('\n', 1)[0]
              and 'package=' + PACKAGE + ' ' in window
              and 'mViewVisibility=0x0' in window
              and 'mHasSurface=true' in window
              and 'isOnScreen=true' in window]
    assert len(popups) == 1, 'Expected one visible application popup'
    frame = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', popups[0])
    assert frame, 'Popup frame missing'
    left, top, right, bottom = map(int, frame.groups())
    assert right > left and bottom > top, frame.group()
    y = top + (bottom - top) * (2 * index + 1) // (2 * count)
    adb('shell', 'input', 'tap', str((left + right) // 2), str(y))
