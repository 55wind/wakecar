# 디자인 원본 (일어나차 화면, 캐릭터, 소리)

앱 화면(`app/`)의 원본 시안과 캐릭터, 소리를 만드는 도구입니다. 앱은 이 폴더 없이도 빌드됩니다. 그림이나 소리를 바꿀 때만 씁니다.

| 경로 | 내용 |
|---|---|
| `wakecar-demo.html` | 브라우저로 바로 여는 시안 한 파일(화면 6종, 설정, 키우기, 소리, 해 이동) |
| `SPEC.md` | 화면, 소리, 키우기 설계와 근거 |
| `CHARACTER.md` | 캐릭터 4종(콩콩, 폭신, 빵삐, 몽실)과 3D 질감, 동작 규칙 |
| `proto/` | 시안 원본(HTML, CSS, JS). `audio.js`가 소리 합성기다 |
| `character-v2/concepts.html`, `png/` | 3D 전 2D 시안(360도 회전 미리보기) |
| `character-v2/viewer.html` | 3D 캐릭터를 360도로 돌려 보는 파일 하나(표정, 옷장 소품 선택) |
| `character-v2/3d/` | three.js 3D 모델(`plush.js`)과 렌더 도구. `out/`에 스프라이트, 옷장 소품 겹침 층, GLB, 앱 아이콘 |
| `scripts/` | 앱으로 내보내기, 소리 굽기, 시안 점검 스크립트 |

## 앱 자산 다시 만들기

캐릭터나 소리를 고친 뒤 아래 순서로 앱 자산을 다시 만듭니다. Python 3, Node, Playwright(Chromium), ffmpeg(libopus)가 필요합니다.

```bash
# 1) 3D 렌더: 스프라이트(회전 48장 + 표정 7장), 소품 겹침 층, 단체 사진, GLB
cd design/character-v2/3d && npm install && python3 render.py && python3 make_icon.py
# 2) 앱으로 내보내기: assets/chars 칸별 webp, mipmap 앱 아이콘
python3 design/scripts/export_android.py .
# 3) 소리 굽기: 수면 음악 5종, 알람 4종 x 깨우기 4단계, 효과음 -> res/raw (Ogg Opus, 이음매 없이 반복)
python3 design/scripts/render_audio.py app/src/main/res/raw
# 4) 시안 파일 다시 묶기
python3 design/scripts/build_demo.py
```

시안 점검: `scripts/check_chars.py`(캐릭터, 표정, 그림자, 끌어 돌리기, 키우기, 해), `check_audio.py`(소리 설정과 단계별 크기), `check_layout.py`(화면 크기별 글자 배치), `check_features.py`.

캐릭터는 모두 이 팀의 원작입니다. 다른 회사 캐릭터의 그림, 이름, 형태를 쓰지 않았습니다.
