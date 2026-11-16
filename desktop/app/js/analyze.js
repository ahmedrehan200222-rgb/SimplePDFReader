// تمييز السطور من غير OCR: إسقاط الحبر على الصفوف والأعمدة.
// ده نقل حرفي لخوارزمية تطبيق التابلت (ReaderView.buildMarks).

export const ANALYSIS_WIDTH = 1000;

/** بيبني خريطة "حبر/مش حبر" من صورة الصفحة المرسومة، بعرض ثابت 1000 بكسل. */
export function buildAnalysis(srcCanvas) {
  const bw = ANALYSIS_WIDTH;
  const bh = Math.max(1, Math.round((srcCanvas.height * bw) / srcCanvas.width));
  const tmp = document.createElement('canvas');
  tmp.width = bw;
  tmp.height = bh;
  const ctx = tmp.getContext('2d', { willReadFrequently: true });
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, bw, bh);
  ctx.drawImage(srcCanvas, 0, 0, bw, bh);
  const d = ctx.getImageData(0, 0, bw, bh).data;
  const dm = new Uint8Array(bw * bh);
  for (let i = 0, j = 0; j < dm.length; i += 4, j++) {
    const lum = Math.floor((d[i] * 30 + d[i + 1] * 59 + d[i + 2] * 11) / 100);
    dm[j] = lum < 178 ? 1 : 0;
  }
  return { bw, bh, dm, rows: new Int32Array(bh).fill(-1) };
}

/** هل اللمسة على كلام؟ (حبر قريب من النقطة) */
export function startsOnText(an, fx, fy) {
  const { bw, bh, dm } = an;
  const cx = Math.min(bw - 1, Math.max(0, Math.floor(fx * bw)));
  const cy = Math.min(bh - 1, Math.max(0, Math.floor(fy * bh)));
  const hx = Math.max(6, Math.floor(bw * 0.02));
  const hy = Math.max(3, Math.floor(bh * 0.004));
  let n = 0;
  for (let y = Math.max(0, cy - hy); y <= Math.min(bh - 1, cy + hy); y++) {
    const xe = Math.min(bw - 1, cx + hx);
    for (let x = Math.max(0, cx - hx); x <= xe; x += 2) {
      if (dm[y * bw + x] === 1 && ++n >= 3) return true;
    }
  }
  return false;
}

/**
 * بيحوّل سحبة من (x1,y1) لـ (x2,y2) (كنسب من الصفحة) لتمييز على السطور.
 * whole=true: السطر كله. whole=false: الجزء المحدد، وبيتقرّب لأقرب كلمة كاملة.
 * بيرجّع مصفوفة {l,t,r,b} كنسب من الصفحة.
 */
export function buildMarks(an, fx1, fy1, fx2, fy2, whole, rtl) {
  const { bw, bh, dm, rows } = an;
  const clampi = (v, lo, hi) => Math.min(Math.max(v, lo), hi);
  const ax = clampi(Math.floor(fx1 * bw), 0, bw - 1);
  const ay = clampi(Math.floor(fy1 * bh), 0, bh - 1);
  const zx = clampi(Math.floor(fx2 * bw), 0, bw - 1);
  const zy = clampi(Math.floor(fy2 * bh), 0, bh - 1);

  const dark = (x, y) => dm[y * bw + x] === 1;
  const rowCount = (y) => {
    const v = rows[y];
    if (v >= 0) return v;
    let n = 0;
    for (let x = 0; x < bw; x += 2) if (dark(x, y)) n++;
    rows[y] = n;
    return n;
  };

  const rowThr = Math.max(2, Math.floor(bw * 0.002));
  const yTop = Math.min(ay, zy);
  const yBot = clampi(Math.max(ay, zy) + 1, yTop + 1, bh);

  const seeds = [];
  for (let y = yTop; y < yBot; y++) if (rowCount(y) >= rowThr) seeds.push(y);
  if (seeds.length === 0) {
    const cy = Math.floor((yTop + yBot) / 2);
    let best = -1;
    let bestCount = 0;
    for (let y = Math.max(0, cy - 28); y <= Math.min(bh - 1, cy + 28); y++) {
      const c = rowCount(y);
      if (c > bestCount) {
        bestCount = c;
        best = y;
      }
    }
    if (best >= 0 && bestCount >= rowThr) seeds.push(best);
  }
  if (seeds.length === 0) return [];

  // نجمّع الصفوف في سطور ونوسّع كل سطر لحدوده الكاملة
  const bands = [];
  let bandStart = seeds[0];
  let prev = seeds[0];
  for (let i = 1; i < seeds.length; i++) {
    const y = seeds[i];
    if (y - prev > 5) {
      bands.push([bandStart, prev + 1]);
      bandStart = y;
    }
    prev = y;
  }
  bands.push([bandStart, prev + 1]);
  for (const bd of bands) {
    let t = bd[0];
    let bt = bd[1];
    let n = 0;
    while (t > 0 && n < 24 && rowCount(t - 1) >= rowThr) {
      t--;
      n++;
    }
    n = 0;
    while (bt < bh && n < 24 && rowCount(bt) >= rowThr) {
      bt++;
      n++;
    }
    bd[0] = t;
    bd[1] = bt;
  }
  bands.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const bd of bands) {
    const l = merged.length ? merged[merged.length - 1] : null;
    if (l && bd[0] <= l[1]) l[1] = Math.max(l[1], bd[1]);
    else merged.push(bd);
  }
  let use = merged;
  if (!whole && merged.length > 1) {
    // نتجاهل السطر اللي المؤشر لمسه من طرفه بس
    const kept = merged.filter((it) => (Math.min(it[1], yBot) - Math.max(it[0], yTop)) / (it[1] - it[0]) >= 0.4);
    if (kept.length) {
      use = kept;
    } else {
      let bestLine = merged[0];
      let bestOv = -Infinity;
      for (const it of merged) {
        const ov = Math.min(it[1], yBot) - Math.max(it[0], yTop);
        if (ov > bestOv) {
          bestOv = ov;
          bestLine = it;
        }
      }
      use = [bestLine];
    }
  }

  // نقطة البداية هي الأعلى (ولو في نفس السطر: حسب اتجاه القراءة)
  const startIsFirst = ay < zy || (ay === zy && (rtl ? ax >= zx : ax <= zx));
  const sx = startIsFirst ? ax : zx;
  const ex = startIsFirst ? zx : ax;

  const colGap = Math.max(24, Math.floor(bw * 0.04));
  let refL = whole || use.length === 1 ? Math.min(ax, zx) : sx;
  let refR = whole || use.length === 1 ? Math.max(ax, zx) : sx;

  const out = [];
  for (let i = 0; i < use.length; i++) {
    const bd = use[i];
    const top = Math.max(0, bd[0] - 2);
    const bot = Math.min(bh, bd[1] + 3);

    // أي أعمدة فيها حبر في السطر ده
    const colThr = Math.max(1, Math.floor((bot - top) * 0.03));
    const occ = new Uint8Array(bw);
    for (let x = 0; x < bw; x++) {
      let n = 0;
      for (let y = top; y < bot; y += 2) if (dark(x, y)) n++;
      occ[x] = n >= colThr ? 1 : 0;
    }
    const runs = (from, to, gap) => {
      const res = [];
      let s = -1;
      let last = -1;
      for (let x = from; x < to; x++) {
        if (occ[x]) {
          if (s < 0) s = x;
          else if (x - last > gap) {
            res.push([s, last]);
            s = x;
          }
          last = x;
        }
      }
      if (s >= 0) res.push([s, last]);
      return res;
    };

    // نختار العمود اللي إحنا فيه (مهم للصفحات اللي فيها عمودين)
    let col = null;
    let bestScore = -Infinity;
    for (const c of runs(0, bw, colGap)) {
      const ov = Math.min(c[1], refR) - Math.max(c[0], refL);
      const score = ov >= 0 ? 100000 + ov : -Math.max(c[0] - refR, refL - c[1]);
      if (score > bestScore) {
        bestScore = score;
        col = c;
      }
    }
    if (!col) continue;

    let xa = col[0];
    let xb = col[1];
    if (!whole) {
      if (use.length === 1) {
        xa = Math.max(col[0], Math.min(ax, zx));
        xb = Math.min(col[1], Math.max(ax, zx));
      } else if (i === 0) {
        if (rtl) xb = Math.min(col[1], sx);
        else xa = Math.max(col[0], sx);
      } else if (i === use.length - 1) {
        if (rtl) xa = Math.max(col[0], ex);
        else xb = Math.min(col[1], ex);
      }
      // نقرّب الحدود لأقرب كلمة كاملة
      const wordGap = Math.max(3, Math.floor((bot - top) * 0.22));
      for (const w of runs(col[0], col[1] + 1, wordGap)) {
        if (xa >= w[0] && xa <= w[1]) xa = w[0];
        if (xb >= w[0] && xb <= w[1]) xb = w[1];
      }
    }
    refL = col[0];
    refR = col[1];
    if (xb - xa < 3) continue;
    out.push({
      l: Math.max(0, xa - 2) / bw,
      t: top / bh,
      r: Math.min(bw, xb + 3) / bw,
      b: bot / bh
    });
  }
  return out;
}
