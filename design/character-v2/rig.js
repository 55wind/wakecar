// 2D 시안용 간이 3D 장치: 몸을 타원체(또는 둥근 상자)로 두고, 눈과 팔다리를 몸 표면 좌표에 붙여
// 회전각(yaw)에 따라 투영한다. 같은 설계가 3D 모델로 그대로 옮겨지도록 치수는 모두 3D 좌표로 적는다.
(() => {
  const D = Math.PI / 180;
  const rot = (p, yaw) => { const c = Math.cos(yaw * D), s = Math.sin(yaw * D); return [p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c]; };
  const add = (a, b, k = 1) => [a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k];
  function onEll(E, lon, lat, lift = 0) {
    const lo = lon * D, la = lat * D;
    const n = [Math.sin(lo) * Math.cos(la), Math.sin(la), Math.cos(lo) * Math.cos(la)];
    const p = [E.x + E.rx * n[0], E.y + E.ry * n[1], E.z + E.rz * n[2]];
    const nn = [n[0] / E.rx, n[1] / E.ry, n[2] / E.rz], L = Math.hypot(...nn);
    const normal = nn.map(v => v / L);
    return { p: add(p, normal, lift), n: normal };
  }
  let uid = 0;
  function grad(defs, c, kind = 'ball') {
    const id = 'g' + (++uid);
    defs.push(kind === 'metal'
      ? `<linearGradient id="${id}" x1="0" x2="1"><stop offset="0" stop-color="${c[2]}"/><stop offset=".35" stop-color="${c[0]}"/><stop offset=".6" stop-color="${c[1]}"/><stop offset="1" stop-color="${c[2]}"/></linearGradient>`
      : `<radialGradient id="${id}" cx=".38" cy=".3" r=".78"><stop offset="0" stop-color="${c[0]}"/><stop offset=".55" stop-color="${c[1]}"/><stop offset="1" stop-color="${c[2]}"/></radialGradient>`);
    return `url(#${id})`;
  }

  // 표정 부품. f = 가로 단축(정면 1, 옆으로 갈수록 0)
  function eye(x, y, f, expr, s = 1, ink = '#2B2522') {
    const t = `translate(${x.toFixed(1)} ${y.toFixed(1)}) scale(${(f * s).toFixed(3)} ${s})`;
    if (expr === 'sleep') return `<path transform="${t}" d="M-9 -1 Q0 7 9 -1" fill="none" stroke="${ink}" stroke-width="3.4" stroke-linecap="round"/>`;
    if (expr === 'happy') return `<path transform="${t}" d="M-8 4 Q0 -8 8 4" fill="none" stroke="${ink}" stroke-width="3.6" stroke-linecap="round"/>`;
    if (expr === 'drowsy') return `<g transform="${t}"><path d="M-7.5 0 A7.5 7.5 0 0 0 7.5 0 Z" fill="${ink}"/><path d="M-9 -0.5 L9 -0.5" stroke="${ink}" stroke-width="3" stroke-linecap="round"/></g>`;
    return `<g transform="${t}"><ellipse rx="7.5" ry="10" fill="${ink}"/><circle cx="-2.4" cy="-4" r="2.9" fill="#fff"/><circle cx="2.6" cy="3.6" r="1.3" fill="#fff" opacity=".8"/></g>`;
  }
  function mouth(x, y, f, expr, s = 1, ink = '#2B2522') {
    const t = `translate(${x.toFixed(1)} ${y.toFixed(1)}) scale(${(f * s).toFixed(3)} ${s})`;
    if (expr === 'happy') return `<g transform="${t}"><path d="M-9 -2 Q0 -2 9 -2 Q8 11 0 11 Q-8 11 -9 -2Z" fill="#7A3B3B"/><path d="M-5 6 Q0 3 5 6 Q3 10 0 10 Q-3 10 -5 6Z" fill="#F08A8A"/></g>`;
    if (expr === 'sleep') return `<ellipse transform="${t}" rx="3.2" ry="3.8" fill="#7A3B3B"/>`;
    if (expr === 'drowsy') return `<path transform="${t}" d="M-6 1 Q-3 -2 0 1 Q3 4 6 1" fill="none" stroke="${ink}" stroke-width="2.6" stroke-linecap="round"/>`;
    return `<path transform="${t}" d="M-7 -1 Q-3.5 4 0 0 Q3.5 4 7 -1" fill="none" stroke="${ink}" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>`;
  }
  const blush = (x, y, f, s = 1) => `<ellipse cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" rx="${(13 * f * s).toFixed(1)}" ry="${7 * s}" fill="#FF8FA3" opacity=".5"/>`;
  const zzz = (x, y) => `<g font-family="Asta Sans, Pretendard, sans-serif" font-weight="800" fill="#8EA2D8"><text x="${x}" y="${y}" font-size="22">Z</text><text x="${x + 18}" y="${y - 18}" font-size="16">z</text><text x="${x + 31}" y="${y - 32}" font-size="12">z</text></g>`;

  // 장면 조립: items = [{z, svg}], body 앞뒤 정렬
  function sceneSVG({ yaw, expr, build, size = 300, cy = 170, bg = 'none', shadowY = 268, shadowRx = 90, float = false }) {
    const defs = [], items = [];
    const P = p => { const r = rot(p, yaw); return { x: size / 2 + r[0], y: cy - r[1], z: r[2] }; };
    const N = n => rot(n, yaw)[2];
    build({ P, N, rot: p => rot(p, yaw), onEll, defs, items, grad: (c, k) => grad(defs, c, k), yaw, expr, eye, mouth, blush, add });
    items.sort((a, b) => a.z - b.z);
    const sh = `<ellipse cx="${size / 2}" cy="${shadowY}" rx="${shadowRx * (float ? .7 : 1)}" ry="${float ? 9 : 13}" fill="#1D2240" opacity="${float ? .12 : .18}"/>`;
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}"><defs>${defs.join('')}</defs>${bg !== 'none' ? `<rect width="${size}" height="${size}" fill="${bg}"/>` : ''}${sh}${items.map(i => i.svg).join('')}${expr === 'sleep' ? zzz(size / 2 + 70, 70) : ''}</svg>`;
  }

  // 공통: 표면 부품(눈 코 입 볼). 법선이 화면 쪽일 때만 그린다.
  function face(ctx, E, o) {
    const { P, N, items, onEll, expr } = ctx;
    const put = (lon, lat, fn, zb = 1000) => { const s = onEll(E, lon, lat, 1); const nz = N(s.n); if (nz < .08) return; const q = P(s.p); items.push({ z: zb + q.z, svg: fn(q.x, q.y, Math.max(.2, nz)) }); };
    put(-o.eyeLon, o.eyeLat, (x, y, f) => eye(x, y, f, expr, o.eyeScale || 1));
    put(o.eyeLon, o.eyeLat, (x, y, f) => eye(x, y, f, expr, o.eyeScale || 1));
    put(-o.blushLon, o.blushLat, (x, y, f) => blush(x, y, f, o.blushScale || 1), 999);
    put(o.blushLon, o.blushLat, (x, y, f) => blush(x, y, f, o.blushScale || 1), 999);
    if (!o.noMouth) put(0, o.mouthLat, (x, y, f) => mouth(x, y, f, expr, o.mouthScale || 1));
  }
  function ellBody(ctx, E, fill, z = 0) {
    const { yaw } = ctx, c = Math.cos(yaw * D), s = Math.sin(yaw * D);
    const rx = Math.hypot(E.rx * c, E.rz * s), q = ctx.P([E.x, E.y, E.z]);
    ctx.items.push({ z: z + q.z * .001, svg: `<ellipse cx="${q.x.toFixed(1)}" cy="${q.y.toFixed(1)}" rx="${rx.toFixed(1)}" ry="${E.ry}" fill="${fill}"/>` });
  }
  // 몸 밖 부품(팔, 귀, 발): 중심 z로 앞뒤를 가른다. 몸 중심보다 뒤면 몸 뒤에 깔린다.
  function blob(ctx, p, rx, ry, fill, extra = '', bias = 0) {
    const q = ctx.P(p);
    ctx.items.push({ z: (q.z > 0 ? 500 : -500) + q.z + bias, svg: `<ellipse cx="${q.x.toFixed(1)}" cy="${q.y.toFixed(1)}" rx="${rx}" ry="${ry}" fill="${fill}"/>${extra}` });
    return q;
  }

  window.Rig = { sceneSVG, face, ellBody, blob, onEll, rot, D, eye, mouth, blush };
})();
