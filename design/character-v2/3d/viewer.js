// 360도 3D 보기: 끌어서 돌리고, 휠로 확대. 캐릭터, 표정, 옷장 소품을 바꿔 볼 수 있다.
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { buildCharacter, makeStage, accessory, NAMES } from './plush.js';
(async () => {
const canvas = document.getElementById('c');
const st = await makeStage(canvas, { size: 900, alpha: true });
const { renderer, scene, camera } = st;
const controls = new OrbitControls(camera, canvas);
controls.target.set(0, 1.18, 0); controls.enableDamping = true; controls.autoRotate = true; controls.autoRotateSpeed = 1.6;
controls.minDistance = 3.5; controls.maxDistance = 11; controls.maxPolarAngle = Math.PI * .55; controls.update();
let obj = null;
const sel = { char: 'kongkong', expr: 'awake', acc: '' };
function rebuild() {
  if (obj) scene.remove(obj);
  obj = buildCharacter(sel.char, sel.expr);
  if (sel.acc) obj.add(accessory(sel.char, sel.acc));
  scene.add(obj);
}
document.querySelectorAll('[data-k]').forEach(b => b.addEventListener('click', () => {
  sel[b.dataset.k] = b.dataset.v; document.querySelectorAll(`[data-k="${b.dataset.k}"]`).forEach(x => x.setAttribute('aria-pressed', String(x === b))); rebuild();
}));
document.getElementById('spin').addEventListener('click', e => { controls.autoRotate = !controls.autoRotate; e.currentTarget.setAttribute('aria-pressed', String(controls.autoRotate)); });
function fit() { const w = canvas.clientWidth, h = canvas.clientHeight; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix(); }
addEventListener('resize', fit); fit(); rebuild();
renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });
window.ready = true;
})();
