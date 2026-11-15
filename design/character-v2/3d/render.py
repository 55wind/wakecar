"""3D 인형 캐릭터 렌더: 앱용 스프라이트(회전 24장 + 표정 4장), 정지 화상, 단체 사진, GLB."""
import asyncio, base64, subprocess, sys, time
from pathlib import Path
from playwright.async_api import async_playwright
HERE = Path(__file__).resolve().parent
OUT = HERE / 'out'; OUT.mkdir(exist_ok=True)
KEYS = ['kongkong', 'poksin', 'ppangppi', 'mongsil']
only = sys.argv[1:] or ['sheet', 'still', 'group', 'glb']
def save(name, data_url):
    (OUT / name).write_bytes(base64.b64decode(data_url.split(',', 1)[1]))
async def main():
    srv = subprocess.Popen([sys.executable, '-m', 'http.server', '8765', '--bind', '127.0.0.1'], cwd=HERE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(.8)
    try:
        async with async_playwright() as p:
            b = await p.chromium.launch(args=['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'])
            pg = await b.new_page(viewport={'width': 1000, 'height': 1000})
            errs = []; pg.on('pageerror', lambda e: errs.append(str(e))); pg.on('console', lambda m: m.type == 'error' and errs.append(m.text))
            await pg.goto('http://127.0.0.1:8765/render.html'); await pg.wait_for_function('window.ready === true', timeout=120000)
            for k in KEYS:
                if 'still' in only:
                    for e in ['awake', 'drowsy', 'sleep', 'happy', 'surprise', 'love', 'wink']: save(f'{k}_{e}.png', await pg.evaluate(f'still("{k}", "{e}", 20)'))
                    for y in [90, 200]: save(f'{k}_yaw{y}.png', await pg.evaluate(f'still("{k}", "awake", {y})'))
                if 'sheet' in only:
                    save(f'{k}_sheet.webp', await pg.evaluate(f'sheet("{k}")'))
                    for a in ['ribbon', 'cap', 'crown']: save(f'{k}_acc_{a}.webp', await pg.evaluate(f'sheet("{k}", "{a}")'))
                if 'glb' in only: (OUT / f'{k}.glb').write_bytes(base64.b64decode(await pg.evaluate(f'glb("{k}")')))
                print('done', k, flush=True)
            if 'group' in only: save('group.png', await pg.evaluate('group()'))
            assert not errs, errs[:5]
            await b.close()
    finally:
        srv.terminate()
asyncio.run(main())
