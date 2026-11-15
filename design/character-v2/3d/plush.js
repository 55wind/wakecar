// 일어나차 캐릭터 3D 모델(인형 질감). 2D 시안(../characters.js)의 치수를 1/100로 옮겼다.
// 질감: 봉제 원단(벨벳 sheen + 가장자리 보풀 빛 + 고운 결 노멀 + 얼룩) + 봉제선 오므림, 눈은 유광 구슬, 입과 감은 눈은 자수 실.
import * as THREE from 'three';

const TAU = Math.PI * 2, D = Math.PI / 180;

// ------------------------------------------------------------ 원단 텍스처
function canvasTex(size, draw, { repeat = 1, color = false } = {}) {
  const c = document.createElement('canvas'); c.width = c.height = size;
  draw(c.getContext('2d'), size);
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(repeat, repeat);
  if (color) t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  return t;
}
let rngSeed = 7;
const rand = () => { rngSeed = (rngSeed * 16807) % 2147483647; return (rngSeed - 1) / 2147483646; };
// 원단 결: 잔물결 높이 -> 노멀
const fabricNormal = canvasTex(256, (g, n) => {
  const h = new Float32Array(n * n);
  for (let i = 0; i < n * n; i++) h[i] = rand();
  for (let pass = 0; pass < 2; pass++) for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const k = y * n + x; h[k] = (h[k] * 2 + h[y * n + (x + 1) % n] + h[((y + 1) % n) * n + x]) / 4;
  }
  const img = g.createImageData(n, n);
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const dx = h[y * n + (x + 1) % n] - h[y * n + (x + n - 1) % n], dy = h[((y + 1) % n) * n + x] - h[((y + n - 1) % n) * n + x];
    const k = (y * n + x) * 4; img.data[k] = 128 + dx * 220; img.data[k + 1] = 128 + dy * 220; img.data[k + 2] = 255; img.data[k + 3] = 255;
  }
  g.putImageData(img, 0, 0);
}, { repeat: 6 });
// 안전띠 직물: 가로 줄무늬 노멀
const webbingNormal = canvasTex(64, (g, n) => {
  for (let y = 0; y < n; y++) { const v = 128 + Math.sin(y / n * TAU * 8) * 70; g.fillStyle = `rgb(128 ${v | 0} 255)`; g.fillRect(0, y, n, 1); }
}, { repeat: 1 });

// ------------------------------------------------------------ 재질
const tint = (hex, k) => new THREE.Color(hex).lerp(new THREE.Color('#ffffff'), k);
// 원단 얼룩: 부드럽게 흐린 잡음(밍크 원단의 미세한 결 차이). 밉맵을 써서 화면에서 거칠게 보이지 않게 한다.
const mottle = canvasTex(256, (g, n) => {
  const img = g.createImageData(n, n), h = new Float32Array(n * n);
  for (let i = 0; i < n * n; i++) h[i] = rand();
  for (let pass = 0; pass < 6; pass++) { const c = h.slice(); for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) h[y * n + x] = (c[y * n + x] * 4 + c[y * n + (x + 1) % n] + c[y * n + (x + n - 1) % n] + c[((y + 1) % n) * n + x] + c[((y + n - 1) % n) * n + x]) / 8; }
  let lo = 1, hi = 0; for (const v of h) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
  for (let i = 0; i < n * n; i++) { const v = 238 + ((h[i] - lo) / (hi - lo)) * 17; img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = v; img.data[i * 4 + 3] = 255; }
  g.putImageData(img, 0, 0);
}, { repeat: 2, color: true });
// 봉제 원단: 털 껍질 대신 벨벳 광택(sheen) + 가장자리 보풀 빛(시선과 비스듬한 면을 부드럽게 밝힘) + 고운 결 노멀
export function plush(hex, { sheen = 1, rim = .55 } = {}) {
  const m = new THREE.MeshPhysicalMaterial({
    color: new THREE.Color(hex), map: mottle, roughness: 1, metalness: 0,
    sheen, sheenRoughness: .62, sheenColor: tint(hex, .5),
    normalMap: fabricNormal, normalScale: new THREE.Vector2(.18, .18),
  });
  m.onBeforeCompile = sh => {
    sh.fragmentShader = sh.fragmentShader.replace('#include <opaque_fragment>', `
      float fuzz = pow(1.0 - clamp(abs(dot(normalize(normal), normalize(vViewPosition))), 0.0, 1.0), 2.2);
      outgoingLight = mix(outgoingLight, outgoingLight * 1.08 + vec3(0.07, 0.065, 0.06), fuzz * ${rim.toFixed(2)});
      #include <opaque_fragment>`);
  };
  m.customProgramCacheKey = () => 'plush' + rim;
  m.userData.hex = hex;
  return m;
}
const felt = hex => { const m = plush(hex, { sheen: .5, rim: .25 }); m.normalScale.set(.5, .5); return m; };
const bead = () => new THREE.MeshPhysicalMaterial({ color: '#15110F', roughness: .12, metalness: 0, clearcoat: 1, clearcoatRoughness: .05 });
const thread = hex => new THREE.MeshStandardMaterial({ color: hex, roughness: 1 });
const chrome = () => new THREE.MeshStandardMaterial({ color: '#E8ECEF', metalness: 1, roughness: .22 });
const rubber = () => new THREE.MeshStandardMaterial({ color: '#363C40', roughness: .7 });

function mesh(geo, mat, { shadow = true } = {}) {
  const m = new THREE.Mesh(geo, mat); m.castShadow = shadow; m.receiveShadow = true;
  return m;
}

// ------------------------------------------------------------ 표면 좌표
function ellipsoid(E) {
  const geo = new THREE.SphereGeometry(1, 160, 110); geo.scale(E.rx, E.ry, E.rz);
  if (E.seam !== false) {   // 봉제선: 앞판과 뒤판이 만나는 옆 둘레를 살짝 오므린다
    geo.computeVertexNormals();
    const pos = geo.attributes.position, nor = geo.attributes.normal;
    for (let i = 0; i < pos.count; i++) { const z = pos.getZ(i), k = .03 * Math.exp(-((z / .045) ** 2)); pos.setXYZ(i, pos.getX(i) - nor.getX(i) * k, pos.getY(i) - nor.getY(i) * k, z - nor.getZ(i) * k); }
  }
  geo.translate(E.x, E.y, E.z); geo.computeVertexNormals();
  // 표면 함수: 얼굴 좌표(u 가로, v 세로, 단위 같음) -> 점, 법선
  const at = (u0, v0, lift = 0) => {
    const u = u0 - E.x, v = v0 - E.y;
    const lon = Math.asin(THREE.MathUtils.clamp(u / (E.rx * Math.cos(Math.asin(THREE.MathUtils.clamp(v / E.ry, -.99, .99)))), -.99, .99));
    const lat = Math.asin(THREE.MathUtils.clamp(v / E.ry, -.99, .99));
    return ell(E, lon / D, lat / D, lift);
  };
  return { geo, at };
}
export function ell(E, lon, lat, lift = 0) {
  const lo = lon * D, la = lat * D;
  const u = new THREE.Vector3(Math.sin(lo) * Math.cos(la), Math.sin(la), Math.cos(lo) * Math.cos(la));
  const p = new THREE.Vector3(E.x + E.rx * u.x, E.y + E.ry * u.y, E.z + E.rz * u.z);
  const n = new THREE.Vector3(u.x / E.rx, u.y / E.ry, u.z / E.rz).normalize();
  return { p: p.addScaledVector(n, lift), n };
}
function place(obj, s, lift = 0) { obj.position.copy(s.p).addScaledVector(s.n, lift); obj.lookAt(obj.position.clone().add(s.n)); return obj; }
const FL = .012;   // 털 길이만큼 얼굴 부품을 띄운다
function threadLine(at, pts, mat, r = .016, lift = FL) {
  const curve = new THREE.CatmullRomCurve3(pts.map(([u, v]) => at(u, v, lift).p));
  return mesh(new THREE.TubeGeometry(curve, 32, r, 8, false), mat, { shadow: false });
}
function disc(r, depth, mat) { const g = new THREE.CylinderGeometry(r, r, depth, 40); g.rotateX(Math.PI / 2); return mesh(g, mat, { shadow: false }); }

// ------------------------------------------------------------ 얼굴 (표정 4종)
// o: { ex 눈 가로 위치, ey 눈 높이, er 눈 크기, my 입 높이, bx, by 볼 }
function face(group, at, o, expr) {
  const ink = thread('#2A1F1C');
  for (const s of [-1, 1]) {
    const u = s * o.ex, v = o.ey, r = o.er;
    if (expr === 'love') {
      // 하트 눈: 분홍 펠트 하트
      const sh = new THREE.Shape(); sh.moveTo(0, -.9); sh.bezierCurveTo(-1.6, .1, -.6, 1.3, 0, .45); sh.bezierCurveTo(.6, 1.3, 1.6, .1, 0, -.9);
      const hg = new THREE.ExtrudeGeometry(sh, { depth: .25, bevelEnabled: true, bevelThickness: .15, bevelSize: .12, bevelSegments: 3 }); hg.center();
      const h = new THREE.Mesh(hg, felt('#FF5C7A')); h.scale.setScalar(r * 1.05); place(h, at(u, v), FL * .7); group.add(h);
      const b = disc(1, .02, felt('#FF9DB0')); b.scale.set(o.br || .14, (o.br || .14) * .62, 1); place(b, at(s * o.bx, o.by), FL * .8); group.add(b);
      continue;
    }
    if (expr === 'surprise' || (expr === 'wink' && s === -1)) {
      const big = expr === 'surprise' ? 1.25 : 1;
      const e = new THREE.Mesh(new THREE.SphereGeometry(1, 32, 24), bead());
      e.scale.set(r * .85 * big, r * big, r * .5); place(e, at(u, v + (big - 1) * r * .3), FL * .6); group.add(e);
      const hl = new THREE.Mesh(new THREE.SphereGeometry(r * .26 * big, 16, 12), new THREE.MeshBasicMaterial({ color: '#ffffff' }));
      place(hl, at(u - r * .3, v + r * .45 * big), r * .42 + FL * .6); group.add(hl);
    } else if (expr === 'wink') {
      group.add(threadLine(at, [[u - r * 1.05, v - r * .4], [u - r * .45, v + r * .35], [u + r * .45, v + r * .35], [u + r * 1.05, v - r * .4]], ink, .019));
    } else if (expr === 'awake') {
      const e = new THREE.Mesh(new THREE.SphereGeometry(1, 32, 24), bead());
      e.scale.set(r * .78, r, r * .5); place(e, at(u, v), FL * .6); group.add(e);
      const hl = new THREE.Mesh(new THREE.SphereGeometry(r * .24, 16, 12), new THREE.MeshBasicMaterial({ color: '#ffffff' }));
      place(hl, at(u - r * .28, v + r * .38), r * .42 + FL * .6); group.add(hl);
    } else if (expr === 'drowsy') {
      const e = new THREE.Mesh(new THREE.SphereGeometry(1, 32, 24), bead());
      e.scale.set(r * .78, r * .42, r * .42); place(e, at(u, v - r * .35), FL * .6); group.add(e);
      group.add(threadLine(at, [[u - r * 1.05, v + r * .12], [u, v + r * .2], [u + r * 1.05, v + r * .12]], ink, .017));
    } else if (expr === 'sleep') {
      group.add(threadLine(at, [[u - r * 1.1, v], [u - r * .5, v - r * .55], [u + r * .5, v - r * .55], [u + r * 1.1, v]], ink, .018));
    } else {
      group.add(threadLine(at, [[u - r * 1.05, v - r * .4], [u - r * .45, v + r * .35], [u + r * .45, v + r * .35], [u + r * 1.05, v - r * .4]], ink, .019));
    }
    const b = disc(1, .02, felt('#FF9DB0')); b.scale.set(o.br || .14, (o.br || .14) * .62, 1); place(b, at(s * o.bx, o.by), FL * .8); group.add(b);
  }
  if (o.noMouth) return;
  const m = o.my, w = o.mw || .07;
  if (expr === 'awake') group.add(threadLine(at, [[-w, m + .015], [-w * .5, m - .025], [0, m + .005], [w * .5, m - .025], [w, m + .015]], ink, .013));
  else if (expr === 'drowsy') group.add(threadLine(at, [[-w * .8, m], [-w * .3, m + .015], [w * .3, m - .015], [w * .8, m]], ink, .012));
  else if (expr === 'sleep') { const d = disc(1, .02, felt('#7A3B3B')); d.scale.set(.034, .042, 1); place(d, at(0, m), FL); group.add(d); }
  else if (expr === 'surprise') { const d = disc(1, .02, felt('#7A3B3B')); d.scale.set(.05, .065, 1); place(d, at(0, m - .01), FL); group.add(d); }
  else {
    const g = new THREE.CircleGeometry(1, 40, Math.PI, Math.PI); const d = new THREE.Mesh(g, felt('#7A3B3B'));
    d.scale.set(w * 1.3, w * 1.4, 1); place(d, at(0, m + .03), FL + .006); group.add(d);
    const t = new THREE.Mesh(new THREE.CircleGeometry(1, 32, Math.PI, Math.PI), felt('#F08A8A'));
    t.scale.set(w * .7, w * .55, 1); place(t, at(0, m - .02), FL + .01); group.add(t);
  }
}

// ------------------------------------------------------------ 캐릭터 4종
const BUILD = {
  // A 콩콩: 민트 찹쌀떡, 바퀴 발, 새싹 전구
  kongkong(expr) {
    const g = new THREE.Group(), E = { x: 0, y: 1.2, z: 0, rx: 1.04, ry: .9, rz: .98 };
    const mint = plush('#86D9C0'), body = ellipsoid(E);
    g.add(mesh(body.geo, mint));
    for (const s of [-1, 1]) {
      const p = ell(E, 34 * s, 58, -.05), ear = mesh(new THREE.SphereGeometry(.24, 48, 32), mint); ear.position.copy(p.p); ear.scale.set(1, 1.05, .8); ear.lookAt(p.p.clone().add(p.n)); g.add(ear);
      const inner = disc(.12, .02, felt('#FFB3C0')); inner.position.copy(p.p).addScaledVector(p.n, .185); inner.lookAt(inner.position.clone().add(p.n)); inner.scale.set(1, 1.15, 1); g.add(inner);
      const arm = mesh(new THREE.SphereGeometry(.16, 32, 24), mint); arm.position.copy(ell(E, 84 * s, -22, .02).p); arm.scale.set(.9, .78, 1); g.add(arm);
      const wheel = mesh(new THREE.TorusGeometry(.15, .075, 20, 40), rubber()); wheel.rotation.y = Math.PI / 2; wheel.position.set(.4 * s, .23, .25); g.add(wheel);
      const hub = mesh(new THREE.CylinderGeometry(.1, .1, .12, 32), felt('#F4F8F7')); hub.rotation.z = Math.PI / 2; hub.position.copy(wheel.position); g.add(hub);
      const cap = mesh(new THREE.CylinderGeometry(.04, .04, .13, 20), felt('#86D9C0')); cap.rotation.z = Math.PI / 2; cap.position.copy(wheel.position); g.add(cap);
    }
    const tail = mesh(new THREE.SphereGeometry(.15, 32, 24), mint); tail.position.copy(ell(E, 180, -38, .05).p); g.add(tail);
    // 새싹 줄기 + 잎 + 전구
    const stem = new THREE.CatmullRomCurve3([new THREE.Vector3(0, 2.05, -.04), new THREE.Vector3(.04, 2.22, -.08), new THREE.Vector3(.02, 2.42, -.12)]);
    g.add(mesh(new THREE.TubeGeometry(stem, 20, .03, 10), felt('#4FA88F')));
    const leaf = mesh(new THREE.SphereGeometry(.1, 24, 16), felt('#6CC79B')); leaf.scale.set(1.3, .35, .7); leaf.position.set(.12, 2.24, -.08); leaf.rotation.z = .5; g.add(leaf);
    const glow = { sleep: 0, drowsy: .5, awake: 1.4, happy: 1.6 }[expr];
    const bulb = new THREE.Mesh(new THREE.SphereGeometry(.13, 40, 28), new THREE.MeshPhysicalMaterial({
      color: expr === 'sleep' ? '#DCE3E8' : '#FFE27A', emissive: '#FFC93C', emissiveIntensity: glow, roughness: .25, clearcoat: 1, transmission: 0 }));
    bulb.position.set(.02, 2.52, -.12); bulb.castShadow = true; g.add(bulb);
    if (glow > 0) { const l = new THREE.PointLight('#FFD86B', glow * .9, 1.6, 2); l.position.copy(bulb.position); g.add(l); }
    face(g, body.at, { ex: .26, ey: 1.24, er: .085, my: 1.06, bx: .44, by: 1.1, br: .13 }, expr);
    return g;
  },

  // B 폭신: 운전석 머리받침
  poksin(expr) {
    const g = new THREE.Group(), W = 1.1, H = .76, Dp = .5, cy = 1.32, e = .42;
    const cream = plush('#EBD8B5');
    const geo = new THREE.SphereGeometry(1, 128, 96), pos = geo.attributes.position, v = new THREE.Vector3();
    const map = (x, ext) => Math.sign(x) * Math.pow(Math.abs(x), e) * ext;
    for (let i = 0; i < pos.count; i++) { v.fromBufferAttribute(pos, i); pos.setXYZ(i, map(v.x, W), map(v.y, H) + cy, map(v.z, Dp)); }
    geo.computeVertexNormals();
    g.add(mesh(geo, cream));
    // 앞면 표면 함수
    const at = (u, vv, lift = 0) => {
      const P = (x, y) => { const vx = Math.sign(x) * Math.pow(Math.min(.999, Math.abs(x) / W), 1 / e), vy = Math.sign(y) * Math.pow(Math.min(.999, Math.abs(y - cy) / H), 1 / e);
        const vz = Math.sqrt(Math.max(0, 1 - vx * vx - vy * vy)); return new THREE.Vector3(x, y, Math.pow(vz, e) * Dp); };
      const p = P(u, vv), h = .01, du = P(u + h, vv).sub(P(u - h, vv)), dv = P(u, vv + h).sub(P(u, vv - h));
      const n = new THREE.Vector3().crossVectors(du, dv).normalize();
      return { p: p.addScaledVector(n, lift), n };
    };
    // 옆 띠 파이핑(민트)
    const ring = [];
    for (let i = 0; i <= 160; i++) { const t = i / 160 * TAU; ring.push(new THREE.Vector3(map(Math.cos(t), W) * 1.005, map(Math.sin(t), H) * 1.005 + cy, 0)); }
    g.add(mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(ring, true), 200, .035, 10, true), felt('#7FD6BE')));
    // 은색 다리와 고무 발
    for (const s of [-1, 1]) {
      const leg = mesh(new THREE.CylinderGeometry(.06, .06, .62, 32), chrome()); leg.position.set(.48 * s, .36, 0); g.add(leg);
      const foot = mesh(new THREE.SphereGeometry(.13, 32, 20), rubber()); foot.scale.set(1, .45, 1); foot.position.set(.48 * s, .06, 0); g.add(foot);
      const arm = mesh(new THREE.SphereGeometry(.15, 32, 24), cream); arm.position.set(1.1 * s, 1.08, .14); arm.scale.set(.85, .75, .9); g.add(arm);
    }
    // 뒤쪽 이름표
    const tag = mesh(new THREE.BoxGeometry(.36, .16, .02), felt('#7FD6BE')); tag.position.set(0, .86, -Dp * .86); tag.rotation.x = -.3; g.add(tag);
    face(g, at, { ex: .3, ey: 1.4, er: .088, my: 1.22, bx: .55, by: 1.24, br: .13 }, expr);
    return g;
  },

  // C 빵삐: 병아리, 가슴 경적 단추
  ppangppi(expr) {
    const g = new THREE.Group(), E = { x: 0, y: 1.02, z: 0, rx: 1, ry: .96, rz: .96 };
    const yellow = plush('#FFD45C'), orange = felt('#F08A3C'), body = ellipsoid(E);
    g.add(mesh(body.geo, yellow));
    const up = { sleep: 0, drowsy: 1, awake: 2, happy: 3 }[expr];
    [-1, 0, 1].forEach((k, i) => {
      const f = mesh(new THREE.CapsuleGeometry(.055, .24, 8, 16), plush('#F5BC3A', {}));
      const stand = up === 3 || (up === 2 && i !== 2) || (up === 1 && i === 1);
      f.geometry.translate(0, .16, 0);
      f.position.set(k * .1, 1.92, -.02);
      f.rotation.z = -k * .38 + (stand ? 0 : (k || 1) * 1.1); f.rotation.x = stand ? 0 : -.5;
      g.add(f);
    });
    for (const s of [-1, 1]) {
      const w = mesh(new THREE.SphereGeometry(.2, 32, 24), yellow); w.scale.set(.75, 1.3, 1); w.position.copy(ell(E, 86 * s, -10, .02).p); w.rotation.z = s * .25; g.add(w);
      const foot = mesh(new THREE.SphereGeometry(.15, 32, 20), orange); foot.scale.set(1, .4, 1.3); foot.position.set(.3 * s, .07, .28); g.add(foot);
    }
    const tail = mesh(new THREE.ConeGeometry(.16, .3, 24), plush('#E9A92A', {})); const tp = ell(E, 180, -18, .02); tail.position.copy(tp.p); tail.lookAt(tp.p.clone().add(tp.n)); tail.rotateX(Math.PI / 2); g.add(tail);
    // 부리가 곧 입: 윗부리와 아랫부리. 기분 좋음, 놀람, 반함, 윙크일 때 아랫부리가 벌어진다(따로 입을 달지 않는다).
    const open = { happy: .5, surprise: .65, love: .4, wink: .35 }[expr] || 0;
    const bp = ell(E, 0, -6, .02), beak = new THREE.Group(); beak.position.copy(bp.p); beak.lookAt(bp.p.clone().add(bp.n)); g.add(beak);
    const half = up => { const geo = new THREE.ConeGeometry(.11, .2, 24, 1, false, up ? 0 : Math.PI, Math.PI); geo.rotateX(Math.PI / 2); geo.translate(0, 0, .1); return mesh(geo, up ? orange : felt('#E57A2E')); };
    const upper = half(true), lower = half(false); upper.scale.set(1.15, .9, 1); lower.scale.set(1.05, .8, .9);
    upper.rotation.x = -open * .25; lower.rotation.x = open * 1.25; beak.add(upper, lower);
    if (open) { const inside = new THREE.Mesh(new THREE.CircleGeometry(.07, 24), felt('#B5452E')); inside.position.set(0, -.005, .035); beak.add(inside); }
    // 경적 단추
    const hb = ell(E, 0, -46, 0);
    const h1 = disc(.17, .05, felt('#7FD6BE')); place(h1, hb, .045); g.add(h1);
    const h2 = disc(.12, .05, felt('#A8E8D6')); place(h2, hb, .07); g.add(h2);
    const h3 = disc(.05, .04, felt('#2E7D6A')); place(h3, hb, .095); g.add(h3);
    face(g, body.at, { ex: .3, ey: 1.24, er: .085, my: 0, bx: .48, by: 1.0, br: .13, noMouth: true }, expr);
    return g;
  },

  // D 몽실: 낮잠 구름, 안전띠
  mongsil(expr) {
    const g = new THREE.Group(), E = { x: 0, y: 1.3, z: 0, rx: 1, ry: .78, rz: .86, seam: false };
    const cloud = plush('#E9EBFF'), body = ellipsoid(E);
    g.add(mesh(body.geo, cloud));
    [[-.66, .34, -.1, .48], [.66, .34, -.1, .48], [0, .56, -.16, .52], [-.36, .56, -.36, .42], [.36, .56, -.36, .42], [-.84, -.06, -.16, .4], [.84, -.06, -.16, .4], [0, .24, -.62, .52], [-.5, .1, -.56, .44], [.5, .1, -.56, .44]]
      .forEach(([x, y, z, r]) => { const l = mesh(new THREE.SphereGeometry(r, 48, 32), cloud); l.position.set(x, E.y + y, z); g.add(l); });
    // 안전띠: 몸통을 비스듬히 감는 띠(납작한 리본)
    const nb = Math.atan2(.35, 1), n = new THREE.Vector3(Math.sin(nb), Math.cos(nb), 0), k = -.55 * Math.cos(nb), rho = Math.sqrt(1 - k * k);
    const e1 = new THREE.Vector3(Math.cos(nb), -Math.sin(nb), 0), e2 = new THREE.Vector3(0, 0, 1);
    const ringPt = t => n.clone().multiplyScalar(k).addScaledVector(e1, rho * Math.cos(t)).addScaledVector(e2, rho * Math.sin(t));
    const N = 200, pos = [], idx = [], uv = [], halfW = .085;
    for (let i = 0; i <= N; i++) {
      const t = i / N * TAU, u = ringPt(t), un = ringPt(t + .01);
      const p = new THREE.Vector3(u.x * E.rx * 1.035, E.y + u.y * E.ry * 1.035, u.z * E.rz * 1.035);
      const p2 = new THREE.Vector3(un.x * E.rx * 1.035, E.y + un.y * E.ry * 1.035, un.z * E.rz * 1.035);
      const nn = new THREE.Vector3(u.x / E.rx, u.y / E.ry, u.z / E.rz).normalize(), tg = p2.sub(p).normalize(), b = new THREE.Vector3().crossVectors(nn, tg).normalize();
      pos.push(...p.clone().addScaledVector(b, halfW).toArray(), ...p.clone().addScaledVector(b, -halfW).toArray()); uv.push(i / N * 30, 0, i / N * 30, 1);
      if (i < N) { const a = i * 2; idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
    }
    const rg = new THREE.BufferGeometry(); rg.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); rg.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); rg.setIndex(idx); rg.computeVertexNormals();
    const strap = new THREE.Mesh(rg, new THREE.MeshStandardMaterial({ color: '#6F80B8', roughness: .75, normalMap: webbingNormal, side: THREE.DoubleSide })); strap.castShadow = true; g.add(strap);
    const bu = ringPt(Math.PI / 2 + .25), bpos = new THREE.Vector3(bu.x * E.rx * 1.06, E.y + bu.y * E.ry * 1.06, bu.z * E.rz * 1.06);
    const buckle = mesh(new THREE.BoxGeometry(.28, .2, .05), chrome()); buckle.position.copy(bpos); buckle.lookAt(bpos.clone().add(new THREE.Vector3(bu.x / E.rx, bu.y / E.ry, bu.z / E.rz).normalize())); buckle.rotateZ(-nb); g.add(buckle);
    const slot = mesh(new THREE.BoxGeometry(.13, .06, .02), rubber()); slot.position.set(0, 0, .03); buckle.add(slot);
    for (const s of [-1, 1]) { const a = mesh(new THREE.SphereGeometry(.15, 32, 24), cloud); a.position.copy(ell(E, 82 * s, -30, .03).p); a.scale.set(.9, .78, 1); g.add(a); }
    const moon = mesh(new THREE.TorusGeometry(.11, .045, 16, 40, 4.0), felt('#FFD45C')); moon.position.set(-.42, 2.18, .18); moon.rotation.z = 1.2; g.add(moon);
    face(g, body.at, { ex: .26, ey: 1.36, er: .085, my: 1.18, bx: .44, by: 1.22, br: .13 }, expr);
    return g;
  },
};
// ------------------------------------------------------------ 옷장 소품(키우기 보상). 머리 왼쪽 자리에 얹는다.
const HEAD = { kongkong: [-.36, 2.12, .02, .45, .78], poksin: [-.5, 2.04, .05, .22, .95], ppangppi: [-.36, 1.84, .06, .42, .85], mongsil: [.32, 2.3, .02, -.3, .85] };
const ACC = {
  ribbon() {
    const g = new THREE.Group(), pink = felt('#FF7FA0');
    for (const s of [-1, 1]) { const l = new THREE.Mesh(new THREE.SphereGeometry(.13, 32, 20), pink); l.scale.set(1.25, .8, .45); l.position.set(s * .15, .08, 0); l.rotation.z = s * .35; g.add(l); }
    const k = new THREE.Mesh(new THREE.SphereGeometry(.07, 24, 16), felt('#F05A82')); k.position.set(0, .08, .02); g.add(k);
    for (const s of [-1, 1]) { const t = new THREE.Mesh(new THREE.ConeGeometry(.05, .2, 16), pink); t.position.set(s * .07, -.04, 0); t.rotation.z = Math.PI + s * .4; g.add(t); }
    return g;
  },
  cap() {
    const g = new THREE.Group(), navy = plush('#4A5A9A', {});
    const c = new THREE.Mesh(new THREE.ConeGeometry(.24, .55, 40, 1, true), navy); c.position.y = .3; c.rotation.z = -.35; c.position.x = .08; g.add(c);
    const rim = new THREE.Mesh(new THREE.TorusGeometry(.23, .07, 16, 40), plush('#F4F1EA', {})); rim.rotation.x = Math.PI / 2; rim.position.y = .05; g.add(rim);
    const pom = new THREE.Mesh(new THREE.SphereGeometry(.09, 24, 16), plush('#F4F1EA', {})); pom.position.set(.27, .54, 0); g.add(pom);
    for (const st of [.16, .3]) { const star = new THREE.Mesh(new THREE.SphereGeometry(.025, 12, 8), felt('#FFD45C')); star.position.set(.0 + st * .3, st + .02, .2 - st * .35); g.add(star); }
    return g;
  },
  crown() {
    const g = new THREE.Group(), gold = new THREE.MeshPhysicalMaterial({ color: '#F3C24B', roughness: .55, sheen: .6, sheenColor: '#FFF0B0', normalMap: fabricNormal });
    const band = new THREE.Mesh(new THREE.CylinderGeometry(.2, .22, .14, 40, 1, true), gold); band.position.y = .07; band.material.side = THREE.DoubleSide; g.add(band);
    for (let i = 0; i < 5; i++) {
      const a = i / 5 * TAU, t = new THREE.Mesh(new THREE.ConeGeometry(.055, .14, 16), gold); t.position.set(Math.sin(a) * .2, .2, Math.cos(a) * .2); g.add(t);
      const gem = new THREE.Mesh(new THREE.SphereGeometry(.03, 12, 8), new THREE.MeshPhysicalMaterial({ color: ['#FF6B8A', '#6BC7FF', '#7FD6BE'][i % 3], roughness: .1, clearcoat: 1 })); gem.position.set(Math.sin(a) * .205, .27, Math.cos(a) * .205); g.add(gem);
    }
    return g;
  },
};
export function accessory(charKey, acc) {
  const [x, y, z, tilt, sc] = HEAD[charKey], g = ACC[acc]();
  g.position.set(x, y, z); g.rotation.z = tilt; g.scale.setScalar(sc); g.traverse(c => { if (c.isMesh) { c.castShadow = true; c.userData.acc = true; } });
  return g;
}
export const ACC_KEYS = ['ribbon', 'cap', 'crown'];
export const CHAR_KEYS = ['kongkong', 'poksin', 'ppangppi', 'mongsil'];
export const NAMES = { kongkong: '콩콩', poksin: '폭신', ppangppi: '빵삐', mongsil: '몽실' };
export const FLOAT = { mongsil: true };
export function buildCharacter(key, expr = 'awake') { const g = BUILD[key](expr); g.name = key; return g; }

// ------------------------------------------------------------ 촬영 무대: 따뜻한 미니어처 조명(참고 영상의 정지 동작 인형 촬영 느낌)
export async function makeStage(canvas, { size = 640, alpha = true } = {}) {
  const { RoomEnvironment } = await import('three/examples/jsm/environments/RoomEnvironment.js');
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1); renderer.setSize(size, size, false);
  renderer.outputColorSpace = THREE.SRGBColorSpace; renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = .95;
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), .04).texture; scene.environmentIntensity = .55;
  const key = new THREE.DirectionalLight('#FFF0DA', 2.4); key.position.set(-3, 5.5, 4.5); key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048); key.shadow.camera.left = -3; key.shadow.camera.right = 3; key.shadow.camera.top = 4; key.shadow.camera.bottom = -2; key.shadow.radius = 8; key.shadow.bias = -.0005; key.shadow.normalBias = .02;
  scene.add(key);
  const fill = new THREE.DirectionalLight('#DCE6FF', .7); fill.position.set(4, 2, 3); scene.add(fill);
  const rim = new THREE.DirectionalLight('#FFFFFF', 1.6); rim.position.set(1, 4, -5); scene.add(rim);
  scene.add(new THREE.HemisphereLight('#FFF6EA', '#B7A99A', .5));
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(20, 20), new THREE.ShadowMaterial({ opacity: .2 })); ground.rotation.x = -Math.PI / 2; ground.receiveShadow = true; scene.add(ground);
  // 접지 그림자(부드러운 원)
  const ao = canvasTex(128, (gc, n) => { const gr = gc.createRadialGradient(n / 2, n / 2, 0, n / 2, n / 2, n / 2); gr.addColorStop(0, 'rgba(40,30,50,.45)'); gr.addColorStop(1, 'rgba(40,30,50,0)'); gc.fillStyle = gr; gc.fillRect(0, 0, n, n); });
  ao.repeat.set(1, 1); ao.wrapS = ao.wrapT = THREE.ClampToEdgeWrapping;
  const contact = new THREE.Mesh(new THREE.PlaneGeometry(2.6, 1.9), new THREE.MeshBasicMaterial({ map: ao, transparent: true, depthWrite: false })); contact.rotation.x = -Math.PI / 2; contact.position.y = .003; scene.add(contact);
  const camera = new THREE.PerspectiveCamera(26, 1, .1, 50);
  camera.position.set(0, 2.2, 7.2); camera.lookAt(0, 1.18, 0);
  return { renderer, scene, camera, contact, ground, THREE };
}
