// 키우기(육성 게임) 규칙: 낮잠을 잘 자고 잘 일어나면 캐릭터가 자란다.
//  - 경험치: 낮잠 성공 40(다시 깨운 적 없으면 +10, 15초 안에 일어나면 +10), 스스로 끝낸 낮잠 10, 쓰다듬기 2(하루 10번까지)
//  - 별사탕: 낮잠 성공 30, 오늘의 할 일 보상, 쓰다듬기 1(하루 10번까지). 옷장 소품을 산다.
//  - 단계: Lv1~2 아기, Lv3~5 꼬마, Lv6 이상 어른(크기가 커진다). 레벨마다 필요한 경험치 = 50 + 30 x (레벨 - 1)
//  - 기분: 쓰다듬기 +5, 낮잠 성공 +15, 시간당 4씩 줄어든다. 졸음: 마지막 낮잠 뒤 시간당 12씩 오른다(낮잠을 자면 0).
// 캐릭터마다 레벨, 경험치, 기분, 옷장은 따로 두고 별사탕과 졸음, 오늘의 할 일은 함께 쓴다.
window.WakeGrow = (() => {
  const KEY = 'wakecar-grow', H = 3600e3;
  const STAGES = [{ min: 1, name: '아기', scale: .84 }, { min: 3, name: '꼬마', scale: .93 }, { min: 6, name: '어른', scale: 1.02 }];
  const SHOP = {
    ribbon: { name: '분홍 리본', price: 40, lv: 1 },
    cap: { name: '별 수면 모자', price: 80, lv: 2 },
    crown: { name: '반짝 왕관', price: 150, lv: 5 },
  };
  const QUESTS = [
    { id: 'nap', text: '낮잠 한 번 잘 자고 일어나기', need: 1, reward: 30 },
    { id: 'quick', text: '알람 15초 안에 일어나기', need: 1, reward: 20 },
    { id: 'pet', text: '캐릭터 5번 쓰다듬기', need: 5, reward: 10 },
  ];
  const today = () => new Date().toDateString();
  const jong = w => (w.charCodeAt(w.length - 1) - 0xAC00) % 28;
  const callName = n => n && jong(n) ? n + '이' : n;            // 콩콩 -> 콩콩이, 빵삐 -> 빵삐
  const ro = w => (jong(w) === 0 || jong(w) === 8 ? w + '로' : w + '으로');
  const charBase = () => ({ lv: 1, exp: 0, mood: 60, moodAt: Date.now(), owned: [], wear: null });
  function load() {
    let s = null;
    try { s = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch {}
    s = { coins: 30, napAt: Date.now() - 4 * H, chars: {}, daily: { date: today(), nap: 0, quick: 0, pet: 0, claimed: [] }, ...(s || {}) };
    if (s.daily.date !== today()) s.daily = { date: today(), nap: 0, quick: 0, pet: 0, claimed: [] };
    return s;
  }
  let S = load();
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch {} ; render(); };
  const key = () => window.WakeChars?.current() || 'kongkong';
  const C = (k = key()) => (S.chars[k] ||= charBase());
  const need = lv => 50 + 30 * (lv - 1);
  const stage = lv => [...STAGES].reverse().find(s => lv >= s.min);
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const mood = (c = C()) => Math.round(clamp(c.mood - (Date.now() - c.moodAt) / H * 4, 0, 100));
  const sleepy = () => Math.round(clamp((Date.now() - S.napAt) / H * 12, 0, 100));
  function addMood(n) { const c = C(); c.mood = clamp(mood(c) + n, 0, 100); c.moodAt = Date.now(); }

  // 경험치와 별사탕을 더하고 레벨 업을 처리한다. 반환값으로 화면에 보상을 보여 준다.
  function gain(exp, coins) {
    const c = C(), ups = [];
    c.exp += exp; S.coins += coins;
    while (c.exp >= need(c.lv)) { c.exp -= need(c.lv); c.lv += 1; ups.push({ lv: c.lv, stage: stage(c.lv), grew: stage(c.lv) !== stage(c.lv - 1) }); }
    return { exp, coins, ups };
  }
  function onPet() {
    S.daily.pet += 1; addMood(5);
    const r = S.daily.pet <= 10 ? gain(2, 1) : { exp: 0, coins: 0, ups: [] };
    save(); r.ups.forEach(celebrate); return r;
  }
  function onNap(nap) {
    S.napAt = Date.now();
    let r;
    if (nap.endReason === 'COMPLETED' || nap.endReason === 'LEFT_CAR') {
      const quick = nap.respMs != null && nap.respMs <= 15000;
      r = gain(40 + (nap.realarm ? 0 : 10) + (quick ? 10 : 0), 30);
      S.daily.nap += 1; if (quick) S.daily.quick += 1; addMood(15);
      r.notes = [nap.realarm ? null : '한 번에 일어남 +10', quick ? '15초 안에 일어남 +10' : null].filter(Boolean);
    } else if (nap.endReason === 'USER_ENDED') { r = gain(10, 0); r.notes = ['스스로 낮잠을 끝냄']; }
    else { r = { exp: 0, coins: 0, ups: [], notes: ['비상 종료는 보상이 없어요'] }; }
    save(); return r;
  }
  function claim(id) {
    const q = QUESTS.find(x => x.id === id); if (!q || S.daily.claimed.includes(id) || S.daily[id] < q.need) return;
    S.daily.claimed.push(id); S.coins += q.reward; save(); window.WakeAudio?.cue('done');
  }
  function buy(item) {
    const it = SHOP[item], c = C(); if (!it || c.owned.includes(item) || S.coins < it.price || c.lv < it.lv) return;
    S.coins -= it.price; c.owned.push(item); c.wear = item; save(); window.WakeAudio?.cue('done'); window.WakeChars?.refresh();
  }
  function wear(item) { const c = C(); c.wear = c.wear === item ? null : item; save(); window.WakeChars?.refresh(); window.WakeAudio?.cue('tap'); }

  // ------------------------------------------------------------ 화면
  const $ = id => document.getElementById(id);
  const coinSvg = '<svg class="coin" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.8l2.6 5.6 6.1.7-4.5 4.2 1.2 6-5.4-3-5.4 3 1.2-6L3.3 9.1l6.1-.7Z"/></svg>';
  function bar(v, cls = '') { return `<span class="gbar ${cls}"><span style="width:${clamp(v, 0, 100)}%"></span></span>`; }
  function render() {
    const c = C(), k = key(), name = window.WakeChars?.CHARS[k]?.name || '';
    const st = stage(c.lv), pct = c.exp / need(c.lv) * 100;
    const hud = $('growHud');
    if (hud) {
      hud.innerHTML = `<span class="g-lv">Lv.${c.lv}</span><span class="g-main"><span class="g-name">${st.name} ${name}</span>${bar(pct, 'exp')}</span><span class="g-coins">${coinSvg}<b class="num">${S.coins}</b></span>`;
      hud.setAttribute('aria-label', `키우기: ${st.name} ${name}, 레벨 ${c.lv}, 경험치 ${Math.round(pct)}%, 별사탕 ${S.coins}개`);
    }
    document.querySelectorAll('.plush, .plush-ground').forEach(el => el.style.scale = String(st.scale));
    const dlg = $('growDialog'); if (!dlg?.open) return;
    $('gTitle').textContent = `${st.name} ${name}`;
    $('gLevel').textContent = `Lv.${c.lv}`;
    $('gExp').innerHTML = `${bar(pct, 'exp big')}<span class="g-exp-num num">${c.exp} / ${need(c.lv)}</span>`;
    $('gCoins').innerHTML = `${coinSvg}<b class="num">${S.coins}</b> 별사탕`;
    const nextStage = STAGES.find(s => s.min > c.lv);
    $('gNext').textContent = nextStage ? `Lv.${nextStage.min}이 되면 ${ro(nextStage.name)} 자라요.` : '다 자랐어요. 옷장을 꾸며 보세요.';
    const m = mood(c), z = sleepy();
    $('gStats').innerHTML = `
      <div class="g-stat"><span>기분</span>${bar(m, 'mood')}<b class="num">${m}</b></div>
      <div class="g-stat"><span>졸음</span>${bar(z, 'sleepy')}<b class="num">${z}</b></div>
      <p class="g-tip">${z >= 70 ? `${callName(name)}도 많이 졸려해요. 같이 낮잠을 자면 졸음이 0이 돼요.` : m < 40 ? `${callName(name)}가 심심해해요. 쓰다듬어 주세요.` : '낮잠을 잘 자고 잘 일어나면 경험치와 별사탕을 받아요.'}</p>`;
    $('gQuests').innerHTML = QUESTS.map(q => {
      const n = Math.min(S.daily[q.id], q.need), done = n >= q.need, got = S.daily.claimed.includes(q.id);
      return `<li class="g-quest"><span class="g-q-text">${q.text}<small class="num">${n} / ${q.need}</small></span>
        <button type="button" class="btn btn-basic g-claim" data-claim="${q.id}" ${!done || got ? 'disabled' : ''}>${got ? '받음' : `${coinSvg}${q.reward} 받기`}</button></li>`;
    }).join('');
    $('gShop').innerHTML = Object.entries(SHOP).map(([id, it]) => {
      const own = c.owned.includes(id), on = c.wear === id, locked = c.lv < it.lv;
      const label = on ? '벗기' : own ? '입기' : locked ? `Lv.${it.lv}부터` : `${coinSvg}${it.price}`;
      return `<li class="g-item${on ? ' on' : ''}"><span class="g-item-pic" data-item="${id}"><span class="g-item-base"></span><span class="g-item-acc"></span></span>
        <b>${it.name}</b><button type="button" class="btn btn-basic g-act" data-item-act="${id}" ${!own && (locked || S.coins < it.price) ? 'disabled' : ''}>${label}</button></li>`;
    }).join('');
    window.WakeChars?.paintShop();
    $('gPreview') && window.WakeChars?.paintPreview($('gPreview'));
    dlg.querySelectorAll('[data-claim]').forEach(b => b.onclick = () => claim(b.dataset.claim));
    dlg.querySelectorAll('[data-item-act]').forEach(b => b.onclick = () => { const id = b.dataset.itemAct; C().owned.includes(id) ? wear(id) : buy(id); });
  }
  // 레벨 업 축하: 장면 위 알림 + 기분 좋은 표정 + 소리
  function celebrate(up) {
    const el = $('levelUp'); if (!el) return;
    const name = window.WakeChars?.CHARS[key()]?.name || '';
    el.querySelector('b').textContent = `Lv.${up.lv}`;
    el.querySelector('span').textContent = up.grew ? `${callName(name)}가 ${ro(up.stage.name)} 자랐어요!` : `${callName(name)}의 레벨이 올랐어요`;
    el.hidden = false; el.dataset.show = 'false'; void el.offsetWidth; el.dataset.show = 'true';
    window.WakeChars?.flash('love', 1800); window.WakeAudio?.cue('done');
    clearTimeout(el._t); el._t = setTimeout(() => { el.hidden = true; }, 2600);
  }
  // 보상 떠오르기(+2 같은 숫자)
  function floatGain(r) {
    const host = $('scene'); if (!host || (!r.exp && !r.coins)) return;
    const f = document.createElement('span'); f.className = 'gain-float'; f.setAttribute('aria-hidden', 'true');
    f.innerHTML = `${r.exp ? `+${r.exp} 경험치` : ''}${r.coins ? ` ${coinSvg}+${r.coins}` : ''}`;
    const w = $('petCar').getBoundingClientRect(), h = host.getBoundingClientRect(), s = h.width / host.offsetWidth;
    f.style.left = `${(w.left + w.width / 2 - h.left) / s}px`; f.style.top = `${(w.top - h.top) / s + 20}px`;
    host.append(f); setTimeout(() => f.remove(), 1400);
  }
  function bind() {
    $('growHud')?.addEventListener('click', e => { e.stopPropagation(); $('growDialog').showModal(); render(); });
    $('closeGrow')?.addEventListener('click', () => $('growDialog').close());
    render();
  }
  return { callName, bind, render, onPet, onNap, celebrate, floatGain, sleepy, mood: () => mood(), level: () => C().lv, wearing: (k = key()) => C(k).wear, stageOf: () => stage(C().lv), coinSvg };
})();
