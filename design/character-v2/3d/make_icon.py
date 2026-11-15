"""단체 사진을 앱 아이콘(둥근 사각, 하늘 그라데이션)으로 합성한다."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
OUT = Path(__file__).resolve().parent / 'out'
g = Image.open(OUT / 'group.png').convert('RGBA')
bbox = g.getchannel('A').point(lambda a: 255 if a > 40 else 0).getbbox()
crop = g.crop(bbox)
S = 1024
bg = Image.new('RGBA', (S, S))
top, bot = (178, 226, 214), (255, 228, 196)
d = ImageDraw.Draw(bg)
for y in range(S):
    t = y / (S - 1); d.line([(0, y), (S, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)) + (255,))
sun = Image.new('RGBA', (S, S)); ImageDraw.Draw(sun).ellipse((560, 120, 860, 420), fill=(255, 214, 120, 150)); bg.alpha_composite(sun.filter(ImageFilter.GaussianBlur(30)))
hill = Image.new('RGBA', (S, S)); ImageDraw.Draw(hill).ellipse((-300, 760, 1324, 1400), fill=(150, 214, 190, 255)); bg.alpha_composite(hill)
w = int(S * .9); h = int(crop.height * w / crop.width)
if h > S * .78: h = int(S * .78); w = int(crop.width * h / crop.height)
crop = crop.resize((w, h), Image.LANCZOS)
bg.alpha_composite(crop, ((S - w) // 2, S - h - int(S * .07)))
mask = Image.new('L', (S, S)); ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * .22), fill=255)
icon = Image.new('RGBA', (S, S)); icon.paste(bg, (0, 0), mask)
icon.save(OUT / 'app_icon_1024.png')
bg.convert('RGB').save(OUT / 'app_icon_square_1024.png')   # 스토어/런처가 직접 마스크하는 경우
for n in (512, 192, 96): icon.resize((n, n), Image.LANCZOS).save(OUT / f'app_icon_{n}.png')
icon.resize((256, 256), Image.LANCZOS).save(OUT / 'app_icon_256.webp', quality=90)
print('icon ok', crop.size)
