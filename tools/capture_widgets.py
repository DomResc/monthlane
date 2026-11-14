#!/usr/bin/env python3
"""Capture the real Android AppWidget host and check mock interactions on an emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', default='emulator-5580')
parser.add_argument('--skip-captures', action='store_true')
parser.add_argument('--output', type=Path, default=Path('outputs/qa'))
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)

def adb(*command, binary=False):
    return subprocess.check_output([args.adb, '-s', args.serial, *command], timeout=30, text=not binary, stderr=subprocess.STDOUT)

def tree():
    for _ in range(5):
        output = adb('shell', 'uiautomator', 'dump', '/sdcard/monthlane-window.xml')
        if 'ERROR:' not in output:
            return ET.fromstring(adb('exec-out', 'cat', '/sdcard/monthlane-window.xml'))
        time.sleep(.5)
    raise AssertionError('Android UI hierarchy was unavailable')

def wait_for(text, attribute='text', timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = list(tree().iter('node'))
        found = next((n for n in nodes if text in n.get(attribute, '')), None)
        if found is not None:
            return found
        time.sleep(.5)
    raise AssertionError(f'Missing {attribute}: {text}')

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)

def start(preset='MINIMAL', theme='LIGHT', palette='SAGE', height=580, reset=True):
    adb('shell', 'am', 'force-stop', 'org.monthlane')
    adb('shell', 'am', 'start', '-n', 'org.monthlane/.qa.WidgetHostActivity',
        '--es', 'preset', preset, '--es', 'theme', theme, '--es', 'palette', palette,
        '--ei', 'width', '336', '--ei', 'height', str(height), '--ez', 'reset', str(reset).lower())
    wait_for('November 2026')

def capture(name):
    time.sleep(.7)
    (args.output / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    (args.output / f'{name}.xml').write_bytes(ET.tostring(tree(), encoding='utf-8'))

adb('shell', 'appwidget', 'grantbind', '--package', 'org.monthlane', '--user', '0')
results = []
if not args.skip_captures:
    for preset in ('MINIMAL', 'MATERIAL'):
        for theme in ('LIGHT', 'DARK'):
            for height, size in ((340, '4x4'), (480, '4x5'), (580, '4x6')):
                start(preset, theme, height=height)
                wait_for('Design review')
                wait_for('Send the proposal')
                name = f'{preset.lower()}-{theme.lower()}-{size}'
                capture(name)
                results.append(name)
                print('Captured', name, flush=True)
    for palette in ('CLAY', 'IRIS', 'DYNAMIC'):
        start('MATERIAL', 'LIGHT', palette)
        capture(f'material-light-{palette.lower()}')
        results.append(f'material-light-{palette.lower()}')
        print('Captured palette', palette, flush=True)

start()
wait_for('Open Tasks.org', 'content-desc')
wait_for('Add task', 'content-desc')
tap(wait_for('Friday 13 November 2026', 'content-desc'))
wait_for('Friday 13 November')
tap(wait_for('Not completed: Send the proposal', 'content-desc'))
wait_for('Completed: Send the proposal', 'content-desc')
start(reset=False)
wait_for('Friday 13 November')
wait_for('Completed: Send the proposal', 'content-desc')
tap(wait_for('Completed: Send the proposal', 'content-desc'))
wait_for('Not completed: Send the proposal', 'content-desc')
tap(wait_for('Next month', 'content-desc'))
wait_for('December 2026')
tap(wait_for('Previous month', 'content-desc'))
wait_for('November 2026')
tap(wait_for('Today', 'content-desc'))
current_month = adb('shell', "date '+%B %Y'").strip()
wait_for(current_month)
capture('interaction-return-to-today')
results += ['day selection', 'task completion', 'persistence after process restart', 'task reopening', 'month navigation', 'return to today']
(args.output / 'results.json').write_text(json.dumps({'passed': results}, indent=2)+'\n')
print('All captures and interaction checks passed.', flush=True)
