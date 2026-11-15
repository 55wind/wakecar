"""소리 설정 v2 점검: 설정 반영, 단계별 알람 크기 증가, 미리듣기, 오류 여부. Playwright(Python) 필요."""
import asyncio
from pathlib import Path
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[1]
URL = (ROOT / 'wakecar-demo.html').as_uri()
# 출력단에 분석기를 몰래 연결해 실제 소리 크기(RMS)를 잰다
TAP = """
(() => {
  const orig = AudioNode.prototype.connect;
  AudioNode.prototype.connect = function (target, ...rest) {
    if (target instanceof AudioDestinationNode && !window.__an) {
      const an = this.context.createAnalyser(); an.fftSize = 2048; orig.call(this, an); window.__an = an;
    }
    return orig.call(this, target, ...rest);
  };
  window.__rms = () => { const a = window.__an; if (!a) return 0; const d = new Float32Array(a.fftSize); a.getFloatTimeDomainData(d); return Math.sqrt(d.reduce((s, x) => s + x * x, 0) / d.length); };
})();
"""


async def peak(page, ms=1500):
    best = 0
    for _ in range(ms // 50):
        best = max(best, await page.evaluate('__rms()'))
        await page.wait_for_timeout(50)
    return best


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        page = await browser.new_page(viewport={'width': 1800, 'height': 1320})
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        await page.add_init_script(TAP)
        await page.goto(URL); await page.wait_for_timeout(1000)

        # 1. 설정에서 알람과 수면 음악 고르기, 화면 요약 반영
        await page.click('#s-SETUP .alarm-change')
        await page.click('[data-alarm="forest"]')
        await page.select_option('#musicTrack', 'waves')
        assert await page.locator('[data-alarm="forest"]').get_attribute('aria-checked') == 'true'
        assert '파도' in await page.locator('#musicHint').text_content()
        await page.locator('#settings').screenshot(path=str(ROOT / 'shots/settings.png'))
        await page.click('#closeSettings')
        assert await page.locator('#setupAlarm').text_content() == '새벽 숲, 3분 전부터 점점 크게'
        await page.locator('#frame').screenshot(path=str(ROOT / 'shots/SETUP_alarm_row.png'))
        await page.reload(); await page.wait_for_timeout(800)
        assert await page.locator('#setupAlarm').text_content() == '새벽 숲, 3분 전부터 점점 크게', '새로고침 뒤 유지'

        # 2. 알람 종류별, 단계별 실제 소리 크기
        report = {}
        for kind in ['sunrise', 'forest', 'musicbox', 'drive']:
            await page.click('#s-SETUP .alarm-change'); await page.click(f'[data-alarm="{kind}"]'); await page.click('#closeSettings')
            await page.click('[data-go="WAKING"]'); await page.wait_for_timeout(300)
            levels = []
            for s in [1, 2, 3, 4]:
                await page.click(f'[data-wake-stage="{s}"]'); await page.wait_for_timeout(2500)
                levels.append(round(await peak(page, 2500), 4))
            report[kind] = levels
            assert levels[0] > 0.002, (kind, levels)
            assert levels[3] > levels[0] * 2, (kind, levels)
            await page.click('[data-go="SETUP"]'); await page.wait_for_timeout(1200)
        print('alarm stage peak RMS', report)

        # 3. 실제 흐름: 낮잠 시작 -> 수면 음악 -> 1단계(작은 알람) -> 깨우기
        await page.click('#s-SETUP .alarm-change'); await page.click('[data-alarm="sunrise"]'); await page.select_option('#musicTrack', 'piano'); await page.click('#closeSettings')
        await page.click('#startBtn'); await page.wait_for_timeout(4000)
        sleep_level = await peak(page, 2000)
        assert '해 뜨는 피아노로' in await page.locator('#sleepAlarm').text_content()
        assert sleep_level > 0.002, sleep_level
        await page.locator('#frame').screenshot(path=str(ROOT / 'shots/SLEEPING_alarm.png'))
        await page.wait_for_selector('#frame[data-phase="WAKING"]', timeout=40000)
        await page.wait_for_timeout(2500)
        wake_level = await peak(page, 2000)
        print('sleep music', round(sleep_level, 4), 'wake stage 2', round(wake_level, 4))

        # 4. 점점 크게 끄기 -> 1단계 라벨 변화, 알람 미리듣기 단계 표시
        await page.click('[data-go="SETUP"]'); await page.wait_for_timeout(800)
        await page.click('#s-SETUP .alarm-change'); await page.click('#rampToggle')
        assert await page.locator('#rampToggle').get_attribute('aria-checked') == 'false'
        await page.click('#previewAlarm'); await page.wait_for_timeout(500)
        assert '2단계' in await page.locator('#previewAlarm').text_content()
        await page.wait_for_timeout(4200)
        assert '3단계' in await page.locator('#previewAlarm').text_content()
        await page.click('#previewAlarm')
        assert await page.locator('#previewAlarm').text_content() == '알람 미리듣기 (16초)'
        await page.click('#rampToggle'); await page.click('#closeSettings')
        assert await page.locator('#setupAlarm').text_content() == '해 뜨는 피아노, 3분 전부터 점점 크게'

        for size, orient in [('l129', 'port'), ('s17', 'land')]:
            await page.click(f'[data-size="{size}"]'); await page.click(f'[data-orient="{orient}"]'); await page.wait_for_timeout(500)
            await page.locator('#frame').screenshot(path=str(ROOT / f'shots/SETUP_{orient}_{size}.png'))
        assert not errors, errors
        print('audio checks passed')
        await browser.close()

asyncio.run(main())
