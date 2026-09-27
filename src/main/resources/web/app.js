'use strict';

// AssetManager browser client. All indexing, thumbnailing, waveform and 3D
// rendering happen in the Java process; this only drives the UI.

const $ = (id) => document.getElementById(id);

// One place that builds a thumbnail address, so the render version cannot be
// forgotten at one of the call sites.
const thumbUrl = (id) => '/api/thumb?id=' + id + (state.render ? '&v=' + state.render : '');

const state = {
  items: [],
  total: 0,
  shown: 0,
  // The server's thumbnail render version; see refresh().
  render: '',
  // The focused asset, whose details fill the right-hand pane.
  selected: null,
  // Every highlighted id, for the batch actions.
  sel: new Set(),
  // Shift-click extends the selection from here.
  anchor: null,
  tags: new Set(),
  cell: 140,
  searchTimer: null,
  // 3D viewer camera
  cam: { az: 0.7, el: 0.3, d: 2.8, grid: false, wire: false },
  drag: null,
  audio: null,
};

// --------------------------------------------------------------------- utils

function fmtSize(bytes) {
  if (bytes == null) return '?';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (i === 0 ? v : v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2)) + ' ' + u[i];
}

function fmtDuration(sec) {
  if (!sec || sec < 0) return '--:--';
  const m = Math.floor(sec / 60), s = sec - m * 60;
  return m > 0 ? `${m}:${s.toFixed(2).padStart(5, '0')}` : s.toFixed(2);
}

function fmtCount(n) { return (n || 0).toLocaleString(); }

function fmtTime(ms) {
  if (!ms) return '-';
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

async function api(path, params) {
  let url = '/api/' + path;
  if (params) {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== null && v !== undefined && v !== '') q.set(k, v);
    }
    const s = q.toString();
    if (s) url += '?' + s;
  }
  const res = await fetch(url);
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { error: text }; }
}

function subtitle(a) {
  if (a.category === 'IMAGE' && a.width > 0) return `${a.width}x${a.height}`;
  if (a.category === 'AUDIO') return fmtDuration(a.duration);
  if (a.category === 'MODEL' && a.tris > 0) return fmtCount(a.tris) + ' tris';
  return fmtSize(a.size);
}

// ------------------------------------------------------------------ loading

async function load() {
  const params = {
    q: $('search').value,
    category: $('category').value,
    sort: $('sort').value,
    mindim: $('mindim').value !== '0' ? $('mindim').value : '',
    untagged: $('untagged').checked ? 1 : '',
    duplicates: $('duplicates').checked ? 1 : '',
    problems: $('problems').checked ? 1 : '',
    tags: [...state.tags].join(','),
  };
  const data = await api('assets', params);
  state.items = data.items || [];
  state.total = data.total || 0;
  state.shown = data.shown || 0;
  // Thumbnails are served cacheable, so the renderer version goes in their URL:
  // when the server changes how a tile looks, its address changes with it and
  // the browser cannot keep replaying the old image.
  state.render = data.render || '';
  // A new query supersedes any leftover action message.
  if (!state.keepNotice) clearNotice();
  state.keepNotice = false;
  // The result set changed, so drop ids that are no longer on screen rather than
  // leaving a batch action pointed at assets the user can no longer see. This
  // also invalidates the shift-click anchor, which is an index into state.items.
  const present = new Set(state.items.map((a) => a.id));
  for (const id of [...state.sel]) if (!present.has(id)) state.sel.delete(id);
  if (state.anchor != null && !present.has(state.anchor)) state.anchor = null;
  renderGrid();
  markSelection();
  renderStatus(data.scan);
  document.documentElement.style.setProperty('--cell', state.cell + 'px');
}

function renderStatus(scan) {
  const bits = [`${fmtCount(state.shown)} of ${fmtCount(state.total)} assets`];
  if (scan && scan !== 'idle') bits.push(scan);
  // An action's own message outranks the counters, which is why every caller
  // announces after its reload rather than before.
  if (state.notice) bits.push(state.notice);
  $('statusbar').textContent = bits.join('  |  ');
}

function renderGrid() {
  const grid = $('grid');
  grid.textContent = '';
  $('empty').classList.toggle('hidden', state.items.length > 0);

  for (const a of state.items) {
    const card = document.createElement('div');
    card.className = 'card' + (state.sel.has(a.id) ? ' sel' : '');
    card.dataset.id = a.id;
    card.title = a.dir + '\n' + a.name;

    const thumb = document.createElement('div');
    thumb.className = 'thumb';
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.alt = a.name;
    img.src = thumbUrl(a.id);
    thumb.appendChild(img);

    const badge = document.createElement('div');
    badge.className = 'badge';
    badge.textContent = (a.ext || '?').toUpperCase();
    thumb.appendChild(badge);

    if (a.problem) {
      const flag = document.createElement('div');
      flag.className = 'flag';
      flag.title = 'Could not be fully read or hashed';
      thumb.appendChild(flag);
    }

    const meta = document.createElement('div');
    meta.className = 'meta';
    const n = document.createElement('div');
    n.className = 'name';
    n.textContent = a.label;
    const s = document.createElement('div');
    s.className = 'sub';
    s.textContent = subtitle(a);
    meta.append(n, s);

    card.append(thumb, meta);
    card.addEventListener('click', (e) => pick(a.id, e));
    grid.appendChild(card);
  }
}

// ---------------------------------------------------------------- selection

/**
 * Click to select one, Ctrl-click to toggle, Shift-click for a range. The grid
 * is rebuilt on every filter change, so ids are resolved against state.items.
 */
function pick(id, e) {
  const order = state.items;
  if (e.shiftKey) {
    // Shift extends from the anchor. Falling back to the focused asset means a
    // shift-click still does something after the anchor was dropped by a reload.
    const from = state.anchor != null ? state.anchor : (state.selected ? state.selected.id : null);
    const a = order.findIndex((x) => x.id === from);
    const b = order.findIndex((x) => x.id === id);
    if (a >= 0 && b >= 0) {
      state.sel.clear();
      for (let i = Math.min(a, b); i <= Math.max(a, b); i++) state.sel.add(order[i].id);
    }
    // The anchor deliberately does not move: repeated shift-clicks keep
    // widening the same range, which is what a file manager does.
  } else if (e.ctrlKey || e.metaKey) {
    if (state.sel.has(id)) state.sel.delete(id);
    else state.sel.add(id);
    state.anchor = id;
  } else {
    state.sel.clear();
    state.sel.add(id);
    state.anchor = id;
  }
  markSelection();
  focusDetail(id);
}

/** Repaints selection state without rebuilding the grid. */
function markSelection() {
  for (const c of document.querySelectorAll('.card')) {
    c.classList.toggle('sel', state.sel.has(Number(c.dataset.id)));
  }
  const n = state.sel.size;
  $('selbar').classList.toggle('hidden', n === 0);
  $('selcount').textContent = n === 1 ? '1 selected' : fmtCount(n) + ' selected';
  return n;
}

/**
 * Loads the detail pane for an id, ignoring the response if a newer request has
 * already been made. Without this, a fast click through a list can paint the
 * wrong asset.
 */
let detailSeq = 0;
async function focusDetail(id) {
  const seq = ++detailSeq;
  const a = await api('asset', { id });
  if (a.error || seq !== detailSeq) return;
  // Only the focus moves. Adding the id to the selection here would undo a
  // ctrl-click that was meant to deselect it, since this runs after pick().
  state.selected = a;

  $('dtitle').textContent = a.name;
  const raw = $('dopen');
  raw.href = '/api/file?id=' + id;
  raw.classList.toggle('hidden', a.preview === 'none');
  // dl=1 asks for attachment, which is what makes the browser hand the file to
  // the operating system and offer its own save dialog.
  const dl = $('ddl');
  dl.href = '/api/file?id=' + id + '&dl=1';
  dl.classList.toggle('hidden', a.preview === 'none');
  dl.setAttribute('download', a.name);
  $('note').value = a.note || '';

  renderChips(a);
  renderMeta(a);
  renderPreview(a);
}

/** Ids for a batch call: the whole selection, or just the focused asset. */
function actionIds() {
  return state.sel.size ? [...state.sel] : (state.selected ? [state.selected.id] : []);
}

function selectAll() {
  state.sel = new Set(state.items.map((a) => a.id));
  if (state.anchor == null && state.items.length) state.anchor = state.items[0].id;
  markSelection();
}

function clearSelection() {
  state.sel.clear();
  markSelection();
}

// -------------------------------------------------------------- file dialogs

/**
 * File dialogs, with a path-based fallback.
 *
 * The native File System Access API gives real OS dialogs, but it exists only in
 * Chromium browsers and -- importantly -- a FileSystemHandle exposes no path. A
 * page can never learn an absolute path from a picker. So:
 *
 *   - "save as" and "copy to folder" use the native picker where available, and
 *     the browser writes the bytes through the handle it returns.
 *   - "add folder" cannot: the Java process has to walk the folder itself, which
 *     needs a real path, so it always uses the path dialog.
 *   - anywhere the API is missing or the user dismisses it, the path dialog takes
 *     over and the server does the writing, which also re-indexes the result.
 */
const fs = {
  get canSave() { return typeof window.showSaveFilePicker === 'function'; },
  get canPickDir() { return typeof window.showDirectoryPicker === 'function'; },

  async saveFile(suggestedName, ext) {
    const handle = await window.showSaveFilePicker({
      suggestedName,
      types: [{ description: ext.toUpperCase(), accept: { [mimeForExt(ext)]: ['.' + ext] } }],
    });
    return handle;
  },

  async directory() {
    return window.showDirectoryPicker({ mode: 'readwrite' });
  },

  /** Writes bytes through a handle. Returns the file name for the status line. */
  async writeHandle(handle, blob) {
    const w = await handle.createWritable();
    try {
      await w.write(blob);
      await w.close();
    } catch (e) {
      try { await w.abort(); } catch (ignored) { /* already closed */ }
      throw e;
    }
    return handle.name;
  },
};

function mimeForExt(ext) {
  switch ((ext || '').toLowerCase()) {
    case 'png': return 'image/png';
    case 'jpg': case 'jpeg': return 'image/jpeg';
    case 'gif': return 'image/gif';
    case 'bmp': return 'image/bmp';
    case 'tif': case 'tiff': return 'image/tiff';
    default: return 'application/octet-stream';
  }
}

/**
 * The path dialog. Resolves to the entered path, or null if cancelled.
 *
 * `check` is asked to validate whatever is typed; the server is the authority on
 * whether a folder exists, so the note under the field is not a guess.
 */
function pathDialog({ title, hint, value, okLabel, check, suggestions }) {
  return new Promise((resolve) => {
    const dlg = $('pathdlg');
    $('pathdlgtitle').textContent = title || 'Choose a path';
    $('pathdlghint').textContent = hint || '';
    $('pathdlgok').textContent = okLabel || 'OK';
    const input = $('pathdlginput');
    input.value = value || '';
    dlg.classList.remove('hidden');
    setTimeout(() => { input.focus(); input.select(); }, 0);

    const sug = $('pathdlgsuggest');
    sug.textContent = '';
    for (const s of (suggestions || []).slice(0, 6)) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = s;
      b.title = s;
      b.addEventListener('click', () => { input.value = s; validate(); });
      sug.appendChild(b);
    }

    let checkSeq = 0;
    async function validate() {
      const v = input.value.trim();
      const note = $('pathdlgcheck');
      note.className = 'muted';
      if (!v) { note.textContent = ''; $('pathdlgok').disabled = true; return false; }
      $('pathdlgok').disabled = true;
      if (!check) { $('pathdlgok').disabled = false; note.textContent = ''; return true; }
      const seq = ++checkSeq;
      const r = await check(v);
      if (seq !== checkSeq) return false;   // a newer keystroke won
      if (r && r.usable) { note.textContent = r.note || ''; note.className = 'muted good'; }
      else { note.textContent = (r && r.note) || 'unusable'; note.className = 'muted bad'; }
      $('pathdlgok').disabled = !(r && r.usable);
      return !!(r && r.usable);
    }

    const onInput = debounce(validate, 260);
    input.addEventListener('input', onInput);
    const onKey = (e) => { if (e.key === 'Escape') { e.preventDefault(); finish(null); } };
    const form = $('pathform');
    const onSubmit = async (e) => { e.preventDefault(); if (await validate()) finish(input.value.trim()); };

    function finish(result) {
      input.removeEventListener('input', onInput);
      document.removeEventListener('keydown', onKey, true);
      form.removeEventListener('submit', onSubmit);
      dlg.classList.add('hidden');
      resolve(result);
    }

    document.addEventListener('keydown', onKey, true);
    form.addEventListener('submit', onSubmit);
    $('pathdlgcancel').onclick = () => finish(null);
    validate();
  });
}

/** Remembers the last directory used, so the dialog opens somewhere sensible. */
const lastPath = {
  dir() { try { return localStorage.getItem('assetmanager.lastDir') || ''; } catch { return ''; } },
  set(p) {
    try {
      const i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
      if (i > 0) localStorage.setItem('assetmanager.lastDir', p.slice(0, i));
    } catch { /* private mode: just do not remember */ }
  },
};

/** Existing indexed folders, offered as one-click suggestions. */
async function folderSuggestions() {
  const d = await api('roots');
  const roots = (d.roots || []).map((r) => r.path);
  const dir = lastPath.dir();
  if (dir && !roots.includes(dir)) roots.unshift(dir);
  return roots;
}

/**
 * Where should a save go? Returns {kind:'handle', handle} or {kind:'path', path},
 * or null if the user cancelled.
 */
async function askSaveTarget(suggestedName, ext) {
  if (fs.canSave) {
    try {
      return { kind: 'handle', handle: await fs.saveFile(suggestedName, ext) };
    } catch (e) {
      // AbortError is the user closing the picker, which is not a fallback case.
      if (e && e.name === 'AbortError') return null;
      // Anything else (no user activation, policy) falls through to the path dialog.
    }
  }
  const guess = (lastPath.dir() ? lastPath.dir() + '/' : '') + suggestedName;
  const p = await pathDialog({
    title: 'Save as',
    hint: 'This browser has no native save dialog, or the picker was unavailable. '
        + 'Enter an absolute path and the Java process will write the file.',
    value: guess,
    okLabel: 'Save',
    suggestions: await folderSuggestions(),
    check: async (v) => api('checkpath', { path: v.slice(0, Math.max(0, v.lastIndexOf('/'))) || v }),
  });
  return p ? { kind: 'path', path: p } : null;
}

/** Where should a copy go? Same shape as askSaveTarget but for a directory. */
async function askCopyTarget(count) {
  if (fs.canPickDir) {
    try {
      return { kind: 'dir', handle: await fs.directory() };
    } catch (e) {
      if (e && e.name === 'AbortError') return null;
    }
  }
  const p = await pathDialog({
    title: 'Copy to folder',
    hint: count + ' file(s). This browser has no native folder picker. '
        + 'Enter an absolute folder path and the Java process will do the copying.',
    value: lastPath.dir(),
    okLabel: 'Copy',
    suggestions: await folderSuggestions(),
    check: (v) => api('checkpath', { path: v }),
  });
  return p ? { kind: 'path', path: p } : null;
}

/**
 * Last-resort read-only view, for text that could not be copied or saved.
 * Reuses the path dialog's box rather than window.prompt, which truncates long
 * values and cannot be styled.
 */
function showText(title, text) {
  const dlg = $('pathdlg');
  const input = $('pathdlginput');
  $('pathdlgtitle').textContent = title;
  $('pathdlghint').textContent = 'Select and copy this text.';
  $('pathdlgok').classList.add('hidden');
  $('pathdlgcheck').textContent = '';
  $('pathdlgsuggest').textContent = '';
  input.readOnly = true;
  input.value = text;
  dlg.classList.remove('hidden');
  setTimeout(() => { input.focus(); input.select(); }, 0);
  $('pathdlgcancel').onclick = () => {
    dlg.classList.add('hidden');
    input.readOnly = false;
    $('pathdlgok').classList.remove('hidden');
  };
}

// ------------------------------------------------------------------ detail

function renderChips(a) {
  const box = $('dchips');
  box.textContent = '';
  if (!a.tags || a.tags.length === 0) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'none';
    box.appendChild(p);
    return;
  }
  for (const t of a.tags) {
    const c = document.createElement('span');
    c.className = 'chip';
    c.title = 'Click to remove';
    c.textContent = t;
    c.addEventListener('click', async () => {
      await api('removetag', { id: a.id, tag: t });
      focusDetail(a.id);
      loadTags();
    });
    box.appendChild(c);
  }
}

function renderMeta(a) {
  const rows = [];
  rows.push(['Type', `${a.category} (${(a.ext || '').toUpperCase()})`]);
  rows.push(['Size', fmtSize(a.size)]);
  if (a.category === 'IMAGE' && a.width > 0) {
    rows.push(['Dimensions', `${a.width} x ${a.height}  (${fmtCount(a.width * a.height)} px)`]);
  }
  if (a.category === 'AUDIO') {
    rows.push(['Duration', fmtDuration(a.duration)]);
    if (a.sampleRate) rows.push(['Format', `${a.sampleRate} Hz, ${a.channels} ch, ${a.bitDepth || 16}-bit`]);
  }
  if (a.category === 'MODEL') {
    if (a.tris > 0) rows.push(['Geometry', `${fmtCount(a.tris)} tris, ${fmtCount(a.verts)} verts`]);
  }
  rows.push(['Modified', fmtTime(a.mtime)]);
  rows.push(['Indexed', fmtTime(a.addedAt)]);
  if (a.hash) rows.push(['Hash', a.hash.slice(0, 16) + '…']);
  rows.push(['Path', a.path]);

  const box = $('dmeta');
  box.textContent = '';
  for (const [k, v] of rows) {
    const line = document.createElement('div');
    line.textContent = k.padEnd(11, ' ') + v;
    box.appendChild(line);
  }
  if (a.problem) {
    const w = document.createElement('div');
    w.className = 'warn';
    w.textContent = '⚠ could not be fully read or hashed';
    box.appendChild(w);
  }
}

function renderPreview(a) {
  const p = $('preview');
  p.textContent = '';
  if (state.audio) { state.audio.pause(); state.audio = null; }

  if (a.preview === 'image') {
    const img = document.createElement('img');
    img.src = '/api/image?id=' + a.id;
    img.alt = a.name;
    p.appendChild(img);
  } else if (a.preview === 'audio') {
    p.style.flexDirection = 'column';
    p.style.gap = '8px';
    const cv = document.createElement('canvas');
    cv.className = 'wave';
    p.appendChild(cv);
    const audio = document.createElement('audio');
    audio.controls = true;
    audio.src = '/api/audio?id=' + a.id;
    p.appendChild(audio);
    state.audio = audio;
    drawWave(cv, a, audio);
  } else if (a.preview === 'model') {
    p.style.padding = '0';
    p.appendChild(buildViewer(a));
  } else if (a.preview === 'vector') {
    p.appendChild(muted('.' + a.ext.toUpperCase() + ' is indexed but cannot be rendered here.\nRasterise it to PNG, or use a dedicated vector tool.'));
  } else {
    p.appendChild(muted('No preview for .' + a.ext));
  }
}

function muted(text) {
  const p = document.createElement('p');
  p.className = 'muted center';
  p.style.whiteSpace = 'pre-line';
  p.style.padding = '24px';
  p.textContent = text;
  return p;
}

// ------------------------------------------------------------------- audio

// The audio waveform's colours live in the stylesheet, so the preview pane and
// the server-rendered thumbnail cannot drift apart, and a theme change reaches
// the canvas without editing JavaScript. Cached: the values cannot change
// without a reload, and getComputedStyle is not free.
let waveStyle = null;
function waveColours() {
  if (waveStyle) return waveStyle;
  const cs = getComputedStyle(document.documentElement);
  // No fallback literals on purpose. A copy of the colour here would be exactly
  // the drift this indirection exists to prevent, and it would only ever be
  // reached when the stylesheet had already failed to load.
  waveStyle = {
    wave: cs.getPropertyValue('--wave').trim(),
    guide: cs.getPropertyValue('--wave-guide').trim(),
    playhead: cs.getPropertyValue('--playhead').trim(),
  };
  return waveStyle;
}

async function drawWave(canvas, a, audio) {
  const data = await api('peaks', { id: a.id });
  if (data.error) return;
  const buckets = data.buckets || [];
  const dur = data.duration || a.duration || 1;

  const draw = () => {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    const g = canvas.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    if (!buckets.length) return;

    const n = buckets.length / 2;
    const mid = h / 2;
    const { wave, guide, playhead } = waveColours();
    g.strokeStyle = guide;
    g.beginPath();
    g.moveTo(0, mid); g.lineTo(w, mid); g.stroke();

    g.strokeStyle = wave;
    g.beginPath();
    for (let x = 0; x < w; x++) {
      const b0 = Math.floor((x / w) * n);
      const b1 = Math.max(b0 + 1, Math.floor(((x + 1) / w) * n));
      let lo = 0, hi = 0;
      for (let b = b0; b < b1 && b < n; b++) {
        lo = Math.min(lo, buckets[b * 2]);
        hi = Math.max(hi, buckets[b * 2 + 1]);
      }
      const y0 = mid - hi * mid * 0.92;
      const y1 = mid - lo * mid * 0.92;
      g.moveTo(x + 0.5, y0);
      g.lineTo(x + 0.5, Math.max(y0 + 1, y1));
    }
    g.stroke();

    if (!audio.paused) {
      const pos = audio.currentTime / dur;
      g.strokeStyle = playhead;
      g.lineWidth = 1.5;
      g.beginPath();
      g.moveTo(pos * w, 0);
      g.lineTo(pos * w, h);
      g.stroke();
      g.lineWidth = 1;
    }
  };

  canvas.style.width = '100%';
  draw();
  window.addEventListener('resize', draw);
  audio.addEventListener('timeupdate', draw);
  audio.addEventListener('play', draw);
  audio.addEventListener('pause', draw);
  audio.addEventListener('ended', draw);

  // click to seek
  canvas.style.cursor = 'pointer';
  canvas.addEventListener('click', (e) => {
    const r = canvas.getBoundingClientRect();
    const frac = (e.clientX - r.left) / r.width;
    audio.currentTime = Math.max(0, Math.min(dur, frac * dur));
    draw();
  });
}

// --------------------------------------------------------------------- 3D

function buildViewer(a) {
  const wrap = document.createElement('div');
  wrap.className = 'viewer';

  const img = document.createElement('img');
  img.alt = a.name;
  img.draggable = false;
  wrap.appendChild(img);

  const tools = document.createElement('div');
  tools.className = 'viewtools';

  const grid = mkToggle('Grid', () => { state.cam.grid = !state.cam.grid; refresh(); });
  const wire = mkToggle('Wireframe', () => { state.cam.wire = !state.cam.wire; refresh(); });
  const reset = document.createElement('button');
  reset.className = 'btn small';
  reset.textContent = 'Reset view';
  reset.addEventListener('click', () => {
    state.cam = { az: 0.7, el: 0.3, d: 2.8, grid: false, wire: false };
    refresh();
  });
  tools.append(grid, wire, reset);
  wrap.appendChild(tools);

  // Render scheduling.
  //
  // Rotating a model asks the server to rasterise it and returns a PNG, so a
  // drag would otherwise fire one request per animation frame. Assigning each one
  // to the visible <img> cancels the previous request before it finished, which
  // surfaces server-side as a broken pipe: the render was already paid for and
  // the result is thrown away.
  //
  // So: never more than one request in flight, keep only the newest camera, and
  // decode into a detached Image. A detached image is not tied to the visible
  // element, so nothing the UI does can cancel it.
  let raf = 0;
  let pending = null;    // newest camera state not yet requested
  let rendering = false; // a request is in flight
  let generation = 0;    // bumped per request, so a stale result is dropped

  function snapshot() {
    const w = Math.max(64, Math.round(img.clientWidth || 640));
    const h = Math.max(64, Math.round(img.clientHeight || 480));
    return new URLSearchParams({
      id: a.id, w, h,
      az: state.cam.az.toFixed(3),
      el: state.cam.el.toFixed(3),
      d: state.cam.d.toFixed(3),
      grid: state.cam.grid ? 1 : '',
      wire: state.cam.wire ? 1 : '',
    }).toString();
  }

  function refresh() {
    pending = snapshot();
    if (!raf) raf = requestAnimationFrame(drain);
  }

  /** Issues the newest pending render, if nothing is already in flight. */
  function drain() {
    raf = 0;
    if (rendering || pending === null) return;
    const query = pending;
    pending = null;
    rendering = true;
    const mine = ++generation;

    const probe = new Image();
    probe.onload = () => {
      rendering = false;
      // The asset may have been deselected while this was in flight, in which
      // case the element is gone and the result is not worth showing.
      if (mine === generation && img.isConnected) img.src = probe.src;
      // A newer camera arrived mid-request: go again, so the view settles on the
      // state the user actually let go at.
      if (pending !== null && !raf) raf = requestAnimationFrame(drain);
    };
    probe.onerror = () => {
      rendering = false;
      if (state.selected && state.selected.id === a.id) say('Could not render this model.');
      if (pending !== null && !raf) raf = requestAnimationFrame(drain);
    };
    probe.src = '/api/model?' + query;
  }


  img.addEventListener('pointerdown', (e) => {
    img.setPointerCapture(e.pointerId);
    img.classList.add('drag');
    state.drag = { x: e.clientX, y: e.clientY, az: state.cam.az, el: state.cam.el, moved: false };
  });
  img.addEventListener('pointermove', (e) => {
    if (!state.drag) return;
    const dx = e.clientX - state.drag.x, dy = e.clientY - state.drag.y;
    if (Math.abs(dx) + Math.abs(dy) > 2) state.drag.moved = true;
    // Both axes follow the same rule: the model follows your finger, as if you
    // were grabbing its surface and pulling it.
    //
    // The azimuth sign is the opposite of the drag direction because the camera
    // orbits the model, not the other way round. Growing the azimuth swings the
    // camera anticlockwise seen from above, which swings the model's front face
    // to the *left* on screen -- so dragging right has to decrease it. Verified
    // against the renderer's own matrices: at az=0 a point at +X projects to the
    // right of centre, and at az=+0.3 a point at +Z projects left of centre.
    //
    // Elevation already had the right sign: dragging down raises the camera, so
    // you look down on the top -- the same "pull it toward me" gesture.
    state.cam.az = state.drag.az - dx * 0.012;
    state.cam.el = Math.max(-1.5, Math.min(1.5, state.drag.el + dy * 0.012));
    refresh();
  });
  const endDrag = () => { img.classList.remove('drag'); state.drag = null; };
  img.addEventListener('pointerup', endDrag);
  img.addEventListener('pointercancel', endDrag);
  img.addEventListener('wheel', (e) => {
    e.preventDefault();
    state.cam.d = Math.max(0.6, Math.min(20, state.cam.d * (e.deltaY < 0 ? 0.9 : 1 / 0.9)));
    refresh();
  }, { passive: false });

  requestAnimationFrame(refresh);
  return wrap;
}

function mkToggle(label, onChange) {
  const l = document.createElement('label');
  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.addEventListener('change', onChange);
  const s = document.createElement('span');
  s.textContent = label;
  l.append(cb, s);
  return l;
}

// ------------------------------------------------------------------- tags

async function loadTags() {
  const data = await api('tags');
  const box = $('taglist');
  box.textContent = '';
  const tags = (data.tags || []);
  if (!tags.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'no tags yet';
    box.appendChild(p);
    return;
  }
  for (const t of tags) {
    const c = document.createElement('span');
    c.className = 'chip' + (state.tags.has(t.name) ? ' on' : '');
    c.innerHTML = '';
    const label = document.createElement('span');
    label.textContent = t.name;
    const count = document.createElement('span');
    count.className = 'n';
    count.textContent = t.count;
    c.append(label, count);
    c.addEventListener('click', () => {
      if (state.tags.has(t.name)) state.tags.delete(t.name);
      else state.tags.add(t.name);
      loadTags();
      load();
    });
    box.appendChild(c);
  }
}

async function loadRoots() {
  const data = await api('roots');
  const box = $('rootlist');
  box.textContent = '';
  const roots = data.roots || [];
  if (!roots.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'none indexed';
    box.appendChild(p);
    return;
  }
  for (const r of roots) {
    const d = document.createElement('div');
    d.textContent = (r.watch ? '● ' : '○ ') + r.path;
    box.appendChild(d);
  }
}

async function loadStats() {
  const s = await api('stats');
  const box = $('stats');
  box.textContent = '';
  const rows = [['Assets', fmtCount(s.count)], ['Total size', fmtSize(s.bytes)]];
  const byCat = s.byCategory || {};
  for (const [k, v] of Object.entries(byCat)) {
    rows.push([k.charAt(0) + k.slice(1).toLowerCase(), fmtCount(v)]);
  }
  for (const [k, v] of rows) {
    const d = document.createElement('div');
    d.innerHTML = k + ' <b>' + v + '</b>';
    box.appendChild(d);
  }
}

// ------------------------------------------------------------ batch actions

/** Applies a change, then refreshes whatever it could have affected. */
async function runBatch(fn, what) {
  const ids = actionIds();
  if (!ids.length) { say('Nothing selected.'); return; }
  say(what + '…');
  const r = await fn(ids);
  if (r && r.error) { say(r.error); return; }
  // The reload repaints the status bar, so the message is held in state.notice
  // and re-rendered alongside the counts instead of being overwritten.
  state.keepNotice = true;
  await load();
  await loadTags();
  await loadStats();
  if (state.selected && ids.includes(state.selected.id)) await focusDetail(state.selected.id);
  say(what);
}

/**
 * Shows a one-off message in the status bar. It is stored rather than written
 * straight to the DOM so the next reload() can put the counts back without
 * dropping it on the floor.
 */
function say(text) {
  state.notice = text || null;
  $('statusbar').textContent = text;
}

/** Clears any action message; used when the filters change under the user. */
function clearNotice() { state.notice = null; }

async function batchTagAdd() {
  const tag = window.prompt('Tag ' + state.sel.size + ' selected asset(s) with:');
  if (!tag || !tag.trim()) return;
  await runBatch((ids) => api('batchtag', { ids: ids.join(','), tag: tag.trim(), op: 'add' }),
    'Tagged ' + state.sel.size + ' asset(s) with "' + tag.trim() + '"');
}

async function batchTagRemove() {
  const tag = window.prompt('Remove which tag from the selection?');
  if (!tag || !tag.trim()) return;
  await runBatch((ids) => api('batchtag', { ids: ids.join(','), tag: tag.trim(), op: 'remove' }),
    'Removed "' + tag.trim() + '" from ' + state.sel.size + ' asset(s)');
}

async function batchCopy() {
  const ids = actionIds();
  if (!ids.length) { say('Nothing selected.'); return; }
  const target = await askCopyTarget(ids.length);
  if (!target) { say('Copy cancelled.'); return; }

  if (target.kind === 'dir') {
    // Write each file through the directory handle the picker returned. The
    // server is only asked for the bytes, so it never needs to know the path.
    say('Copying ' + ids.length + ' file(s) via the browser…');
    const items = ids.map((id) => state.items.find((a) => a.id === id)).filter(Boolean);
    let ok = 0;
    const failed = [];
    for (const a of items) {
      try {
        const res = await fetch('/api/file?id=' + a.id);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const blob = await res.blob();
        const fh = await target.handle.getFileHandle(a.name, { create: true });
        await fs.writeHandle(fh, blob);
        ok++;
      } catch (e) {
        failed.push(a.name);
        if (window.console) console.warn('[assetmanager] copy failed ' + a.name + ': '
          + (e && e.message ? e.message : e));
      }
    }
    lastPath.set(target.handle.name);
    say(`Copied ${ok} file(s) into ${target.handle.name}`
      + (failed.length ? `, ${failed.length} failed: ${failed.join(', ')}` : ''));
    return;
  }

  lastPath.set(target.path);
  const r = await api('copyto', { ids: ids.join(','), dest: target.path });
  if (r.error) { say(r.error); return; }
  await reportCopy(r, target.path);
}

async function batchForget() {
  const n = state.sel.size;
  if (!n) { say('Nothing selected.'); return; }
  if (!window.confirm('Remove ' + n + ' asset(s) from the library?\n'
    + 'The files on disk are NOT deleted.')) return;
  const r = await api('forget', { ids: actionIds().join(',') });
  if (r.error) { say(r.error); return; }
  state.sel.clear();
  state.selected = null;
  $('dtitle').textContent = 'No asset selected';
  $('dmeta').textContent = '';
  $('dchips').textContent = '';
  $('preview').textContent = '';
  markSelection();
  state.keepNotice = true;
  await load();
  await loadStats();
  say('Removed ' + r.count + ' asset(s) from the library');
}

/** Where a batch copy landed, reported after the copy so the message survives. */
async function reportCopy(r, dest) {
  state.keepNotice = true;
  await load();
  await loadStats();
  say('Copied ' + r.copied + ' file(s) to ' + dest
    + (r.failed ? ', ' + r.failed + ' failed: ' + r.files.join(', ') : ''));
}

// -------------------------------------------------------------- duplicates

async function showDuplicates() {
  say('Looking for duplicates…');
  const d = await api('duplicates');
  if (d.error) { say(d.error); return; }

  const body = $('dupebody');
  body.textContent = '';
  const groups = d.groups || [];
  $('dupesum').textContent = groups.length
    ? `${groups.length} group(s) · ${fmtSize(d.reclaimable)} reclaimable`
    : '';

  if (!groups.length) {
    const p = document.createElement('p');
    p.className = 'muted center';
    p.style.padding = '40px';
    p.textContent = 'No duplicate files found.';
    body.appendChild(p);
  }

  for (const g of groups) {
    const box = document.createElement('div');
    box.className = 'dupegroup';

    const h = document.createElement('h4');
    const left = document.createElement('span');
    left.textContent = `${g.count}× ${g.items[0].label}`;
    const right = document.createElement('span');
    right.textContent = `${fmtSize(g.size)} each · ${g.hash.slice(0, 12)}…`;
    h.append(left, right);
    box.appendChild(h);

    g.items.forEach((a, i) => {
      const row = document.createElement('div');
      row.className = 'dupeitem';
      const img = document.createElement('img');
      img.loading = 'lazy';
      img.alt = '';
      img.src = thumbUrl(a.id);
      const p = document.createElement('div');
      p.className = 'p';
      p.textContent = a.dir;
      const keep = document.createElement('div');
      keep.className = 'keep';
      keep.textContent = i === 0 ? 'keep' : 'reclaimable';
      row.append(img, p, keep);
      row.addEventListener('click', () => {
        state.sel = new Set(g.items.map((x) => x.id));
        state.anchor = g.items[0].id;
        markSelection();
        focusDetail(a.id);
        closeSheets();
      });
      box.appendChild(row);
    });
    body.appendChild(box);
  }

  $('dupesheet').classList.remove('hidden');
  say(`${groups.length} duplicate group(s), ${fmtSize(d.reclaimable)} reclaimable`);
}

function closeSheets() {
  $('sheet').classList.add('hidden');
  $('dupesheet').classList.add('hidden');
}

// --------------------------------------------------------- texture workbench

const wb = {
  asset: null,      // the image being edited
  // {route, params} for whatever produced the image on screen, so saving
  // replays that and not some earlier operation.
  current: null,
  result: null,     // last result URL, for reference
  resultSize: null,
  atlasMeta: null,   // the last placement map, for the UV table
  sources: [],      // the selection, for the atlas
  seq: 0,           // cache-buster, so a repeat op re-fetches
};

function wbCanEdit() {
  const a = state.selected;
  return !!a && a.category === 'IMAGE' && a.preview === 'image';
}

async function openWorkbench() {
  if (!wbCanEdit()) { say('Select an image asset first.'); return; }
  const a = state.selected;
  wb.asset = a;
  // The atlas packs the selection, so it has to come from the ids currently
  // highlighted, not from whatever happens to be focused.
  wb.sources = [...state.sel]
    .map((id) => state.items.find((x) => x.id === id))
    .filter((x) => x && x.category === 'IMAGE' && x.preview === 'image');
  if (!wb.sources.length) wb.sources = [a];

  $('wbinfo').textContent = `${a.name}  ·  ${a.width}×${a.height}  ·  ${fmtSize(a.size)}`;
  $('wbatlascount').textContent = wb.sources.length > 1
    ? `${wb.sources.length} images in the selection` : '';
  wbStatus('Ready. Pick an operation.');

  const src = document.createElement('img');
  src.src = '/api/image?id=' + a.id;
  $('wbsrc').textContent = '';
  $('wbsrc').appendChild(src);

  $('wbw').value = a.width;
  $('wbh').value = a.height;
  $('wbcw').value = a.width;
  $('wbch').value = a.height;
  $('wbcx').value = 0;
  $('wbcy').value = 0;
  wb.result = null;
  wb.current = null;
  wb.resultSize = null;
  hideAtlasInfo();
  showResult(null);

  await wbInitControls();
  $('sheet').classList.remove('hidden');
}

function showResult(url) {
  const box = $('wbres');
  box.textContent = '';
  if (!url) { box.appendChild(mutedNoPad('result')); return; }
  const img = document.createElement('img');
  img.src = url;
  box.appendChild(img);
}

function mutedNoPad(text) {
  const p = document.createElement('p');
  p.className = 'muted';
  p.textContent = text;
  return p;
}

function wbStatus(text) { $('wbstatus').textContent = text; }

async function wbInitControls() {
  const d = await api('formats');
  const channels = d.channels || ['R', 'G', 'B', 'A'];
  for (const sel of document.querySelectorAll('#wbpack .chan')) {
    const want = sel.dataset.k.toUpperCase();
    sel.textContent = '';
    for (const c of channels) {
      const o = document.createElement('option');
      o.value = c;
      o.textContent = c;
      if (c === want) o.selected = true;
      sel.appendChild(o);
    }
  }
  const fmt = $('wbfmt');
  const keep = fmt.value;
  fmt.textContent = '';
  for (const f of d.formats || [{ ext: 'png', label: 'PNG' }]) {
    const o = document.createElement('option');
    o.value = f.ext;
    o.textContent = f.label;
    fmt.appendChild(o);
  }
  if (keep) fmt.value = keep;
  if (!fmt.value) fmt.value = 'png';
}

/** Collects the query for an operation, straight off the form fields. */
function wbParams(op) {
  const p = { id: wb.asset.id, op };
  const n = (k) => $(k).value;
  if (op === 'resize') {
    p.w = Number(n('wbw')) || wb.asset.width;
    p.h = Number(n('wbh')) || wb.asset.height;
  } else if (op === 'pot') {
    if ($('wbup').checked) p.upscale = 1;
  } else if (op === 'crop') {
    p.x = Number(n('wbcx')) || 0;
    p.y = Number(n('wbcy')) || 0;
    p.w = Number(n('wbcw')) || 0;
    p.h = Number(n('wbch')) || 0;
  } else if (op === 'pack') {
    for (const sel of document.querySelectorAll('#wbpack .chan')) p[sel.dataset.k] = sel.value;
  } else if (op === 'normal') {
    p.strength = (Number($('wbnorm').value) / 100).toFixed(2);
  } else if (op === 'opacity') {
    p.factor = (Number($('wbop').value) / 100).toFixed(2);
  }
  return p;
}

async function wbRun(op) {
  const p = wbParams(op);
  wbStatus(OP_NAMES[op] + '…');
  const url = '/api/imgtool?' + new URLSearchParams(p).toString() + '&v=' + (++wb.seq);
  const img = new Image();
  img.onload = () => {
    wb.result = url;
    // Save replays the operation that produced this image; see wbSave.
    wb.current = { route: 'imgtool', params: p };
    wb.resultSize = { w: img.naturalWidth, h: img.naturalHeight };
    showResult(url);
    wbStatus(`${OP_NAMES[op]}  ·  ${img.naturalWidth} × ${img.naturalHeight} px`);
    if (op === 'resize' || op === 'pot' || op === 'crop' || op === 'crop50') {
      $('wbw').value = img.naturalWidth;
      $('wbh').value = img.naturalHeight;
    }
  };
  img.onerror = () => wbStatus(OP_NAMES[op] + ' failed.');
  img.src = url;
}

const OP_NAMES = {
  resize: 'Resize', pot: 'Power of two', rot90: 'Rotate 90°', rot270: 'Rotate 270°',
  fliph: 'Flip horizontal', flipv: 'Flip vertical', crop: 'Crop', crop50: 'Crop centre 50%',
  pack: 'Pack channels', normal: 'Normal map', opacity: 'Opacity',
};

async function wbSplit() {
  wbStatus('Splitting channels…');
  const r = await api('channelsplit', { id: wb.asset.id });
  if (r.error) { wbStatus(r.error); return; }
  wbStatus(`Wrote ${r.count} channel images beside the source`);
  await load();
}

async function wbAtlas() {
  const ids = wb.sources.map((a) => a.id);
  wbStatus(`Packing ${ids.length} image(s)…`);
  hideAtlasInfo();
  const p = { ids: ids.join(','), pad: Number($('wbpad').value) || 0 };
  const url = '/api/atlas?' + new URLSearchParams(p).toString() + '&v=' + (++wb.seq);
  const img = new Image();
  img.onload = async () => {
    wb.result = url;
    // Save has to replay whatever produced the image on screen. Recorded as one
    // value so running a single-image op after an atlas cannot leave the atlas
    // parameters behind and save the wrong picture.
    wb.current = { route: 'atlas', params: p };
    showResult(url);
    wbStatus(`Atlas ${img.naturalWidth} × ${img.naturalHeight}, ${ids.length} images`);
    await loadAtlasInfo(p);
  };
  img.onerror = () => wbStatus('Atlas packing failed.');
  img.src = url;
}

/**
 * Fetches the placement map and shows it.
 *
 * An atlas on its own is half an answer: an engine needs each tile's UV rectangle
 * to address anything in it, so the coordinates are the useful half. The server
 * packs again for this call, which is cheap next to the encode the preview needed.
 */
async function loadAtlasInfo(params) {
  const meta = await api('atlas', { ...params, meta: 1 });
  if (meta.error) return;
  wb.atlasMeta = meta;

  const pct = (meta.efficiency * 100).toFixed(1);
  $('wbatlassum').textContent =
    `${meta.width}×${meta.height} · ${meta.count} tile(s) · padding ${meta.padding}px`
    + ` · ${pct}% filled · UV origin top-left`;

  const body = $('wbatlastable');
  body.textContent = '';
  for (const t of meta.tiles || []) {
    const tr = document.createElement('tr');
    const cells = [t.name, t.x, t.y, t.w, t.h,
      t.u0.toFixed(4), t.v0.toFixed(4), t.u1.toFixed(4), t.v1.toFixed(4)];
    for (const c of cells) {
      const td = document.createElement('td');
      td.textContent = c;
      tr.appendChild(td);
    }
    body.appendChild(tr);
  }
  $('wbatlasinfo').classList.remove('hidden');
}

function hideAtlasInfo() {
  $('wbatlasinfo').classList.add('hidden');
  $('wbatlastable').textContent = '';
  wb.atlasMeta = null;
}

/** The placement map as JSON, in the shape most importers want. */
function atlasUvJson() {
  if (!wb.atlasMeta) return null;
  const out = {
    image: `${wb.atlasMeta.width}x${wb.atlasMeta.height}`,
    padding: wb.atlasMeta.padding,
    fill: +wb.atlasMeta.efficiency.toFixed(4),
    frames: {},
  };
  for (const t of wb.atlasMeta.tiles || []) {
    // Key without the extension, which is how sprite sheets are usually addressed.
    const key = t.name.replace(/\.[^.]+$/, '');
    out.frames[key] = {
      frame: { x: t.x, y: t.y, w: t.w, h: t.h },
      rotated: false,
      trimmed: false,
      spriteSourceSize: { x: 0, y: 0, w: t.w, h: t.h },
      sourceSize: { w: t.w, h: t.h },
      uv: { u0: t.u0, v0: t.v0, u1: t.u1, v1: t.v1 },
    };
  }
  return JSON.stringify(out, null, 2);
}

async function wbSave(overwrite) {
  if (!wb.result || !wb.current) { wbStatus('Nothing to save yet.'); return; }
  const fmt = $('wbfmt').value;
  const isAtlas = wb.current.route === 'atlas';

  if (overwrite) {
    // The server already knows this path, so let it write: the write is atomic
    // and the file is re-indexed straight away.
    if (isAtlas) { wbStatus('An atlas cannot overwrite a file. Use "Save as".'); return; }
    wbStatus('Saving…');
    const r = await api('imgtool', { ...wb.current.params, save: 1, overwrite: 1, format: fmt });
    if (r.error) { wbStatus(r.error); return; }
    wbStatus(`Wrote ${r.file}  (${r.width}×${r.height} ${String(r.format).toUpperCase()})`);
    state.keepNotice = true;
    await load();
    await loadStats();
    return;
  }

  const stem = isAtlas ? 'atlas' : baseName(wb.asset.name);
  const target = await askSaveTarget(stem + '_out.' + fmt, fmt);
  if (!target) { wbStatus('Save cancelled.'); return; }

  if (target.kind === 'handle') {
    // The server encodes, the browser writes: inline=1 is the no-write variant.
    wbStatus('Encoding…');
    const url = '/api/' + wb.current.route + '?' + new URLSearchParams({
      ...wb.current.params, inline: 1, format: fmt,
    }).toString();
    const res = await fetch(url);
    if (!res.ok) { wbStatus('Encode failed: ' + (await res.text())); return; }
    const blob = await res.blob();
    wbStatus('Writing…');
    try {
      const name = await fs.writeHandle(target.handle, blob);
      lastPath.set(name);
      wbStatus(`Wrote ${name}  (${fmtSize(blob.size)} ${fmt.toUpperCase()}) via the browser`);
      say(`Saved ${name}. AssetManager only indexes folders it has been given, `
        + 'so use Add folder if you want it in the library.');
    } catch (e) {
      wbStatus('Write failed: ' + (e && e.message ? e.message : e));
    }
    return;
  }

  // Path fallback: the Java process writes, exactly as before.
  lastPath.set(target.path);
  wbStatus('Saving…');
  const r = await api(wb.current.route, { ...wb.current.params, save: 1, dest: target.path, format: fmt });
  if (r.error) { wbStatus(r.error); return; }
  wbStatus(`Wrote ${r.file}  (${r.width}×${r.height} ${String(r.format).toUpperCase()})`);
  state.keepNotice = true;
  await load();
  await loadStats();
}

/** Strips the extension; mirrors Formats.baseName so the dialog matches the source. */
function baseName(name) {
  const i = name.lastIndexOf('.');
  return i > 0 ? name.slice(0, i) : name;
}

// ------------------------------------------------------------------ wiring

function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

function init() {
  $('search').addEventListener('input', debounce(load, 220));
  for (const id of ['category', 'sort', 'mindim', 'untagged', 'duplicates', 'problems']) {
    $(id).addEventListener('change', () => { clearNotice(); load(); });
  }
  $('cellsize').addEventListener('input', (e) => {
    state.cell = Number(e.target.value);
    document.documentElement.style.setProperty('--cell', state.cell + 'px');
  });

  $('rescan').addEventListener('click', async () => {
    clearNotice();
    say('scan requested...');
    await api('scan', {});
    pollScan();
  });

  $('addfolder').addEventListener('click', async () => {
    // No native dialog here, and there cannot be one: indexing means the Java
    // process walks the folder, so it needs an absolute path -- and no file
    // picker in any browser will hand a web page one.
    const p = await pathDialog({
      title: 'Add a folder to the library',
      hint: 'Absolute path of a folder to index. AssetManager indexes this folder and '
          + 'keeps watching it for changes.',
      value: lastPath.dir(),
      okLabel: 'Add folder',
      suggestions: await folderSuggestions(),
      check: (v) => api('checkpath', { path: v }),
    });
    if (!p) { say('Add folder cancelled.'); return; }
    lastPath.set(p);
    const r = await api('addfolder', { path: p });
    if (r.error) { say(r.error); return; }
    pollScan();
  });

  $('dupes').addEventListener('click', showDuplicates);
  $('dupeclose').addEventListener('click', closeSheets);
  $('dupeshow').addEventListener('click', async () => {
    $('duplicates').checked = true;
    closeSheets();
    await load();
  });

  $('clearcache').addEventListener('click', async () => {
    if (!window.confirm('Delete every cached thumbnail and waveform?\nThey are rebuilt on demand.')) return;
    await api('clearcache', {});
    await load();
    say('Thumbnail and waveform cache cleared');
  });

  // ---- selection actions
  $('seltag').addEventListener('click', batchTagAdd);
  $('seluntag').addEventListener('click', batchTagRemove);
  $('selcopy').addEventListener('click', batchCopy);
  $('selforget').addEventListener('click', batchForget);
  $('seltools').addEventListener('click', openWorkbench);
  $('selselectall').addEventListener('click', selectAll);
  $('selnone').addEventListener('click', clearSelection);

  // ---- workbench
  $('wbclose').addEventListener('click', closeSheets);
  for (const b of document.querySelectorAll('.wbctl [data-op]')) {
    b.addEventListener('click', () => wbRun(b.dataset.op));
  }
  for (const b of document.querySelectorAll('.wbctl [data-act]')) {
    b.addEventListener('click', () => (b.dataset.act === 'split' ? wbSplit() : wbAtlas()));
  }
  $('wbsaveas').addEventListener('click', () => wbSave(false));
  $('wboverwrite').addEventListener('click', () => wbSave(true));
  $('wbatlashide').addEventListener('click', hideAtlasInfo);
  $('wbatlascopy').addEventListener('click', async () => {
    const json = atlasUvJson();
    if (!json) { wbStatus('Pack an atlas first.'); return; }
    // Prefer a real save dialog, so the UV map lands wherever the user wants.
    if (fs.canSave) {
      try {
        const name = await fs.writeHandle(
          await fs.saveFile((wb.atlasMeta ? 'atlas' : 'atlas') + '.json', 'json'), new Blob([json]));
        wbStatus('Wrote ' + name + ' via the browser');
        return;
      } catch (e) {
        if (e && e.name === 'AbortError') { wbStatus('Save cancelled.'); return; }
      }
    }
    // Clipboard first, since that is usually what is wanted; a read-only view of
    // the text is the last resort so the data is never trapped in a dialog.
    try {
      await navigator.clipboard.writeText(json);
      wbStatus('UV map copied to the clipboard');
    } catch {
      showText('UV map', json);
    }
  });
  $('wbnorm').addEventListener('input', (e) => {
    $('wbnormv').textContent = (Number(e.target.value) / 100).toFixed(2);
  });
  $('wbop').addEventListener('input', (e) => {
    $('wbopv').textContent = (Number(e.target.value) / 100).toFixed(2);
  });

  $('tagadd').addEventListener('click', addTag);
  $('taginput').addEventListener('keydown', (e) => { if (e.key === 'Enter') addTag(); });

  $('note').addEventListener('change', async () => {
    if (!state.selected) return;
    await api('setnote', { id: state.selected.id, note: $('note').value });
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && document.activeElement !== $('search')) { e.preventDefault(); $('search').focus(); }
    if (e.key === 'Escape') {
      // Escape backs out one layer: close a sheet, else drop the tag filters.
      if (!$('sheet').classList.contains('hidden') || !$('dupesheet').classList.contains('hidden')) {
        closeSheets();
        return;
      }
      clearNotice();
      state.tags.clear();
      loadTags();
      load();
      return;
    }
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement.tagName);
    if (typing) return;
    if (e.key === 'a' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); selectAll(); }
    if (e.key === 'w' && state.selected) { e.preventDefault(); openWorkbench(); }
    if (e.key === 'F2' && state.sel.size) { e.preventDefault(); batchTagAdd(); }
    if (e.key === 'Delete' && state.sel.size) { e.preventDefault(); batchForget(); }
  });

  load();
  loadTags();
  loadRoots();
  loadStats();
  markSelection();
  setInterval(loadStats, 5000);
}

async function addTag() {
  const v = $('taginput').value.trim();
  if (!v || !state.selected) return;
  await api('addtag', { id: state.selected.id, tag: v });
  $('taginput').value = '';
  focusDetail(state.selected.id);
  loadTags();
}

let pollTimer = null;
function pollScan() {
  clearInterval(pollTimer);
  pollTimer = setInterval(async () => {
    const d = await api('assets', { limit: 1 });
    renderStatus(d.scan);
    if (!d.scan || !d.scan.startsWith('scanning')) {
      clearInterval(pollTimer);
      load();
      loadTags();
      loadRoots();
      loadStats();
    }
  }, 1200);
}

document.addEventListener('DOMContentLoaded', init);
