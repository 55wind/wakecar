"""캐릭터 A~D, 쓰다듬기 표정, 그림자 분리, 끌어 돌리기 방향, 해 이동, 키우기(보상, 레벨 업, 옷장) 점검 + 캡처."""
import asyncio
from pathlib import Path
from playwright.async_api import async_playwright
ROOT = Path(__file__).resolve().parents[1]
URL = (ROOT / 'wakecar-demo.html').as_uri()
SHOTS = ROOT / 'shots'
BG = 'e => WakeChars.frame()'

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page(viewport={'width': 1800, 'height': 1320})
        errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto(URL); await pg.evaluate('localStorage.clear()'); await pg.reload(); await pg.wait_for_timeout(1500)
        # 1. 설정에서 A~D 고르기
        for k in ['kongkong', 'poksin', 'ppangppi', 'mongsil']:
            await pg.click('#s-SETUP .settings-open'); await pg.click(f'[data-char-pick="{k}"]'); await pg.wait_for_timeout(400)
            assert await pg.locator(f'[data-char-pick="{k}"]').get_attribute('aria-checked') == 'true'
            if k == 'poksin': await pg.locator('#settings').screenshot(path=str(SHOTS / 'settings_chars.png'))
            await pg.wait_for_timeout(1400); await pg.click('#closeSettings')
            assert await pg.locator('#scene').get_attribute('data-char') == k
            await pg.wait_for_timeout(300); await pg.locator('#frame').screenshot(path=str(SHOTS / f'SETUP_{k}.png'))
        await pg.click('#s-SETUP .settings-open'); await pg.click('[data-char-pick="kongkong"]'); await pg.wait_for_timeout(1800); await pg.click('#closeSettings')
        # 2. 쓰다듬기: 누를 때마다 표정 칸이 바뀌고, 그림자는 캐릭터와 따로 바닥에 있다
        rest = await pg.locator('#plushImg').evaluate(BG)
        shadow0 = await pg.locator('#plushShadow').bounding_box()
        faces = []
        for i in range(5):
            await pg.click('#petCar'); await pg.wait_for_timeout(250)
            faces.append(await pg.locator('#plushImg').evaluate(BG))
            if i == 2:
                body = await pg.locator('#plush').evaluate('e => getComputedStyle(e).transform')
                sh = await pg.locator('#plushShadow').bounding_box()
                await pg.locator('#frame').screenshot(path=str(SHOTS / 'pet_plush.png'))
            await pg.wait_for_timeout(1300)
        print('rest', rest, 'faces', faces)
        print('faces', rest, faces); assert all(f != rest for f in faces), '쓰다듬을 때마다 표정이 바뀌어야 함'
        assert abs((sh['y'] + sh['height'] / 2) - (shadow0['y'] + shadow0['height'] / 2)) < 3, ('그림자 중심은 바닥에 남아야 함', shadow0, sh)
        assert await pg.locator('#growHud').is_visible()
        hud = await pg.locator('#growHud').inner_text(); print('hud', hud.replace('\n', ' '))
        # 3. 끌어 돌리기: 오른쪽으로 끌면 0번 다음 칸(오른쪽으로 돈 모습), 놓으면 정면으로
        box = await pg.locator('#petCar').bounding_box(); cx, cy = box['x'] + box['width'] / 2, box['y'] + box['height'] / 2
        await pg.mouse.move(cx, cy); await pg.mouse.down(); await pg.mouse.move(cx + 40, cy, steps=6)
        mid = await pg.locator('#plushImg').evaluate(BG)
        await pg.locator('#frame').screenshot(path=str(SHOTS / 'drag_rotate.png'))
        await pg.mouse.up(); await pg.wait_for_timeout(1000)
        print('drag right ->', mid); assert 3 <= mid <= 6, mid    # 7.5도 칸, 36도 근처
        assert await pg.locator('#plushImg').evaluate(BG) == rest
        # 4. 키우기: 낮잠 보상으로 레벨 업, 옷장 구매와 입기
        r = await pg.evaluate("""() => { const out = []; for (let i = 0; i < 3; i++) out.push(WakeGrow.onNap({ endReason: 'COMPLETED', respMs: 9000, realarm: 0 })); return out.map(x => [x.exp, x.coins, x.ups.map(u => u.lv)]); }""")
        print('nap rewards', r); assert await pg.evaluate('WakeGrow.level()') >= 3
        await pg.click('#growHud'); await pg.wait_for_timeout(300)
        await pg.click('[data-claim="nap"]'); await pg.click('[data-item-act="ribbon"]'); await pg.wait_for_timeout(300)
        assert await pg.evaluate('WakeGrow.wearing()') == 'ribbon'
        await pg.locator('#growDialog').screenshot(path=str(SHOTS / 'grow_dialog.png'))
        await pg.click('#closeGrow'); await pg.wait_for_timeout(300)
        assert not await pg.locator('.plush-layer.is-front .plush-acc').is_hidden()
        await pg.locator('#frame').screenshot(path=str(SHOTS / 'SETUP_grown_ribbon.png'))
        # 5. 끝 화면 보상(탭으로 들어온 시연 화면은 보상 없음 안내)
        await pg.click('[data-go="DONE"]'); await pg.wait_for_timeout(1500)
        assert '시연 화면' in await pg.locator('#reward').inner_text()
        await pg.locator('#frame').screenshot(path=str(SHOTS / 'DONE_reward.png'))
        # 6. 해 뜨고 지기: 왼쪽(동)에서 오른쪽(서)으로
        await pg.click('#sunSweep'); xs = []
        for _ in range(4):
            await pg.wait_for_timeout(3000); xs.append(await pg.locator('#sun').evaluate('e => parseFloat(e.style.left)'))
        print('sun x', xs); assert xs == sorted(xs) and xs[-1] - xs[0] > 40
        await pg.locator('#frame').screenshot(path=str(SHOTS / 'sun_sweep.png'))
        await pg.click('#sunSweep')
        assert not errs, errs; print('char checks passed'); await b.close()
asyncio.run(main())
