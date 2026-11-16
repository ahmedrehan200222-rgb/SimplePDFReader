// عرض الصفحات والتمييز بالماوس. نفس قواعد تطبيق التابلت:
//  - السحب على الكلام = تمييز، والسحب من مكان فاضي = تحريك الصفحة.
import { COLORS, TAGS, cssColor, fillOpacity } from './store.js';
import { buildAnalysis, startsOnText, buildMarks } from './analyze.js';
import { el } from './ui.js';

const GAP = 14;
const PAD_TOP = 16;
const SLOP = 5;
const MAX_PIXELS = 14e6;

/** بيرسم تمييز واحد (مستطيل أو خط فرشاة) على سياق canvas بمقاس w×h. */
export function paintMark(ctx, m, w, h) {
  ctx.save();
  ctx.globalAlpha = fillOpacity(m.a);
  if (m.brush) {
    ctx.strokeStyle = cssColor(m.c, 1);
    ctx.lineCap = 'round';
    ctx.lineWidth = Math.max(7, w * 0.009);
    ctx.beginPath();
    ctx.moveTo(m.l * w, m.t * h);
    ctx.lineTo(m.r * w, m.b * h);
    ctx.stroke();
  } else {
    ctx.fillStyle = cssColor(m.c, 1);
    ctx.fillRect(m.l * w, m.t * h, (m.r - m.l) * w, (m.b - m.t) * h);
  }
  ctx.restore();
}

class PageView {
  constructor(v, i) {
    this.v = v;
    this.i = i;
    this.visible = false;
    this.token = 0;
    this.task = null;
    this.version = 0;
    this.an = null;
    this.anVersion = -1;
    this.cv = el('canvas', { class: 'pdf' });
    this.ov = el('canvas', { class: 'ov' });
    this.boxes = el('div', { class: 'boxes' });
    this.badge = el('div', { class: 'badge' });
    this.num = el('div', { class: 'num', text: String(i + 1) });
    this.el = el('div', { class: 'page', 'data-i': String(i) }, this.cv, this.ov, this.boxes, this.badge, this.num);
    this.el.addEventListener('pointerdown', (e) => v.pointerDown(e, this));
    this.applyFilters();
    this.layout();
  }

  applyFilters() {
    this.cv.classList.toggle('night', this.v.night);
    this.cv.classList.toggle('enh', this.v.enhance);
  }

  layout() {
    const { w, h } = this.v.pageSize(this.i);
    this.cssW = w;
    this.cssH = h;
    this.el.style.width = w + 'px';
    this.el.style.height = h + 'px';
    this.drawOverlay();
  }

  async render() {
    if (!this.visible || !this.v.pdf) return;
    const token = ++this.token;
    const v = this.v;
    const page = await v.pdf.getPage(this.i + 1);
    if (token !== this.token) return;
    const sz = v.sizes[this.i];
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    let sc = (this.cssW * dpr) / sz.w;
    const px = sz.w * sc * sz.h * sc;
    if (px > MAX_PIXELS) sc *= Math.sqrt(MAX_PIXELS / px);
    const viewport = page.getViewport({ scale: sc });
    const off = document.createElement('canvas');
    off.width = Math.max(1, Math.floor(viewport.width));
    off.height = Math.max(1, Math.floor(viewport.height));
    if (this.task) {
      try {
        this.task.cancel();
      } catch (e) {
        /* ignore */
      }
    }
    const task = page.render({ canvasContext: off.getContext('2d'), viewport });
    this.task = task;
    try {
      await task.promise;
    } catch (e) {
      return; // اتلغى أو فشل
    }
    if (token !== this.token) return;
    off.className = this.cv.className;
    this.el.replaceChild(off, this.cv);
    this.cv = off;
    this.version++;
    this.renderedW = this.cssW;
  }

  release() {
    this.token++;
    if (this.task) {
      try {
        this.task.cancel();
      } catch (e) {
        /* ignore */
      }
      this.task = null;
    }
    const blank = el('canvas', { class: this.cv.className });
    blank.width = 1;
    blank.height = 1;
    this.el.replaceChild(blank, this.cv);
    this.cv = blank;
    this.an = null;
    this.renderedW = 0;
  }

  analysis() {
    if (this.cv.width <= 2) return null;
    if (!this.an || this.anVersion !== this.version) {
      this.an = buildAnalysis(this.cv);
      this.anVersion = this.version;
    }
    return this.an;
  }

  drawOverlay(preview = null) {
    const v = this.v;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = this.cssW;
    const h = this.cssH;
    const pw = Math.round(w * dpr);
    const ph = Math.round(h * dpr);
    if (this.ov.width !== pw || this.ov.height !== ph) {
      this.ov.width = pw;
      this.ov.height = ph;
    }
    const ctx = this.ov.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    const draw = (m) => paintMark(ctx, m, w, h);
    if (v.book) for (const m of v.book.marksOn(this.i)) draw(m);
    if (preview) for (const m of preview) draw(m);
  }

  refreshBoxes() {
    this.boxes.innerHTML = '';
    this.boxEls = [];
    const b = this.v.book;
    if (!b) return;
    for (const tb of b.boxes) {
      if (tb.p !== this.i) continue;
      const d = el('div', { class: 'tbox', text: tb.t });
      d.style.left = tb.x * 100 + '%';
      d.style.top = tb.y * 100 + '%';
      d.style.fontSize = Math.max(8, ((tb.s || 18) * this.cssW) / 700) + 'px';
      d.style.color = cssColor(tb.c === undefined ? -16777216 : tb.c, 1);
      this.boxes.append(d);
      this.boxEls.push({ el: d, box: tb });
    }
  }

  refreshBadge() {
    this.badge.innerHTML = '';
    const b = this.v.book;
    if (!b || !b.hasInfo(this.i)) return;
    const x = b.pageInfo(this.i);
    for (const k of ['main', 'sub', 'ref', 'star']) {
      if (x.tags.has(k)) {
        const s = el('span', { text: TAGS[k].short });
        s.style.background = TAGS[k].color;
        this.badge.append(s);
      }
    }
    if (x.title) this.badge.append(el('span', { class: 'ttl', text: x.title }));
  }

  refreshAll() {
    this.drawOverlay();
    this.refreshBoxes();
    this.refreshBadge();
  }
}

export class Viewer {
  constructor(scroller, pagesEl, host) {
    this.scroller = scroller;
    this.pagesEl = pagesEl;
    this.host = host;
    this.pdf = null;
    this.book = null;
    this.n = 0;
    this.sizes = [];
    this.pv = [];
    this.tops = [];
    this.zoom = 1;
    this.mode = 'cont'; // cont | single
    this.cur = 0;
    this.tool = 'hand';
    this.colorIdx = 0;
    this.alpha = 55;
    this.night = false;
    this.enhance = false;
    this.rtl = true; // اتجاه النص (عربي)
    this.drag = null;
    this.io = null;
    this.resizeTimer = null;
    this.scrollRaf = 0;
    this.token = 0;
    scroller.addEventListener('scroll', () => this.onScroll(), { passive: true });
    scroller.addEventListener(
      'wheel',
      (e) => {
        if (e.ctrlKey) {
          e.preventDefault();
          this.zoomBy(e.deltaY < 0 ? 1.1 : 1 / 1.1);
        }
      },
      { passive: false }
    );
    window.addEventListener('resize', () => {
      clearTimeout(this.resizeTimer);
      this.resizeTimer = setTimeout(() => this.relayout(true), 150);
    });
  }

  // ---------- تحميل ----------
  async load(pdf, book, startPage) {
    this.unload();
    this.pdf = pdf;
    this.book = book;
    this.n = pdf.numPages;
    const token = ++this.token;
    const first = await pdf.getPage(1);
    const vp = first.getViewport({ scale: 1 });
    this.sizes = Array.from({ length: this.n }, () => ({ w: vp.width, h: vp.height }));
    this.cur = Math.min(Math.max(0, startPage | 0), this.n - 1);
    this.build();
    this.goTo(this.cur, false);
    this.measureAll(token);
  }

  /** مقاسات باقي الصفحات في الخلفية (ممكن تختلف عن الأولى). */
  async measureAll(token) {
    let changed = false;
    for (let i = 1; i < this.n; i++) {
      if (token !== this.token) return;
      try {
        const p = await this.pdf.getPage(i + 1);
        const vp = p.getViewport({ scale: 1 });
        const s = this.sizes[i];
        if (Math.abs(s.w - vp.width) > 0.5 || Math.abs(s.h - vp.height) > 0.5) {
          s.w = vp.width;
          s.h = vp.height;
          changed = true;
        }
      } catch (e) {
        /* صفحة تالفة */
      }
      if (i % 25 === 0) {
        if (changed) {
          this.relayout(false);
          changed = false;
        }
        await new Promise((r) => setTimeout(r, 0));
      }
    }
    if (token === this.token && changed) this.relayout(false);
  }

  unload() {
    this.token++;
    if (this.io) this.io.disconnect();
    this.io = null;
    for (const p of this.pv) p.release();
    this.pv = [];
    this.pagesEl.innerHTML = '';
    if (this.pdf) {
      try {
        this.pdf.destroy();
      } catch (e) {
        /* ignore */
      }
    }
    this.pdf = null;
    this.book = null;
    this.n = 0;
  }

  // ---------- التخطيط ----------
  baseWidth() {
    return Math.max(260, Math.min(this.scroller.clientWidth - 48, 1000));
  }
  pageSize(i) {
    const w = this.baseWidth() * this.zoom;
    const s = this.sizes[i] || this.sizes[0] || { w: 1, h: 1.4 };
    return { w, h: (w * s.h) / s.w };
  }

  build() {
    if (this.io) this.io.disconnect();
    for (const p of this.pv) p.release();
    this.pagesEl.innerHTML = '';
    this.pv = [];
    this.io = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          const pv = en.target._pv;
          if (!pv) continue;
          if (en.isIntersecting) {
            pv.visible = true;
            if (!pv.renderedW || Math.abs(pv.renderedW - pv.cssW) > 1) pv.render();
          } else {
            pv.visible = false;
            pv.release();
          }
        }
      },
      { root: this.scroller, rootMargin: '1400px 0px 1400px 0px' }
    );
    const idx = this.mode === 'single' ? [this.cur] : Array.from({ length: this.n }, (_, i) => i);
    for (const i of idx) {
      const pv = new PageView(this, i);
      pv.el._pv = pv;
      pv.refreshAll();
      this.pv.push(pv);
      this.pagesEl.append(pv.el);
      this.io.observe(pv.el);
    }
    this.computeTops();
    this.applyToolClass();
  }

  computeTops() {
    this.tops = [];
    let y = PAD_TOP;
    for (const pv of this.pv) {
      this.tops.push(y);
      y += pv.cssH + GAP;
    }
  }

  relayout(rerender) {
    if (!this.pdf) return;
    const keep = this.anchor();
    for (const pv of this.pv) {
      pv.layout();
      if (rerender && pv.visible) pv.render();
    }
    this.computeTops();
    this.restoreAnchor(keep);
  }

  anchor() {
    const k = this.indexInView();
    const pv = this.pv[k];
    if (!pv) return null;
    const top = this.tops[k];
    return { k, frac: (this.scroller.scrollTop - top) / Math.max(1, pv.cssH), hx: this.scroller.scrollLeft, cw: this.scroller.scrollWidth };
  }
  restoreAnchor(a) {
    if (!a) return;
    const pv = this.pv[a.k];
    if (!pv) return;
    this.scroller.scrollTop = this.tops[a.k] + a.frac * pv.cssH;
    if (a.cw > 0) this.scroller.scrollLeft = (a.hx / a.cw) * this.scroller.scrollWidth;
  }

  indexInView() {
    if (!this.pv.length) return 0;
    const probe = this.scroller.scrollTop + this.scroller.clientHeight * 0.3;
    let lo = 0;
    let hi = this.pv.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (this.tops[mid] <= probe) lo = mid;
      else hi = mid - 1;
    }
    return lo;
  }

  onScroll() {
    if (this.scrollRaf) return;
    this.scrollRaf = requestAnimationFrame(() => {
      this.scrollRaf = 0;
      if (this.mode !== 'cont' || !this.pv.length) return;
      const k = this.indexInView();
      if (k !== this.cur) {
        this.cur = k;
        this.host.onPage(k);
      }
    });
  }

  // ---------- التنقل والتكبير ----------
  goTo(i, notify = true) {
    if (!this.pdf) return;
    i = Math.min(Math.max(0, i | 0), this.n - 1);
    this.cur = i;
    if (this.mode === 'single') {
      this.build();
      this.scroller.scrollTop = 0;
    } else {
      this.scroller.scrollTop = Math.max(0, (this.tops[i] || 0) - PAD_TOP);
    }
    if (notify) this.host.onPage(i);
    else this.host.onPage(i, true);
  }

  setMode(m) {
    if (m === this.mode) return;
    this.mode = m;
    if (this.pdf) {
      this.build();
      this.goTo(this.cur, false);
    }
  }

  setZoom(z) {
    z = Math.min(5, Math.max(0.4, z));
    if (Math.abs(z - this.zoom) < 0.001) return;
    this.zoom = z;
    this.relayout(true);
    this.host.onZoom(z);
  }
  zoomBy(f) {
    this.setZoom(this.zoom * f);
  }

  setNight(on) {
    this.night = on;
    this.pv.forEach((p) => p.applyFilters());
  }
  setEnhance(on) {
    this.enhance = on;
    this.pv.forEach((p) => p.applyFilters());
  }

  setTool(t) {
    this.tool = t;
    this.applyToolClass();
  }
  applyToolClass() {
    for (const pv of this.pv) {
      pv.el.className = 'page cur-' + this.tool;
    }
  }

  // ---------- تحديثات من الخارج ----------
  refreshPage(i) {
    const pv = this.pv.find((p) => p.i === i);
    if (pv) pv.refreshAll();
  }
  refreshAllPages() {
    this.pv.forEach((p) => p.refreshAll());
  }
  refreshOverlays() {
    this.pv.forEach((p) => p.drawOverlay());
  }

  // ---------- الماوس ----------
  frac(pv, e) {
    const r = pv.el.getBoundingClientRect();
    return { fx: (e.clientX - r.left) / r.width, fy: (e.clientY - r.top) / r.height };
  }

  pointerDown(e, pv) {
    if (e.button !== 0 || !this.book) return;
    const { fx, fy } = this.frac(pv, e);
    const drawTool = this.tool === 'hl' || this.tool === 'brush';
    let an = null;
    let onText = false;
    if (drawTool) {
      an = pv.analysis();
      onText = !!an && startsOnText(an, fx, fy);
    }
    this.drag = {
      pv,
      id: e.pointerId,
      x0: e.clientX,
      y0: e.clientY,
      fx0: fx,
      fy0: fy,
      sx: this.scroller.scrollLeft,
      sy: this.scroller.scrollTop,
      moved: false,
      an,
      onText,
      preview: null
    };
    try {
      pv.el.setPointerCapture(e.pointerId);
    } catch (err) {
      /* ignore */
    }
    const move = (ev) => this.pointerMove(ev);
    const up = (ev) => {
      pv.el.removeEventListener('pointermove', move);
      pv.el.removeEventListener('pointerup', up);
      pv.el.removeEventListener('pointercancel', up);
      this.pointerUp(ev, ev.type === 'pointercancel');
    };
    pv.el.addEventListener('pointermove', move);
    pv.el.addEventListener('pointerup', up);
    pv.el.addEventListener('pointercancel', up);
    e.preventDefault();
  }

  pointerMove(e) {
    const d = this.drag;
    if (!d || e.pointerId !== d.id) return;
    const dx = e.clientX - d.x0;
    const dy = e.clientY - d.y0;
    if (!d.moved && Math.hypot(dx, dy) > SLOP) {
      d.moved = true;
      if (!(d.onText && (this.tool === 'hl' || this.tool === 'brush'))) d.pv.el.classList.add('panning');
    }
    if (!d.moved) return;
    if (d.onText && (this.tool === 'hl' || this.tool === 'brush')) {
      const { fx, fy } = this.frac(d.pv, e);
      d.preview = this.tool === 'hl' ? this.selectionMarks(d, fx, fy) : this.brushMark(d, fx, fy);
      d.pv.drawOverlay(d.preview);
    } else {
      this.scroller.scrollLeft = d.sx - dx;
      this.scroller.scrollTop = d.sy - dy;
    }
  }

  selectionMarks(d, fx, fy) {
    const arr = buildMarks(d.an, d.fx0, d.fy0, fx, fy, false, this.rtl);
    return arr.map((m) => ({ p: d.pv.i, l: m.l, t: m.t, r: m.r, b: m.b, c: COLORS[this.colorIdx], a: this.alpha, brush: false }));
  }

  brushMark(d, fx, fy) {
    const pv = d.pv;
    let ex = Math.min(1, Math.max(0, fx));
    let ey = Math.min(1, Math.max(0, fy));
    const sx = Math.min(1, Math.max(0, d.fx0));
    const sy = Math.min(1, Math.max(0, d.fy0));
    const dx = (ex - sx) * pv.cssW;
    const dy = (ey - sy) * pv.cssH;
    if (Math.hypot(dx, dy) < SLOP * 2) return null;
    if (Math.abs(dy) <= Math.abs(dx) * 0.27) ey = sy;
    else if (Math.abs(dx) <= Math.abs(dy) * 0.27) ex = sx;
    return [{ p: pv.i, l: sx, t: sy, r: ex, b: ey, c: COLORS[this.colorIdx], a: this.alpha, brush: true }];
  }

  pointerUp(e, cancelled) {
    const d = this.drag;
    this.drag = null;
    if (!d) return;
    d.pv.el.classList.remove('panning');
    try {
      d.pv.el.releasePointerCapture(d.id);
    } catch (err) {
      /* ignore */
    }
    if (cancelled) {
      d.pv.drawOverlay();
      return;
    }
    if (!d.moved) {
      this.click(d.pv, d.fx0, d.fy0, e);
      return;
    }
    if (d.onText && (this.tool === 'hl' || this.tool === 'brush')) {
      const prev = d.preview;
      d.pv.drawOverlay();
      if (prev && prev.length) {
        this.book.remember();
        for (const m of prev) this.book.marks.push(m);
        this.book.save();
        d.pv.drawOverlay();
        this.host.onMarksChanged(d.pv.i);
      } else if (this.tool === 'hl') {
        this.host.toast('مش قادر أحدد سطر واضح هنا. قرّب من الكلام وحاول تاني');
      }
    }
  }

  hitMark(m, fx, fy, w, h) {
    if (!m.brush) return fx >= m.l && fx <= m.r && fy >= m.t && fy <= m.b;
    const x1 = m.l * w;
    const y1 = m.t * h;
    const x2 = m.r * w;
    const y2 = m.b * h;
    const x = fx * w;
    const y = fy * h;
    const dx = x2 - x1;
    const dy = y2 - y1;
    const len2 = dx * dx + dy * dy;
    const t = len2 === 0 ? 0 : Math.min(1, Math.max(0, ((x - x1) * dx + (y - y1) * dy) / len2));
    const cx = x1 + t * dx;
    const cy = y1 + t * dy;
    const tol = Math.max(10, Math.max(7, w * 0.009) / 2 + 4);
    return Math.hypot(x - cx, y - cy) <= tol;
  }

  click(pv, fx, fy, e) {
    const b = this.book;
    const w = pv.cssW;
    const h = pv.cssH;
    const marks = b.marks.filter((m) => m.p === pv.i && this.hitMark(m, fx, fy, w, h));
    const mark = marks.length ? marks[marks.length - 1] : null;
    // مربعات النص: بنتحقق من مستطيلها الفعلي
    let tbox = null;
    for (const t of pv.boxEls || []) {
      const r = t.el.getBoundingClientRect();
      if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) tbox = t.box;
    }
    if (this.tool === 'eraser') {
      if (mark) {
        b.remember();
        b.marks.splice(b.marks.indexOf(mark), 1);
        b.save();
        pv.drawOverlay();
        this.host.onMarksChanged(pv.i);
        this.host.toast('تم مسح التمييز');
      } else if (tbox) {
        b.remember();
        b.boxes.splice(b.boxes.indexOf(tbox), 1);
        b.save();
        pv.refreshBoxes();
        this.host.onMarksChanged(pv.i);
        this.host.toast('تم مسح مربع النص');
      } else {
        this.host.toast('مفيش عنصر تحت الممحاة');
      }
      return;
    }
    if (this.tool === 'text' && !mark && !tbox) {
      this.host.addBox(pv.i, Math.min(0.9, Math.max(0, fx)), Math.min(0.97, Math.max(0, fy)));
      return;
    }
    if (tbox) this.host.editBox(tbox, pv.i);
    else if (mark) this.host.editMark(mark, pv.i);
  }
}
