"""시안 페이지를 안별 PNG와 전체 PNG로 저장한다."""
import asyncio
from pathlib import Path
from playwright.async_api import async_playwright
HERE = Path(__file__).resolve().parent
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page(viewport={'width': 1600, 'height': 1000}, device_scale_factor=2)
        errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto((HERE / 'concepts.html').as_uri()); await pg.wait_for_timeout(1500)
        out = HERE / 'png'; out.mkdir(exist_ok=True)
        for key in ['kongkong', 'poksin', 'ppangppi', 'mongsil']:
            await pg.locator(f'#{key}').screenshot(path=str(out / f'{key}.png'))
        await pg.screenshot(path=str(out / 'all.png'), full_page=True)
        assert not errs, errs; print('ok', out); await b.close()
asyncio.run(main())
