#!/usr/bin/env python3
"""Exercise calendar permissions and real local Calendar Provider data on a test emulator."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
from urllib.parse import urlencode
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', default='emulator-5580')
parser.add_argument('--output', type=Path, default=Path('outputs/calendar-qa'))
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]
calendars = []
calendar_app_disabled = False
passed = []
background_process = None
account = 'monthlane-ui-qa-' + str(time.time_ns())
sync_query = urlencode({'caller_is_syncadapter': 'true', 'account_name': account, 'account_type': 'LOCAL'})

def adb(*command, binary=False):
    return subprocess.check_output(base + list(command), timeout=30, text=not binary, stderr=subprocess.STDOUT)

def tree():
    for _ in range(6):
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/monthlane-calendar-window.xml')
        if 'ERROR:' not in result:
            return ET.fromstring(adb('exec-out', 'cat', '/sdcard/monthlane-calendar-window.xml'))
        time.sleep(.4)
    raise AssertionError('UI hierarchy unavailable')

def find(text, attribute='text', timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = tree()
        node = next((n for n in root.iter('node') if text in n.get(attribute, '')), None)
        if node is not None:
            return node
        time.sleep(.5)
    raise AssertionError('Missing ' + attribute + ': ' + text)

def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds'))))

def tap(node):
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.5)

def capture(name):
    (args.output / (name+'.xml')).write_bytes(ET.tostring(tree()))
    (args.output / (name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('Captured', name, flush=True)

def host(height=580, preset='MINIMAL', theme='LIGHT', reset=True):
    adb('shell', 'am', 'force-stop', 'org.monthlane')
    adb('shell', 'am', 'start', '-n', 'org.monthlane/.qa.WidgetHostActivity', '--es', 'source', 'DEVICE',
        '--es', 'preset', preset, '--es', 'theme', theme, '--es', 'palette', 'SAGE',
        '--ei', 'width', '336', '--ei', 'height', str(height), '--ez', 'reset', str(reset).lower())
    find('November 2026')

def provider(operation, uri, values=None, where=None, projection=None):
    command = ['content', operation, '--uri', uri]
    for key, (kind, value) in (values or {}).items(): command += ['--bind', f'{key}:{kind}:{value}']
    if where: command += ['--where', where]
    if projection: command += ['--projection', projection]
    output = adb('shell', shlex.join(command))
    if any(error in output for error in ('Error', 'Exception', 'SecurityException')):
        raise AssertionError(output)
    return output

def calendar(name, color):
    uri = 'content://com.android.calendar/calendars?' + sync_query
    result = provider('insert', uri, {
        'account_name': ('s', account), 'account_type': ('s', 'LOCAL'), 'name': ('s', name),
        'calendar_displayName': ('s', name), 'ownerAccount': ('s', account),
        'calendar_access_level': ('i', 700), 'calendar_color': ('i', color),
        'visible': ('i', 1), 'sync_events': ('i', 1)})
    rows = provider('query', 'content://com.android.calendar/calendars', where=f"account_name='{account}' AND name='{name}'", projection="_id")
    inserted = int(re.search(r'_id=(\d+)', rows).group(1))
    calendars.append(inserted)
    return inserted

def event(calendar_id, title, start, end=None, all_day=False, recurring=False):
    values = {'calendar_id': ('i', calendar_id), 'title': ('s', title), 'dtstart': ('l', int(start.timestamp()*1000)),
        'eventTimezone': ('s', 'UTC' if all_day else zone.key), 'allDay': ('i', int(all_day)),
        'eventStatus': ('i', 1), 'eventLocation': ('s', 'Test office')}
    if recurring: values.update({'rrule': ('s', 'FREQ=WEEKLY;COUNT=3'), 'duration': ('s', 'PT3600S')})
    else: values['dtend'] = ('l', int(end.timestamp()*1000))
    result = provider('insert', 'content://com.android.calendar/events', values)
    rows = provider('query', 'content://com.android.calendar/events', where=f"calendar_id={calendar_id} AND title='{title}'", projection="_id")
    return int(re.search(r'_id=(\d+)', rows).group(1))

def select_calendar(name):
    label = find(name)
    _, y1, _, y2 = bounds(label)
    center = (y1+y2)//2
    candidates = [n for n in tree().iter('node') if n.get('checkable') == 'true' and n.get('clickable') == 'true']
    assert candidates, 'No calendar checkboxes'
    tap(min(candidates, key=lambda n: abs((bounds(n)[1]+bounds(n)[3])//2-center)))

zone = ZoneInfo(adb('shell', 'getprop', 'persist.sys.timezone').strip() or 'UTC')
local = lambda day, hour, minute=0: datetime(2026, 11, day, hour, minute, tzinfo=zone)
try:
    adb('shell', 'appwidget', 'grantbind', '--package', 'org.monthlane', '--user', '0')
    adb('shell', 'pm', 'revoke', 'org.monthlane', 'android.permission.READ_CALENDAR')
    adb('shell', 'pm', 'clear-permission-flags', 'org.monthlane', 'android.permission.READ_CALENDAR', 'user-set', 'user-fixed')
    host()
    find('Connect your calendar')
    assert not any(n.get('text') == 'Design review' for n in tree().iter('node'))
    capture('permission-required')
    tap(find('Connect your calendar'))
    tap(find('Connect calendar'))
    root = tree()
    deny = next(n for n in root.iter('node') if n.get('resource-id', '').endswith('permission_deny_button'))
    tap(deny)
    find('Connect calendar')
    passed.append('permission denial remains usable and never displays sample events as real')
    tap(find('Connect calendar'))
    allow = next(n for n in tree().iter('node') if n.get('resource-id', '').endswith(('permission_allow_button', 'permission_allow_foreground_only_button')))
    tap(allow)
    find('No calendars found')
    passed.append('runtime calendar permission and empty provider')
    capture('no-calendars')

    work = calendar('Monthlane QA Work', -12097723)
    personal = calendar('Monthlane QA Personal', -11832188)
    conference = event(work, 'Two day conference', datetime(2026,11,12,tzinfo=timezone.utc), datetime(2026,11,14,tzinfo=timezone.utc), all_day=True)
    event(work, 'Night shift', local(11,23,30), local(12,1))
    review = event(work, 'Real calendar review', local(12,9,30), local(12,10,30))
    event(work, 'Weekly planning', local(12,11), recurring=True)
    event(personal, 'Personal lunch', local(12,13), local(12,14))
    adb('shell','am','start','-n','org.monthlane/.settings.SettingsActivity')
    tap(find('Use device visibility defaults'))

    for preset, theme, height, name in [('MINIMAL','LIGHT',340,'real-compact'), ('MINIMAL','LIGHT',480,'real-medium'),
        ('MINIMAL','LIGHT',580,'real-minimal-light'), ('MINIMAL','DARK',580,'real-minimal-dark'),
        ('MATERIAL','LIGHT',580,'real-material-light'), ('MATERIAL','DARK',580,'real-material-dark')]:
        host(height,preset,theme)
        find('Two day conference')
        find('Sample tasks')
        capture(name)
    passed.append('real provider data and all-day events across widget sizes and themes')

    host()
    find('Real calendar review')
    provider('update', f'content://com.android.calendar/events/{review}', {'title': ('s', 'Edited calendar review')})
    find('Edited calendar review')
    passed.append('live provider edits update the widget')
    adb('shell','pm','disable-user','--user','0','com.android.calendar')
    calendar_app_disabled = True
    tap(find('Edited calendar review'))
    find('No calendar app could open this event')
    passed.append('missing calendar app has a usable fallback')
    capture('missing-calendar-app')

    select_calendar('Monthlane QA Work')
    host()
    find('Personal lunch')
    assert not any(n.get('text') == 'Two day conference' for n in tree().iter('node'))
    passed.append('calendar selection is persisted and filters both agenda and indicators')
    capture('personal-calendar-only')
    adb('shell','am','start','-n','org.monthlane/.settings.SettingsActivity')
    select_calendar('Monthlane QA Personal')
    host()
    find('Choose calendars to display')
    passed.append('explicit empty selection never re-enables calendars')
    capture('no-selection')
    adb('shell','am','start','-n','org.monthlane/.settings.SettingsActivity')
    tap(find('Use device visibility defaults'))
    host()
    find('Edited calendar review')
    tap(find('Saturday 14 November 2026','content-desc'))
    find('No events')
    assert not any(n.get('text') == 'Two day conference' for n in tree().iter('node'))
    passed.append('all-day exclusive end is reflected in the actual widget')
    tap(find('Thursday 12 November 2026','content-desc'))
    find('Edited calendar review')
    tap(find('Next month','content-desc'))
    find('December 2026')
    find('No events')
    tap(find('Previous month','content-desc'))
    find('November 2026')
    find('Edited calendar review')
    passed.append('month navigation reloads the corresponding provider range')

    # Leave the app normally, then kill only its background process, preserving scheduled jobs.
    adb('shell','input','keyevent','3')
    time.sleep(2)
    if not any('November 2026' in n.get('text', '') for n in tree().iter('node')):
        adb('shell', 'am', 'start', '-n', 'org.monthlane/.settings.SettingsActivity')
        for _ in range(15):
            button = next((n for n in tree().iter('node') if n.get('text') == 'Add to home screen'), None)
            if button is not None:
                tap(button)
                break
            adb('shell', 'input', 'swipe', '520', '1850', '520', '650', '300')
        else:
            raise AssertionError('Could not reach widget pinning control')
        tap(find('ADD TO HOME SCREEN'))
        adb('shell', 'input', 'keyevent', '3')
    tap(find('Thursday 12 November 2026', 'content-desc'))
    find('Two day conference')
    previous_pids = adb('shell', 'pidof', 'org.monthlane').strip().split()
    assert previous_pids, 'Expected a running app before the background restart check'
    adb('shell','am','kill','org.monthlane')
    time.sleep(1)
    current_pids = subprocess.run(base + ['shell', 'pidof', 'org.monthlane'], capture_output=True, text=True, check=False).stdout.split()
    if set(previous_pids).intersection(current_pids):
        # Glance can keep a short-lived job active; kill only this debuggable app's own process.
        for pid in set(previous_pids).intersection(current_pids):
            adb('shell', 'run-as', 'org.monthlane', 'kill', '-9', pid)
        time.sleep(1)
    current_pids = subprocess.run(base + ['shell', 'pidof', 'org.monthlane'], capture_output=True, text=True, check=False).stdout.split()
    assert not set(previous_pids).intersection(current_pids), 'The original app process is still alive'
    background_process = {'previousPids': previous_pids, 'pidsAfterTermination': current_pids}
    provider('update', f'content://com.android.calendar/events/{conference}', {'title': ('s', 'Background calendar update')})
    find('Background calendar update',timeout=75)
    background_process['pidsAfterRefresh'] = adb('shell', 'pidof', 'org.monthlane').strip().split()
    passed.append('provider changes refresh a launcher widget after background process death')
    capture('background-refresh-launcher')
finally:
    if calendar_app_disabled:
        adb('shell','pm','enable','com.android.calendar')
    for calendar_id in calendars:
        provider('delete', f'content://com.android.calendar/calendars/{calendar_id}?{sync_query}')
    remaining = provider('query', 'content://com.android.calendar/calendars', where=f"account_name='{account}'", projection='_id')
    assert 'No result found' in remaining, remaining
    (args.output/'calendar-ui-results.json').write_text(json.dumps({'passed':passed,'temporaryCalendarsRemoved':calendars,'backgroundProcess':background_process},indent=2)+'\n')
    print('Temporary calendar fixtures removed.',flush=True)
print('All calendar UI checks passed.',flush=True)
