// بيانات الكتب بنفس صيغة تطبيق التابلت بالظبط، عشان النسخة الاحتياطية تتبادل بينهم.
// التمييز:  {p,l,t,r,b,c,a,brush}   (إحداثيات كنسب من الصفحة، c لون ARGB، a شفافية 10-90)
// مربع نص:  {p,x,y,t,c,s}
// الصفحة:   {"<رقم>": {t:اسم, c:تعليق, g:[وسوم]}}

export const COLORS = [0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFFE91E63, 0xFFFF9800, 0xFF9C27B0, 0xFFF44336, 0xFF00BCD4].map((c) => c | 0);
export const COLOR_NAMES = ['أصفر', 'أخضر', 'أزرق', 'وردي', 'برتقالي', 'بنفسجي', 'أحمر', 'سماوي'];

export const TAGS = {
  main: { key: 'main', label: 'عنوان رئيسي', short: 'رئيسي', color: '#B71C1C' },
  sub: { key: 'sub', label: 'عنوان جانبي', short: 'جانبي', color: '#0D47A1' },
  ref: { key: 'ref', label: 'صفحة مرجعية', short: 'مرجع', color: '#1B5E20' },
  star: { key: 'star', label: 'مهمة ★', short: '★ مهمة', color: '#E65100' }
};
export const TAG_KEYS = ['main', 'sub', 'ref', 'star'];

export function rgbOf(c) {
  const u = c >>> 0;
  return [(u >> 16) & 255, (u >> 8) & 255, u & 255];
}
export function cssColor(c, opacity = 1) {
  const [r, g, b] = rgbOf(c);
  return `rgba(${r},${g},${b},${opacity})`;
}
export function hexColor(c) {
  const [r, g, b] = rgbOf(c);
  return '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('');
}
/** شفافية التمييز: a = نسبة الشفافية (10-90)، والتعبئة = (100-a)/100 */
export function fillOpacity(a) {
  return Math.min(1, Math.max(0, (100 - a) / 100));
}

export function normalizeAr(s) {
  let out = '';
  for (const ch0 of String(s).toLowerCase()) {
    const ch = ch0;
    if ((ch >= 'ً' && ch <= 'ْ') || ch === 'ـ') continue;
    if (ch === 'أ' || ch === 'إ' || ch === 'آ') out += 'ا';
    else if (ch === 'ى') out += 'ي';
    else if (ch === 'ة') out += 'ه';
    else if (ch >= '٠' && ch <= '٩') out += String(ch.charCodeAt(0) - 0x660);
    else out += ch;
  }
  return out;
}

const P = 'pdfr:';

function lsGet(k) {
  try {
    return localStorage.getItem(k);
  } catch (e) {
    return null;
  }
}
function lsSet(k, v) {
  try {
    localStorage.setItem(k, v);
  } catch (e) {
    /* التخزين ممتلئ أو غير متاح */
  }
}

export const Prefs = {
  get(k, d) {
    const v = lsGet(P + 'pref:' + k);
    if (v === null) return d;
    try {
      return JSON.parse(v);
    } catch (e) {
      return d;
    }
  },
  set(k, v) {
    lsSet(P + 'pref:' + k, JSON.stringify(v));
  }
};

export function makeFp(name, size) {
  return `${name}|${size}`;
}

export function loadRecent() {
  try {
    const a = JSON.parse(lsGet(P + 'recent') || '[]');
    return Array.isArray(a) ? a : [];
  } catch (e) {
    return [];
  }
}
export function addRecent(item) {
  const list = loadRecent().filter((x) => x.fp !== item.fp);
  list.unshift(item);
  lsSet(P + 'recent', JSON.stringify(list.slice(0, 12)));
}
export function removeRecent(fp) {
  lsSet(P + 'recent', JSON.stringify(loadRecent().filter((x) => x.fp !== fp)));
}

export class Book {
  constructor(fp, name) {
    this.fp = fp;
    this.name = name;
    this.marks = [];
    this.boxes = [];
    this.info = {}; // "رقم الصفحة (من 0)" -> {t,c,g}
    this.page = 0;
    this.undoStack = [];
    this.redoStack = [];
    this._timer = null;
    const raw = lsGet(P + 'book:' + fp);
    if (raw) {
      try {
        const o = JSON.parse(raw);
        this.marks = Array.isArray(o.marks) ? o.marks : [];
        this.boxes = Array.isArray(o.boxes) ? o.boxes : [];
        this.info = o.info && typeof o.info === 'object' ? o.info : {};
        this.page = o.page | 0;
      } catch (e) {
        /* بيانات تالفة: نبدأ من جديد */
      }
    }
  }

  save() {
    clearTimeout(this._timer);
    this._timer = setTimeout(() => this.saveNow(), 250);
  }
  saveNow() {
    clearTimeout(this._timer);
    lsSet(
      P + 'book:' + this.fp,
      JSON.stringify({ name: this.name, marks: this.marks, boxes: this.boxes, info: this.info, page: this.page })
    );
  }

  // ---------- تراجع وإعادة ----------
  snapshot() {
    return JSON.stringify({ marks: this.marks, boxes: this.boxes });
  }
  remember() {
    this.undoStack.push(this.snapshot());
    if (this.undoStack.length > 50) this.undoStack.shift();
    this.redoStack = [];
  }
  restore(s) {
    const o = JSON.parse(s);
    this.marks = o.marks;
    this.boxes = o.boxes;
    this.save();
  }
  undo() {
    if (!this.undoStack.length) return false;
    this.redoStack.push(this.snapshot());
    this.restore(this.undoStack.pop());
    return true;
  }
  redo() {
    if (!this.redoStack.length) return false;
    this.undoStack.push(this.snapshot());
    this.restore(this.redoStack.pop());
    return true;
  }

  // ---------- بيانات الصفحات ----------
  pageInfo(p) {
    const x = this.info[String(p)];
    return x ? { title: x.t || '', comment: x.c || '', tags: new Set(x.g || []) } : { title: '', comment: '', tags: new Set() };
  }
  setPageInfo(p, title, comment, tags) {
    title = title.trim();
    comment = comment.trim();
    if (!title && !comment && tags.size === 0) delete this.info[String(p)];
    else this.info[String(p)] = { t: title, c: comment, g: [...tags] };
    this.save();
  }
  hasInfo(p) {
    return !!this.info[String(p)];
  }
  marksOn(p) {
    return this.marks.filter((m) => m.p === p);
  }
  matches(p, q) {
    if (!q) return true;
    const x = this.pageInfo(p);
    let hay = `${p + 1} صفحة ${p + 1} ${x.title} ${x.comment} `;
    for (const t of x.tags) hay += `${TAGS[t].label} ${TAGS[t].short} `;
    hay = normalizeAr(hay);
    for (const tok of q.split(' ')) {
      if (tok && !hay.includes(tok)) return false;
    }
    return true;
  }
  hasData() {
    return this.marks.length > 0 || this.boxes.length > 0 || Object.keys(this.info).length > 0;
  }
}

// ---------- النسخة الاحتياطية (نفس صيغة التابلت) ----------
export function allBookKeys() {
  const keys = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith(P + 'book:')) keys.push(k.slice((P + 'book:').length));
    }
  } catch (e) {
    /* ignore */
  }
  return keys;
}

export function buildBackup(currentBook) {
  if (currentBook) currentBook.saveNow();
  const books = [];
  for (const fp of allBookKeys()) {
    const b = new Book(fp, fp.substring(0, fp.lastIndexOf('|')));
    if (!b.hasData()) continue;
    books.push({
      fp,
      name: b.name,
      marks: JSON.stringify(b.marks),
      notes: JSON.stringify({ boxes: b.boxes }),
      info: JSON.stringify(b.info),
      page: b.page
    });
  }
  return { app: 'SimplePDFReader', v: 1, books };
}

export function applyBackup(root) {
  if (!root || root.app !== 'SimplePDFReader' || !Array.isArray(root.books)) {
    throw new Error('الملف ده مش نسخة احتياطية من التطبيق');
  }
  let n = 0;
  for (const bk of root.books) {
    if (!bk.fp) continue;
    const parse = (s, d) => {
      try {
        return JSON.parse(s);
      } catch (e) {
        return d;
      }
    };
    const marks = parse(bk.marks, []);
    const notes = parse(bk.notes, {});
    const info = parse(bk.info, {});
    lsSet(
      P + 'book:' + bk.fp,
      JSON.stringify({
        name: bk.name || bk.fp.substring(0, bk.fp.lastIndexOf('|')),
        marks: Array.isArray(marks) ? marks : [],
        boxes: Array.isArray(notes.boxes) ? notes.boxes : [],
        info: info && typeof info === 'object' ? info : {},
        page: bk.page | 0
      })
    );
    n++;
  }
  return n;
}
