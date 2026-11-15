// 일어나차 오디오 엔진 v2. 모든 소리는 Web Audio로 직접 합성한 원작이며, 사용자 조작 이후에만 재생한다.
//
// 설계 근거(SPEC.md "소리 설계" 절 참고)
//  - 잠들 때: 분당 60박 이하의 느린 박, 낮은 음역, 급격한 강약 변화 없음, 고음역 차단(저역 통과 필터).
//    잠들고 나면 음량을 30%까지 서서히 내리고 박도 60에서 52로 늦춘다.
//  - 깨울 때: 선율형 알람(McFarlane et al., 2020, PLoS ONE)을 깨우기 단계(1~4)에 맞춰 키운다.
//    1단계(깨우기 3분 전): 아주 작게, 멀게(잔향 많음), 느리게.  4단계: 크게, 가깝게(잔향 적음), 빠르게.
window.WakeAudio = (() => {
  const SLEEP_TRACKS = {
    piano:   { name: '고요한 피아노', hint: '분당 60박에서 52박으로 천천히 느려져요.' },
    ambient: { name: '느린 앰비언트', hint: '박자 없이 넓게 퍼지는 화음이에요.' },
    rain:    { name: '창밖의 빗소리', hint: '고르게 내리는 비로 바깥 소음을 덮어요.' },
    waves:   { name: '파도 호흡', hint: '파도가 분당 6번 오가며 숨을 천천히 고르게 해요.' },
    cabin:   { name: '달리는 차 안', hint: '낮게 웅웅대는 주행음이에요.' },
  };
  const ALARMS = {
    sunrise:  { name: '해 뜨는 피아노', hint: '낮은 화음에서 시작해 밝은 음으로 올라가요.' },
    forest:   { name: '새벽 숲', hint: '멀리서 들리던 새소리가 가까워지고 많아져요.' },
    musicbox: { name: '오르골', hint: '맑은 종소리 선율이 점점 빨라져요.' },
    drive:    { name: '출발 신호', hint: '경쾌한 마림바에 빵빵 소리가 더해져요.' },
  };
  // 단계별 크기(층 음량), 빠르기, 잔향(멀고 가까움)
  const STAGE = { 1: { lvl: .22, bpm: 66, rev: .7 }, 2: { lvl: .5, bpm: 84, rev: .45 }, 3: { lvl: .75, bpm: 100, rev: .32 }, 4: { lvl: 1, bpm: 120, rev: .2 } };

  const defaults = { effects: true, music: true, track: 'piano', volume: 35, fade: true, alarm: 'sunrise', ramp: true, alarmVolume: 70 };
  let prefs = { ...defaults };
  try { prefs = { ...defaults, ...JSON.parse(localStorage.getItem('wakecar-audio') || '{}') }; } catch {}
  if (!SLEEP_TRACKS[prefs.track]) prefs.track = defaults.track;
  if (!ALARMS[prefs.alarm]) prefs.alarm = defaults.alarm;
  const clampVol = v => Math.max(0, Math.min(100, Number.isFinite(+v) ? +v : 0));
  prefs.volume = clampVol(prefs.volume); prefs.alarmVolume = clampVol(prefs.alarmVolume);

  let unlocked = false;
  document.addEventListener('pointerdown', () => { unlocked = true; }, { capture: true });
  document.addEventListener('keydown', () => { unlocked = true; }, { capture: true });

  let ctx, out, buses = {}, noise = {}, ticker = null;
  let phase = 'SETUP', napMs = 0, stage = 0;
  let music = null, alarm = null, preview = null;   // preview: { kind: 'music'|'alarm', layer, timer, stage }
  const live = new Set();

  // ------------------------------------------------------------ helpers
  const mtof = m => 440 * Math.pow(2, (m - 69) / 12);
  const rnd = (a, b) => a + Math.random() * (b - a);
  const pick = a => a[Math.floor(Math.random() * a.length)];
  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));

  function init() {
    if (!unlocked && !ctx) return false;
    if (!ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return false;
      ctx = new AC();
      buildGraph(prefs.volume / 100, prefs.alarmVolume / 100);
    }
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    if (!ticker) ticker = setInterval(tick, 90);
    return true;
  }
  function buildGraph(musicVol, alarmVol) {
    const limiter = ctx.createDynamicsCompressor();
    limiter.threshold.value = -8; limiter.knee.value = 6; limiter.ratio.value = 12; limiter.attack.value = .004; limiter.release.value = .3;
    out = ctx.createGain(); out.gain.value = .9; out.connect(limiter); limiter.connect(ctx.destination);
    buses = {};
    buses.music = bus(musicVol, 2400);
    buses.alarm = bus(alarmVol, 9000);
    buses.fx = bus(.35 * alarmVol, 12000, false);
    noise = {};
    for (const k of ['pink', 'brown', 'white']) noise[k] = makeNoise(k);
  }
  // 앱(안드로이드)용 반복 음원 굽기: 같은 합성기를 OfflineAudioContext에서 돌려 seconds 길이 + 이음매 교차 xfade를 만든다.
  // 반환: { sr, left, right } (Float32Array, 길이 = seconds * sr, 끝부분을 앞부분에 섞어 이음매 없이 반복된다)
  async function renderLoop({ kind, name, stage = 2, seconds = 32, xfade = 2, variation = 0 }) {
    const saved = { ctx, out, buses, noise, live: [...live] };
    const sr = 44100, total = seconds + xfade + (kind === 'cue' ? 0 : 0);
    ctx = new OfflineAudioContext(2, Math.ceil(sr * total), sr);
    buildGraph(1, 1); live.clear();
    try {
      if (kind === 'cue') {
        buses.fx.gain.gain.value = 1;
        const L = { in: buses.fx.gain, nodes: new Set() }, petMelodies = [[76, 79], [79, 83, 86], [76, 83, 88], [79, 84, 86, 91], [84, 88, 91, 88, 96]];
        const melody = name === 'pet' ? petMelodies[variation % 5] : name === 'done' ? [72, 76, 84] : name === 'start' ? [72, 67] : name === 'alarm' ? [72, 76, 79] : [76];
        melody.forEach((m, i) => bell(L, mtof(m), .05 + i * .13, name === 'tap' ? .5 : .8, .9, 0));
      } else {
        const L = kind === 'sleep' ? layer('music', SLEEP_SEND[name]) : layer('alarm', STAGE[stage].rev);
        L.gain.gain.value = 1; L.stage = stage;
        L.seq = kind === 'sleep' ? SLEEP[name](L) : ALARM_SEQ[name](L);
        L.next = .05;
        while (L.next < total) L.next += Math.max(.03, L.seq(L.step++, L.next) || .25);
      }
      const buf = await ctx.startRendering();
      const n = Math.round(seconds * sr), xf = Math.round(xfade * sr);
      const ch = [0, 1].map(c => {
        const d = buf.getChannelData(c), o = new Float32Array(kind === 'cue' ? d.length : n);
        if (kind === 'cue') { o.set(d); return o; }
        o.set(d.subarray(0, n));
        for (let i = 0; i < xf; i++) { const k = i / xf; o[i] = d[n + i] * (1 - k) + o[i] * k; }   // 꼬리를 머리에 섞는다
        return o;
      });
      return { sr, left: ch[0], right: ch[1] };
    } finally {
      ctx = saved.ctx; out = saved.out; buses = saved.buses; noise = saved.noise; live.clear(); saved.live.forEach(l => live.add(l));
    }
  }
  // 버스: 음량 -> 고역 차단 -> 출력, 잔향은 버스마다 하나
  function bus(level, cutoff, withReverb = true) {
    const gain = ctx.createGain(), tone = ctx.createBiquadFilter();
    gain.gain.value = level; tone.type = 'lowpass'; tone.frequency.value = cutoff; tone.Q.value = .5;
    gain.connect(tone); tone.connect(out);
    let rev = null;
    if (withReverb) { rev = ctx.createConvolver(); rev.buffer = impulse(3.6, 2.6); rev.connect(gain); }
    return { gain, tone, rev };
  }
  function impulse(sec, decay) {
    const n = Math.floor(ctx.sampleRate * sec), b = ctx.createBuffer(2, n, ctx.sampleRate);
    for (let c = 0; c < 2; c++) { const d = b.getChannelData(c); for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / n, decay) * .5; }
    return b;
  }
  function makeNoise(kind) {
    const sr = ctx.sampleRate, n = sr * 6, b = ctx.createBuffer(1, n, sr), d = b.getChannelData(0);
    let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0, last = 0;
    for (let i = 0; i < n; i++) {
      const w = Math.random() * 2 - 1;
      if (kind === 'brown') { last = (last + .02 * w) / 1.02; d[i] = last * 3.5; }
      else if (kind === 'pink') {
        b0 = .99886 * b0 + w * .0555179; b1 = .99332 * b1 + w * .0750759; b2 = .969 * b2 + w * .153852;
        b3 = .8665 * b3 + w * .3104856; b4 = .55 * b4 + w * .5329522; b5 = -.7616 * b5 - w * .016898;
        d[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * .5362) * .11; b6 = w * .115926;
      } else d[i] = w * .5;
    }
    const f = Math.floor(sr * .08);           // 반복 이음매를 없애려고 끝을 앞부분에 섞는다
    for (let i = 0; i < f; i++) { const k = i / f; d[n - f + i] = d[n - f + i] * (1 - k) + d[i] * k; }
    return { buffer: b, loopStart: f / sr };
  }

  // 층: 한 곡(또는 한 알람)이 쓰는 노드 묶음. 끌 때는 층 음량만 내리고 정리한다.
  function layer(busName, send = .4) {
    const b = buses[busName], gain = ctx.createGain(), wet = ctx.createGain();
    gain.gain.value = .0001; wet.gain.value = send;
    gain.connect(b.gain); gain.connect(wet); if (b.rev) wet.connect(b.rev);
    const L = { gain, wet, in: gain, nodes: new Set(), seq: null, next: ctx.currentTime + .08, step: 0, t0: ctx.currentTime, span: Infinity, alive: true };
    live.add(L); return L;
  }
  function kill(L, fade = .8) {
    if (!L || !L.alive) return;
    L.alive = false; L.seq = null; live.delete(L);
    const t = ctx.currentTime;
    L.gain.gain.cancelScheduledValues(t); L.gain.gain.setValueAtTime(Math.max(L.gain.gain.value, .0001), t);
    L.gain.gain.exponentialRampToValueAtTime(.0001, t + fade);
    setTimeout(() => { L.nodes.forEach(n => { try { n.stop(); } catch {} }); try { L.gain.disconnect(); L.wet.disconnect(); } catch {} }, fade * 1000 + 300);
  }
  function tick() {
    if (!ctx) return;
    const horizon = ctx.currentTime + .7;
    for (const L of live) {
      if (!L.seq) continue;
      if (L.next < ctx.currentTime) L.next = ctx.currentTime + .02;   // 탭이 쉬다 돌아온 경우
      while (L.alive && L.seq && L.next < horizon) L.next += Math.max(.03, L.seq(L.step++, L.next) || .25);
    }
  }
  function panNode(L, pan) {
    if (!ctx.createStereoPanner) return L.in;
    const p = ctx.createStereoPanner(); p.pan.value = clamp(pan, -1, 1); p.connect(L.in); return p;
  }
  function play(L, srcs, t, end, extra) {
    srcs.forEach(s => { s.start(t); s.stop(end); L.nodes.add(s); });
    srcs[0].onended = () => { srcs.forEach(s => L.nodes.delete(s)); [...srcs, ...extra].forEach(n => { try { n.disconnect(); } catch {} }); };
  }
  const progress = L => clamp((ctx.currentTime - L.t0) / L.span, 0, 1);

  // ------------------------------------------------------------ voices
  function piano(L, hz, t, v, len = 3, pan = 0, attack = .012) {
    const o1 = ctx.createOscillator(), o2 = ctx.createOscillator(), g2 = ctx.createGain(), f = ctx.createBiquadFilter(), g = ctx.createGain(), p = panNode(L, pan);
    o1.type = 'triangle'; o1.frequency.value = hz; o2.type = 'sine'; o2.frequency.value = hz * 2.003; g2.gain.value = .16;
    f.type = 'lowpass'; f.Q.value = .4;
    f.frequency.setValueAtTime(Math.min(hz * 7, 7000), t); f.frequency.exponentialRampToValueAtTime(Math.max(hz * 1.4, 180), t + len * .7);
    g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(v * .3, t + attack);
    g.gain.exponentialRampToValueAtTime(v * .1, t + attack + .5); g.gain.exponentialRampToValueAtTime(.0001, t + len);
    o1.connect(f); o2.connect(g2); g2.connect(f); f.connect(g); g.connect(p);
    play(L, [o1, o2], t, t + len + .05, [g2, f, g, p !== L.in ? p : null].filter(Boolean));
  }
  function pad(L, hz, t, dur, v, pan = 0, cutoff = 650) {
    const a = ctx.createOscillator(), b = ctx.createOscillator(), f = ctx.createBiquadFilter(), g = ctx.createGain(), p = panNode(L, pan);
    a.type = b.type = 'sawtooth'; a.frequency.value = hz; b.frequency.value = hz; a.detune.value = -7; b.detune.value = 7;
    f.type = 'lowpass'; f.Q.value = .7;
    f.frequency.setValueAtTime(cutoff * .6, t); f.frequency.linearRampToValueAtTime(cutoff, t + dur * .5); f.frequency.linearRampToValueAtTime(cutoff * .6, t + dur);
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(v * .08, t + dur * .35); g.gain.setValueAtTime(v * .08, t + dur * .6); g.gain.linearRampToValueAtTime(0, t + dur);
    a.connect(f); b.connect(f); f.connect(g); g.connect(p);
    play(L, [a, b], t, t + dur + .05, [f, g, p !== L.in ? p : null].filter(Boolean));
  }
  function bell(L, hz, t, v, len = 2, pan = 0) {      // 오르골 금속살: 비정수배 배음
    const parts = [[1, 1, len], [3.93, .18, len * .25], [9.1, .05, len * .1]];
    const p = panNode(L, pan), extra = p !== L.in ? [p] : [];
    parts.forEach(([mul, amp, d]) => {
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.type = 'sine'; o.frequency.value = hz * mul;
      g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(v * amp * .25, t + .004); g.gain.exponentialRampToValueAtTime(.0001, t + d);
      o.connect(g); g.connect(p); play(L, [o], t, t + d + .05, [g, ...extra.splice(0)]);
    });
  }
  function marimba(L, hz, t, v, pan = 0) {
    const o = ctx.createOscillator(), o4 = ctx.createOscillator(), g = ctx.createGain(), g4 = ctx.createGain(), p = panNode(L, pan);
    o.type = 'sine'; o.frequency.value = hz; o4.type = 'sine'; o4.frequency.value = hz * 4;
    g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(v * .32, t + .003); g.gain.exponentialRampToValueAtTime(.0001, t + .7);
    g4.gain.setValueAtTime(.0001, t); g4.gain.exponentialRampToValueAtTime(v * .08, t + .002); g4.gain.exponentialRampToValueAtTime(.0001, t + .12);
    o.connect(g); o4.connect(g4); g.connect(p); g4.connect(p);
    play(L, [o, o4], t, t + .75, [g, g4, p !== L.in ? p : null].filter(Boolean));
  }
  function horn(L, hz, t, d, v) {                    // 장난감 경적: 장3도 두 음
    const a = ctx.createOscillator(), b = ctx.createOscillator(), f = ctx.createBiquadFilter(), g = ctx.createGain();
    a.type = b.type = 'square'; a.frequency.value = hz; b.frequency.value = hz * 1.26;
    a.frequency.setValueAtTime(hz, t + d * .7); a.frequency.linearRampToValueAtTime(hz * .97, t + d);
    f.type = 'lowpass'; f.frequency.value = 1500; f.Q.value = 2;
    g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(v * .07, t + .02); g.gain.setValueAtTime(v * .07, t + d - .04); g.gain.exponentialRampToValueAtTime(.0001, t + d);
    a.connect(f); b.connect(f); f.connect(g); g.connect(L.in);
    play(L, [a, b], t, t + d + .05, [f, g]);
  }
  function thump(L, t, v) {
    const o = ctx.createOscillator(), g = ctx.createGain();
    o.type = 'sine'; o.frequency.setValueAtTime(120, t); o.frequency.exponentialRampToValueAtTime(48, t + .18);
    g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(v * .5, t + .006); g.gain.exponentialRampToValueAtTime(.0001, t + .25);
    o.connect(g); g.connect(L.in); play(L, [o], t, t + .3, [g]);
  }
  function chirp(L, t, f0, f1, d, v, pan) {
    const o = ctx.createOscillator(), g = ctx.createGain(), p = panNode(L, pan);
    o.type = 'sine'; o.frequency.setValueAtTime(f0, t); o.frequency.exponentialRampToValueAtTime(f1, t + d);
    g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(v * .2, t + .008); g.gain.exponentialRampToValueAtTime(.0001, t + d);
    o.connect(g); g.connect(p); play(L, [o], t, t + d + .03, [g, p !== L.in ? p : null].filter(Boolean));
  }
  function bird(L, t, kind, v, pan) {
    if (kind === 0) {                       // 내려가는 떨림
      const n = 4 + Math.floor(Math.random() * 4), top = rnd(3600, 4400);
      for (let i = 0; i < n; i++) chirp(L, t + i * .075, top - i * 90, top - i * 90 - 700, .055, v, pan);
    } else if (kind === 1) {                // 두 음 휘파람
      chirp(L, t, 2300, 3100, .18, v, pan); chirp(L, t + .24, 3000, 2500, .22, v * .9, pan);
    } else {                                // 짧은 지저귐
      for (let i = 0; i < 3; i++) chirp(L, t + i * .11, rnd(2800, 3400), rnd(4200, 5000), .07, v * .8, pan);
    }
  }
  // 반복 잡음: kind, 필터, 음량. 반환값의 gain/filter로 이후 변화를 준다.
  function bed(L, kind, type, freq, level, q = .5) {
    const s = ctx.createBufferSource(), f = ctx.createBiquadFilter(), g = ctx.createGain();
    s.buffer = noise[kind].buffer; s.loop = true; s.loopStart = noise[kind].loopStart; s.loopEnd = s.buffer.duration;
    f.type = type; f.frequency.value = freq; f.Q.value = q; g.gain.value = level;
    s.connect(f); f.connect(g); g.connect(L.in);
    s.start(ctx.currentTime, rnd(.1, 5)); L.nodes.add(s);
    s.onended = () => { L.nodes.delete(s); [s, f, g].forEach(n => { try { n.disconnect(); } catch {} }); };
    return { src: s, filter: f, gain: g };
  }

  // ------------------------------------------------------------ 잠들 때 음악
  const SLEEP = {
    piano(L) {
      // D♭장조, 낮은 음역. Ⅰ-ⅵ-Ⅳ-Ⅴsus의 4마디 반복. 선율은 5음 음계에서 한두 칸씩만 움직인다.
      const prog = [[49, 56, 60, 63], [46, 53, 56, 60], [42, 49, 53, 58], [44, 51, 54, 56]];
      const scale = [61, 63, 65, 68, 70, 73, 75, 77];
      let mel = 3;
      return (step, t) => {
        const p = progress(L), beat = 60 / (60 - 8 * p), vel = .85 - .35 * p;
        const bar = Math.floor(step / 4), b = step % 4, ch = prog[bar % 4];
        if (b === 0) {
          piano(L, mtof(ch[0] - 12), t, vel * .55, 7, 0, .04);
          ch.slice(1).forEach((m, i) => piano(L, mtof(m), t + .06 + i * .07, vel * .3, 6, (i - 1) * .3, .05));
        }
        if (b === 2 && Math.random() < .4) piano(L, mtof(pick(ch.slice(1))), t + rnd(0, .04), vel * .22, 4, rnd(-.4, .4), .05);
        if (Math.random() < (b === 0 ? .25 : .55 - .3 * p)) {
          mel = clamp(mel + pick([-2, -1, -1, 0, 1, 1, 2]), 0, scale.length - 1);
          piano(L, mtof(scale[mel]), t + rnd(0, .05), vel * .42, 4.5, rnd(-.35, .35), .03);
        }
        return beat;
      };
    },
    ambient(L) {
      const prog = [[38, 45, 50, 57, 62], [41, 48, 53, 57, 64], [36, 43, 48, 55, 59], [43, 50, 55, 59, 62]];
      return (step, t) => {
        const p = progress(L), dur = 12 + 4 * p, ch = prog[step % prog.length];
        ch.forEach((m, i) => pad(L, mtof(m), t, dur + 2, (i === 0 ? .9 : .55) * (1 - .3 * p), (i - 2) * .25, 500 + 200 * (1 - p)));
        if (Math.random() < .6) bell(L, mtof(pick(ch.slice(2)) + 12), t + rnd(2, 6), .18, 4, rnd(-.6, .6));
        return dur;
      };
    },
    rain(L) {
      bed(L, 'pink', 'lowpass', 2600, .55); bed(L, 'brown', 'lowpass', 500, .4);
      const hiss = bed(L, 'white', 'bandpass', 5200, .05, .8);
      return (step, t) => {          // 굵은 빗방울과 가끔 똑 떨어지는 처마 물방울
        const drops = 1 + Math.floor(Math.random() * 3);
        for (let i = 0; i < drops; i++) chirp(L, t + rnd(0, .2), rnd(1800, 3200), rnd(900, 1400), .02, rnd(.05, .15), rnd(-.8, .8));
        if (Math.random() < .03) chirp(L, t + .1, 1300, 650, .06, .35, rnd(-.5, .5));
        hiss.gain.gain.setTargetAtTime(rnd(.03, .07), t, 1.5);
        return rnd(.12, .3);
      };
    },
    waves(L) {
      const sea = bed(L, 'brown', 'lowpass', 400, .1), foam = bed(L, 'pink', 'bandpass', 900, .02, .6);
      return (step, t) => {          // 들숨 4초, 날숨 6초 = 분당 6회. 잠들수록 12초 주기(분당 5회)로 늘린다.
        const p = progress(L), cycle = 10 + 2 * p, up = cycle * .4;
        sea.gain.gain.setTargetAtTime(.75, t, up / 3); sea.gain.gain.setTargetAtTime(.12, t + up, (cycle - up) / 3);
        sea.filter.frequency.setTargetAtTime(1300, t, up / 3); sea.filter.frequency.setTargetAtTime(380, t + up, (cycle - up) / 3);
        foam.gain.gain.setTargetAtTime(.12, t + up * .7, .6); foam.gain.gain.setTargetAtTime(.01, t + up + .5, 1.6);
        return cycle;
      };
    },
    cabin(L) {
      const road = bed(L, 'brown', 'lowpass', 180, .9), air = bed(L, 'pink', 'lowpass', 700, .12);
      const hum = ctx.createOscillator(), hg = ctx.createGain();
      hum.type = 'sine'; hum.frequency.value = 52; hg.gain.value = .05; hum.connect(hg); hg.connect(L.in); hum.start(); L.nodes.add(hum);
      return (step, t) => {          // 노면이 조금씩 바뀌는 느낌만, 툭툭 튀는 소리는 넣지 않는다
        road.filter.frequency.setTargetAtTime(rnd(150, 230), t, 3); air.gain.gain.setTargetAtTime(rnd(.08, .14), t, 3);
        hum.frequency.setTargetAtTime(rnd(48, 56), t, 4);
        return 6;
      };
    },
  };
  const SLEEP_SEND = { piano: .45, ambient: .6, rain: .15, waves: .2, cabin: .05 };

  function startMusic(forPreview = false) {
    if (!init()) return null;
    const L = layer('music', SLEEP_SEND[prefs.track]);
    L.seq = SLEEP[prefs.track](L);
    const t = ctx.currentTime;
    L.gain.gain.setValueAtTime(.0001, t); L.gain.gain.exponentialRampToValueAtTime(1, t + 6);
    if (!forPreview) applyFade(L);
    return L;
  }
  // 잠든 뒤 음량을 30%까지 서서히 내린다. 기간 = 낮잠 길이의 절반, 최대 10분.
  function applyFade(L) {
    if (!L || !L.alive) return;
    const span = napMs > 0 ? Math.min(600, napMs / 2000) : 300;
    L.span = Math.max(span, 12);
    if (!prefs.fade) return;
    const t = ctx.currentTime, start = Math.max(L.t0 + 6, t);
    L.gain.gain.setValueAtTime(1, start); L.gain.gain.linearRampToValueAtTime(.3, L.t0 + L.span);
  }

  // ------------------------------------------------------------ 깨울 때 알람
  const ALARM_SEQ = {
    sunrise(L) {
      const prog = [[48, 55, 64, 67, 71], [52, 59, 64, 67, 71], [53, 60, 65, 69, 72], [55, 62, 67, 71, 74]];
      const arp = [1, 2, 3, 4, 2, 3, 4, 3];
      return (step, t) => {
        const s = L.stage, bar = Math.floor(step / 8), e = step % 8, ch = prog[bar % 4], dt = 30 / STAGE[s].bpm;
        if (s === 1) {                       // 낮은 화음만 길게
          if (e === 0) ch.slice(0, 4).forEach((m, i) => piano(L, mtof(m), t + i * .16, .32, 6, (i - 1.5) * .3, .06));
          if (e === 4 && Math.random() < .5) piano(L, mtof(ch[4]), t, .2, 4, .3, .05);
          return dt;
        }
        const up = s >= 3 && e >= 4 ? 12 : 0;
        piano(L, mtof(ch[arp[e]] + up), t, .38 + .06 * s, 1.8, e % 2 ? .35 : -.35);
        if (e === 0) piano(L, mtof(ch[0] - 12), t, .5, 3);
        if (s >= 3 && e % 4 === 0) bell(L, mtof(ch[4] + 12), t, .35, 1.6, .2);
        if (s === 4 && e % 2 === 1) piano(L, mtof(ch[arp[e]] + 24), t, .18, .6, -.2);
        return dt;
      };
    },
    forest(L) {
      const wind = bed(L, 'pink', 'lowpass', 600, .18);
      const tune = [67, 69, 71, 74, 76, 79, 76, 74];      // G장조 5음 음계를 오르내리는 작은 선율
      return (step, t) => {
        const s = L.stage, beat = 60 / STAGE[s].bpm;
        const chance = [0, .14, .3, .5, .7][s];
        if (Math.random() < chance) bird(L, t + rnd(0, beat * .5), Math.floor(Math.random() * 3), [0, .35, .6, .8, 1][s], rnd(-.9, .9));
        if (s >= 3 && Math.random() < chance * .5) bird(L, t + rnd(0, beat), Math.floor(Math.random() * 3), .6, rnd(-.9, .9));
        if (s >= 3) marimba(L, mtof(tune[step % 8]), t, s === 4 ? .5 : .35, step % 2 ? .3 : -.3);
        wind.gain.gain.setTargetAtTime([0, .18, .14, .1, .08][s], t, 2);
        return s >= 3 ? beat / 2 : beat;
      };
    },
    musicbox(L) {
      // 직접 지은 16음 선율(F장조). 3단계부터 아래 3도 화음, 4단계는 옥타브 위 겹침과 낮은 박.
      const tune = [72, 77, 76, 72, 69, 72, 74, 0, 77, 81, 79, 76, 74, 76, 77, 0];
      const third = { 72: 69, 77: 74, 76: 72, 69: 65, 74: 70, 81: 77, 79: 76 };
      return (step, t) => {
        const s = L.stage, dt = 30 / (STAGE[s].bpm * (s === 1 ? .8 : 1)), m = tune[step % 16];
        if (m) {
          bell(L, mtof(m), t, s === 1 ? .5 : .75, 2.2, rnd(-.2, .2));
          if (s >= 3 && third[m]) bell(L, mtof(third[m]), t + .01, .4, 1.8, -.3);
          if (s === 4) bell(L, mtof(m + 12), t + .015, .3, 1.2, .3);
        }
        if (s >= 2 && step % 4 === 0) bell(L, mtof(53 + (Math.floor(step / 16) % 2 ? 5 : 0)), t, .35, 2.5, 0);
        return dt;
      };
    },
    drive(L) {
      // A장조 마림바 반복음형. 3단계부터 두 마디마다 경적 두 번, 4단계는 매 마디 경적과 낮은 박.
      const riff = [[69, 0, 76, 73, 0, 76, 78, 76], [69, 0, 76, 73, 0, 81, 80, 76], [66, 0, 73, 69, 0, 73, 76, 73], [68, 0, 76, 71, 0, 76, 80, 76]];
      const bass = [45, 45, 42, 40];
      return (step, t) => {
        const s = L.stage, bar = Math.floor(step / 8), e = step % 8, dt = 30 / STAGE[s].bpm, m = riff[bar % 4][e];
        if (s === 1) { if (e % 4 === 0) marimba(L, mtof(riff[bar % 4][e] || 69), t, .35, 0); return dt * 1.5; }
        if (m) marimba(L, mtof(m), t, .55, e % 2 ? .3 : -.3);
        if (e === 0 || e === 4) marimba(L, mtof(bass[bar % 4]), t, .6, 0);
        const honk = s === 4 ? e === 6 : s === 3 && bar % 2 === 1 && e === 6;
        if (honk) { horn(L, mtof(76), t, .14, 1); horn(L, mtof(76), t + .2, .2, 1); }
        if (s === 4 && e % 2 === 0) thump(L, t, .6);
        return dt;
      };
    },
  };

  function startAlarm(level, kind = prefs.alarm) {
    if (!init()) return null;
    const L = layer('alarm', STAGE[level].rev);
    L.stage = level; L.seq = ALARM_SEQ[kind](L);
    return L;
  }
  // 단계 전환: 층 음량, 잔향, 빠르기를 바꾼다. rampMs 동안 서서히.
  function stageTo(L, s, rampMs) {
    if (!L || !L.alive) return;
    L.stage = s;
    const t = ctx.currentTime, lvl = prefs.ramp ? STAGE[s].lvl : 1, ramp = Math.max(.3, rampMs / 1000);
    L.gain.gain.cancelScheduledValues(t);
    L.gain.gain.setValueAtTime(Math.max(L.gain.gain.value, .0001), t);
    if (s === 1) L.gain.gain.setValueAtTime(.01, t);
    L.gain.gain.exponentialRampToValueAtTime(lvl, t + ramp);
    L.wet.gain.setTargetAtTime(STAGE[s].rev, t, ramp / 3);
  }

  // ------------------------------------------------------------ 효과음
  function cue(name, variation = 0) {
    if (!prefs.effects || !init()) return;
    const L = { in: buses.fx.gain, nodes: new Set() }, t = ctx.currentTime;
    const petMelodies = [[76, 79], [79, 83, 86], [76, 83, 88], [79, 84, 86, 91], [84, 88, 91, 88, 96]];
    const melody = name === 'pet' ? petMelodies[variation % 5] : name === 'done' ? [72, 76, 84] : name === 'start' ? [72, 67] : name === 'alarm' ? [72, 76, 79] : [76];
    melody.forEach((m, i) => bell(L, mtof(m), t + i * .13, name === 'tap' ? .5 : .8, .9, 0));
  }

  // ------------------------------------------------------------ phase / stage from app.js
  function stopPreview() {
    if (!preview) return;
    clearTimeout(preview.timer); kill(preview.layer, .5); preview = null;
  }
  function setPhase(next) {
    const previous = phase; phase = next;
    stopPreview();
    if (!ctx && !init()) { sync(); return; }
    if (next === 'SLEEPING') {
      kill(alarm, 1); alarm = null; stage = 0;
      if (previous !== 'SLEEPING') cue('start');
      if (!music && prefs.music) music = startMusic();
    } else {
      kill(music, 1.2); music = null;
    }
    if (next === 'WAKING') {
      if (!alarm) alarm = startAlarm(2);
    } else if (next !== 'SLEEPING') { kill(alarm, .6); alarm = null; stage = 0; }
    if (next === 'DONE' || next === 'GUARD') cue('done');
    sync();
  }
  function napLength(ms) { napMs = ms; if (music) { music.t0 = ctx.currentTime; applyFade(music); } }
  // 1단계는 자는 중(깨우기 직전), 2~4단계는 깨우기 화면에서 온다.
  function setStage(s, rampMs) {
    if (s === stage) return;
    stage = s;
    if (!init()) return;
    if (s === 1) {
      kill(music, Math.max(2, (rampMs || 6000) / 3000)); music = null;
      if (!prefs.ramp) return;                 // 점점 크게 끔: 깨우기 전에는 조용히 둔다
      if (!alarm) alarm = startAlarm(1);
      stageTo(alarm, 1, rampMs || 6000);
      return;
    }
    if (!alarm) alarm = startAlarm(s);
    stageTo(alarm, s, s === 2 && alarm.stage === 1 ? 1500 : 2500);
  }

  // ------------------------------------------------------------ 설정 화면
  function save() {
    try { localStorage.setItem('wakecar-audio', JSON.stringify(prefs)); } catch {}
    if (ctx) {
      buses.music.gain.gain.setTargetAtTime(prefs.volume / 100, ctx.currentTime, .1);
      buses.alarm.gain.gain.setTargetAtTime(prefs.alarmVolume / 100, ctx.currentTime, .1);
      buses.fx.gain.gain.setTargetAtTime(.35 * prefs.alarmVolume / 100, ctx.currentTime, .1);
    }
    document.dispatchEvent(new CustomEvent('wakeaudio:change', { detail: summary() }));
  }
  function summary() {
    return { alarm: ALARMS[prefs.alarm].name, ramp: prefs.ramp, music: prefs.music ? SLEEP_TRACKS[prefs.track].name : null };
  }
  function previewMusic() {
    if (preview?.kind === 'music') { stopPreview(); restoreLive(); sync(); return; }
    stopPreview(); muteLive();
    const L = startMusic(true); if (!L) return;
    preview = { kind: 'music', layer: L, timer: setTimeout(() => { stopPreview(); restoreLive(); sync(); }, 30000) };
    sync();
  }
  // 알람 미리듣기: 1단계부터 4단계까지 4초씩, 실제 깨우기를 16초로 압축해 들려준다.
  function previewAlarm() {
    if (preview?.kind === 'alarm') { stopPreview(); restoreLive(); sync(); return; }
    stopPreview(); muteLive();
    const first = prefs.ramp ? 1 : 2, L = startAlarm(first); if (!L) return;
    preview = { kind: 'alarm', layer: L, stage: first };
    stageTo(L, first, first === 1 ? 2500 : 600);
    const advance = () => {
      if (!preview || preview.layer !== L) return;
      if (preview.stage >= 4) { stopPreview(); restoreLive(); sync(); return; }
      preview.stage += 1; stageTo(L, preview.stage, 1200); sync();
      preview.timer = setTimeout(advance, 4000);
    };
    preview.timer = setTimeout(advance, 4000);
    sync();
  }
  // 미리듣기 동안 실제 재생 중인 층은 잠시 줄인다
  function muteLive() { for (const L of [music, alarm]) if (L?.alive) L.gain.gain.setTargetAtTime(.0001, ctx.currentTime, .15); }
  function restoreLive() {
    if (!ctx) return;
    if (music?.alive) { music.gain.gain.setTargetAtTime(prefs.fade ? .6 : 1, ctx.currentTime, .4); }
    if (alarm?.alive) stageTo(alarm, alarm.stage, 800);
  }

  function sync() {
    const $ = id => document.getElementById(id);
    for (const [id, key] of [['fxToggle', 'effects'], ['musicToggle', 'music'], ['fadeToggle', 'fade'], ['rampToggle', 'ramp']]) $(id)?.setAttribute('aria-checked', String(prefs[key]));
    if ($('musicTrack')) $('musicTrack').value = prefs.track;
    if ($('musicHint')) $('musicHint').textContent = SLEEP_TRACKS[prefs.track].hint;
    if ($('audioVolume')) $('audioVolume').value = prefs.volume;
    if ($('volumeValue')) $('volumeValue').textContent = prefs.volume + '%';
    if ($('alarmVolume')) $('alarmVolume').value = prefs.alarmVolume;
    if ($('alarmVolumeValue')) $('alarmVolumeValue').textContent = prefs.alarmVolume + '%';
    document.querySelectorAll('[data-alarm]').forEach(b => b.setAttribute('aria-checked', String(b.dataset.alarm === prefs.alarm)));
    document.querySelectorAll('.music-only').forEach(el => el.classList.toggle('is-off', !prefs.music));
    const pm = $('previewMusic');
    if (pm) { const on = preview?.kind === 'music'; pm.textContent = on ? '미리듣기 중지' : '수면 음악 미리듣기'; pm.setAttribute('aria-pressed', String(on)); pm.disabled = !prefs.music; }
    const pa = $('previewAlarm');
    if (pa) { const on = preview?.kind === 'alarm'; pa.textContent = on ? `중지 (${preview.stage}단계 재생 중)` : '알람 미리듣기 (16초)'; pa.setAttribute('aria-pressed', String(on)); }
  }
  function bind() {
    const $ = id => document.getElementById(id), dialog = $('settings');
    document.querySelectorAll('[data-open-settings]').forEach(b => b.addEventListener('click', e => { e.stopPropagation(); dialog.showModal(); sync(); }));
    $('closeSettings').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => { if (preview) { stopPreview(); restoreLive(); } sync(); });
    for (const [id, key] of [['fxToggle', 'effects'], ['musicToggle', 'music'], ['fadeToggle', 'fade'], ['rampToggle', 'ramp']]) {
      $(id)?.addEventListener('click', () => {
        prefs[key] = !prefs[key]; save();
        if (key === 'music') {
          if (prefs.music && phase === 'SLEEPING' && stage === 0 && !music) music = startMusic();
          if (!prefs.music) { kill(music, .8); music = null; if (preview?.kind === 'music') stopPreview(); }
        }
        if (key === 'effects' && prefs.effects) cue('tap');
        sync();
      });
    }
    $('musicTrack').addEventListener('change', e => {
      prefs.track = e.target.value; save();
      if (preview?.kind === 'music') { stopPreview(); previewMusic(); }
      else if (music) { kill(music, 1.5); music = startMusic(); }
      sync();
    });
    document.querySelectorAll('[data-alarm]').forEach(b => b.addEventListener('click', () => {
      prefs.alarm = b.dataset.alarm; save();
      if (alarm) { const s = alarm.stage; kill(alarm, .6); alarm = startAlarm(s); stageTo(alarm, s, 800); }
      if (preview?.kind === 'alarm') { stopPreview(); previewAlarm(); } else sync();
    }));
    $('audioVolume').addEventListener('input', e => { prefs.volume = +e.target.value; save(); sync(); });
    $('alarmVolume').addEventListener('input', e => { prefs.alarmVolume = +e.target.value; save(); sync(); });
    $('previewMusic').addEventListener('click', previewMusic);
    $('previewAlarm').addEventListener('click', previewAlarm);
    document.addEventListener('click', e => {
      const b = e.target.closest('button');
      if (b && !b.disabled && !b.matches('#petCar,#previewMusic,#previewAlarm,#fxToggle,[data-alarm],[data-hold],#startBtn,#depart')) cue('tap');
    });
    sync(); save();
  }
  return { setPhase, setStage, napLength, bind, cue, summary, renderLoop, ALARMS, SLEEP_TRACKS, STAGE };
})();
