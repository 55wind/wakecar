// 일어나차 화면 가안: 상태 흐름은 앱의 NapSession(시연 모드 NapTimings.DEMO)을 따라 하고,
// 각 행동에는 DesignBookmark Motion의 스프링 값을 그대로 쓴다. 스프링 값은 "강성 감쇠".
(() => {
  const { S, to, set, after, cancel, clamp, render, reduce } = Spring;
  const $ = id => document.getElementById(id);
  const frame = $('frame'), stage = $('stage');

  // NapTimings.DEMO (ms)
  const T = { perMin: 2000, preWake: 6000, stage3: 8000, stage4: 20000, guard: 20000, snooze: 10000, rest: 20000 };
  const HOLD_MS = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--hold-ms')) || 3000;
  const MISSIONS = {
    BRAKE: { prompt: '브레이크를 두\u00A0번 밟아 주세요.', steps: ['한 번', '두 번'], need: ['brake', 'brake'] },
    WIPER: { prompt: '와이퍼를 한\u00A0번 켜 주세요.', steps: ['켜기'], need: ['wiper'] },
    WINDOW: { prompt: '운전석 창문을 내렸다가 다시 올려 주세요.', steps: ['내리기', '올리기'], need: ['winDown', 'winUp'] },
  };
  const ICONS = {
    BRAKE: '<svg viewBox="0 0 84 84"><rect x="24" y="12" width="36" height="44" rx="10"/><path d="M33 24h18M33 34h18M33 44h18M42 56v18M30 74h24"/></svg>',
    WIPER: '<svg viewBox="0 0 84 84"><path d="M8 62Q42 6 76 62Z"/><path d="M42 62 22 26"/><circle cx="42" cy="62" r="4"/><path d="M58 40l6-6M64 50l7-3"/></svg>',
    WINDOW: '<svg viewBox="0 0 84 84"><path d="M12 66V34L30 16h42v50Z"/><path d="M46 28v26m-8-8 8 8 8-8"/></svg>',
  };
  const DAYS = ['월요일', '화요일', '수요일', '목요일', '금요일', '토요일', '일요일'];
  const nowMinute = () => Math.floor(Date.now() / 60000);

  const st = {
    phase: 'SETUP', minutes: 15, byTime: false, returnBy: Math.ceil((nowMinute() + 33) / 5) * 5, demo: true, seatRaise: false,
    car: { gear: 'P', door: false },
    nap: null,
    stats: { woken: 0, realarmNaps: 0, restDone: 0, restTotal: 0 },
  };
  let timers = [];
  const later = (ms, fn) => { const id = setTimeout(fn, ms); timers.push(id); return id; };
  const every = (ms, fn) => { const id = setInterval(fn, ms); timers.push(id); return id; };
  const clearTimers = () => { timers.forEach(id => { clearTimeout(id); clearInterval(id); }); timers = []; };
  const clock = m => new Date(m * 60000).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });

  // ------------------------------------------------------------ frame fit
  function fit() {
    const r = stage.getBoundingClientRect();
    const fw = frame.offsetWidth, fh = frame.offsetHeight;
    const s = Math.min((r.width - 48) / fw, (r.height - 48) / fh, 1);
    frame.style.transform = `scale(${s.toFixed(4)})`;
  }
  addEventListener('resize', fit);

  // ------------------------------------------------------------ rolling text (Word roll + Number stepper: 260/24, stagger 28ms)
  const rolls = new Map();
  function roll(el, text, dir = 1) {
    let r = rolls.get(el);
    const chars = [...text];
    if (!r || r.cells.length !== chars.length) {
      el.textContent = '';
      const sr = document.createElement('span'); sr.className = 'visually-hidden'; el.append(sr);
      r = { sr, cells: chars.map(ch => {
        const c = document.createElement('span'); c.className = 'roll-cell'; c.setAttribute('aria-hidden', 'true');
        const n = document.createElement('span'); n.textContent = ch; c.append(n);
        el.append(c); return { c, n, o: null, ch, s: S(0, 260, 24), dir: 1 };
      }) };
      rolls.set(el, r); r.sr.textContent = text; return;
    }
    r.sr.textContent = text;
    chars.forEach((ch, i) => {
      const cell = r.cells[i];
      if (cell.ch === ch) return;
      if (!cell.o) { cell.o = document.createElement('span'); cell.o.className = 'roll-old'; cell.c.prepend(cell.o); }
      cell.o.textContent = cell.ch; cell.n.textContent = ch; cell.ch = ch; cell.dir = dir;
      set(cell.s, 1);
      after(i * 0.028, () => to(cell.s, 0));
    });
  }
  render(() => rolls.forEach(r => r.cells.forEach(cell => {
    if (!cell.o) return;
    const x = cell.s.x, d = cell.dir;
    cell.n.style.transform = `translateY(${(d * x * 100).toFixed(2)}%)`;
    cell.n.style.opacity = clamp(1 - Math.abs(x), 0, 1).toFixed(3);
    cell.o.style.transform = `translateY(${((x - 1) * d * 100).toFixed(2)}%)`;
    cell.o.style.opacity = clamp(x, 0, 1).toFixed(3);
  })));

  // ------------------------------------------------------------ the scene (little car that naps with you)
  const sc = {
    el: $('scene'), sunEl: $('sun'), plush: $('plush'), shadowEl: $('plushShadow'), body: $('mBody'), shadow: $('mShadow'), eyeWrap: $('mEyeWrap'), eyeLeft: $('mEyeLeft'),
    pupils: [...document.querySelectorAll('.pupil-gaze')],
    pane: $('mPane'), wiper: $('mWiper'), brakeEl: $('mBrake'), brakeGlow: $('mBrakeGlow'), mouth: $('mMouth'), mouthOEl: $('mMouthO'),
    wheels: [[$('mWheelR'), 127, 240], [$('mWheelF'), 276, 240]], sparks: [...document.querySelectorAll('#mSparkles .sp')],
    jump: S(0, 520, 16),        // 통통 (spring-pop)
    wiggle: S(0, 700, 9),       // 알람에 부르르 (hold-button shake)
    look: S(0, 280, 22),        // 눈동자
    sun: S(0, 14, 7.5),         // 해: 1단계 조명처럼 서서히
    paneDown: S(0, 90, 18),     // 창문
    wipe: S(0, 420, 20),        // 와이퍼(도)
    brake: S(0, 600, 40),       // 브레이크등
    spark: S(0, 500, 14),       // 반짝이
    roll: S(0, 60, 14),         // 바퀴
    mouthO: S(0, 420, 32),      // 놀란 입
  };
  function setEye(openness) {
    const pose = openness <= 0.1 ? 'closed' : openness < 0.45 ? 'drowsy' : openness < 0.65 ? 'half' : openness < 0.95 ? 'alert' : 'awake';
    sc.eyeWrap.dataset.eyePose = pose; sc.eyeLeft.dataset.eyePose = pose;
  }
  function scene(p) {
    if ('eye' in p) { setEye(p.eye); to(sc.mouthO, p.eye > 0.2 && p.eye < 0.9 ? 1 : 0); }
    if ('look' in p) to(sc.look, p.look);
    if ('sun' in p && p.sun > 0) sunTo(-0.06 + p.sun * 0.3, 2600);
    if ('pane' in p) to(sc.paneDown, p.pane);
    if (p.jolt) { to(sc.wiggle, 0, p.jolt * 0.6); to(sc.jump, 0, p.jolt * 1.4); }
    if ('sky' in p) sc.el.dataset.sky = p.sky;
  }
  const carFx = {
    brake() { set(sc.brake, 1); after(0.35, () => to(sc.brake, 0)); },
    wiper() { to(sc.wipe, -58); after(0.32, () => to(sc.wipe, 0)); },
    winDown() { to(sc.paneDown, 1); },
    winUp() { to(sc.paneDown, 0); },
  };
  function stretch() {           // 다 깼어요: 통통 두 번, 반짝, 바퀴 살짝
    to(sc.jump, 0, 900); after(0.45, () => to(sc.jump, 0, 700));
    set(sc.spark, 0); after(0.1, () => to(sc.spark, 1)); after(1.6, () => to(sc.spark, 0));
    to(sc.roll, sc.roll.t + 90);
  }
  const mix = (a, b, t) => `color-mix(in srgb, var(${a}) ${(clamp(t, 0, 1) * 100).toFixed(1)}%, var(${b}))`;
  render(() => {
    sc.pupils.forEach(pupil => pupil.setAttribute('transform', `translate(${(sc.look.x * 4).toFixed(2)} ${(-Math.abs(sc.look.x) * 1.5).toFixed(2)})`));
    sc.body.style.transform = `translateY(${(-Math.max(0, sc.jump.x) * 0.06).toFixed(2)}px) rotate(${(sc.wiggle.x * 0.02).toFixed(3)}deg)`;
    const lift = Math.max(0, sc.jump.x) * 0.09;
    sc.plush.style.transform = `translateY(${(-lift).toFixed(2)}px) rotate(${(sc.wiggle.x * 0.03).toFixed(3)}deg)`;
    sc.shadowEl.style.transform = `scale(${clamp(1 - lift * 0.006, .55, 1).toFixed(3)})`; sc.shadowEl.style.opacity = clamp(1 - lift * 0.008, .35, 1).toFixed(3);
    sc.shadow.setAttribute('rx', (140 - Math.max(0, sc.jump.x) * 0.05).toFixed(1));
    sc.pane.setAttribute('transform', `translate(0 ${(sc.paneDown.x * 58).toFixed(2)})`);
    sc.wiper.setAttribute('transform', `rotate(${sc.wipe.x.toFixed(2)} 251 163)`);
    sc.brakeEl.style.fill = mix('--mascot-light-on', '--mascot-light-off', sc.brake.x);
    sc.brakeGlow.style.opacity = clamp(sc.brake.x, 0, 1).toFixed(3);
    sc.mouth.style.opacity = (1 - clamp(sc.mouthO.x, 0, 1)).toFixed(3);
    sc.mouthOEl.style.opacity = clamp(sc.mouthO.x, 0, 1).toFixed(3);
    sc.wheels.forEach(([w, cx, cy]) => w.setAttribute('transform', `rotate(${sc.roll.x.toFixed(2)} ${cx} ${cy})`));
    const sp = clamp(sc.spark.x, 0, 1.3);
    sc.sparks.forEach((s, i) => { s.style.transform = `scale(${(sp * (1 - i * 0.12)).toFixed(3)}) rotate(${(sp * 45).toFixed(1)}deg)`; });
  });
  // ------------------------------------------------------------ 해: 동쪽(왼쪽) 지평선에서 떠서 서쪽(오른쪽)으로 진다
  // t = 0 일출(6:00), t = 1 일몰(18:30). 지평선 아래(t<0, t>1)에서는 언덕 뒤로 숨는다.
  const sunArc = { t: -0.12, from: -0.12, to: -0.12, t0: 0, dur: 1, raf: 0 };
  let sweeping = false;
  const sunOfHour = h => h < 6 ? -0.14 : h > 18.5 ? 1.14 : (h - 6) / 12.5;
  const skyOfSun = t => t < 0 || t > 1.02 ? 'night' : t < 0.14 ? 'dawn' : t < 0.84 ? 'morning' : 'evening';
  function placeSun(t) {
    const up = Math.sin(Math.PI * clamp(t, 0, 1)), below = t < 0 ? -t : t > 1 ? t - 1 : 0;
    sc.sunEl.style.left = `${(8 + 84 * clamp(t, -0.1, 1.1)).toFixed(2)}%`;
    sc.sunEl.style.top = `${(76 - 60 * up + below * 120).toFixed(2)}%`;
  }
  function sunTo(t, ms) {
    cancelAnimationFrame(sunArc.raf); sunArc.after = null;
    if (!sweeping && t < sunArc.t && sunArc.t > 1) sunArc.t = -0.14;   // 서쪽 지평선 아래에서 동쪽 아래로 옮겨 다시 뜨게 한다
    if (!sweeping && t < sunArc.t - 0.01 && sunArc.t > 0) {             // 해는 거꾸로 가지 않는다: 서쪽으로 지고 동쪽에서 다시 뜬다
      const target = t; sunTo(1.14, 1400); sunArc.after = () => { sunArc.t = -0.14; placeSun(-0.14); if (target > 0) sunTo(target, 1800); else sunArc.t = target; };
      return;
    }
    sunArc.from = sunArc.t; sunArc.to = t; sunArc.t0 = performance.now();
    sunArc.dur = reduce ? 1 : ms ?? 900 + 2600 * Math.min(1, Math.abs(t - sunArc.t));
    const step = now => {
      const k = clamp((now - sunArc.t0) / sunArc.dur, 0, 1), e = sweeping ? k : k < .5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
      sunArc.t = sunArc.from + (sunArc.to - sunArc.from) * e; placeSun(sunArc.t);
      if (sweeping) sc.el.dataset.sky = skyOfSun(sunArc.t);
      if (k < 1) sunArc.raf = requestAnimationFrame(step);
      else if (sweeping) { stopSweep(); systemScene(); }
      else if (sunArc.after) { const f = sunArc.after; sunArc.after = null; f(); }
    };
    sunArc.raf = requestAnimationFrame(step);
  }
  function stopSweep() { if (!sweeping) return; sweeping = false; $('sunSweep').setAttribute('aria-pressed', 'false'); }
  $('sunSweep').addEventListener('click', () => {
    if (sweeping) { stopSweep(); systemScene(); return; }
    if (st.phase !== 'SETUP') go('SETUP');
    sweeping = true; $('sunSweep').setAttribute('aria-pressed', 'true');
    sunArc.t = -0.14; placeSun(-0.14); sunTo(1.16, 14000);
  });
  placeSun(sunArc.t);

  let petTimer, petCount = 0;
  $('petCar').addEventListener('click', () => {
    clearTimeout(petTimer);
    const phase = st.phase;
    const previous = st.phase === 'SETUP' ? 'awake' : sc.eyeWrap.dataset.eyePose;
    petCount += 1; const reaction = (petCount - 1) % 5;
    sc.el.dataset.reaction = ['curious','smile','hop','love','joy'][reaction];
    sc.el.dataset.affection = String(Math.min(petCount,5));
    const friendly = phase !== 'SLEEPING' && phase !== 'WAKING';
    WakeChars.react(phase, reaction);
    if (friendly) WakeChars.flash(['surprise', 'happy', 'wink', 'love', 'happy'][reaction], 1300);
    else if (phase === 'SLEEPING') WakeChars.flash('drowsy', 900);   // 잠결에 살짝 뒤척
    else WakeChars.flash('surprise', 500);
    if (friendly) WakeGrow.floatGain(WakeGrow.onPet());
    sc.el.dataset.pet = 'false'; void sc.el.offsetWidth; sc.el.dataset.pet = 'true';
    to(sc.jump, 0, phase === 'SLEEPING' ? 80 : [160,260,500,620,850][reaction]); to(sc.wiggle, 0, 50);
    if (phase !== 'SLEEPING') WakeAudio.cue('pet', reaction);
    petTimer = setTimeout(() => {
      sc.el.dataset.pet = 'false';
    }, 1400);
  });
  function moveScene(phase) {
    const slot = document.querySelector(`#s-${phase} [data-scene-slot]`);
    if (slot && sc.el.parentElement !== slot) slot.prepend(sc.el);
  }

  // ------------------------------------------------------------ segmented control (Segmented control: 300/20, squash by speed)
  const seg = $('seg'), segHl = $('segHl'), segBtns = [...seg.querySelectorAll('button')];
  const SX = S(0, 300, 20), SW = S(100, 300, 20);
  let segInit = true;
  function segSelect() {
    const i = st.byTime ? 3 : segBtns.findIndex(b => +b.dataset.min === st.minutes);
    segBtns.forEach((b, k) => b.setAttribute('aria-checked', String(k === i)));
    const b = segBtns[i];
    if (!b.offsetWidth) return;
    if (segInit) { set(SX, b.offsetLeft); set(SW, b.offsetWidth); segInit = false; }
    else { to(SX, b.offsetLeft); to(SW, b.offsetWidth); }
  }
  render(() => {
    const sp = clamp(Math.abs(SX.v) / 1400, 0, 0.22);
    segHl.style.transform = `translateX(${SX.x.toFixed(2)}px) scale(${(SW.x / 100 * (1 + sp)).toFixed(4)}, ${(1 - sp * 0.5).toFixed(4)})`;
  });
  segBtns.forEach(b => b.addEventListener('click', () => {
    const before = wakeMinute();
    if (b.dataset.mode === 'time') st.byTime = true;
    else { st.byTime = false; st.minutes = +b.dataset.min; }
    segSelect(); renderSetup(before);
  }));

  // ------------------------------------------------------------ switches (Toggle switch: position 320/18, stretch 300/20)
  const switches = ['swSeat', 'swDemo'].map(id => {
    const el = $(id), on = el.getAttribute('aria-checked') === 'true';
    return { el, k: el.firstElementChild, p: S(on ? 1 : 0, 320, 18), w: S(0, 300, 20), on };
  });
  function flip(sw) {
    sw.on = !sw.on; sw.el.setAttribute('aria-checked', String(sw.on));
    to(sw.p, sw.on ? 1 : 0); to(sw.w, 0, 60);
    if (sw.el.id === 'swSeat') { st.seatRaise = sw.on; }
    if (sw.el.id === 'swDemo') { st.demo = sw.on; renderSetup(); }
  }
  switches.forEach(sw => {
    sw.el.addEventListener('click', e => { e.stopPropagation(); flip(sw); });
    sw.el.closest('.toggle-row').addEventListener('click', () => flip(sw));
  });
  render(() => switches.forEach(sw => {
    const p = clamp(sw.p.x, 0, 1), grow = clamp(sw.w.x, 0, 1) * 10, x = sw.p.x * 32;
    const left = sw.on ? x - grow * p : x;
    sw.k.style.transform = `translateX(${left.toFixed(2)}px) scaleX(${((32 + grow) / 32).toFixed(4)})`;
    sw.k.style.background = `color-mix(in srgb, var(--color-ink) ${(p * 100).toFixed(1)}%, var(--color-knob-off))`;
    sw.el.style.background = `color-mix(in srgb, var(--color-ok) ${(p * 100).toFixed(1)}%, var(--color-paper-4))`;
  }));

  // ------------------------------------------------------------ 1. setup
  const napMinutes = () => {
    if (!st.byTime) return st.minutes;
    const n = (st.returnBy - nowMinute()) - 5 - 3;
    return n > 0 ? n : null;
  };
  const wakeMinute = () => { const n = napMinutes(); return n == null ? null : nowMinute() + n; };
  function systemScene() {
    const date = new Date(), hour = date.getHours() + date.getMinutes() / 60;
    const sky = hour >= 5 && hour < 8 ? 'dawn' : hour >= 8 && hour < 17 ? 'morning' : hour >= 17 && hour < 20 ? 'evening' : 'night';
    $('sceneGreeting').textContent = { dawn: '천천히, 아침을 맞아요', morning: '잠깐 쉬어 가도 괜찮아요', evening: '하루의 속도를 낮춰요', night: '오늘은 잠시 쉬어 가요' }[sky];
    if (window.WakeGrow && WakeGrow.sleepy() >= 70) $('sceneGreeting').textContent = `${WakeGrow.callName(WakeChars.CHARS[WakeChars.current()].name)}도 졸려해요`;
    $('sceneClock').textContent = date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
    scene({ sky });
    if (!sweeping) sunTo(sunOfHour(hour));
  }
  function startBlock() {
    if (st.car.gear !== 'P') return '기어를 P에 놓으면 시작할 수 있어요.';
    if (st.car.door) return '문을 닫으면 시작할 수 있어요.';
    return null;
  }
  function renderSetup(before) {
    const n = napMinutes();
    const wt = $('wakeTime');
    if (n == null) {
      roll(wt, '--:--', 1); $('wakeLabel').textContent = '잘 시간이 부족해요';
      $('wakeNote').textContent = '복귀 시각을 늦춰 주세요.';
    } else if (st.demo) {
      const secs = n * T.perMin / 1000;
      roll(wt, `${secs}초`, before != null && nowMinute() + n < before ? -1 : 1);
      $('wakeLabel').textContent = '깨우기까지';
      $('wakeNote').textContent = `${n}분 낮잠 (시연)`;
    } else {
      roll(wt, clock(nowMinute() + n), before != null && nowMinute() + n < before ? -1 : 1);
      $('wakeLabel').textContent = '깨울 시각';
      $('wakeNote').textContent = `${n}분 낮잠`;
    }
    $('stepper').hidden = !st.byTime; $('stepperNote').hidden = !st.byTime;
    roll($('returnBy'), clock(st.returnBy), 1);
    const block = startBlock();
    $('startBtn').disabled = !!block || n == null;
    $('blockNotice').hidden = !block; if (block) $('blockText').textContent = block;
    rowState('gear', st.car.gear === 'P', st.car.gear === 'P' ? '기어 P' : '기어를 P로');
    rowState('door', !st.car.door, st.car.door ? '문이 열려 있어요' : '문 닫힘');
  }
  const ICON_OK = '<path d="m5 12.5 4.5 4.5L19 7.5"/>', ICON_BAD = '<path d="M12 3 2.5 20h19L12 3Zm0 6v5m0 3v.5"/>';
  function rowState(name, good, text) {
    const row = document.querySelector(`[data-check="${name}"]`); if (!row) return;
    row.classList.toggle('is-bad', !good);
    const ico = row.querySelector('.ico'); ico.innerHTML = good ? ICON_OK : ICON_BAD; ico.classList.toggle('ok', good); ico.classList.toggle('bad', !good);
    row.querySelector('span').textContent = text;
  }
  $('earlier').addEventListener('click', () => { st.returnBy -= 5; roll($('returnBy'), clock(st.returnBy), -1); renderSetup(); });
  $('later').addEventListener('click', () => { st.returnBy += 5; roll($('returnBy'), clock(st.returnBy), 1); renderSetup(); });
  $('startBtn').addEventListener('click', startNap);

  // ------------------------------------------------------------ 2. sleeping
  function startNap() {
    const n = napMinutes(); if (n == null || startBlock()) return;
    st.nap = { real: true, minutes: n, left: n, stage: 0, mission: null, step: 0, realarm: 0, snoozeUsed: false, q: 0, questions: [], alarmAt: 0, respMs: null, endReason: null, sleptMin: 0, restDone: null };
    go('SLEEPING');
  }
  function enterSleeping(fromSnooze) {
    const nap = st.nap;
    $('sleepCount').hidden = false; $('sleepSoon').hidden = true;
    systemScene();
    scene({ tilt: 16, eye: 0, look: 0, pane: 0 });
    // 잠들면 해가 서쪽으로 진다(깨울 때는 동쪽에서 다시 뜬다)
    if (sunArc.t > 0 && sunArc.t < 1) { sunTo(1.14, 3200); sc.el.dataset.sky = 'evening'; later(3200, () => { sc.el.dataset.sky = 'night'; }); }
    const per = st.demo ? T.perMin : 60000;
    if (fromSnooze) { nap.left = Math.round(T.snooze / per) || 1; }
    roll($('sleepMin'), String(nap.left), -1);
    WakeAudio.napLength(nap.left * per);
    every(per, () => {
      nap.left -= 1; nap.sleptMin += 1;
      if (nap.left <= 0) { wake(); return; }
      roll($('sleepMin'), String(nap.left), -1);
      if (nap.left * per <= (st.demo ? T.preWake : 180000) && nap.stage === 0) preWake();
    });
  }
  function preWake() {            // 1단계: 조명을 서서히 밝힌다
    st.nap.stage = 1;
    WakeAudio.setStage(1, st.demo ? T.preWake : 180000);
    $('sleepCount').hidden = true; $('sleepSoon').hidden = false;
    scene({ sky: 'dawn', sun: 0.55 });
  }
  $('endNap').addEventListener('click', () => finish('USER_ENDED'));

  // ------------------------------------------------------------ 3. waking: mission track (Step progress)
  const track = $('track');
  let tr = null;
  function buildTrack(m) {
    track.textContent = '';
    track.style.setProperty('--n', m.steps.length);
    const line = document.createElement('span'); line.className = 'track-line'; line.setAttribute('aria-hidden', 'true');
    const bar = document.createElement('span'); bar.className = 'track-bar'; bar.setAttribute('aria-hidden', 'true');
    line.hidden = bar.hidden = m.steps.length < 2;
    track.append(line, bar);
    const steps = m.steps.map((name, i) => {
      const li = document.createElement('li'); li.className = 'track-step';
      li.innerHTML = `<span class="dot"><b>${i + 1}</b><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></span><span class="track-name">${name}</span>`;
      track.append(li);
      return { li, d: li.querySelector('.dot'), num: li.querySelector('b'), path: li.querySelector('path'), f: S(0, 260, 24), c: S(0, 140, 20), p: S(1, 500, 14) };
    });
    tr = { steps, line, bar, L: S(0, 160, 22) };
    requestAnimationFrame(() => {
      const first = steps[0].d, last = steps[steps.length - 1].d;
      const x0 = first.offsetLeft + first.offsetWidth / 2, x1 = last.parentElement.offsetLeft + last.offsetWidth / 2;
      [line, bar].forEach(e => { e.style.left = x0 + 'px'; e.style.right = 'auto'; e.style.width = Math.max(0, x1 - x0) + 'px'; });
    });
  }
  function trackTo(done) {
    if (!tr) return;
    const n = tr.steps.length;
    to(tr.L, n > 1 ? clamp(done / (n - 1), 0, 1) : 0);
    tr.steps.forEach((s, k) => {
      const isDone = k < done;
      to(s.f, isDone ? 1 : 0);
      s.li.classList.toggle('is-done', isDone);
      if (isDone) after(0.12, () => to(s.c, 1)); else to(s.c, 0);
    });
    const cur = tr.steps[Math.min(done, n) - 1];
    if (cur) { set(cur.p, 0.7); to(cur.p, 1, 6); }
  }
  render(() => {
    if (!tr) return;
    tr.bar.style.transform = `scaleX(${clamp(tr.L.x, 0, 1).toFixed(4)})`;
    tr.steps.forEach(s => {
      const f = clamp(s.f.x, 0, 1);
      s.d.style.background = f > 0.01 ? `color-mix(in srgb, var(--color-wake) ${(f * 100).toFixed(1)}%, var(--color-paper-3))` : '';
      s.num.style.opacity = (1 - f).toFixed(3);
      s.path.style.strokeDashoffset = (22 * (1 - clamp(s.c.x, 0, 1))).toFixed(2);
      s.d.style.transform = `scale(${s.p.x.toFixed(4)})`;
    });
  });

  // wake stages list
  const stageEls = [...document.querySelectorAll('#stages li')];
  const stagePop = stageEls.map(() => S(1, 500, 14));
  stageEls.forEach(li => li.querySelector('.dot').insertAdjacentHTML('beforeend', '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>'));
  function renderStages(stage) {
    $('stageTitle').textContent = `깨우기 ${stage}단계`;
    WakeAudio.setStage(stage);
    document.querySelectorAll('[data-wake-stage]').forEach(b => b.setAttribute('aria-pressed', String(+b.dataset.wakeStage === stage)));
    $('stage3Label').textContent = st.seatRaise ? '등받이 세우기' : '바람 세게';
    stageEls.forEach((li, i) => {
      const n = i + 1, on = n === stage, past = n < stage;
      const was = li.classList.contains('is-on');
      li.classList.toggle('is-on', on); li.classList.toggle('is-past', past);
      li.querySelector('svg path').style.strokeDasharray = 22;
      li.querySelector('svg path').style.strokeDashoffset = past ? 0 : 22;
      if (on && !was) { set(stagePop[i], 0.7); to(stagePop[i], 1, 6); }
    });
  }
  render(() => stageEls.forEach((li, i) => { li.querySelector('.dot').style.transform = `scale(${stagePop[i].x.toFixed(4)})`; }));

  function wake() {
    const nap = st.nap; clearTimers();
    nap.stage = 2; st.stats.woken += 1;
    const keys = Object.keys(MISSIONS).filter(k => k !== nap.missionKey);
    nap.missionKey = keys[Math.floor(Math.random() * keys.length)];
    nap.mission = MISSIONS[nap.missionKey]; nap.step = 0; nap.alarmAt = performance.now();
    go('WAKING');
  }
  // 줄바꿈된 글의 실제 가장 긴 줄 너비로 상자를 맞춘다(카드 오른쪽 빈 공간 없애기)
  function fitLines(el) {
    el.style.width = '';
    const range = document.createRange(); range.selectNodeContents(el);
    const rects = [...range.getClientRects()], s = el.getBoundingClientRect().width / el.offsetWidth || 1;
    if (!rects.length) return;
    const left = Math.min(...rects.map(r => r.left)), right = Math.max(...rects.map(r => r.right));
    el.style.width = `${Math.ceil((right - left) / s) + 2}px`;
  }
  addEventListener('resize', () => { if (st.phase === 'WAKING') fitLines($('missionText')); });
  function enterWaking() {
    const nap = st.nap;
    $('wakeLead').textContent = nap.realarm > 0 ? '다시 잠드신 것 같아요' : '일어날 시간이에요';
    $('missionText').textContent = nap.mission.prompt;
    requestAnimationFrame(() => fitLines($('missionText')));
    $('missionIco').innerHTML = ICONS[nap.missionKey] || ICONS.BRAKE;
    buildTrack(nap.mission); trackTo(0);
    renderStages(2);
    scene({ sky: 'dawn', eye: 0.35, tilt: 7, sun: 0.8, jolt: 120 });
    later(st.demo ? T.stage3 : 60000, () => {
      nap.stage = 3; renderStages(3);
      scene({ eye: 0.5, tilt: 4, jolt: 160 });
    });
    later(st.demo ? T.stage4 : 300000, () => { nap.stage = 4; renderStages(4); scene({ eye: 0.7, jolt: 220 }); });
  }
  document.querySelectorAll('[data-wake-stage]').forEach(b => b.addEventListener('click', () => {
    if (st.phase !== 'WAKING') return;
    clearTimers();
    const level = +b.dataset.wakeStage;
    st.nap.stage = level; renderStages(level);
    scene({ eye: [0, 0, 0.35, 0.5, 0.7][level], tilt: [0, 16, 7, 4, 0][level], sun: level / 4, sky: 'dawn', jolt: level > 1 ? 100 : 0 });
  }));
  function missionInput(sig) {
    const nap = st.nap, m = nap.mission;
    if (m.need[nap.step] !== sig) return;
    nap.step += 1; trackTo(nap.step);
    scene({ eye: 0.5 + 0.5 * nap.step / m.need.length, tilt: 4 * (1 - nap.step / m.need.length) });
    if (nap.step === m.need.length) {
      if (nap.respMs == null) nap.respMs = performance.now() - nap.alarmAt;
      WakeChars.flash('happy', 700);
      clearTimers();
      later(700, () => go('GUARD'));
    }
  }

  // ------------------------------------------------------------ hold to stop (Hold button: release 260/14, press 520/20, shake 700/9)
  document.querySelectorAll('[data-hold]').forEach(btn => {
    const fill = btn.querySelector('.hold-fill'), on = btn.querySelector('.hold-label-on'), labels = btn.querySelectorAll('.hold-label');
    const p = S(0, 260, 14), sq = S(1, 520, 20), shake = S(0, 700, 9);
    let holding = false, t0 = 0, raf = 0;
    function tick(now) {
      if (!holding) return;
      p.x = p.t = clamp((now - t0) / HOLD_MS, 0, 1); p.v = 0; Spring.wake();
      if (p.x >= 1) { holding = false; done(); return; }
      raf = requestAnimationFrame(tick);
    }
    function start(e) {
      if (holding || btn.dataset.done) return;
      holding = true; t0 = performance.now(); to(sq, 0.95);
      try { btn.setPointerCapture(e.pointerId); } catch {}
      raf = requestAnimationFrame(tick);
    }
    function stop() {
      if (!holding) return;
      holding = false; cancelAnimationFrame(raf); to(sq, 1);
      if (p.x < 1) { to(p, 0); to(shake, 0, 260 * Math.min(1, p.x * 2 + 0.3)); }
    }
    function done() {
      btn.dataset.done = '1'; to(sq, 1, 2.5);
      labels.forEach(l => { l.textContent = '알람을 멈췄어요'; });
      later(600, () => { finish('EMERGENCY_STOP'); });
    }
    btn.reset = () => { holding = false; cancelAnimationFrame(raf); delete btn.dataset.done; set(p, 0); set(sq, 1); set(shake, 0); labels.forEach(l => { l.textContent = '비상 종료'; }); };
    btn.addEventListener('pointerdown', start);
    btn.addEventListener('pointerup', stop); btn.addEventListener('pointercancel', stop); btn.addEventListener('lostpointercapture', stop);
    btn.addEventListener('keydown', e => { if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) { e.preventDefault(); start(e); } });
    btn.addEventListener('keyup', e => { if (e.key === ' ' || e.key === 'Enter') stop(); });
    btn.addEventListener('contextmenu', e => e.preventDefault());
    render(() => {
      const x = clamp(p.x, 0, 1);
      fill.style.transform = `scaleX(${x.toFixed(4)})`;
      on.style.clipPath = `inset(0 ${((1 - x) * 100).toFixed(2)}% 0 0)`;
      btn.style.transform = `translateX(${shake.x.toFixed(2)}px) scale(${sq.x.toFixed(4)})`;
    });
  });

  // ------------------------------------------------------------ 4. guard
  function enterGuard() {
    const nap = st.nap;
    nap.questions = [
      { text: '오늘이 무슨 요일이에요?', choices: shuffle([0, 2, 4].map(offset => (new Date().getDay() + 6 + offset) % 7)).map(i => DAYS[i]) },
      { text: '지금 몇 시쯤일까요?', choices: shuffle([0, 3, 6].map(offset => (new Date().getHours() + offset) % 24)).map(h => `${h < 12 ? '오전' : '오후'} ${h % 12 || 12}시쯤`) },
    ];
    nap.q = 0;
    scene({ sky: 'morning', sun: 1.3, tilt: 0, eye: 1, look: 0.6, jolt: 60 });
    askQuestion(); guardCountdown();
  }
  const shuffle = a => a.map(v => [Math.random(), v]).sort((x, y) => x[0] - y[0]).map(x => x[1]);
  function askQuestion() {
    const nap = st.nap, q = nap.questions[nap.q];
    $('guardLead').textContent = `확인 ${nap.q + 1} / ${nap.questions.length}`;
    $('question').textContent = q.text;
    [...$('choices').children].forEach((b, i) => { b.textContent = q.choices[i]; });
    $('snooze').hidden = nap.snoozeUsed;
  }
  let guardLeft = 0, guardTick = 0;
  function guardCountdown() {
    const total = (st.demo ? T.guard : 180000) / 1000;
    guardLeft = total; clearInterval(guardTick);
    const bar = $('cdBar');
    bar.style.transition = 'none'; bar.style.transform = 'scaleX(1)';
    $('cdSec').textContent = guardLeft;
    guardTick = every(1000, () => {
      guardLeft -= 1;
      bar.style.transition = reduce ? 'none' : 'transform 1s linear';
      bar.style.transform = `scaleX(${(guardLeft / total).toFixed(4)})`;
      $('cdSec').textContent = Math.max(0, guardLeft);
      if (guardLeft <= 0) realarm();
    });
  }
  $('choices').addEventListener('click', e => {
    if (!e.target.closest('.choice')) return;
    const nap = st.nap; nap.q += 1;
    if (nap.q >= nap.questions.length) { go('REST'); return; }
    to(sc.look, -0.6); after(0.3, () => to(sc.look, 0.6));
    askQuestion(); guardCountdown();
  });
  $('snooze').addEventListener('click', () => { st.nap.snoozeUsed = true; st.nap.stage = 0; go('SLEEPING', { snooze: true }); });
  function realarm() {
    const nap = st.nap; clearTimers();
    if (nap.realarm === 0) st.stats.realarmNaps += 1;
    nap.realarm += 1; nap.stage = 2;
    scene({ sky: 'night', tilt: 16, eye: 0 });
    later(400, () => wake());
  }

  // ------------------------------------------------------------ 5. rest
  function enterRest() {
    const total = (st.demo ? T.rest : 600000) / 1000;
    let left = total;
    const unit = s => (s >= 60 ? [String(Math.ceil(s / 60)), '분 남음'] : [String(s), '초 남음']);
    const show = () => {
      const [n, u] = unit(left); roll($('restLeft'), n, -1); $('restUnit').textContent = u;
    };
    $('restLeftLine').classList.remove('is-ready'); $('restLeft').hidden = false;
    $('depart').disabled = true; show();
    scene({ sky: 'morning', sun: 1.5, tilt: 0, eye: 1, look: 1, pane: 1 });
    const C = 326.73, ring = $('ringBar'); ring.style.transition = 'none'; ring.style.strokeDashoffset = 0;
    every(1000, () => {
      left -= 1;
      ring.style.transition = reduce ? 'none' : ''; ring.style.strokeDashoffset = (C * (1 - left / total)).toFixed(2);
      if (left > 0) { show(); return; }
      clearTimers();
      $('restLeft').hidden = true; $('restUnit').textContent = '휴식 완료';
      $('restLeftLine').classList.add('is-ready'); $('depart').disabled = false;
      st.nap.restDone = true;
    });
  }
  $('depart').addEventListener('click', () => finish('COMPLETED'));
  $('endRest').addEventListener('click', () => finish('USER_ENDED'));

  // ------------------------------------------------------------ 6. done (Loading to success: ring 300/22, check 120/20)
  const disc = document.querySelector('.success-disc'), check = $('successCheck');
  const RG = S(1, 300, 22), CK = S(1, 120, 20);
  render(() => {
    disc.style.transform = `scale(${RG.x.toFixed(4)})`;
    check.style.strokeDashoffset = (52 * (1 - clamp(CK.x, 0, 1))).toFixed(2);
  });
  const TITLES = {
    COMPLETED: '조심히 다녀오세요', LEFT_CAR: '잘 다녀오세요', USER_ENDED: '낮잠을 끝냈어요', EMERGENCY_STOP: '알람을 멈췄어요',
    DOOR_OPENED: '문이 열려 낮잠을 끝냈어요', BRAKE_PRESSED: '브레이크를 밟아 낮잠을 끝냈어요', GEAR_LEFT_PARK: '기어가 P에서 벗어나 낮잠을 끝냈어요',
  };
  function finish(reason) {
    if (st.nap) {
      st.nap.endReason = reason;
      if (st.phase === 'REST' || reason === 'COMPLETED') { st.stats.restTotal += 1; if (st.nap.restDone) st.stats.restDone += 1; }
    }
    go('DONE');
  }
  function enterDone() {
    const nap = st.nap || { endReason: 'COMPLETED', sleptMin: 15, respMs: 12000, realarm: 1 };
    $('doneTitle').textContent = TITLES[nap.endReason] || '낮잠을 끝냈어요';
    $('statSlept').textContent = `${nap.sleptMin}분`;
    $('statResp').parentElement.hidden = nap.respMs == null;
    if (nap.respMs != null) $('statResp').textContent = `${Math.ceil(nap.respMs / 1000)}초`;
    $('statRealarmWrap').hidden = !nap.realarm;
    $('statRealarm').textContent = `${nap.realarm}번`;
    set(RG, 0.6); to(RG, 1); set(CK, 0); after(0.18, () => to(CK, 1));
    scene({ sky: 'morning', sun: 1.5, pane: 0, look: 0, eye: 1, tilt: 0 });
    if (nap.endReason === 'COMPLETED') {
      sc.eyeWrap.dataset.eyePose = 'happy'; sc.eyeLeft.dataset.eyePose = 'happy';
      after(1.15, () => { if (st.phase === 'DONE') setEye(1); });
    }
    after(0.3, () => { if (st.phase === 'DONE') stretch(); });
    // 키우기 보상
    const rw = $('reward'), coin = WakeGrow.coinSvg;
    if (nap.real && !nap.rewarded) {
      nap.rewarded = WakeGrow.onNap(nap);
      after(1.2, () => nap.rewarded.ups.forEach(WakeGrow.celebrate));
    }
    const r = nap.rewarded;
    rw.innerHTML = r
      ? `<p class="reward-title">${WakeGrow.callName(WakeChars.CHARS[WakeChars.current()].name)}가 받은 보상</p><p class="reward-gains"><span>+${r.exp} 경험치</span><span>${coin}+${r.coins} 별사탕</span></p>${r.notes?.length ? `<p class="reward-notes">${r.notes.join(', ')}</p>` : ''}${r.ups.length ? `<p class="reward-up">레벨 업! Lv.${r.ups[r.ups.length - 1].lv}</p>` : ''}`
      : '<p class="reward-notes">시연 화면이에요. 실제로 낮잠을 마치면 경험치와 별사탕을 받아요.</p>';
  }
  $('resetBtn').addEventListener('click', () => { st.nap = null; go('SETUP'); });

  // ------------------------------------------------------------ car signals (Virtual Console 대신)
  document.querySelectorAll('[data-car]').forEach(b => b.addEventListener('click', () => {
    const sig = b.dataset.car;
    if (carFx[sig]) carFx[sig]();
    if (sig === 'door') { st.car.door = !st.car.door; b.setAttribute('aria-pressed', String(st.car.door)); }
    if (sig === 'gear') { st.car.gear = st.car.gear === 'P' ? 'D' : 'P'; b.setAttribute('aria-pressed', String(st.car.gear !== 'P')); }
    const ph = st.phase;
    if (ph === 'SETUP') renderSetup();
    if (ph === 'SLEEPING') {
      if (sig === 'door' && st.car.door) finish('DOOR_OPENED');
      else if (sig === 'brake') finish('BRAKE_PRESSED');
      else if (sig === 'gear') finish('GEAR_LEFT_PARK');
    }
    if (ph === 'WAKING') { if (sig === 'door' && st.car.door) finish('LEFT_CAR'); else missionInput(sig); }
    if (ph === 'GUARD') { if (sig === 'door' && st.car.door) finish('LEFT_CAR'); else guardCountdown(); }
    if (ph === 'REST' && sig === 'door' && st.car.door) finish('LEFT_CAR');
  }));

  // ------------------------------------------------------------ screens
  function go(phase, opt = {}) {
    clearTimers(); clearInterval(guardTick);
    if (!st.nap && phase !== 'SETUP') {     // 탭으로 바로 들어온 경우 낮잠 하나를 꾸민다
      st.nap = { minutes: 15, left: 15, stage: 0, missionKey: 'BRAKE', mission: MISSIONS.BRAKE, step: 0, realarm: 0, snoozeUsed: false, q: 0, questions: [], alarmAt: performance.now(), respMs: phase === 'DONE' ? 12000 : null, endReason: 'COMPLETED', sleptMin: 15, restDone: null };
      if (phase !== 'SLEEPING') st.stats.woken = Math.max(st.stats.woken, 1);
    }
    clearTimeout(petTimer); sc.el.dataset.pet = 'false';
    st.phase = phase; frame.dataset.phase = phase;
    $('eyePreview').hidden = phase !== 'WAKING';
    sc.el.dataset.driving = String(phase === 'DONE' && st.nap?.endReason === 'COMPLETED');
    $('sceneIntro').hidden = phase !== 'SETUP';
    if (phase !== 'SETUP') stopSweep();
    WakeAudio.setPhase(phase);
    document.querySelectorAll('.screen').forEach(s => { s.hidden = s.dataset.phase !== phase; });
    document.querySelectorAll('[data-go]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.go === phase)));
    document.querySelectorAll('[data-hold]').forEach(b => b.reset && b.reset());
    moveScene(phase);
    if (phase === 'SETUP') { scene({ tilt: 0, eye: 1, look: 0, pane: 0 }); if (WakeGrow.sleepy() >= 70) { sc.eyeWrap.dataset.eyePose = 'drowsy'; sc.eyeLeft.dataset.eyePose = 'drowsy'; } systemScene(); segInit = true; requestAnimationFrame(() => { segSelect(); renderSetup(); }); }
    if (phase === 'SLEEPING') enterSleeping(opt.snooze);
    if (phase === 'WAKING') { if (!st.nap.mission) st.nap.mission = MISSIONS.BRAKE; enterWaking(); }
    if (phase === 'GUARD') enterGuard();
    if (phase === 'REST') enterRest();
    if (phase === 'DONE') enterDone();
  }
  document.querySelectorAll('[data-go]').forEach(b => b.addEventListener('click', () => {
    if (b.dataset.go === 'SETUP') st.nap = null;
    go(b.dataset.go);
  }));
  document.querySelectorAll('[data-orient]').forEach(b => b.addEventListener('click', () => {
    frame.dataset.orient = b.dataset.orient;
    document.querySelectorAll('[data-orient]').forEach(x => x.setAttribute('aria-pressed', String(x === b)));
    fit(); segInit = true; requestAnimationFrame(() => { segSelect(); if (st.phase === 'WAKING') buildTrack(st.nap.mission), trackTo(st.nap.step), fitLines($('missionText')); });
  }));
  document.querySelectorAll('[data-size]').forEach(b => b.addEventListener('click', () => {
    frame.dataset.size = b.dataset.size;
    document.querySelectorAll('[data-size]').forEach(x => x.setAttribute('aria-pressed', String(x === b)));
    fit(); segInit = true; requestAnimationFrame(() => { segSelect(); if (st.phase === 'WAKING') buildTrack(st.nap.mission), trackTo(st.nap.step), fitLines($('missionText')); });
  }));

  // start: allow ?phase=WAKING&orient=port&size=l129 for screenshots
  const q = new URLSearchParams(location.search);
  if (q.get('orient')) frame.dataset.orient = q.get('orient');
  if (q.get('size')) frame.dataset.size = q.get('size');
  document.querySelectorAll('[data-orient]').forEach(x => x.setAttribute('aria-pressed', String(x.dataset.orient === frame.dataset.orient)));
  document.querySelectorAll('[data-size]').forEach(x => x.setAttribute('aria-pressed', String(x.dataset.size === frame.dataset.size)));
  if (q.get('stats')) st.stats = { woken: 3, realarmNaps: 1, restDone: 2, restTotal: 3 };
  function renderAlarm() {
    const a = WakeAudio.summary();
    $('setupAlarm').textContent = a.ramp ? `${a.alarm}, 3분 전부터 점점 크게` : `${a.alarm}, 처음부터 같은 크기`;
    const jong = (a.alarm.charCodeAt(a.alarm.length - 1) - 0xAC00) % 28;
    $('sleepAlarm').textContent = `${a.alarm}${jong === 0 || jong === 8 ? '로' : '으로'} 깨워 드릴게요`;
    $('stage1Label').textContent = a.ramp ? '작은 알람' : '조명';
  }
  document.addEventListener('wakeaudio:change', renderAlarm);
  WakeAudio.bind();
  WakeChars.init();
  WakeGrow.bind();
  renderAlarm();
  setInterval(() => { if (st.phase === 'SETUP') { systemScene(); renderSetup(); } }, 30000);
  fit();
  (document.fonts ? document.fonts.ready : Promise.resolve()).then(() => { fit(); go(q.get('phase') || 'SETUP'); });
})();
