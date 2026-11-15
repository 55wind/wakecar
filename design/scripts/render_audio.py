"""앱용 소리 굽기: 디자인 시안의 합성기(proto/audio.js)를 OfflineAudioContext로 돌려 반복 음원을 만든다.
- 잠들 때 음악 5종(이음매 없이 반복), 알람 4종 x 깨우기 단계 4개(단계마다 빠르기와 잔향이 다름), 효과음
- 크기 맞춤: 알람은 종류마다 4단계 중 가장 큰 값 기준으로 같이 맞춰 단계 사이 크기 차이를 지킨다(앱에서 단계별 음량을 더 곱한다)
사용: python3 render_audio.py <res/raw 출력 폴더>
"""
import asyncio, base64, subprocess, sys, tempfile, wave
from pathlib import Path
import numpy as np
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(sys.argv[1]).resolve(); OUT.mkdir(parents=True, exist_ok=True)
BPM = {1: 66, 2: 84, 3: 100, 4: 120}

def alarm_len(name, s):
    if name == 'forest': return 14.4 if s == 3 else 16.0
    k = {'sunrise': 1, 'musicbox': 1 / .8 if s == 1 else 1, 'drive': 1.5 if s == 1 else 1}[name]
    cyc = 960 / BPM[s] * k
    return cyc * (1 if cyc >= 14 else 2)

JOBS = [('sleep', n, 0, 50.0 if n == 'waves' else 48.0, 0) for n in ['piano', 'ambient', 'rain', 'waves', 'cabin']]
JOBS += [('alarm', n, s, round(alarm_len(n, s), 4), 0) for n in ['sunrise', 'forest', 'musicbox', 'drive'] for s in [1, 2, 3, 4]]
JOBS += [('cue', 'tap', 0, 1.2, 0), ('cue', 'start', 0, 1.4, 0), ('cue', 'done', 0, 1.6, 0)] + [('cue', 'pet', 0, 1.6, v) for v in range(5)]

PAGE = f"<!doctype html><meta charset=utf-8><script>{(ROOT / 'proto/audio.js').read_text()}</script>"
JS = """async ({kind, name, stage, seconds, variation}) => {
  const r = await WakeAudio.renderLoop({ kind, name, stage, seconds, xfade: kind === 'cue' ? 0 : 2, variation });
  const n = r.left.length, pcm = new Float32Array(n * 2);
  for (let i = 0; i < n; i++) { pcm[i * 2] = r.left[i]; pcm[i * 2 + 1] = r.right[i]; }
  const b = new Uint8Array(pcm.buffer); let s = '';
  for (let i = 0; i < b.length; i += 32768) s += String.fromCharCode(...b.subarray(i, i + 32768));
  return btoa(s);
}"""

def fname(kind, name, stage, var):
    return {'sleep': f'sleep_{name}', 'alarm': f'alarm_{name}_{stage}', 'cue': f'fx_{name}{var if name == "pet" else ""}'}[kind]

async def main():
    data = {}
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page()
        errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.set_content(PAGE)
        for kind, name, stage, sec, var in JOBS:
            raw = await pg.evaluate(JS, {'kind': kind, 'name': name, 'stage': stage, 'seconds': sec, 'variation': var})
            data[(kind, name, stage, var)] = np.frombuffer(base64.b64decode(raw), dtype=np.float32).reshape(-1, 2).copy()
            print('rendered', fname(kind, name, stage, var), f'{sec}s', flush=True)
        assert not errs, errs
        await b.close()
    # 크기 맞춤
    def norm(x, peak=.89): m = np.abs(x).max(); return x * (peak / m) if m > 0 else x
    for name in ['sunrise', 'forest', 'musicbox', 'drive']:
        keys = [k for k in data if k[0] == 'alarm' and k[1] == name]
        m = max(np.abs(data[k]).max() for k in keys)
        for k in keys: data[k] = data[k] * (.89 / m)
    for k in data:
        if k[0] == 'sleep':   # RMS -20 dBFS, 봉우리 -3 dBFS 이하
            x = data[k]; r = np.sqrt((x ** 2).mean()); g = min(.1 / r, .7 / np.abs(x).max()); data[k] = x * g
        elif k[0] == 'cue': data[k] = norm(data[k], .8)
    with tempfile.TemporaryDirectory() as tmp:
        for (kind, name, stage, var), x in data.items():
            w = Path(tmp) / 'a.wav'
            with wave.open(str(w), 'wb') as f:
                f.setnchannels(2); f.setsampwidth(2); f.setframerate(44100)
                f.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes())
            dst = OUT / (fname(kind, name, stage, var) + '.ogg')
            rate = '64k' if kind == 'sleep' else '96k'   # Ogg Opus: 안드로이드 MediaPlayer가 바로 재생하고 반복 이음매가 없다
            subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', str(w), '-ar', '48000', '-c:a', 'libopus', '-b:a', rate, str(dst)], check=True)
    print('wrote', len(data), 'files to', OUT)

asyncio.run(main())
