"""Browser checks for the standalone demo. Requires Playwright for Python."""
import asyncio, json
from pathlib import Path
from playwright.async_api import async_playwright
ROOT=Path(__file__).resolve().parents[1]
URL=(ROOT/'wakecar-demo.html').as_uri()
async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch()
  errors=[]
  page=await browser.new_page(viewport={'width':1800,'height':1320})
  page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto(URL);await page.wait_for_timeout(1200)
  await page.click('#petCar');await page.wait_for_timeout(200)
  assert await page.locator('#scene').get_attribute('data-pet')=='true'
  assert await page.evaluate('WakeChars.frame()')==52  # 3D 캐릭터: 첫 쓰다듬기는 놀란 표정(52번 칸)
  await page.locator('#frame').screenshot(path=str(ROOT/'shots/pet.png'))
  reactions=['curious']
  for name in ['smile','hop','love','joy']:
   await page.click('#petCar');reactions.append(await page.locator('#scene').get_attribute('data-reaction'));await page.wait_for_timeout(150)
  assert reactions==['curious','smile','hop','love','joy'],reactions
  await page.click('[data-go="WAKING"]')
  poses=[]
  for level,pose in [(1,'closed'),(2,'drowsy'),(3,'half'),(4,'alert')]:
   await page.click(f'[data-wake-stage="{level}"]');await page.wait_for_timeout(200)
   assert await page.locator('#mEyeWrap').get_attribute('data-eye-pose')==pose
   assert await page.locator('#mEyeLeft').get_attribute('data-eye-pose')==pose
   poses.append(pose)
   await page.locator('#petCar').screenshot(path=str(ROOT/f'shots/eye_{level}.png'))
  btn=page.locator('#s-WAKING [data-hold]');box=await btn.bounding_box()
  await page.mouse.move(box['x']+box['width']/2,box['y']+box['height']/2)
  await page.mouse.down();await page.wait_for_timeout(750)
  metrics=await btn.evaluate('''b=>{const f=b.querySelector('.hold-fill'),t=b.querySelector('.hold-label-on');return {button:b.getBoundingClientRect().width,label:t.getBoundingClientRect().width,fill:new DOMMatrix(getComputedStyle(f).transform).a,clip:t.style.clipPath};}''')
  assert abs(metrics['button']-metrics['label'])<1,metrics
  assert .18<metrics['fill']<.35,metrics
  await page.locator('#frame').screenshot(path=str(ROOT/'shots/hold_quarter.png'))
  await page.mouse.up();await page.wait_for_timeout(700)
  assert await page.locator('#frame').get_attribute('data-phase')=='WAKING'
  await page.mouse.down();await page.wait_for_timeout(3700);await page.mouse.up();await page.wait_for_timeout(200)
  assert await page.locator('#frame').get_attribute('data-phase')=='DONE'
  assert await page.locator('#scene').get_attribute('data-driving')=='false'
  await page.click('[data-go="SETUP"]')
  await page.click('.pagenav [data-open-settings]')
  assert await page.locator('#settings').is_visible()
  await page.locator('#settings').screenshot(path=str(ROOT/'shots/settings.png'))
  for track in ['piano','ambient','rain']:
   await page.select_option('#musicTrack',track)
   await page.click('#previewMusic');assert await page.locator('#previewMusic').get_attribute('aria-pressed')=='true'
   await page.wait_for_timeout(250);await page.click('#previewMusic')
  await page.locator('#audioVolume').fill('23');await page.locator('#audioVolume').dispatch_event('input')
  assert await page.locator('#volumeValue').inner_text()=='23%'
  await page.click('#closeSettings')
  await page.reload();await page.wait_for_timeout(700);await page.click('.pagenav [data-open-settings]')
  assert await page.locator('#audioVolume').input_value()=='23'
  await page.click('#closeSettings')
  # Dedicated fake-clock pages cover day/night boundaries and local midnight arithmetic.
  for hour,sky in [(6,'dawn'),(12,'morning'),(18,'evening'),(23,'night')]:
   ctx=await browser.new_context(viewport={'width':1800,'height':1320})
   await ctx.add_init_script(f'''const RealDate=Date;const fixed=new RealDate(2026,10,15,{hour},30).valueOf();window.Date=class extends RealDate{{constructor(...a){{super(...(a.length?a:[fixed]));}}static now(){{return fixed;}}}};''')
   pg=await ctx.new_page();pg.on('pageerror',lambda e:errors.append(str(e)));await pg.goto(URL);await pg.wait_for_timeout(700)
   assert await pg.locator('#scene').get_attribute('data-sky')==sky
   await pg.locator('#frame').screenshot(path=str(ROOT/f'shots/time_{sky}.png'))
   await ctx.close()
  # Accelerate only the rest countdown; animation and long-press timings remain real.
  ctx=await browser.new_context(viewport={'width':1800,'height':1320})
  await ctx.add_init_script('const nativeInterval=window.setInterval;window.setInterval=(fn,ms,...a)=>nativeInterval(fn,ms===1000?50:ms,...a);')
  pg=await ctx.new_page();pg.on('pageerror',lambda e:errors.append(str(e)));await pg.goto(URL);await pg.wait_for_timeout(600)
  await pg.click('[data-go="REST"]');await pg.wait_for_timeout(1200);await pg.click('#depart')
  assert await pg.locator('#scene').get_attribute('data-driving')=='true'
  await pg.wait_for_timeout(700);x0=(await pg.locator('#petCar').bounding_box())['x'];await pg.wait_for_timeout(2000);x1=(await pg.locator('#petCar').bounding_box())['x']
  assert x1>x0+50,(x0,x1)
  await pg.locator('#frame').screenshot(path=str(ROOT/'shots/departure.png'))
  assert not errors,errors
  print(json.dumps({'eyes':poses,'hold_text_alignment':metrics,'music_tracks':5,'settings_persist':True,'time_scenes':4,'pet':True,'departure':True,'page_errors':errors},ensure_ascii=False))
  await browser.close()
asyncio.run(main())
