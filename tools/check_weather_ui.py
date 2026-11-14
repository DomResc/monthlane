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
parser.add_argument('--output', type=Path, default=Path('outputs/weather-030'))
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]
calendars = []
calendar_app_disabled = False
passed = []
background_process = None
account = 'monthlane-weather-qa-' + str(time.time_ns())
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


assert args.serial == 'emulator-5580', 'Use only the dedicated Monthlane test emulator'
zone = ZoneInfo(adb('shell', 'getprop', 'persist.sys.timezone').strip() or 'UTC')
local = lambda day, hour: datetime(2026, 11, day, hour, tzinfo=zone)
breezy_disabled = False
passed = []
try:
    adb('shell','appwidget','grantbind','--package','org.monthlane','--user','0')
    adb('shell','cmd','locale','set-app-locales','org.monthlane','--user','0')
    adb('shell','pm','revoke','org.monthlane','org.breezyweather.READ_PROVIDER')
    host()
    find('Connect Breezy')
    assert not any(n.get('text') == 'Milan' for n in tree().iter('node'))
    capture('weather-permission-required')
    passed.append('permission denial has no sample weather fallback')
    adb('shell','pm','grant','org.monthlane','org.breezyweather.READ_PROVIDER')
    work=calendar('Monthlane weather QA Work',-12097723)
    personal=calendar('Monthlane weather QA Personal',-11832188)
    team=calendar('Monthlane weather QA Team',-7423718)
    event(work,'All day planning',datetime(2026,11,12,tzinfo=timezone.utc),datetime(2026,11,13,tzinfo=timezone.utc),all_day=True)
    event(personal,'Personal appointment',local(12,9),local(12,10))
    event(team,'Team meeting',local(12,13),local(12,14))
    for preset in ['MINIMAL','MATERIAL']:
        for theme in ['LIGHT','DARK']:
            for height in [340,580]:
                host(height,preset,theme)
                find('Milan')
                find('All day planning')
                find('Today')
                capture(f'weather-{preset.lower()}-{theme.lower()}-{height}')
    for theme in ['LIGHT','DARK']:
        host(480,'MATERIAL',theme)
        find('Milan')
        capture('weather-material-'+theme.lower()+'-480')
    passed.append('live Breezy data and internal event markers across presets themes and sizes')
    tap(find('Next month','content-desc'))
    find('December 2026')
    tap(find('Today','content-desc'))
    today = datetime.fromisoformat(adb('shell','date','+%Y-%m-%d').strip()).date()
    find(today.strftime('%A')+' '+str(today.day)+' '+today.strftime('%B'))
    selected=[n for n in tree().iter('node') if ', selected' in n.get('content-desc','')]
    assert len(selected)==1 and ', today' in selected[0].get('content-desc','')
    capture('today-selected')
    passed.append('Today button restores current month date and a single selection')
    tap(find('Milan'))
    assert any(n.get('package')=='org.breezyweather' for n in tree().iter('node'))
    capture('breezy-opened-from-widget')
    passed.append('weather tap opens the installed Breezy application')
    adb('shell','cmd','locale','set-app-locales','org.monthlane','--user','0','--locales','it-IT')
    for height in [340,580]:
        adb('shell','am','force-stop','org.monthlane')
        adb('shell','am','start','-n','org.monthlane/.qa.WidgetHostActivity','--es','source','DEVICE','--es','preset','MATERIAL','--es','theme','DARK','--es','palette','CLAY','--ei','height',str(height),'--ez','reset','true')
        find('novembre 2026')
        find('Milan')
        find('Oggi')
        capture('weather-italian-'+str(height))
    adb('shell','am','start','-n','org.monthlane/.settings.SettingsActivity')
    adb('shell','input','swipe','520','1850','520','650','300')
    find('Meteo · Breezy')
    find('Milan')
    capture('weather-italian-settings')
    passed.append('Italian weather controls and calendar action labels')
    adb('shell','cmd','locale','set-app-locales','org.monthlane','--user','0')
    adb('shell','pm','disable-user','--user','0','org.breezyweather')
    breezy_disabled=True
    host()
    find('Install Breezy Weather')
    assert not any(n.get('text')=='Milan' for n in tree().iter('node'))
    capture('weather-missing-app')
    passed.append('disabled Breezy is handled without sample readings')
finally:
    if breezy_disabled: adb('shell','pm','enable','org.breezyweather')
    adb('shell','cmd','locale','set-app-locales','org.monthlane','--user','0')
    for calendar_id in calendars: provider('delete',f'content://com.android.calendar/calendars/{calendar_id}?{sync_query}')
    assert 'No result found' in provider('query','content://com.android.calendar/calendars',where=f"account_name='{account}'",projection='_id')
    (args.output/'weather-ui-results.json').write_text(json.dumps({'passed':passed,'temporaryCalendarsRemoved':calendars},indent=2)+'\n')
    print('Weather QA fixtures removed and Breezy restored.',flush=True)
print('Weather UI checks passed.',flush=True)
