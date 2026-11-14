# -*- coding: utf-8 -*-
"""Virtual Console(HTTP)로 차량 값을 넣고 adb로 화면을 읽어 일어나차 흐름을 돌려 본다.

먼저 에뮬레이터를 띄우고 `adb forward tcp:8088 tcp:8088`을 해 둔다. 화면 캡처는 build/screens 에 쌓인다.

usage: python vc_test.py <단계...>     (단계: install start block sleep interrupt wake stage3 mission
                                        realarm finish gear all)
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ADB = os.path.join(os.environ.get('ANDROID_HOME', 'E:/Android/Sdk'), 'platform-tools', 'adb.exe')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = os.environ.get('WAKECAR_APK') or os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
SHOTS = os.path.join(ROOT, 'build', 'screens')
os.makedirs(SHOTS, exist_ok=True)
BASE = 'http://127.0.0.1:8088'
NO_WINDOW = subprocess.CREATE_NO_WINDOW


def adb(*args, binary=False, timeout=60):
    out = subprocess.run([ADB, *args], capture_output=True, timeout=timeout, creationflags=NO_WINDOW).stdout
    return out if binary else out.decode('utf-8', 'replace')


def http(method, path, **params):
    url = BASE + path + ('?' + urllib.parse.urlencode(params) if params else '')
    # 부팅 직후에는 Virtual Console이 준비되지 않아 503을 돌려준다. 잠깐씩 기다리며 다시 시도한다.
    for attempt in range(40):
        req = urllib.request.Request(url, method=method, data=b'' if method != 'GET' else None)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            if e.code != 503 or attempt == 39:
                raise
            time.sleep(1.5)


def state():
    return json.loads(http('GET', '/vehicle/state'))


_beat = True


def heartbeat():
    while _beat:
        try:
            http('POST', '/browser/session', client='wakecar-test')
        except Exception:
            pass
        time.sleep(1)


threading.Thread(target=heartbeat, daemon=True).start()

DISPLAY = re.search(r'Display (\d+)', adb('shell', 'dumpsys', 'SurfaceFlinger', '--display-id')).group(1)


def shot(name):
    data = adb('exec-out', 'screencap', '-p', '-d', DISPLAY, binary=True)
    path = '%s\\%s.png' % (SHOTS, name)
    open(path, 'wb').write(data)
    return path


def texts():
    """화면에 보이는 글자와 가운데 좌표."""
    adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    xml = adb('shell', 'cat', '/sdcard/ui.xml')
    found = []
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        if t:
            found.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return found


def show(label):
    ts = [t for t, _, _ in texts()]
    print('[%s] %s' % (label, ' | '.join(ts)[:420]))
    return ts


def tap(text):
    for t, x, y in texts():
        if text in t:
            adb('shell', 'input', 'tap', str(x), str(y))
            return True
    print('  !! 화면에 없음:', text)
    return False


def car(label=''):
    s = state()
    b, h, seat = s['body'], s['hvac'], s['seat']
    print('  차량%s: 등받이=%s 무드등=%s%% 내기순환=%s 풍량=%s 와이퍼=%s 창문=%s 브레이크=%s 기어=%s' % (
        label, seat['positions']['recline']['driver'], b['ambient_light']['brightness_pct'], h['recirc_on'],
        h['fan_speed'], b['wipers']['front']['mode'], b['windows']['front_left'],
        s['controls']['brake_pct'], s['gear'].get('actual')))


def brake_twice():
    for _ in range(2):
        http('PUT', '/vehicle/controls', speed=0, accelerator=0, brake=60)
        time.sleep(0.7)
        http('PUT', '/vehicle/controls', speed=0, accelerator=0, brake=0)
        time.sleep(0.7)


def wiper_on_off():
    http('PUT', '/vehicle/body', front_wiper='Low')
    time.sleep(1.6)
    http('PUT', '/vehicle/body', front_wiper='Off')
    time.sleep(1.0)


def window_down_up():
    http('PUT', '/vehicle/body', window_fl=60)
    time.sleep(1.5)
    http('PUT', '/vehicle/body', window_fl=0)
    time.sleep(1.5)


def do_mission(ts):
    """화면의 미션 문구를 읽어 그 조작만 한다."""
    joined = ' '.join(ts)
    if '브레이크' in joined:
        print('  미션: 브레이크 두 번'); brake_twice()
    elif '와이퍼' in joined:
        print('  미션: 와이퍼'); wiper_on_off()
    elif '창문' in joined:
        print('  미션: 창문'); window_down_up()
    else:
        print('  !! 미션 문구 없음')


def answer_questions():
    days = ['월요일', '화요일', '수요일', '목요일', '금요일', '토요일', '일요일']
    for _ in range(2):
        ts = [t for t, _, _ in texts()]
        dow = int(adb('shell', 'date', '+%u').strip())
        hour = int(adb('shell', 'date', '+%H').strip())
        if any('요일이에요' in t for t in ts):
            want = days[dow - 1]
        else:
            want = '%s %d시쯤' % ('오전' if hour < 12 else '오후', 12 if hour % 12 == 0 else hour % 12)
        print('  질문 답:', want)
        tap(want)
        time.sleep(1.0)


def step_install():
    print(adb('install', '-r', APK).strip().splitlines()[-1])


def step_start():
    adb('shell', 'am', 'force-stop', 'kr.contest.wakecar')
    http('PUT', '/vehicle/gear', gear='Park')
    http('PUT', '/vehicle/body', door_fl=0, window_fl=0, front_wiper='Off')
    http('PUT', '/vehicle/controls', speed=0, accelerator=0, brake=0)
    try:
        # 에뮬레이터는 등받이가 끝까지 젖혀진 값(0)에서 시작한다. 세워 둔 상태로 맞춘다.
        print('  seat preset:', http('PUT', '/vehicle/seat', kind='position', control='recline', area='driver', value=80)[:80])
    except Exception as e:
        print('  seat preset failed:', e)
    adb('logcat', '-c')
    adb('shell', 'am', 'start', '--user', 'current', '-n', 'kr.contest.wakecar/.MainActivity')
    time.sleep(5)
    show('시작 화면'); shot('01_setup')


def step_block():
    http('PUT', '/vehicle/gear', gear='Drive'); time.sleep(2.5)
    show('기어 D'); shot('02_block_drive')
    http('PUT', '/vehicle/gear', gear='Park'); time.sleep(2.5)
    http('PUT', '/vehicle/body', door_fl=1); time.sleep(2.5)
    show('문 열림')
    http('PUT', '/vehicle/body', door_fl=0); time.sleep(2.5)
    show('다시 P, 문 닫힘')


def step_sleep():
    car(' (낮잠 전)')
    tap('시연 모드'); time.sleep(1.0)
    show('시연 모드 켬'); shot('03_setup_demo')
    tap('낮잠 시작'); time.sleep(3.5)
    show('자는 중'); shot('04_sleeping'); car(' (자는 중)')


def step_interrupt():
    # 자는 중 문이 열리면 바로 끝난다. 확인한 뒤 다시 잠든다.
    http('PUT', '/vehicle/body', door_fl=1); time.sleep(2.0)
    show('자는 중 문 열림'); shot('05_interruption')
    http('PUT', '/vehicle/body', door_fl=0); time.sleep(1.0)
    tap('처음으로'); time.sleep(1.0)
    tap('낮잠 시작'); time.sleep(3.0)
    show('다시 자는 중')


def wait_for(word, limit=60):
    t0 = time.time()
    while time.time() - t0 < limit:
        ts = [t for t, _, _ in texts()]
        if any(word in t for t in ts):
            return ts
        time.sleep(1.0)
    print('  !! 기다려도 안 나옴:', word)
    return []


def step_wake():
    ts = wait_for('꺼지지 않아요', 60)
    print('[깨우기] ' + ' | '.join(ts)[:420]); shot('06_waking'); car(' (2단계)')
    tap('일어날 시간'); tap('꺼지지 않아요'); time.sleep(1.0)
    still = any('꺼지지 않아요' in t for t, _, _ in texts())
    print('  화면을 눌러도 알람 화면 유지:', still)


def step_stage3():
    ts = wait_for('깨우기 3단계', 20)
    print('[3단계] ' + ' | '.join(ts)[:200]); car(' (3단계)')
    ts = wait_for('깨우기 4단계', 25)
    print('[4단계] ' + ' | '.join(ts)[:200]); shot('07_stage4'); car(' (4단계)')


def step_mission():
    ts = [t for t, _, _ in texts()]
    do_mission(ts)
    time.sleep(1.5)
    show('미션 뒤'); shot('08_guard')


def step_realarm():
    ts = wait_for('다시 잠드신', 40)
    print('[다시 깨움] ' + ' | '.join(ts)[:300]); shot('09_realarm'); car(' (다시 깨움)')
    do_mission(ts); time.sleep(1.5)
    show('두 번째 미션 뒤')


def step_finish():
    answer_questions(); time.sleep(1.5)
    show('끝'); shot('10_done'); car(' (끝난 뒤)')
    tap('처음으로'); time.sleep(1.0)


def step_gear():
    tap('낮잠 시작'); time.sleep(3.0)
    show('다시 자는 중')
    http('PUT', '/vehicle/gear', gear='Drive'); time.sleep(2.5)
    show('기어 D로 바꿈'); shot('11_gear_end'); car(' (P 이탈 뒤)')
    http('PUT', '/vehicle/gear', gear='Park'); time.sleep(1.5)
    tap('처음으로')


STEPS = dict(install=step_install, start=step_start, block=step_block, sleep=step_sleep,
             interrupt=step_interrupt, wake=step_wake, stage3=step_stage3, mission=step_mission,
             realarm=step_realarm, finish=step_finish, gear=step_gear)
names = sys.argv[1:] or ['all']
if names == ['all']:
    names = list(STEPS)
for n in names:
    print('=== ' + n)
    STEPS[n]()
print('--- logcat')
for line in adb('logcat', '-d', '-s', 'WakeCar:*').splitlines()[-40:]:
    if 'fuel:' not in line:
        print(line[31:230])
_beat = False
