// 일어나차 캐릭터 2D 시안 4종. 모두 원작이며, 자동차는 몸이 아니라 소품(바퀴 발, 머리받침, 경적 단추, 안전띠)으로만 남긴다.
(() => {
  const { face, ellBody, blob, onEll } = Rig;
  const bulbColor = e => e === 'sleep' ? ['#E9EEF2', '#C9D3DA', '#AAB6BF'] : e === 'drowsy' ? ['#FFF6D8', '#FFE39A', '#F2C35B'] : ['#FFFBE6', '#FFE066', '#F5B82E'];

  const CHARS = {
    kongkong: {
      name: '콩콩', tag: 'A안', color: '#7FD6BE',
      line: '말랑한 민트 찹쌀떡. 발이 작은 바퀴라서 걸으면 데굴데굴 굴러간다.',
      points: ['몸 하나에 짧은 팔다리, 점 눈의 치이카와식 비율', '머리 위 새싹 전구가 깨우기 단계마다 밝아짐', '자동차 흔적은 바퀴 발 두 개뿐'],
      build(c) {
        const E = { x: 0, y: 0, z: 0, rx: 104, ry: 90, rz: 98 };
        const body = c.grad(['#E4FAF2', '#9FE3CF', '#5FBFA5']);
        ellBody(c, E, body);
        // 귀: 둥근 귀 두 개, 앞에서 보이면 안쪽 분홍
        for (const s of [-1, 1]) {
          const ear = onEll(E, 34 * s, 58, -6), q = c.P(ear.p), nz = c.N(ear.n);
          const inner = nz > .25 ? `<ellipse cx="${q.x.toFixed(1)}" cy="${(q.y + 2).toFixed(1)}" rx="${(10 * nz).toFixed(1)}" ry="11" fill="#FFB7C2"/>` : '';
          c.items.push({ z: (q.z > -10 ? -400 : -600) + q.z, svg: `<ellipse cx="${q.x.toFixed(1)}" cy="${q.y.toFixed(1)}" rx="24" ry="26" fill="${body}"/>${inner}` });
        }
        // 새싹 전구
        const base = c.P([0, E.ry - 4, -6]), top = c.P([0, E.ry + 34, -14]), bulb = c.grad(bulbColor(c.expr));
        const glow = c.expr === 'awake' || c.expr === 'happy' ? `<circle cx="${top.x}" cy="${top.y}" r="22" fill="#FFE066" opacity=".25"/>` : '';
        c.items.push({ z: -300, svg: `<path d="M${base.x} ${base.y} Q${base.x + 8} ${(base.y + top.y) / 2} ${top.x} ${top.y}" stroke="#4FA88F" stroke-width="5" fill="none" stroke-linecap="round"/>${glow}<circle cx="${top.x}" cy="${top.y}" r="12" fill="${bulb}"/>` });
        // 팔
        for (const s of [-1, 1]) { const a = onEll(E, 84 * s, -22, 4); blob(c, a.p, 15, 12, body); }
        // 바퀴 발
        for (const s of [-1, 1]) {
          const q = c.P([40 * s, -E.ry + 6, 26]);
          const w = Math.max(.35, Math.abs(Math.cos(c.yaw * Math.PI / 180)));
          c.items.push({ z: 400 + q.z, svg: `<g transform="translate(${q.x.toFixed(1)} ${q.y.toFixed(1)})"><ellipse rx="27" ry="17" fill="#3A4448"/><ellipse rx="${(11 * w + 4).toFixed(1)}" ry="8" fill="#DDE6E8"/><ellipse rx="${(4 * w + 1).toFixed(1)}" ry="3" fill="#9FE3CF"/></g>` });
        }
        // 꼬리
        const tail = onEll(E, 180, -38, 6); blob(c, tail.p, 15, 14, body, '', -50);
        face(c, E, { eyeLon: 21, eyeLat: 4, blushLon: 38, blushLat: -12, mouthLat: -14 });
      },
    },

    poksin: {
      name: '폭신', tag: 'B안', color: '#E9D3AE',
      line: '운전석 머리받침이 살아난 아이. 낮잠 내내 머리를 받쳐 주다가 시간이 되면 깨운다.',
      points: ['차 안에서만 만날 수 있는 소품이라 앱 주제와 바로 이어짐', '박음질 선과 은색 다리 두 개가 특징', '네모난 몸이라 3D에서 실루엣이 가장 또렷함'],
      build(c) {
        const w = 220, h = 150, d = 96, cy = 22, r = 62;
        const yr = c.yaw * Math.PI / 180, co = Math.cos(yr), si = Math.sin(yr);
        const W = w * Math.abs(co) + d * Math.abs(si);
        const fill = c.grad(['#FFF9EE', '#F1E0C2', '#CDB48B']);
        const cx = 150;
        // 은색 다리
        for (const s of [-1, 1]) {
          const top = c.P([48 * s, cy - h / 2 + 10, 0]), bot = c.P([48 * s, cy - h / 2 - 48, 0]);
          const metal = c.grad(['#FFFFFF', '#C9D0D6', '#8A949C'], 'metal');
          c.items.push({ z: -200 + top.z, svg: `<rect x="${(top.x - 7).toFixed(1)}" y="${top.y}" width="14" height="${bot.y - top.y}" rx="7" fill="${metal}"/><ellipse cx="${bot.x.toFixed(1)}" cy="${bot.y}" rx="17" ry="9" fill="#3A4448"/>` });
        }
        const topY = 170 - (cy + h / 2);
        c.items.push({ z: 0, svg: `<rect x="${cx - W / 2}" y="${topY}" width="${W}" height="${h}" rx="${r}" fill="${fill}"/>` });
        // 박음질 선: 보이는 면마다
        const seam = (fx, fw, op) => fw > 30 ? `<rect x="${(fx - fw / 2 + 14).toFixed(1)}" y="${topY + 14}" width="${(fw - 28).toFixed(1)}" height="${h - 28}" rx="${Math.min(r - 12, (fw - 28) / 2).toFixed(1)}" fill="none" stroke="#B89A6C" stroke-width="2.4" stroke-dasharray="7 6" opacity="${op}"/>` : '';
        const frontX = cx + (d / 2) * si, frontW = w * Math.abs(co);
        c.items.push({ z: 10, svg: seam(frontX, frontW, co > 0 ? .9 : .7) });
        // 뒷면 이름표
        if (co < -.2) c.items.push({ z: 20, svg: `<rect x="${(frontX - 22 * Math.abs(co)).toFixed(1)}" y="${topY + h - 46}" width="${(44 * Math.abs(co)).toFixed(1)}" height="22" rx="5" fill="#7FD6BE"/><text x="${frontX.toFixed(1)}" y="${topY + h - 30}" font-size="13" font-weight="800" text-anchor="middle" fill="#fff" font-family="Asta Sans, Pretendard, sans-serif" transform="translate(${frontX.toFixed(1)} 0) scale(${Math.abs(co).toFixed(2)} 1) translate(${(-frontX).toFixed(1)} 0)">ZZ</text>` });
        // 팔
        for (const s of [-1, 1]) blob(c, [(w / 2 + 2) * s, cy - 28, 16], 16, 13, fill);
        // 얼굴: 앞면 평면 위
        if (co > .08) {
          const F = (u, v) => c.P([u, cy + v, d / 2 + 4]);
          const f = Math.max(.2, co);
          const add = (z, svg) => c.items.push({ z: 1000 + z, svg });
          for (const s of [-1, 1]) { const e = F(30 * s, 8); add(1, Rig.eye(e.x, e.y, f, c.expr, 1.05)); const b = F(54 * s, -10); add(0, Rig.blush(b.x, b.y, f)); }
          const m = F(0, -10); add(1, Rig.mouth(m.x, m.y, f, c.expr));
        }
      },
    },

    ppangppi: {
      name: '빵삐', tag: 'C안', color: '#FFD45C',
      line: '아침을 알리는 동그란 병아리. 가슴의 경적 단추를 누르면 "빵!" 하고 운다.',
      points: ['아침 알람이라는 앱 역할을 몸으로 설명함', '머리 깃털 세 가닥이 기상 단계마다 하나씩 섬', '가장 단순해서 작은 아이콘으로도 알아보기 쉬움'],
      build(c) {
        const E = { x: 0, y: 0, z: 0, rx: 100, ry: 96, rz: 96 };
        const body = c.grad(['#FFF6CF', '#FFD45C', '#E9A92A']);
        ellBody(c, E, body);
        // 머리 깃털: 단계에 따라 서는 개수
        const up = { sleep: 0, drowsy: 1, awake: 2, happy: 3 }[c.expr];
        [-1, 0, 1].forEach((k, i) => {
          const base = c.P([k * 12, E.ry - 6, 0]), stand = i < up || (up === 2 && i === 1) ? 1 : 0;
          const ang = (k * 28 + (stand ? 0 : 50 + k * 10));
          c.items.push({ z: -300 + base.z, svg: `<path transform="translate(${base.x.toFixed(1)} ${base.y.toFixed(1)}) rotate(${ang})" d="M0 0 C-9 -14 -6 -30 0 -36 C6 -30 9 -14 0 0Z" fill="#F5BC3A"/>` });
        });
        // 날개
        for (const s of [-1, 1]) { const a = onEll(E, 86 * s, -10, 2); blob(c, a.p, 18, 28, body); }
        // 발
        for (const s of [-1, 1]) { const q = c.P([30 * s, -E.ry + 4, 22]); c.items.push({ z: 400 + q.z, svg: `<path transform="translate(${q.x.toFixed(1)} ${q.y.toFixed(1)})" d="M-14 6 Q-12 -6 0 -6 Q12 -6 14 6 Q0 10 -14 6Z" fill="#F08A3C"/>` }); }
        // 꼬리 깃
        const tail = onEll(E, 180, -20, 4); const tq = c.P(tail.p);
        c.items.push({ z: (tq.z > 0 ? 500 : -500) + tq.z, svg: `<path transform="translate(${tq.x.toFixed(1)} ${tq.y.toFixed(1)})" d="M-14 0 L0 -26 L14 0 Z" fill="#E9A92A" stroke="#E9A92A" stroke-width="8" stroke-linejoin="round"/>` });
        // 부리: 몸에서 튀어나오게
        const bk = onEll(E, 0, -6, 6), bn = c.N(bk.n);
        if (bn > .05) { const q = c.P(bk.p); c.items.push({ z: 1100, svg: `<path transform="translate(${q.x.toFixed(1)} ${q.y.toFixed(1)}) scale(${Math.max(.45, bn).toFixed(2)} 1)" d="M-11 -3 Q0 -9 11 -3 Q0 9 -11 -3Z" fill="#F08A3C"/>` }); }
        // 경적 단추
        const hb = onEll(E, 0, -46, 1), hn = c.N(hb.n);
        if (hn > .1) { const q = c.P(hb.p); c.items.push({ z: 1000, svg: `<g transform="translate(${q.x.toFixed(1)} ${q.y.toFixed(1)}) scale(${hn.toFixed(2)} ${(.6 + .4 * hn).toFixed(2)})"><circle r="17" fill="#7FD6BE"/><circle r="12" fill="#A8E8D6"/><path d="M-5 -3 L1 -3 L6 -7 L6 7 L1 3 L-5 3Z" fill="#2E7D6A"/></g>` }); }
        face(c, E, { eyeLon: 26, eyeLat: 12, blushLon: 44, blushLat: -4, mouthLat: -6, noMouth: true });
      },
    },

    mongsil: {
      name: '몽실', tag: 'D안', color: '#C9CFF5',
      line: '좌석 위에 내려앉은 낮잠 구름. 안전띠를 매고 둥실 떠서 잠을 지켜 준다.',
      points: ['수면 앱의 포근함을 가장 직접 보여 줌', '안전띠 한 줄로 차 안 캐릭터임을 표시', '몸이 뭉게뭉게라서 3D에서 말랑한 움직임이 잘 어울림'],
      float: true,
      build(c) {
        const E = { x: 0, y: -4, z: 0, rx: 100, ry: 78, rz: 86 };
        const fill = c.grad(['#FFFFFF', '#E6E9FF', '#AEB6EA']);
        // 뭉게 덩이: 몸 뒤 둘레에만 붙여서 어느 각도에서도 얼굴 쪽 몸통이 깨끗하게 보이게 한다
        const lumps = [[-66, 34, -10, 48], [66, 34, -10, 48], [0, 56, -16, 52], [-36, 56, -36, 42], [36, 56, -36, 42], [-84, -6, -16, 40], [84, -6, -16, 40], [0, 24, -62, 52], [-50, 10, -56, 44], [50, 10, -56, 44]];
        lumps.forEach(([x, y, z, r]) => { const q = c.P([x, y, z]); c.items.push({ z: -1 + q.z * .001, svg: `<circle cx="${q.x.toFixed(1)}" cy="${q.y.toFixed(1)}" r="${r}" fill="${fill}"/>` }); });
        ellBody(c, E, fill, 0.2);
        // 안전띠: 왼쪽 옆구리에서 오른쪽 아래로 비스듬히 감는 고리. 얼굴(눈, 입) 아래로 지나가도록 몸 중심보다 낮은 평면에 둔다.
        const nb = Math.atan2(.35, 1), n = [Math.sin(nb), Math.cos(nb), 0], k = -.55 * Math.cos(nb), rho = Math.sqrt(1 - k * k);
        const e1 = [Math.cos(nb), -Math.sin(nb), 0], ring = t => [0, 1, 2].map(i => k * n[i] + rho * (Math.cos(t) * e1[i] + Math.sin(t) * (i === 2 ? 1 : 0)));
        const pts = [];
        for (let i = 0; i <= 96; i++) {
          const u = ring(i / 96 * Math.PI * 2);
          pts.push({ q: c.P([E.x + u[0] * E.rx * 1.03, E.y + u[1] * E.ry * 1.03, E.z + u[2] * E.rz * 1.03]), nz: c.N([u[0] / E.rx, u[1] / E.ry, u[2] / E.rz]) });
        }
        let path = '', on = false;
        pts.forEach(({ q, nz }) => { if (nz > 0) { path += `${on ? 'L' : 'M'}${q.x.toFixed(1)} ${q.y.toFixed(1)}`; on = true; } else on = false; });
        c.items.push({ z: 900, svg: `<path d="${path}" fill="none" stroke="#6F80B8" stroke-width="15" stroke-linecap="round" stroke-linejoin="round"/><path d="${path}" fill="none" stroke="#8C9BD0" stroke-width="5" stroke-dasharray="2 9" stroke-linecap="round"/>` });
        // 버클: 고리의 앞쪽 가운데
        const bu = ring(Math.PI / 2 + .25), bq = c.P([bu[0] * E.rx * 1.05, E.y + bu[1] * E.ry * 1.05, bu[2] * E.rz * 1.05]), bv = [bu[0] / E.rx, bu[1] / E.ry, bu[2] / E.rz], bl = Math.hypot(...bv), bn = c.N(bv.map(v => v / bl)) * 1.4;
        if (bn > .12) { const w = Math.min(1, bn); c.items.push({ z: 950, svg: `<rect x="${(bq.x - 14 * w).toFixed(1)}" y="${(bq.y - 11).toFixed(1)}" width="${(28 * w).toFixed(1)}" height="22" rx="6" fill="#E9EDF2" stroke="#9AA6B2" stroke-width="2"/><rect x="${(bq.x - 6 * w).toFixed(1)}" y="${(bq.y - 4).toFixed(1)}" width="${(12 * w).toFixed(1)}" height="8" rx="2" fill="#B9C3CC"/>` }); }
        // 작은 팔
        for (const s of [-1, 1]) { const p = onEll(E, 82 * s, -30, 2); blob(c, p.p, 15, 12, fill); }
        // 달 머리핀
        const pin = c.P([-40, 84, 14]);
        c.items.push({ z: (pin.z > 0 ? 960 : -600), svg: `<path transform="translate(${pin.x.toFixed(1)} ${pin.y.toFixed(1)}) rotate(-20)" d="M6 -12 A13 13 0 1 0 6 12 A10 10 0 1 1 6 -12Z" fill="#FFD45C"/>` });
        face(c, E, { eyeLon: 22, eyeLat: 6, blushLon: 40, blushLat: -10, mouthLat: -12 });
      },
    },
  };
  window.CHARS = CHARS;
})();
