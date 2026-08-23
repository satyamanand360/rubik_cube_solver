/**
 * app.js – Rubik's Cube 3D Solver front-end
 *
 * Architecture:
 *   • Three.js scene with 27 cubie meshes (each has 6 per-face materials).
 *   • Logical position (ix,iy,iz) ∈ {-1,0,1}³ is tracked for each cubie.
 *   • On each move animation a pivot Object3D is used to rotate the affected
 *     9 cubies as a group; afterwards cubies are re-attached to the scene and
 *     their logical coordinates are updated.
 *   • Colors are recomputed from a local JS state array after every move
 *     using the permutation tables loaded from the server (/movetables).
 */

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ──────────────────────────────────────────────────────────────
// Constants
// ──────────────────────────────────────────────────────────────
const API   = 'http://localhost:8080';
const GAP   = 1.08;    // centre-to-centre spacing
const SIZE  = 0.96;    // cubie side length
const INNER = 0x000000; // pure black for hidden inner faces

// WCA standard colours indexed by face id 0-5
const FACE_COLORS = [
  0xFFFFFF,  // 0 = U  white
  0xC41E3A,  // 1 = R  red
  0x009B48,  // 2 = F  green
  0xFFD500,  // 3 = D  yellow
  0xFF5800,  // 4 = L  orange
  0x0046AD,  // 5 = B  blue
];

// Per-face animation config for each face letter
// axis / baseAngle derived from right-hand coordinate system:
//   U CW (from top)  → -Y rotation
//   D CW (from bot)  → +Y rotation
//   F CW (from front)→ -Z rotation
//   B CW (from back) → +Z rotation
//   R CW (from right)→ -X rotation
//   L CW (from left) → +X rotation
const FACE_CFG = {
  U: { axis:'y', base: -Math.PI/2, filter: c => c.iy ===  1 },
  D: { axis:'y', base:  Math.PI/2, filter: c => c.iy === -1 },
  F: { axis:'z', base: -Math.PI/2, filter: c => c.iz ===  1 },
  B: { axis:'z', base:  Math.PI/2, filter: c => c.iz === -1 },
  R: { axis:'x', base: -Math.PI/2, filter: c => c.ix ===  1 },
  L: { axis:'x', base:  Math.PI/2, filter: c => c.ix === -1 },
};

// Logical coordinate transforms for one CW quarter-turn
// (verified against Cube.java's facePos / rowColOf geometry)
// Logical coordinate transforms derived from Cube.java's rotate():
//   U  rotate(U,x,y,z)={-z,y,x}  → (ix,iz)→(-iz, ix)
//   D  rotate(D,x,y,z)={z,y,-x}  → (ix,iz)→( iz,-ix)
//   F  rotate(F,x,y,z)={y,-x,z}  → (ix,iy)→( iy,-ix)
//   B  rotate(B,x,y,z)={-y,x,z}  → (ix,iy)→(-iy, ix)
//   R  rotate(R,x,y,z)={x,z,-y}  → (iy,iz)→( iz,-iy)
//   L  rotate(L,x,y,z)={x,-z,y}  → (iy,iz)→(-iz, iy)
const COORD_UPDATE = {
  U: c => { const t=c.ix; c.ix=-c.iz; c.iz=t;   },  // (ix,iz)→(-iz, ix)  ← was swapped with D
  D: c => { const t=c.ix; c.ix=c.iz;  c.iz=-t;  },  // (ix,iz)→( iz,-ix)  ← was swapped with U
  F: c => { const t=c.ix; c.ix=c.iy;  c.iy=-t;  },  // (ix,iy)→( iy,-ix)
  B: c => { const t=c.ix; c.ix=-c.iy; c.iy=t;   },  // (ix,iy)→(-iy, ix)
  R: c => { const t=c.iy; c.iy=c.iz;  c.iz=-t;  },  // (iy,iz)→( iz,-iy)
  L: c => { const t=c.iy; c.iy=-c.iz; c.iz=t;   },  // (iy,iz)→(-iz, iy)
};

// BoxGeometry material index → what cube face they represent
// Three.js order: [+X, -X, +Y, -Y, +Z, -Z]
// Maps to:        [R,   L,   U,   D,   F,   B  ]
// getFacelet(ix,iy,iz, matIdx) returns the index into the 54-element state,
// or -1 if that face is an inner face.
function getFacelet(ix, iy, iz, mi) {
  switch (mi) {
    case 0: return ix === 1  ? 9  + (1-iy)*3 + (1-iz)   : -1; // R face
    case 1: return ix === -1 ? 36 + (1-iy)*3 + (iz+1)   : -1; // L face
    case 2: return iy === 1  ? 0  + (iz+1)*3 + (ix+1)   : -1; // U face
    case 3: return iy === -1 ? 27 + (1-iz)*3 + (ix+1)   : -1; // D face
    case 4: return iz === 1  ? 18 + (1-iy)*3 + (ix+1)   : -1; // F face
    case 5: return iz === -1 ? 45 + (1-iy)*3 + (1-ix)   : -1; // B face
    default: return -1;
  }
}

// Parse a move string ("R", "R2", "R'") into { face, turns }
function parseMove(str) {
  const face  = str[0];
  const turns = str.endsWith("'") ? 3 : str.includes('2') ? 2 : 1;
  return { face, turns };
}

// ──────────────────────────────────────────────────────────────
// State
// ──────────────────────────────────────────────────────────────
let movePerm   = null;   // 18 × 54 permutation tables from server
let cubeState  = Array.from({length:54}, (_,i) => Math.floor(i/9));
let cubies     = [];     // [{mesh, ix, iy, iz}, ...]
let animSpeed  = 320;    // ms per quarter-turn
let isAnimating = false;

// Timer
let timerStart    = 0;
let timerHandle   = null;
let timerRunning  = false;

// Stats
let stats = { solves:0, bestMs:Infinity, totalMs:0, totalMovesAll:0 };
let sessionMoves  = 0;

// Scene refs
let scene, camera, renderer, controls;

// ──────────────────────────────────────────────────────────────
// Initialisation
// ──────────────────────────────────────────────────────────────
async function init() {
  buildScene();
  buildCubies();
  updateAllColors(cubeState);
  animate();

  try {
    // Load server state and permutation tables in parallel
    const [stateRes, permRes] = await Promise.all([
      fetch(`${API}/state`).then(r=>r.json()),
      fetch(`${API}/movetables`).then(r=>r.json()),
    ]);
    movePerm  = permRes.perms;
    cubeState = stateRes.state;
    updateAllColors(cubeState);
    setStatus('idle', '● Ready');
    showToast('🎲 Server connected — click Scramble to begin!');
  } catch (e) {
    setStatus('idle', '● Offline');
    showToast('⚠️ Cannot reach server. Is CubeServer running?', 5000);
  }
}

// ──────────────────────────────────────────────────────────────
// Three.js scene
// ──────────────────────────────────────────────────────────────
function buildScene() {
  const canvas = document.getElementById('cube-canvas');

  scene = new THREE.Scene();

  camera = new THREE.PerspectiveCamera(42, canvas.clientWidth / canvas.clientHeight, 0.1, 100);
  camera.position.set(5.5, 4.5, 5.5);

  renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.setSize(canvas.clientWidth, canvas.clientHeight);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  // Lighting — ambient boosted so no face appears black from any angle
  const ambLight = new THREE.AmbientLight(0xffffff, 0.85);
  scene.add(ambLight);

  const sun = new THREE.DirectionalLight(0xffffff, 0.7);
  sun.position.set(8, 12, 8);
  sun.castShadow = false; // shadows make bottom faces dark; disable
  scene.add(sun);

  const fill = new THREE.DirectionalLight(0xffffff, 0.5);
  fill.position.set(-6, -4, -6);
  scene.add(fill);

  // Bottom fill so D face is visible
  const bottom = new THREE.DirectionalLight(0xffffff, 0.4);
  bottom.position.set(0, -8, 0);
  scene.add(bottom);

  const rim = new THREE.PointLight(0x2dd4bf, 0.4, 20);
  rim.position.set(-4, 3, -4);
  scene.add(rim);

  // Orbit controls
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping   = true;
  controls.dampingFactor   = 0.06;
  controls.minDistance     = 4;
  controls.maxDistance     = 14;
  controls.autoRotate      = false;
  controls.autoRotateSpeed = 0.5;

  window.addEventListener('resize', () => {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h);
  });
}

function buildCubies() {
  const geo = new THREE.BoxGeometry(SIZE, SIZE, SIZE);

  for (let ix = -1; ix <= 1; ix++) {
    for (let iy = -1; iy <= 1; iy++) {
      for (let iz = -1; iz <= 1; iz++) {
        // MeshStandardMaterial: not affected by shadow map darkness
        const mats = Array.from({length:6}, () =>
          new THREE.MeshStandardMaterial({ color: INNER, roughness: 0.5, metalness: 0.0 })
        );
        const mesh = new THREE.Mesh(geo, mats);
        mesh.position.set(ix*GAP, iy*GAP, iz*GAP);
        scene.add(mesh);
        cubies.push({ mesh, ix, iy, iz });
      }
    }
  }

  // Solid cover cube: fills the interior so inner faces never bleed through
  // during face-rotation animations. Size just under the full 3×SIZE span.
  const coverSize = SIZE * 3 - 0.05;
  const coverGeo  = new THREE.BoxGeometry(coverSize, coverSize, coverSize);
  const coverMat  = new THREE.MeshBasicMaterial({ color: 0x000000 });
  const cover     = new THREE.Mesh(coverGeo, coverMat);
  scene.add(cover);
}

function updateAllColors(state) {
  for (const c of cubies) {
    for (let mi = 0; mi < 6; mi++) {
      const fi = getFacelet(c.ix, c.iy, c.iz, mi);
      c.mesh.material[mi].color.setHex(fi >= 0 ? FACE_COLORS[state[fi]] : INNER);
    }
  }
}

// ──────────────────────────────────────────────────────────────
// Local state application (using server perm tables)
// ──────────────────────────────────────────────────────────────
const MOVE_NAME_TO_IDX = {
  "U":0,"U2":1,"U'":2,
  "D":3,"D2":4,"D'":5,
  "F":6,"F2":7,"F'":8,
  "R":9,"R2":10,"R'":11,
  "L":12,"L2":13,"L'":14,
  "B":15,"B2":16,"B'":17,
};

function applyMoveLocal(state, moveName) {
  if (!movePerm) return state;
  const perm = movePerm[MOVE_NAME_TO_IDX[moveName]];
  if (!perm) return state;
  const next = new Array(54);
  for (let i = 0; i < 54; i++) next[i] = state[perm[i]];
  return next;
}

// ──────────────────────────────────────────────────────────────
// Move animation
// ──────────────────────────────────────────────────────────────
function easeInOut(t) { return t < 0.5 ? 2*t*t : -1+(4-2*t)*t; }

function animateMove(moveName) {
  return new Promise(resolve => {
    const { face, turns } = parseMove(moveName);
    const cfg = FACE_CFG[face];
    if (!cfg) { resolve(); return; }

    // Angle: CW = baseAngle, double = 2×base, CCW = -base (shortest path)
    const totalAngle = turns === 3 ? -cfg.base : turns * cfg.base;
    const duration   = animSpeed * (turns === 2 ? 1.6 : 1.0);

    const affected = cubies.filter(cfg.filter);

    // Reparent affected cubies onto a pivot for group rotation
    const pivot = new THREE.Object3D();
    scene.add(pivot);
    affected.forEach(c => pivot.attach(c.mesh));

    const startTime = performance.now();

    function tick() {
      const elapsed  = performance.now() - startTime;
      const progress = Math.min(elapsed / duration, 1);
      pivot.rotation[cfg.axis] = totalAngle * easeInOut(progress);

      if (progress < 1) {
        requestAnimationFrame(tick);
      } else {
        // Finalize rotation exactly, then reattach
        pivot.rotation[cfg.axis] = totalAngle;
        scene.updateMatrixWorld(true);

        affected.forEach(c => {
          scene.attach(c.mesh);

          // Snap to integer grid (eliminates float accumulation)
          c.mesh.position.x = Math.round(c.mesh.position.x / GAP) * GAP;
          c.mesh.position.y = Math.round(c.mesh.position.y / GAP) * GAP;
          c.mesh.position.z = Math.round(c.mesh.position.z / GAP) * GAP;

          // Clear accumulated rotation – colors are rebuilt from logical pos
          c.mesh.rotation.set(0, 0, 0);

          // Update logical grid coordinates
          for (let t = 0; t < turns; t++) COORD_UPDATE[face](c);
        });

        scene.remove(pivot);

        // Apply move to local state and refresh colors
        cubeState = applyMoveLocal(cubeState, moveName);
        updateAllColors(cubeState);

        resolve();
      }
    }
    requestAnimationFrame(tick);
  });
}

async function playMoveSequence(moves, onStart, onDone) {
  isAnimating = true;
  setBtns(false);

  for (let i = 0; i < moves.length; i++) {
    if (onStart) onStart(i);
    await animateMove(moves[i]);
    sessionMoves++;
    document.getElementById('move-count').textContent = sessionMoves;
  }

  if (onDone) onDone();
  isAnimating = false;
  setBtns(true);
}

// ──────────────────────────────────────────────────────────────
// Render loop
// ──────────────────────────────────────────────────────────────
function animate() {
  requestAnimationFrame(animate);
  controls.update();
  renderer.render(scene, camera);
}

// ──────────────────────────────────────────────────────────────
// Public actions (called from HTML)
// ──────────────────────────────────────────────────────────────
window.doScramble = async function() {
  if (isAnimating) return;
  stopTimer();
  sessionMoves = 0;
  document.getElementById('move-count').textContent = 0;

  const n = +document.getElementById('scramble-slider').value;

  setStatus('scramble', '● Scrambling…');
  setBtns(false);

  let data;
  try {
    data = await fetch(`${API}/scramble?n=${n}`, {method:'POST'}).then(r=>r.json());
  } catch {
    showToast('⚠️ Server error during scramble'); setBtns(true); return;
  }

  clearSolutionList();
  document.getElementById('btn-solve').disabled = true;

  // Animate each scramble move
  await playMoveSequence(data.moves,
    () => {},
    () => {
      // After scramble: verify local state matches server
      cubeState = data.state;
      updateAllColors(cubeState);
      setStatus('scramble', '● Scrambled');
      document.getElementById('btn-solve').disabled = false;
      showToast(`🔀 Scrambled with ${data.moves.length} moves — ready to solve!`);
      startTimer();
    }
  );
};

window.doSolve = async function() {
  if (isAnimating) return;

  setStatus('solving', '● Solving…');
  document.getElementById('btn-solve').innerHTML = '<span class="spinner"></span> Solving…';
  setBtns(false);

  let data;
  try {
    data = await fetch(`${API}/solve`, {method:'POST'}).then(r=>r.json());
  } catch {
    showToast('⚠️ Server error during solve');
    setBtns(true);
    setStatus('idle', '● Error');
    document.getElementById('btn-solve').innerHTML = '<span class="btn-icon">✨</span> Solve';
    return;
  }

  document.getElementById('btn-solve').innerHTML = '<span class="btn-icon">✨</span> Solve';

  const sol = data.solution || [];
  if (sol.length === 0) {
    stopTimer();
    setStatus('success','● Already solved');
    showToast('✅ Already solved!');
    setBtns(true);
    return;
  }

  // Render solution chips
  renderSolutionList(sol);

  // Animate solution moves, highlighting current chip
  await playMoveSequence(sol,
    (i) => highlightChip(i),
    () => {
      stopTimer();
      markAllChipsDone();
      cubeState = data.state;
      updateAllColors(cubeState);
      setStatus('success', '● Solved! 🎉');
      document.querySelector('.cube-section').classList.add('solved');
      setTimeout(() => document.querySelector('.cube-section').classList.remove('solved'), 1200);

      recordSolve();
      showToast(`🏆 Solved in ${document.getElementById('timer-display').textContent}s with ${sol.length} moves!`);
    }
  );
};

window.doReset = async function() {
  if (isAnimating) return;
  stopTimer();
  sessionMoves = 0;
  document.getElementById('move-count').textContent = 0;
  document.getElementById('timer-display').textContent = '0.00';
  clearSolutionList();

  try {
    const data = await fetch(`${API}/reset`, {method:'POST'}).then(r=>r.json());
    cubeState = data.state;
    updateAllColors(cubeState);
  } catch {
    // Reset visually even if server fails
    cubeState = Array.from({length:54}, (_,i) => Math.floor(i/9));
    updateAllColors(cubeState);
  }

  setStatus('idle', '● Ready');
  document.getElementById('btn-solve').disabled = true;
  showToast('↺ Cube reset');
};

window.fillExample = function(str, autoApply = false) {
  const input = document.getElementById('custom-input');
  input.value = str;
  if (autoApply) window.doCustom();
};

window.doCustom = async function() {
  if (isAnimating) return;
  
  const rawStr = document.getElementById('custom-input').value.trim();
  if (!rawStr) return;
  
  // Normalize string for validation (split by space)
  const tokens = rawStr.toUpperCase().replace(/I/g, "'").split(/\s+/);
  const validRegex = /^[UDFRLB][2']?$/;
  const validMoves = tokens.filter(t => validRegex.test(t));
  
  if (validMoves.length === 0) {
    showToast('⚠️ No valid moves found. Use U, D, F, R, L, B (optionally with 2 or \').');
    return;
  }

  stopTimer();
  sessionMoves = 0;
  document.getElementById('move-count').textContent = 0;
  
  setStatus('scramble', '● Applying…');
  setBtns(false);
  document.getElementById('btn-custom').disabled = true;

  let data;
  try {
    const encoded = encodeURIComponent(validMoves.join(' '));
    data = await fetch(`${API}/custom?moves=${encoded}`, {method:'POST'}).then(r=>r.json());
  } catch {
    showToast('⚠️ Server error applying custom state');
    setBtns(true);
    document.getElementById('btn-custom').disabled = false;
    return;
  }

  clearSolutionList();
  document.getElementById('btn-solve').disabled = true;
  
  // Actually animate the requested moves so the user sees the setup
  await playMoveSequence(data.moves,
    () => {},
    () => {
      cubeState = data.state;
      updateAllColors(cubeState);
      setStatus('scramble', '● Custom state ready');
      document.getElementById('btn-solve').disabled = false;
      document.getElementById('btn-custom').disabled = false;
      showToast(`✨ Custom position set (${data.moves.length} moves). Ready to solve!`);
      // Update scramble length stat just for UI tracking
      document.getElementById('scramble-n').textContent = data.moves.length;
      startTimer();
    }
  );
};

window.updateSpeed = function(v) {
  // v: 1=slowest, 5=fastest
  const labels = ['', 'Very Slow', 'Slow', 'Medium', 'Fast', 'Very Fast'];
  document.getElementById('speed-label').textContent = labels[v];
  animSpeed = [0, 700, 500, 320, 180, 90][v];
};

// ──────────────────────────────────────────────────────────────
// Timer helpers
// ──────────────────────────────────────────────────────────────
function startTimer() {
  stopTimer();
  timerStart   = performance.now();
  timerRunning = true;
  timerHandle  = setInterval(() => {
    const elapsed = (performance.now() - timerStart) / 1000;
    document.getElementById('timer-display').textContent = elapsed.toFixed(2);
  }, 50);
}

function stopTimer() {
  if (timerHandle) { clearInterval(timerHandle); timerHandle = null; }
  timerRunning = false;
}

// ──────────────────────────────────────────────────────────────
// UI helpers
// ──────────────────────────────────────────────────────────────
function setBtns(enabled) {
  document.getElementById('btn-scramble').disabled = !enabled;
  document.getElementById('btn-reset').disabled    = !enabled;
  // btn-solve enabled state is managed separately
}

function setStatus(type, text) {
  const el = document.getElementById('status-pill');
  el.className = `status-pill status-${type}`;
  el.textContent = text;
}

function renderSolutionList(moves) {
  const list = document.getElementById('solution-list');
  list.innerHTML = '';
  document.getElementById('solution-badge').textContent = `${moves.length} moves`;
  moves.forEach((m, i) => {
    const chip = document.createElement('span');
    chip.className = 'move-chip';
    chip.id = `chip-${i}`;
    chip.textContent = m;
    list.appendChild(chip);
  });
}

function clearSolutionList() {
  const list = document.getElementById('solution-list');
  list.innerHTML = '<span class="solution-placeholder">Press Scramble, then Solve</span>';
  document.getElementById('solution-badge').textContent = '—';
}

function highlightChip(i) {
  // Mark previous chip as done
  if (i > 0) {
    const prev = document.getElementById(`chip-${i-1}`);
    if (prev) { prev.classList.remove('active'); prev.classList.add('done'); }
  }
  const chip = document.getElementById(`chip-${i}`);
  if (chip) {
    chip.classList.add('active');
    chip.scrollIntoView({ behavior:'smooth', block:'nearest' });
  }
}

function markAllChipsDone() {
  document.querySelectorAll('.move-chip').forEach(c => {
    c.classList.remove('active');
    c.classList.add('done');
  });
}

let toastTimer = null;
function showToast(msg, ms = 3500) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.classList.remove('hidden');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), ms);
}

function recordSolve() {
  const elapsed = (performance.now() - timerStart);
  stats.solves++;
  stats.totalMs   += elapsed;
  stats.totalMovesAll += sessionMoves;
  if (elapsed < stats.bestMs) stats.bestMs = elapsed;

  document.getElementById('stat-solves').textContent = stats.solves;
  document.getElementById('stat-best').textContent   = (stats.bestMs/1000).toFixed(2) + 's';
  document.getElementById('stat-avg').textContent    = (stats.totalMs/stats.solves/1000).toFixed(2) + 's';
  document.getElementById('stat-moves').textContent  = stats.totalMovesAll;
}

// ──────────────────────────────────────────────────────────────
// Boot
// ──────────────────────────────────────────────────────────────
init();
