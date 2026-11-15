// 캐릭터 A~D: 3D 인형 렌더 스프라이트(character-v2/3d)로 화면 캐릭터를 바꾼다.
// 스프라이트 = 회전 48장(20도부터 7.5도씩) + 표정 7장. 깸 표정은 회전 0번과 같은 각도다.
window.WakeChars = (() => {
  const SHEETS = {
    kongkong: 'assets/chars/kongkong_sheet.webp', poksin: 'assets/chars/poksin_sheet.webp',
    ppangppi: 'assets/chars/ppangppi_sheet.webp', mongsil: 'assets/chars/mongsil_sheet.webp',
  };
  const CHARS = {
    kongkong: { tag: 'A', name: '콩콩', desc: '말랑한 민트 찹쌀떡이에요. 발이 작은 바퀴라 데굴데굴 굴러다니고, 머리 위 새싹 전구가 깨울 때마다 밝아져요.', fav: '데굴데굴 한 바퀴', motion: 'roll' },
    poksin:   { tag: 'B', name: '폭신', desc: '운전석 머리받침이 살아났어요. 낮잠 내내 머리를 받쳐 주다가 시간이 되면 살살 깨워요.', fav: '폭신폭신 눌리기', motion: 'squish' },
    ppangppi: { tag: 'C', name: '빵삐', desc: '아침을 알리는 동그란 병아리예요. 잠이 깰수록 머리 깃털이 하나씩 서고, 가슴 단추를 누르면 빵 하고 울어요.', fav: '깡충깡충 두 번', motion: 'hop' },
    mongsil:  { tag: 'D', name: '몽실', desc: '좌석 위에 내려앉은 낮잠 구름이에요. 안전띠를 단단히 매고 둥실 떠서 잠을 지켜 줘요.', fav: '둥실둥실 흔들기', motion: 'float' },
  };
  // 스프라이트 격자 28열 2줄 = 55칸: 회전 48장(20도부터 7.5도씩, 깬 표정) + 표정 7장
  const TURN = 48, COLS = 28, ROWS = 2, STEP = 360 / 48, AWAKE = 48;
  const EXPR = { awake: 48, alert: 48, half: 49, drowsy: 49, closed: 50, sleep: 50, happy: 51, surprise: 52, love: 53, wink: 54 };
  const ACC_SHEETS = {
    kongkong: { ribbon: 'assets/chars/kongkong_acc_ribbon.webp', cap: 'assets/chars/kongkong_acc_cap.webp', crown: 'assets/chars/kongkong_acc_crown.webp' },
    poksin: { ribbon: 'assets/chars/poksin_acc_ribbon.webp', cap: 'assets/chars/poksin_acc_cap.webp', crown: 'assets/chars/poksin_acc_crown.webp' },
    ppangppi: { ribbon: 'assets/chars/ppangppi_acc_ribbon.webp', cap: 'assets/chars/ppangppi_acc_cap.webp', crown: 'assets/chars/ppangppi_acc_crown.webp' },
    mongsil: { ribbon: 'assets/chars/mongsil_acc_ribbon.webp', cap: 'assets/chars/mongsil_acc_cap.webp', crown: 'assets/chars/mongsil_acc_crown.webp' },
  };
  const accUrl = (k, a) => ACC_SHEETS[k][a];
  let key = 'kongkong';
  try { const k = localStorage.getItem('wakecar-char'); if (CHARS[k]) key = k; } catch {}
  let pose = 'awake', flashPose = null, flashT = 0, look = null, lastTouch = 0, turnRaf = 0;
  const frameStyle = i => `${((i % COLS) / (COLS - 1) * 100).toFixed(4)}% ${(Math.floor(i / COLS) / (ROWS - 1) * 100).toFixed(0)}%`;
  const sheetUrl = k => `url("${SHEETS[k]}")`;
  const turnFrame = a => ((Math.round(a / STEP) % TURN) + TURN) % TURN;
  const ease = t => t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;

  function paint(el, k, i) { el.style.backgroundImage = sheetUrl(k); el.style.backgroundPosition = frameStyle(i); }
  // 몸과 옷장 소품 층을 같은 칸으로 그린다
  function paintBoth(img, acc, k, i) {
    paint(img, k, i);
    const w = window.WakeGrow?.wearing(k);
    if (acc) { acc.hidden = !w; if (w) { acc.style.backgroundImage = `url("${accUrl(k, w)}")`; acc.style.backgroundPosition = frameStyle(i); } }
  }
  const curFrame = () => look != null ? turnFrame(look) : EXPR[flashPose || pose] ?? AWAKE;
  // 화면 캐릭터는 두 층을 겹쳐 두고, 표정이 바뀔 때 뒤 층에 그린 다음 엇갈려 흐리게 바꾼다(뚝 끊기지 않게)
  let front = 0, shown = -1, shownWear = null;
  const layers = () => [...document.querySelectorAll('.plush-layer')];
  function show(i, fade) {
    const L = layers(); if (!L.length) return;
    const wear = window.WakeGrow?.wearing(key) || null;
    if (i === shown && wear === shownWear && L[front].dataset.char === key) return;
    if (!fade || shown < 0) { paintBoth(L[front].firstElementChild, L[front].lastElementChild, key, i); L[front].dataset.char = key; }
    else {
      const back = 1 - front; paintBoth(L[back].firstElementChild, L[back].lastElementChild, key, i); L[back].dataset.char = key;
      L[back].classList.add('is-front'); L[front].classList.remove('is-front'); front = back;
    }
    shown = i; shownWear = wear;
  }
  function apply(fade = true) {
    const wrap = document.getElementById('petCar');
    if (!wrap) return;
    document.getElementById('scene').dataset.char = key;
    show(curFrame(), fade && look == null);
    wrap.setAttribute('aria-label', `${CHARS[key].name} 쓰다듬기`);
    document.querySelectorAll('[data-char-pick]').forEach(b => b.setAttribute('aria-checked', String(b.dataset.charPick === key)));
  }
  function setPose(p) { pose = p; if (look == null) apply(); }

  // 회전 각도를 시간에 따라 부드럽게 옮긴다(중간 칸을 모두 거친다)
  function turnTo(target, ms, done) {
    cancelAnimationFrame(turnRaf);
    const from = look ?? 0, t0 = performance.now();
    const step = now => { const k = Math.min(1, (now - t0) / ms); look = from + (target - from) * ease(k); apply(false); if (k < 1) turnRaf = requestAnimationFrame(step); else done?.(); };
    turnRaf = requestAnimationFrame(step);
  }
  const turnHome = () => turnTo(Math.round((look ?? 0) / 360) * 360, 520, () => { look = null; shown = -1; apply(false); });

  // 화면 캐릭터 끌어 돌리기(360도). 손을 떼면 정면으로 부드럽게 돌아온다.
  function bindDrag() {
    const wrap = document.getElementById('petCar');
    let down = null;
    wrap.addEventListener('pointerdown', e => { cancelAnimationFrame(turnRaf); down = { x: e.clientX, angle: look ?? 0, moved: false }; });
    addEventListener('pointermove', e => {
      if (!down) return;
      const dx = e.clientX - down.x;
      if (!down.moved && Math.abs(dx) < 10) return;
      if (!down.moved) { down.moved = true; try { wrap.setPointerCapture(e.pointerId); } catch {} }
      look = down.angle + dx * .9;   // 오른쪽으로 끌면 얼굴도 오른쪽으로 돈다
      apply(false);
    });
    const end = () => {
      if (!down) return;
      const moved = down.moved; down = null;
      if (!moved) return;
      wrap.dataset.dragged = 'true'; setTimeout(() => { delete wrap.dataset.dragged; }, 50);
      turnHome();
    };
    addEventListener('pointerup', end); addEventListener('pointercancel', end);
    // 끌기 뒤에 따라오는 클릭(쓰다듬기)은 막는다
    wrap.addEventListener('click', e => { if (wrap.dataset.dragged) { e.stopImmediatePropagation(); e.preventDefault(); } }, true);
  }

  // 좋아하는 모션: 카드 그림(또는 화면 캐릭터)에 붙여 재생
  function playFavorite(el, k, n = 0) {
    const c = CHARS[k]; if (!el || !c) return;
    cancelAnimationFrame(el._spin); clearTimeout(el._end);
    el.dataset.motion = ''; void el.offsetWidth; el.dataset.motion = c.motion;
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (c.motion === 'roll' && !reduce) {       // 회전 프레임으로 한 바퀴
      const t0 = performance.now(), run = now => { const t = Math.min(1, (now - t0) / 1000); paint(el, k, turnFrame(ease(t) * 360)); if (t < 1) el._spin = requestAnimationFrame(run); else paint(el, k, EXPR.happy); }; el._spin = requestAnimationFrame(run);
    } else paint(el, k, EXPR.happy);
    window.WakeAudio?.cue('pet', n + (k === 'ppangppi' ? 4 : 2));
    el._end = setTimeout(() => { el.dataset.motion = ''; paint(el, k, AWAKE); }, 1700);
  }

  function select(k, card) {
    key = k; try { localStorage.setItem('wakecar-char', k); } catch {}
    shown = -1; apply(false);
    playFavorite(card?.querySelector('.char-thumb'), k);
    for (const id of ['plushMotion', 'plushShadow']) { const el = document.getElementById(id); if (!el) continue; el.dataset.motion = ''; void el.offsetWidth; el.dataset.motion = CHARS[k].motion; setTimeout(() => { el.dataset.motion = ''; }, 1700); }
    window.WakeGrow?.render();
  }
  function buildPicker() {
    const box = document.getElementById('charOptions'); if (!box) return;
    box.innerHTML = Object.entries(CHARS).map(([k, c]) => `
      <button type="button" class="char-option" role="radio" aria-checked="false" data-char-pick="${k}">
        <span class="char-thumb" aria-hidden="true"></span>
        <span class="char-text"><b>${c.name}</b><span class="char-desc">${c.desc}</span><span class="char-fav">좋아하는 것: ${c.fav}</span></span>
      </button>`).join('');
    box.querySelectorAll('[data-char-pick]').forEach(b => {
      paint(b.querySelector('.char-thumb'), b.dataset.charPick, AWAKE);
      b.addEventListener('click', () => select(b.dataset.charPick, b));
    });
  }
  // 잠깐 다른 표정(쓰다듬기, 레벨 업)
  function flash(p, ms = 1300) { flashPose = p; clearTimeout(flashT); apply(); flashT = setTimeout(() => { flashPose = null; apply(); }, ms); }
  const frame = () => curFrame();
  function refresh() { shown = -1; apply(false); paintShop(); }
  function paintShop() {
    document.querySelectorAll('.g-item-pic[data-item]').forEach(el => {
      paint(el.querySelector('.g-item-base'), key, AWAKE);
      const a = el.querySelector('.g-item-acc'); a.style.backgroundImage = `url("${accUrl(key, el.dataset.item)}")`; a.style.backgroundPosition = frameStyle(AWAKE);
    });
  }
  function paintPreview(el) { paintBoth(el.querySelector('.g-item-base'), el.querySelector('.g-item-acc'), key, EXPR.happy); }
  // 동작: 몸(plushMotion)과 바닥 그림자에 같은 이름을 붙여 CSS 동작을 재생
  function act(name, ms = 1200) {
    for (const id of ['plushMotion', 'plushShadow']) {
      const el = document.getElementById(id); if (!el) continue;
      el.dataset.motion = ''; void el.offsetWidth; el.dataset.motion = name;
      clearTimeout(el._m); el._m = setTimeout(() => { el.dataset.motion = ''; }, ms);
    }
  }
  // 제자리 한 바퀴(회전 칸을 빠르게 넘긴다)
  function spinOnce(ms = 950) { look = 0; turnTo(360, ms, () => { look = null; shown = -1; apply(false); }); }
  // 고개 돌려 두리번(앞 칸 기준 왼쪽, 오른쪽)
  function glance() {
    const side = Math.random() < .5 ? 30 : -30;
    look = 0; turnTo(side, 520, () => setTimeout(() => turnTo(Math.random() < .5 ? -side : side * .5, 640, () => setTimeout(turnHome, 380)), 420));
  }
  // 쓰다듬기 반응: 누를 때마다 다른 동작
  function react(phase, n) {
    lastTouch = performance.now();
    if (phase === 'SLEEPING') { act('toss', 2400); return; }
    if (phase === 'WAKING') { act('shiver', 1000); return; }
    const moves = [() => act('lean', 900), () => act('squish', 1100), () => act('hop', 1000), () => act('float', 1500), () => { spinOnce(); act('bounce', 800); }];
    moves[n % moves.length]();
  }
  // 스스로 움직이기: 몇 초마다 화면 상태에 맞는 대기 동작을 고른다
  function idle() {
    setTimeout(idle, 2600 + Math.random() * 2600);
    const frame = document.getElementById('frame'), phase = frame?.dataset.phase;
    if (document.hidden || flashPose || look != null || performance.now() - lastTouch < 2500) return;
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const r = Math.random();
    if (phase === 'SLEEPING') { if (r < .35) act('toss', 2400); else if (r < .5) flash('drowsy', 700); return; }   // 뒤척이기, 잠꼬대
    if (phase === 'WAKING') { act('shiver', 1000); if (r < .3) flash('surprise', 500); return; }
    if (r < .3) flash('drowsy', 180);                 // 눈 깜빡
    else if (r < .52) glance();                        // 두리번
    else if (r < .66) act('bounce', 800);              // 통통
    else if (r < .78) act('peek', 1200);               // 꾸벅
    else if (r < .9) { flash('happy', 900); act('lean', 900); }
    else act(CHARS[key].motion, 1600);                 // 좋아하는 모션
  }
  function init() {
    buildPicker(); bindDrag(); apply(); setTimeout(idle, 1800);
    const ew = document.getElementById('mEyeWrap');
    if (ew) new MutationObserver(() => setPose(ew.dataset.eyePose || 'awake')).observe(ew, { attributes: true, attributeFilter: ['data-eye-pose'] });
  }
  return { frame, init, setPose, playFavorite, flash, react, act, refresh, paintShop, paintPreview, current: () => key, CHARS, frameOf: p => EXPR[p] };
})();
