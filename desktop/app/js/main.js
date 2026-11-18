import * as pdfjsLib from '../vendor/pdf.min.mjs';
import {
  COLORS, COLOR_NAMES, TAGS, TAG_KEYS, Book, Prefs, cssColor, hexColor, makeFp, normalizeAr,
  loadRecent, addRecent, removeRecent, buildBackup, applyBackup
} from './store.js';
import { Viewer, paintMark } from './viewer.js';
import { $, el, toast, modal, closeModal, isModalOpen, download, debounce } from './ui.js';

// نسخة الويب المدمجة بتحط __WORKER_URL__ (عامل pdf.js كـ blob)، ونسخة الكمبيوتر بتحمّله من الملفات.
const WEB = !!window.__WEB_BUILD__;
const rel = (p) => new URL(p, import.meta.url).href;
pdfjsLib.GlobalWorkerOptions.workerSrc = WEB ? window.__WORKER_URL__ : rel('../vendor/pdf.worker.min.mjs');
const PDF_OPTS = WEB
  ? {}
  : { cMapUrl: rel('../vendor/cmaps/'), cMapPacked: true, standardFontDataUrl: rel('../vendor/standard_fonts/') };

const isDesktop = !!window.desktop;
const TOOL_HINTS = {
  hand: 'اسحب لتحريك الصفحة. اضغط على تمييز لتعديله.',
  hl: 'اسحب على الكلام لتمييز الجزء المحدد (حتى أكتر من سطر). السحب من مكان فاضي بيحرّك الصفحة.',
  brush: 'اسحب على الكلام لرسم خط مستقيم (بيتعدّل لأفقي أو رأسي لو الميل بسيط).',
  eraser: 'اضغط على تمييز أو مربع نص لحذفه.',
  text: 'اضغط في أي مكان فاضي من الصفحة لإضافة مربع نص.'
};

let book = null;
let current = null; // {name,size,fp}
let panelOpen = Prefs.get('panel', false);
let filterKey = 'all';
let flipRtl = Prefs.get('flipRtl', true);

// ---------------------------------------------------------------- العارض
const viewer = new Viewer($('scroller'), $('pages'), {
  onPage(i, silent) {
    $('pageInput').value = String(i + 1);
    if (book) {
      book.page = i;
      book.save();
    }
    if (panelOpen) markCurrentInPanel();
  },
  onZoom(z) {
    $('zoomLabel').textContent = Math.round(z * 100) + '%';
  },
  onMarksChanged() {
    updateUndo();
    if (panelOpen) renderPageList();
  },
  toast,
  editMark,
  editBox,
  addBox
});
viewer.night = Prefs.get('night', false);
viewer.enhance = Prefs.get('enhance', false);
viewer.rtl = Prefs.get('rtl', true);
viewer.colorIdx = Math.min(COLORS.length - 1, Math.max(0, Prefs.get('color', 0)));
viewer.alpha = Prefs.get('alpha', 55);
viewer.mode = Prefs.get('mode', 'cont');

// ---------------------------------------------------------------- فتح الملفات
async function openBytes(name, bytes, path) {
  const size = bytes.length;
  const fp = makeFp(name, size);
  let pdf;
  try {
    pdf = await pdfjsLib.getDocument({ data: bytes.slice(), ...PDF_OPTS }).promise;
  } catch (e) {
    toast('تعذر فتح الملف: ' + (e && e.message ? e.message : e));
    return false;
  }
  if (book) book.saveNow();
  book = new Book(fp, name);
  current = { name, size, fp, path: path || '' };
  addRecent({ fp, name, size, path: path || '', t: Date.now() });
  $('home').hidden = true;
  $('app').hidden = false;
  $('docTitle').textContent = name.replace(/\.pdf$/i, '');
  document.title = name + ' — Simple PDF Reader';
  syncUi();
  await viewer.load(pdf, book, book.page);
  $('pageTotal').textContent = '/ ' + pdf.numPages;
  updateUndo();
  if (panelOpen) refreshPanel();
  return true;
}

async function openFile(file) {
  if (!file) return;
  const buf = new Uint8Array(await file.arrayBuffer());
  let path = '';
  if (isDesktop) path = window.desktop.pathForFile(file) || '';
  await openBytes(file.name, buf, path);
}

async function openPath(p) {
  try {
    const bytes = await window.desktop.readFile(p);
    const name = p.split(/[\\/]/).pop();
    await openBytes(name, new Uint8Array(bytes), p);
  } catch (e) {
    toast('تعذر فتح الملف: ' + (e && e.message ? e.message : e));
  }
}

function goHome() {
  if (book) book.saveNow();
  viewer.unload();
  book = null;
  current = null;
  $('app').hidden = true;
  $('home').hidden = false;
  document.title = 'Simple PDF Reader';
  renderRecent();
}

// ---------------------------------------------------------------- المكتبة
function renderRecent() {
  const box = $('recentList');
  box.innerHTML = '';
  const list = loadRecent();
  $('recentTitle').hidden = list.length === 0;
  for (const r of list) {
    const b = new Book(r.fp, r.name);
    const info = [];
    if (b.marks.length) info.push(b.marks.length + ' تمييز');
    const pages = Object.keys(b.info).length;
    if (pages) info.push(pages + ' صفحة موسومة');
    if (b.page) info.push('آخر صفحة: ' + (b.page + 1));
    box.append(
      el(
        'button',
        {
          class: 'recent',
          onclick: () => {
            if (isDesktop && r.path) {
              openPath(r.path).then(() => {});
            } else {
              toast('اختار الملف من زر "فتح ملف PDF"');
              $('fileInput').click();
            }
          }
        },
        el('div', { class: 'rn', text: r.name.replace(/\.pdf$/i, '') }),
        el('div', { class: 'rs', text: info.join(' · ') || 'لسه ما اتميّزش' })
      )
    );
  }
}

$('btnOpen').onclick = () => $('fileInput').click();
$('fileInput').onchange = (e) => {
  const f = e.target.files[0];
  e.target.value = '';
  openFile(f);
};
$('btnHome').onclick = goHome;

document.addEventListener('dragover', (e) => {
  e.preventDefault();
  document.body.classList.add('dragging');
});
document.addEventListener('dragleave', (e) => {
  if (e.target === document.documentElement || e.relatedTarget === null) document.body.classList.remove('dragging');
});
document.addEventListener('drop', (e) => {
  e.preventDefault();
  document.body.classList.remove('dragging');
  const f = [...(e.dataTransfer.files || [])].find((x) => /\.pdf$/i.test(x.name));
  if (f) openFile(f);
});

// ---------------------------------------------------------------- النسخة الاحتياطية
function doBackup() {
  const data = buildBackup(book);
  if (!data.books.length) {
    toast('مفيش تمييزات أو ملاحظات تتحفظ لسه');
    return;
  }
  const d = new Date().toISOString().slice(0, 10);
  download(`pdfreader-backup-${d}.json`, JSON.stringify(data), 'application/json');
  toast(`تم حفظ نسخة فيها ${data.books.length} كتاب`);
}
function doRestore() {
  modal(
    'استرجاع نسخة احتياطية',
    el('p', { text: 'التمييزات والأسماء والتعليقات في النسخة هتحل محل الموجود لنفس الكتب. باقي الكتب مش هتتأثر. تنفع نسخة من التابلت.' }),
    [
      { label: 'اختيار الملف', kind: 'primary', onClick: () => $('restoreInput').click() },
      { label: 'إلغاء' }
    ]
  );
}
$('restoreInput').onchange = async (e) => {
  const f = e.target.files[0];
  e.target.value = '';
  if (!f) return;
  try {
    const root = JSON.parse(await f.text());
    const n = applyBackup(root);
    if (book) {
      book = new Book(book.fp, book.name);
      viewer.book = book;
      viewer.refreshAllPages();
      updateUndo();
      if (panelOpen) refreshPanel();
    } else {
      renderRecent();
    }
    toast(`تم استرجاع ${n} كتاب. افتح الكتاب وهتلاقي تمييزاتك`);
  } catch (err) {
    toast('فشل الاسترجاع: ' + (err && err.message ? err.message : err));
  }
};
$('btnBackup').onclick = doBackup;
$('btnRestore').onclick = doRestore;

// ---------------------------------------------------------------- شريط الأدوات
function syncUi() {
  document.body.classList.toggle('night', viewer.night);
  $('btnNight').classList.toggle('on', viewer.night);
  $('btnEnhance').classList.toggle('on', viewer.enhance);
  $('btnMode').textContent = viewer.mode === 'cont' ? 'عرض متواصل' : 'صفحة واحدة';
  $('opacity').value = String(viewer.alpha);
  $('zoomLabel').textContent = Math.round(viewer.zoom * 100) + '%';
  $('btnPanel').classList.toggle('on', panelOpen);
  $('panel').hidden = !panelOpen;
  setToolUi();
  renderPalette();
}

function setToolUi() {
  document.querySelectorAll('.tool').forEach((b) => b.classList.toggle('on', b.dataset.tool === viewer.tool));
  $('toolHint').textContent = TOOL_HINTS[viewer.tool] || '';
}
function setTool(t) {
  viewer.setTool(t);
  setToolUi();
}
document.querySelectorAll('.tool').forEach((b) => (b.onclick = () => setTool(b.dataset.tool)));

function renderPalette() {
  const p = $('palette');
  p.innerHTML = '';
  COLORS.forEach((c, i) => {
    const s = el('button', {
      class: 'swatch' + (i === viewer.colorIdx ? ' on' : ''),
      title: COLOR_NAMES[i],
      onclick: () => {
        viewer.colorIdx = i;
        Prefs.set('color', i);
        renderPalette();
        if (viewer.tool !== 'hl' && viewer.tool !== 'brush') setTool('hl');
      }
    });
    s.style.background = cssColor(c, 1);
    p.append(s);
  });
}
$('opacity').oninput = (e) => {
  viewer.alpha = parseInt(e.target.value, 10);
  Prefs.set('alpha', viewer.alpha);
};

function updateUndo() {
  $('btnUndo').disabled = !book || !book.undoStack.length;
  $('btnRedo').disabled = !book || !book.redoStack.length;
}
function doUndo() {
  if (book && book.undo()) {
    viewer.refreshAllPages();
    updateUndo();
    if (panelOpen) renderPageList();
  }
}
function doRedo() {
  if (book && book.redo()) {
    viewer.refreshAllPages();
    updateUndo();
    if (panelOpen) renderPageList();
  }
}
$('btnUndo').onclick = doUndo;
$('btnRedo').onclick = doRedo;

$('btnNight').onclick = () => {
  viewer.setNight(!viewer.night);
  Prefs.set('night', viewer.night);
  syncUi();
};
$('btnEnhance').onclick = () => {
  viewer.setEnhance(!viewer.enhance);
  Prefs.set('enhance', viewer.enhance);
  syncUi();
};
$('btnMode').onclick = () => {
  viewer.setMode(viewer.mode === 'cont' ? 'single' : 'cont');
  Prefs.set('mode', viewer.mode);
  syncUi();
};

// التنقل والتكبير
$('btnPrev').onclick = () => viewer.goTo(viewer.cur - 1);
$('btnNext').onclick = () => viewer.goTo(viewer.cur + 1);
$('btnZoomIn').onclick = () => viewer.zoomBy(1.15);
$('btnZoomOut').onclick = () => viewer.zoomBy(1 / 1.15);
$('zoomLabel').onclick = () => viewer.setZoom(1);
$('pageInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    const n = parseInt($('pageInput').value.replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x660)), 10);
    if (!isNaN(n)) viewer.goTo(n - 1);
    $('pageInput').blur();
  }
});

// ---------------------------------------------------------------- نوافذ التعديل
function swatchRow(selected, onPick) {
  const row = el('div', { class: 'group', style: 'flex-wrap:wrap' });
  const draw = (sel) => {
    row.innerHTML = '';
    COLORS.forEach((c, i) => {
      const s = el('button', {
        class: 'swatch' + (c === sel ? ' on' : ''),
        title: COLOR_NAMES[i],
        onclick: () => {
          onPick(c);
          draw(c);
        }
      });
      s.style.background = cssColor(c, 1);
      row.append(s);
    });
  };
  draw(selected);
  return row;
}

function editMark(m, pageIdx) {
  let remembered = false;
  const touch = () => {
    if (!remembered) {
      book.remember();
      remembered = true;
    }
  };
  const label = el('span', { text: 'الشفافية: ' + m.a + '%' });
  const range = el('input', { type: 'range', min: '10', max: '90', step: '5', value: String(m.a), style: 'width:100%' });
  range.oninput = () => {
    touch();
    m.a = parseInt(range.value, 10);
    label.textContent = 'الشفافية: ' + m.a + '%';
    viewer.refreshPage(pageIdx);
    book.save();
  };
  const body = el(
    'div',
    {},
    el('label', { class: 'f', text: 'اللون' }),
    swatchRow(m.c, (c) => {
      touch();
      m.c = c;
      viewer.refreshPage(pageIdx);
      book.save();
    }),
    el('label', { class: 'f' }, label),
    range
  );
  modal('تعديل التمييز', body, [
    { label: 'تم', kind: 'primary', onClick: () => { updateUndo(); } },
    {
      label: 'حذف التمييز',
      kind: 'danger',
      onClick: () => {
        if (!remembered) book.remember();
        book.marks.splice(book.marks.indexOf(m), 1);
        book.save();
        viewer.refreshPage(pageIdx);
        updateUndo();
        if (panelOpen) renderPageList();
      }
    }
  ]);
}

function boxDialog(title, initial, size, onSave, onDelete) {
  const ta = el('textarea', { placeholder: 'اكتب النص هنا' });
  ta.value = initial;
  const sizeInput = el('input', { type: 'range', min: '12', max: '48', step: '2', value: String(size), style: 'width:100%' });
  const label = el('span', { text: 'حجم الخط: ' + size });
  sizeInput.oninput = () => (label.textContent = 'حجم الخط: ' + sizeInput.value);
  const buttons = [
    {
      label: 'حفظ',
      kind: 'primary',
      onClick: () => {
        const v = ta.value.trim();
        if (!v) return false;
        onSave(v, parseInt(sizeInput.value, 10));
      }
    },
    { label: 'إلغاء' }
  ];
  if (onDelete) buttons.splice(1, 0, { label: 'حذف', kind: 'danger', onClick: onDelete });
  modal(title, el('div', {}, ta, el('label', { class: 'f' }, label), sizeInput), buttons);
}

function addBox(page, x, y) {
  boxDialog('مربع نص جديد', '', 18, (t, s) => {
    book.remember();
    book.boxes.push({ p: page, x, y, t, c: 0xff000000 | 0, s });
    book.save();
    viewer.refreshPage(page);
    updateUndo();
  });
}
function editBox(tb, page) {
  boxDialog(
    'تعديل مربع النص',
    tb.t,
    tb.s || 18,
    (t, s) => {
      book.remember();
      tb.t = t;
      tb.s = s;
      book.save();
      viewer.refreshPage(page);
      updateUndo();
    },
    () => {
      book.remember();
      book.boxes.splice(book.boxes.indexOf(tb), 1);
      book.save();
      viewer.refreshPage(page);
      updateUndo();
    }
  );
}

function editPageInfo(i) {
  if (!book) return;
  const x = book.pageInfo(i);
  const title = el('input', { type: 'text', placeholder: 'اسم الصفحة (مثلاً: تعريف الأهلية)' });
  title.value = x.title;
  const comment = el('textarea', { placeholder: 'تعليقك على الصفحة' });
  comment.value = x.comment;
  const checks = el('div', { class: 'checks' });
  const boxes = {};
  for (const k of TAG_KEYS) {
    const cb = el('input', { type: 'checkbox' });
    cb.checked = x.tags.has(k);
    boxes[k] = cb;
    const chip = el('span', { class: 'tagchip', text: TAGS[k].label });
    chip.style.background = TAGS[k].color;
    checks.append(el('label', {}, cb, chip));
  }
  modal(
    `الصفحة ${i + 1}`,
    el('div', {}, el('label', { class: 'f', text: 'الاسم' }), title, el('label', { class: 'f', text: 'النوع' }), checks, el('label', { class: 'f', text: 'التعليق' }), comment),
    [
      {
        label: 'حفظ',
        kind: 'primary',
        onClick: () => {
          const tags = new Set(TAG_KEYS.filter((k) => boxes[k].checked));
          book.setPageInfo(i, title.value, comment.value, tags);
          viewer.refreshPage(i);
          if (panelOpen) renderPageList();
        }
      },
      { label: 'إلغاء' }
    ]
  );
}
$('btnTag').onclick = () => viewer.pdf && editPageInfo(viewer.cur);

// ---------------------------------------------------------------- اللوحة الجانبية
$('btnPanel').onclick = () => togglePanel();
function togglePanel(force) {
  panelOpen = force === undefined ? !panelOpen : force;
  Prefs.set('panel', panelOpen);
  $('panel').hidden = !panelOpen;
  $('btnPanel').classList.toggle('on', panelOpen);
  if (panelOpen) refreshPanel();
  setTimeout(() => viewer.relayout(false), 0);
}

document.querySelectorAll('.tab').forEach((t) => {
  t.onclick = () => {
    document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('on', x === t));
    $('pagesTab').hidden = t.dataset.tab !== 'pages';
    $('outlineTab').hidden = t.dataset.tab !== 'outline';
    if (t.dataset.tab === 'outline') renderOutline();
  };
});

const CHIPS = [
  ['all', 'الكل'],
  ['main', 'رئيسي'],
  ['sub', 'جانبي'],
  ['ref', 'مرجعي'],
  ['star', 'مهمة'],
  ['comment', 'بتعليق'],
  ['marks', 'فيها تمييز']
];
function renderChips() {
  const c = $('chips');
  c.innerHTML = '';
  for (const [k, label] of CHIPS) {
    c.append(
      el('button', {
        class: 'chip' + (filterKey === k ? ' on' : ''),
        text: label,
        onclick: () => {
          filterKey = k;
          renderChips();
          renderPageList();
        }
      })
    );
  }
}

function pagePasses(i, q) {
  if (!book.matches(i, q)) return false;
  const x = book.pageInfo(i);
  switch (filterKey) {
    case 'all':
      return true;
    case 'comment':
      return !!x.comment;
    case 'marks':
      return book.marksOn(i).length > 0;
    default:
      return x.tags.has(filterKey);
  }
}

let thumbQueue = Promise.resolve();
let thumbIO = null;
function renderThumb(canvas, i) {
  thumbQueue = thumbQueue.then(async () => {
    if (!viewer.pdf || !canvas.isConnected) return;
    try {
      const page = await viewer.pdf.getPage(i + 1);
      const vp0 = page.getViewport({ scale: 1 });
      const sc = 140 / vp0.width;
      const vp = page.getViewport({ scale: sc });
      canvas.width = Math.floor(vp.width);
      canvas.height = Math.floor(vp.height);
      await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise;
    } catch (e) {
      /* تجاهل */
    }
  });
}

function renderPageList() {
  if (!book || !viewer.pdf) return;
  const q = normalizeAr($('search').value.trim());
  const list = $('pageList');
  list.innerHTML = '';
  if (thumbIO) thumbIO.disconnect();
  thumbIO = new IntersectionObserver(
    (entries) => {
      for (const en of entries) {
        if (en.isIntersecting && !en.target._done) {
          en.target._done = true;
          renderThumb(en.target, parseInt(en.target.dataset.i, 10));
          thumbIO.unobserve(en.target);
        }
      }
    },
    { root: list, rootMargin: '300px' }
  );
  let count = 0;
  for (let i = 0; i < viewer.n; i++) {
    if (!pagePasses(i, q)) continue;
    count++;
    const x = book.pageInfo(i);
    const cv = el('canvas', { 'data-i': String(i) });
    cv.width = 70;
    cv.height = 96;
    const tags = el('div');
    for (const k of TAG_KEYS) {
      if (x.tags.has(k)) {
        const c = el('span', { class: 'tagchip', text: TAGS[k].short });
        c.style.background = TAGS[k].color;
        tags.append(c);
      }
    }
    const mk = book.marksOn(i).length;
    const item = el(
      'div',
      { class: 'pitem' + (i === viewer.cur ? ' cur' : ''), 'data-i': String(i), onclick: () => viewer.goTo(i), ondblclick: () => editPageInfo(i) },
      cv,
      el(
        'div',
        { class: 'pi' },
        el('div', { class: 'pn', text: 'صفحة ' + (i + 1) + (mk ? ` · ${mk} تمييز` : '') }),
        tags,
        x.title ? el('div', { class: 'pt', text: x.title }) : null,
        x.comment ? el('div', { class: 'pc', text: x.comment }) : null
      )
    );
    list.append(item);
    thumbIO.observe(cv);
  }
  $('pageCount').textContent = count === viewer.n ? `${viewer.n} صفحة` : `${count} من ${viewer.n} صفحة. اضغط مرتين على الصفحة لتعديل اسمها أو وسمها.`;
  if (count === viewer.n) $('pageCount').textContent += '. اضغط مرتين على الصفحة لتعديل اسمها أو وسمها.';
}

function markCurrentInPanel() {
  document.querySelectorAll('.pitem').forEach((p) => p.classList.toggle('cur', parseInt(p.dataset.i, 10) === viewer.cur));
}

async function resolveDest(dest) {
  const pdf = viewer.pdf;
  let d = dest;
  if (typeof d === 'string') d = await pdf.getDestination(d);
  if (!Array.isArray(d)) return -1;
  const ref = d[0];
  if (typeof ref === 'object' && ref !== null) return await pdf.getPageIndex(ref);
  if (Number.isInteger(ref)) return ref;
  return -1;
}

async function renderOutline() {
  const box = $('outlineTab');
  box.innerHTML = '';
  if (!viewer.pdf) return;
  let ol = null;
  try {
    ol = await viewer.pdf.getOutline();
  } catch (e) {
    ol = null;
  }
  if (!ol || !ol.length) {
    box.append(el('div', { class: 'muted', style: 'padding:12px', text: 'الملف ده معندوش فهرس مدمج (الكتب الممسوحة غالباً كده). استخدم "وسم الصفحة" وسمّي العناوين بنفسك من تبويب الصفحات.' }));
    return;
  }
  const walk = (items, depth) => {
    for (const it of items) {
      const b = el('button', {
        class: 'olitem',
        text: it.title,
        style: `padding-inline-start:${6 + depth * 16}px`,
        onclick: async () => {
          const i = await resolveDest(it.dest);
          if (i >= 0) viewer.goTo(i);
        }
      });
      box.append(b);
      if (it.items && it.items.length) walk(it.items, depth + 1);
    }
  };
  walk(ol, 0);
}

function refreshPanel() {
  renderChips();
  renderPageList();
  if (!$('outlineTab').hidden) renderOutline();
}
$('search').addEventListener('input', debounce(() => renderPageList(), 150));

// ---------------------------------------------------------------- المراجعة والتصدير
function pagesFor(sel) {
  const out = [];
  for (let i = 0; i < viewer.n; i++) {
    const x = book.pageInfo(i);
    let ok = false;
    for (const k of TAG_KEYS) if (sel.tags[k] && x.tags.has(k)) ok = true;
    if (sel.marks && book.marksOn(i).length) ok = true;
    if (sel.comment && x.comment) ok = true;
    if (ok) out.push(i);
  }
  return out;
}

function reviewText(pages) {
  const lines = [`# ${current.name.replace(/\.pdf$/i, '')}`, '', `تاريخ المراجعة: ${new Date().toLocaleDateString('ar-EG')}`, ''];
  const row = (i) => {
    const x = book.pageInfo(i);
    const mk = book.marksOn(i).length;
    const tags = [...x.tags].map((k) => TAGS[k].short).join('، ');
    let s = `- صفحة ${i + 1}`;
    if (x.title) s += ` — ${x.title}`;
    if (tags) s += ` [${tags}]`;
    if (mk) s += ` (${mk} تمييز)`;
    if (x.comment) s += `\n  ${x.comment.replace(/\n/g, '\n  ')}`;
    return s;
  };
  for (const k of TAG_KEYS) {
    const ps = pages.filter((i) => book.pageInfo(i).tags.has(k));
    if (!ps.length) continue;
    lines.push(`## ${TAGS[k].label}`, '', ...ps.map(row), '');
  }
  const rest = pages.filter((i) => book.pageInfo(i).tags.size === 0);
  if (rest.length) lines.push('## صفحات أخرى', '', ...rest.map(row), '');
  return lines.join('\n');
}

async function exportPdf(pages) {
  if (!pages.length) return;
  const { PDFDocument } = window.PDFLib;
  const out = await PDFDocument.create();
  let k = 0;
  for (const i of pages) {
    toast(`جاري التصدير... ${++k} / ${pages.length}`, 60000);
    const page = await viewer.pdf.getPage(i + 1);
    const vp1 = page.getViewport({ scale: 1 });
    const sc = 1.7;
    const vp = page.getViewport({ scale: sc });
    const cv = document.createElement('canvas');
    cv.width = Math.floor(vp.width);
    cv.height = Math.floor(vp.height);
    const ctx = cv.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, cv.width, cv.height);
    await page.render({ canvasContext: ctx, viewport: vp }).promise;
    ctx.globalCompositeOperation = 'multiply';
    for (const m of book.marksOn(i)) paintMark(ctx, m, cv.width, cv.height);
    ctx.globalCompositeOperation = 'source-over';
    for (const tb of book.boxes) {
      if (tb.p !== i) continue;
      ctx.fillStyle = cssColor(tb.c === undefined ? -16777216 : tb.c, 1);
      ctx.font = `${Math.max(8, ((tb.s || 18) * cv.width) / 700)}px "Segoe UI", Tahoma, sans-serif`;
      ctx.textBaseline = 'top';
      tb.t.split('\n').forEach((ln, j) => ctx.fillText(ln, tb.x * cv.width, tb.y * cv.height + j * ((tb.s || 18) * cv.width) / 540));
    }
    const blob = await new Promise((r) => cv.toBlob(r, 'image/jpeg', 0.88));
    const img = await out.embedJpg(new Uint8Array(await blob.arrayBuffer()));
    const pg = out.addPage([vp1.width, vp1.height]);
    pg.drawImage(img, { x: 0, y: 0, width: vp1.width, height: vp1.height });
  }
  const bytes = await out.save();
  download(current.name.replace(/\.pdf$/i, '') + '-مراجعة.pdf', bytes, 'application/pdf');
  toast(`تم تصدير ${pages.length} صفحة`);
}

function openReview() {
  if (!book || !viewer.pdf) return;
  const sel = { tags: { main: true, sub: true, ref: true, star: true }, marks: true, comment: true };
  const body = el('div');
  const summary = el('div', { class: 'muted small' });
  const sections = el('div');
  const filters = el('div', { class: 'checks' });
  const exportBtns = el('div', { class: 'actions' });
  const redraw = () => {
    const pages = pagesFor(sel);
    summary.textContent = pages.length ? `الصفحات المختارة: ${pages.length} من ${viewer.n}` : 'مفيش صفحات مطابقة. وسّم صفحات أو ميّز كلام الأول.';
    sections.innerHTML = '';
    const groups = [];
    for (const k of TAG_KEYS) groups.push([TAGS[k].label, TAGS[k].color, pages.filter((i) => book.pageInfo(i).tags.has(k))]);
    groups.push(['صفحات أخرى (تمييز أو تعليق بدون وسم)', '#555', pages.filter((i) => book.pageInfo(i).tags.size === 0)]);
    for (const [label, color, ps] of groups) {
      if (!ps.length) continue;
      const h = el('h4', {}, el('span', { class: 'tagchip', style: `background:${color}`, text: label }), el('span', { class: 'muted small', text: ps.length + ' صفحة' }));
      sections.append(el('div', { class: 'sect' }, h));
      for (const i of ps) {
        const x = book.pageInfo(i);
        const mk = book.marksOn(i).length;
        sections.lastChild.append(
          el(
            'div',
            { class: 'rowp', onclick: () => { closeModal(); viewer.goTo(i); } },
            el('b', { text: 'ص ' + (i + 1) }),
            x.title || '',
            mk ? el('span', { class: 'muted small', text: `  · ${mk} تمييز` }) : null,
            x.comment ? el('div', { class: 'muted small', text: x.comment }) : null
          )
        );
      }
    }
    exportBtns.querySelectorAll('button').forEach((b) => (b.disabled = !pages.length));
  };
  const addCheck = (label, get, set, color) => {
    const cb = el('input', { type: 'checkbox' });
    cb.checked = get();
    cb.onchange = () => {
      set(cb.checked);
      redraw();
    };
    const t = color ? el('span', { class: 'tagchip', text: label, style: `background:${color}` }) : el('span', { text: label });
    filters.append(el('label', {}, cb, t));
  };
  for (const k of TAG_KEYS) addCheck(TAGS[k].label, () => sel.tags[k], (v) => (sel.tags[k] = v), TAGS[k].color);
  addCheck('فيها تمييزات', () => sel.marks, (v) => (sel.marks = v));
  addCheck('ليها تعليق', () => sel.comment, (v) => (sel.comment = v));
  exportBtns.append(
    el('button', { class: 'btn primary', text: 'تصدير ملخص نصي', onclick: () => {
      const t = reviewText(pagesFor(sel));
      download(current.name.replace(/\.pdf$/i, '') + '-ملخص.md', t, 'text/markdown');
    } }),
    el('button', { class: 'btn primary', text: 'تصدير PDF بالصفحات المختارة', onclick: () => exportPdf(pagesFor(sel)).catch((e) => toast('فشل التصدير: ' + e.message)) })
  );
  body.append(el('p', { class: 'muted small', text: 'اختار أنهي صفحات تدخل في الملخص:' }), filters, summary, exportBtns, sections);
  modal('ملخص المراجعة', body, [{ label: 'إغلاق' }], { wide: true });
  redraw();
}
$('btnReview').onclick = openReview;

// ---------------------------------------------------------------- المزيد
$('btnMore').onclick = () => {
  const mk = (label, fn) => el('button', { class: 'btn', text: label, onclick: () => { closeModal(); fn(); } });
  const onOff = (v) => (v ? ' ✓' : '');
  const menu = el(
    'div',
    { class: 'menu' },
    mk('النص عربي (التحديد من اليمين لليسار)' + onOff(viewer.rtl), () => {
      viewer.rtl = !viewer.rtl;
      Prefs.set('rtl', viewer.rtl);
      toast(viewer.rtl ? 'النص عربي' : 'النص من اليسار لليمين');
    }),
    mk('التقليب في وضع الصفحة الواحدة من اليمين لليسار' + onOff(flipRtl), () => {
      flipRtl = !flipRtl;
      Prefs.set('flipRtl', flipRtl);
    }),
    mk('حفظ نسخة احتياطية من التمييزات', doBackup),
    mk('استرجاع نسخة احتياطية', doRestore),
    mk('اختصارات لوحة المفاتيح', showShortcuts)
  );
  modal('المزيد', menu, [{ label: 'إغلاق' }]);
};

function showShortcuts() {
  const rows = [
    ['H / T / B / E / X', 'تحريك / تمييز / فرشاة / ممحاة / نص'],
    ['Ctrl + Z / Ctrl + Y', 'تراجع / إعادة'],
    ['Ctrl + عجلة الماوس، Ctrl + (+ / -)', 'تكبير وتصغير'],
    ['Ctrl + 0', 'الرجوع لملاءمة العرض'],
    ['Page Up / Page Down أو Space', 'تمرير صفحة'],
    ['Home / End', 'أول وآخر صفحة'],
    ['Ctrl + F', 'بحث في أسماء الصفحات'],
    ['P', 'اسم الصفحة والوسوم والتعليق'],
    ['N', 'الوضع الليلي']
  ];
  const t = el('div');
  for (const [k, v] of rows) t.append(el('div', { class: 'rowp' }, el('b', { text: k }), v));
  modal('اختصارات لوحة المفاتيح', t, [{ label: 'إغلاق' }]);
}

// ---------------------------------------------------------------- لوحة المفاتيح
window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (isModalOpen()) closeModal();
    return;
  }
  const tag = (e.target && e.target.tagName) || '';
  if (tag === 'INPUT' || tag === 'TEXTAREA') return;
  if (isModalOpen() || !book || !viewer.pdf) return;
  const sc = $('scroller');
  const step = () => sc.clientHeight * 0.85;
  const single = viewer.mode === 'single';
  const k = e.key;
  if (e.ctrlKey || e.metaKey) {
    if (k === 'z' || k === 'Z') {
      e.preventDefault();
      e.shiftKey ? doRedo() : doUndo();
    } else if (k === 'y' || k === 'Y') {
      e.preventDefault();
      doRedo();
    } else if (k === '=' || k === '+') {
      e.preventDefault();
      viewer.zoomBy(1.15);
    } else if (k === '-') {
      e.preventDefault();
      viewer.zoomBy(1 / 1.15);
    } else if (k === '0') {
      e.preventDefault();
      viewer.setZoom(1);
    } else if (k === 'f' || k === 'F') {
      e.preventDefault();
      togglePanel(true);
      $('search').focus();
    }
    return;
  }
  const next = () => (single ? viewer.goTo(viewer.cur + 1) : (sc.scrollTop += step()));
  const prev = () => (single ? viewer.goTo(viewer.cur - 1) : (sc.scrollTop -= step()));
  switch (k) {
    case 'PageDown':
    case ' ':
      e.preventDefault();
      e.shiftKey ? prev() : next();
      break;
    case 'PageUp':
      e.preventDefault();
      prev();
      break;
    case 'ArrowDown':
      e.preventDefault();
      single ? viewer.goTo(viewer.cur + 1) : (sc.scrollTop += 80);
      break;
    case 'ArrowUp':
      e.preventDefault();
      single ? viewer.goTo(viewer.cur - 1) : (sc.scrollTop -= 80);
      break;
    case 'ArrowLeft':
      if (single) {
        e.preventDefault();
        viewer.goTo(viewer.cur + (flipRtl ? 1 : -1));
      }
      break;
    case 'ArrowRight':
      if (single) {
        e.preventDefault();
        viewer.goTo(viewer.cur + (flipRtl ? -1 : 1));
      }
      break;
    case 'Home':
      e.preventDefault();
      viewer.goTo(0);
      break;
    case 'End':
      e.preventDefault();
      viewer.goTo(viewer.n - 1);
      break;
    case 'h': case 'H': setTool('hand'); break;
    case 't': case 'T': setTool('hl'); break;
    case 'b': case 'B': setTool('brush'); break;
    case 'e': case 'E': setTool('eraser'); break;
    case 'x': case 'X': setTool('text'); break;
    case 'p': case 'P': editPageInfo(viewer.cur); break;
    case 'n': case 'N': $('btnNight').click(); break;
    default:
  }
});

window.addEventListener('beforeunload', () => {
  if (book) book.saveNow();
});

// ---------------------------------------------------------------- التشغيل
syncUi();
renderRecent();
window.__app = { viewer, openBytes, getBook: () => book }; // للاختبار
if (isDesktop) {
  window.desktop.onOpenPath((p) => openPath(p));
  window.desktop.ready();
}
