"""디자인 산출물을 Compose 앱(wakecar 저장소)으로 내보낸다.
- 캐릭터 스프라이트(28열 2줄 = 55칸)를 칸마다 webp로 잘라 app/src/main/assets/chars/<캐릭터>/<번호>.webp
- 옷장 소품 겹침 층도 같은 방식으로 assets/chars/<캐릭터>/<소품>/<번호>.webp
- 앱 아이콘(네 캐릭터 단체 사진)을 mipmap 밀도별 webp로
사용: python3 export_android.py [저장소 경로, 기본은 design 폴더의 상위]
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

DESIGN = Path(__file__).resolve().parents[1]
OUT3D = DESIGN / 'character-v2' / '3d' / 'out'
repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DESIGN.parent
assets = repo / 'app/src/main/assets/chars'
FRAME, COLS, N = 512, 28, 55   # 칸 512px(앱 최대 표시 크기와 같음)

def slice_sheet(job):
    src, dst = job
    dst.mkdir(parents=True, exist_ok=True)
    sheet = Image.open(src).convert('RGBA')
    for i in range(N):
        x, y = (i % COLS) * FRAME, (i // COLS) * FRAME
        sheet.crop((x, y, x + FRAME, y + FRAME)).save(dst / f'{i:02d}.webp', quality=92, method=4)

if __name__ == '__main__':
    from concurrent.futures import ProcessPoolExecutor
    jobs = []
    for k in ['kongkong', 'poksin', 'ppangppi', 'mongsil']:
        jobs.append((OUT3D / f'{k}_sheet.webp', assets / k))
        jobs += [(OUT3D / f'{k}_acc_{a}.webp', assets / k / a) for a in ['ribbon', 'cap', 'crown']]
    with ProcessPoolExecutor() as ex:   # 시트마다 따로 돌려 빠르게
        list(ex.map(slice_sheet, jobs))

if __name__ == '__main__':
    # 앱 아이콘: 기존 적응형 아이콘이 아닌 정적 webp만 쓰므로 밀도별 크기로 저장(둥근 모양은 원으로 자름)
    icon = Image.open(OUT3D / 'app_icon_square_1024.png').convert('RGBA')
    for d, px in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
        res = repo / f'app/src/main/res/mipmap-{d}'
        sq = icon.resize((px, px), Image.LANCZOS)
        m = Image.new('L', (px * 4, px * 4)); ImageDraw.Draw(m).rounded_rectangle((0, 0, px * 4 - 1, px * 4 - 1), radius=int(px * 4 * .22), fill=255)
        rr = Image.new('RGBA', (px, px)); rr.paste(sq, (0, 0), m.resize((px, px), Image.LANCZOS)); rr.save(res / 'ic_launcher.webp', quality=92)
        c = Image.new('L', (px * 4, px * 4)); ImageDraw.Draw(c).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
        rd = Image.new('RGBA', (px, px)); rd.paste(sq, (0, 0), c.resize((px, px), Image.LANCZOS)); rd.save(res / 'ic_launcher_round.webp', quality=92)
        fg = int(px * 108 / 48)   # 적응형 전경(108dp 중 가운데 72dp 안전 영역)
        canvas = Image.new('RGBA', (fg, fg), (0, 0, 0, 0)); inner = int(fg * 72 / 108)
        canvas.paste(icon.resize((inner, inner), Image.LANCZOS), ((fg - inner) // 2, (fg - inner) // 2)); canvas.save(res / 'ic_launcher_foreground.webp', quality=92)
    print('exported to', repo)
