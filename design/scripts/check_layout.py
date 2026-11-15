"""글자 배치 점검: 6개 화면 x 2개 방향 x 3개 화면 크기 + 설정 창.
잡는 것: 틀 밖으로 나간 글자, 잘린 글자(넘침), 서로 겹친 글자 상자, 두 줄로 꺾인 버튼 이름, 한 글자만 남은 줄.
결과는 shots/layout/ 아래 캡처와 표준 출력 목록."""
import asyncio, json
from pathlib import Path
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[1]
URL = (ROOT / 'wakecar-demo.html').as_uri()
OUT = ROOT / 'shots' / 'layout'; OUT.mkdir(parents=True, exist_ok=True)

AUDIT = r"""
(root) => {
  const R = root.getBoundingClientRect(), issues = [];
  const vis = el => { const s = getComputedStyle(el); if (s.visibility === 'hidden' || s.display === 'none' || +s.opacity === 0) return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && !el.closest('[hidden],[aria-hidden="true"] svg'); };
  const leaves = [...root.querySelectorAll('*')].filter(el => [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim()) && vis(el) && !el.closest('.visually-hidden,.roll-old,svg,.plush-layer'));
  const name = el => (el.id ? '#' + el.id : el.className && typeof el.className === 'string' ? '.' + el.className.split(' ')[0] : el.tagName) + ' "' + el.textContent.trim().slice(0, 24) + '"';
  for (const el of leaves) {
    const r = el.getBoundingClientRect();
    const scroller = el.closest('dialog');
    const scrolls = el.closest('.screen') && getComputedStyle(el.closest('.screen')).overflowY === 'auto';
    const sideOut = r.left < R.left - 1 || r.right > R.right + 1, vertOut = r.top < R.top - 1 || r.bottom > R.bottom + 1;
    if (!scroller && (sideOut || (vertOut && !scrolls))) issues.push(['틀 밖', name(el)]);
    if (vertOut && scrolls && el.closest('.frame[data-orient="land"]')) issues.push(['가로 화면 넘침(스크롤 필요)', name(el)]);
    if (el.id === 'startBtn' && vertOut) issues.push(['주 버튼이 화면 밖', name(el)]);
    const s = getComputedStyle(el);
    if ((s.overflow !== 'visible' || s.textOverflow === 'ellipsis') && (el.scrollWidth > el.clientWidth + 2 || el.scrollHeight > el.clientHeight + 2)) issues.push(['잘림', name(el)]);
    // 글줄 나누기: 글자 상자들을 세로로 겹치는 것끼리 한 줄로 묶는다
    const range = document.createRange(); range.selectNodeContents(el);
    const rects = [...range.getClientRects()].filter(x => x.width > 1).sort((a, b) => a.top - b.top);
    const lines = [];
    for (const x of rects) { const l = lines.find(L => Math.min(L.bottom, x.bottom) - Math.max(L.top, x.top) > Math.min(L.bottom - L.top, x.height) * .5); if (l) { l.w += x.width; l.top = Math.min(l.top, x.top); l.bottom = Math.max(l.bottom, x.bottom); } else lines.push({ top: x.top, bottom: x.bottom, w: x.width }); }
    const btn = el.closest('button, .chip, .sim-btn');
    if (btn && lines.length > 1 && !btn.closest('.char-option,.alarm-option,.toggle-row,.g-quest')) issues.push(['버튼 두 줄', name(el)]);
    if (lines.length > 1 && lines[lines.length - 1].w < parseFloat(s.fontSize) * 1.3) issues.push(['한 글자 줄', name(el)]);
  }
  for (let i = 0; i < leaves.length; i++) for (let j = i + 1; j < leaves.length; j++) {
    const a = leaves[i], b = leaves[j]; if (a.contains(b) || b.contains(a) || (a.closest('.hold') && a.closest('.hold') === b.closest('.hold'))) continue;
    const x = a.getBoundingClientRect(), y = b.getBoundingClientRect();
    const ox = Math.min(x.right, y.right) - Math.max(x.left, y.left), oy = Math.min(x.bottom, y.bottom) - Math.max(x.top, y.top);
    if (ox > 4 && oy > 4) issues.push(['겹침', name(a) + ' / ' + name(b)]);
  }
  return issues;
}
"""

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page(viewport={'width': 1800, 'height': 1320})
        await pg.goto(URL); await pg.wait_for_timeout(1500)
        report = {}
        for size in ['s17', 's146', 'l129']:
            for orient in ['land', 'port']:
                await pg.click(f'[data-size="{size}"]'); await pg.click(f'[data-orient="{orient}"]')
                for phase in ['SETUP', 'SLEEPING', 'WAKING', 'GUARD', 'REST', 'DONE']:
                    await pg.click(f'[data-go="{phase}"]'); await pg.wait_for_timeout(700)
                    found = await pg.locator('#frame').evaluate(AUDIT)
                    key = f'{phase}_{orient}_{size}'
                    if found: report[key] = found
                    await pg.locator('#frame').screenshot(path=str(OUT / f'{key}.png'))
        await pg.click('[data-size="s17"]'); await pg.click('[data-orient="land"]'); await pg.click('[data-go="SETUP"]')
        await pg.click('#s-SETUP .settings-open'); await pg.wait_for_timeout(400)
        found = await pg.locator('#settings').evaluate(AUDIT)
        if found: report['SETTINGS'] = found
        await pg.locator('#settings').screenshot(path=str(OUT / 'SETTINGS.png'))
        await pg.set_viewport_size({'width': 760, 'height': 1000}); await pg.wait_for_timeout(300)
        found = await pg.locator('#settings').evaluate(AUDIT)
        if found: report['SETTINGS_narrow'] = found
        await pg.locator('#settings').screenshot(path=str(OUT / 'SETTINGS_narrow.png'))
        await pg.evaluate("document.getElementById('settings').close()")
        await pg.set_viewport_size({'width': 1800, 'height': 1320}); await pg.click('#growHud'); await pg.wait_for_timeout(300)
        found = await pg.locator('#growDialog').evaluate(AUDIT)
        if found: report['GROW'] = found
        await pg.locator('#growDialog').screenshot(path=str(OUT / 'GROW.png'))
        for k, v in report.items():
            print(k); [print('   ', a, b) for a, b in v]
        print('screens with issues:', len(report))
        await b.close()

asyncio.run(main())
