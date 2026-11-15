// Spring engine. Adapted from DesignBookmark Motion (designbookmark.com/motion-src/shared/motion.js):
// the same "stiffness damping" springs with mass 1, so a value here moves the same way as the reference.
// One rAF loop that sleeps when every spring has settled (Pleos: keep CPU low, no idle polling).
// Compose port: spring(dampingRatio = damping / (2 * sqrt(stiffness)), stiffness = stiffness).
window.Spring = (() => {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const all = new Set();
  const renders = new Set();
  const timers = [];
  let running = false, last = 0;

  // x: value, t: target, v: velocity, k: stiffness, c: damping
  function S(x, k = 400, c = 30) { const s = { x, t: x, v: 0, k, c: reduce ? 2 * Math.sqrt(k) : c }; all.add(s); return s; }
  function tune(s, k, c) { s.k = k; s.c = reduce ? 2 * Math.sqrt(k) : c; }
  function to(s, t, kick = 0) { s.t = t; s.v += reduce ? 0 : kick; if (reduce) { s.x = t; s.v = 0; } wake(); }
  function set(s, x) { s.x = x; s.t = x; s.v = 0; wake(); }
  function settled(s) { return Math.abs(s.x - s.t) < 1e-3 && Math.abs(s.v) < 1e-3; }

  function after(sec, fn) { const t = { t: sec, fn }; timers.push(t); wake(); return t; }
  function cancel(t) { const i = timers.indexOf(t); if (i > -1) timers.splice(i, 1); }

  function wake() { if (!running) { running = true; last = performance.now(); requestAnimationFrame(loop); } }
  function loop(now) {
    const dt = Math.min((now - last) / 1000, 1 / 30); last = now;
    for (let i = timers.length - 1; i >= 0; i--) if ((timers[i].t -= dt) <= 0) timers.splice(i, 1)[0].fn();
    const sub = 4, h = dt / sub;
    let moving = false;
    all.forEach(s => {
      if (settled(s)) { s.x = s.t; s.v = 0; return; }
      for (let k = 0; k < sub; k++) { const a = -s.k * (s.x - s.t) - s.c * s.v; s.v += a * h; s.x += s.v * h; }
      moving = true;
    });
    renders.forEach(r => r());
    if (moving || timers.length) requestAnimationFrame(loop); else running = false;
  }
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  return { S, tune, to, set, after, cancel, clamp, reduce, wake, render: f => { renders.add(f); wake(); } };
})();
