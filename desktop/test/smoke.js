// اختبار دخاني بمتصفح حقيقي: بيفتح PDF ويجرّب التمييز والتحريك والتراجع والنسخة الاحتياطية.
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const URL_ = process.env.APP_URL || 'http://localhost:8765/index.html';
const OUT = path.join(__dirname, 'out');
let failed = 0;
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) failed++; };

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 1150 }, acceptDownloads: true });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.goto(URL_);
  await page.setInputFiles('#fileInput', path.join(OUT, 'sample.pdf'));
  await page.waitForSelector('.page canvas.pdf', { timeout: 15000 });
  await page.waitForFunction(() => { const c = document.querySelector('.page canvas.pdf'); return c && c.width > 100; }, null, { timeout: 15000 });
  await page.waitForTimeout(500);
  ok(await page.evaluate(() => !document.getElementById('app').hidden), 'reader opened');
  ok((await page.evaluate(() => document.querySelectorAll('.page').length)) === 3, '3 pages created');
  await page.screenshot({ path: path.join(OUT, 's1-open.png') });

  const R = await page.evaluate(() => { const r = document.querySelector('.page').getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height }; });
  const lineY = (i) => R.y + ((260 + 62 * i + 22) / 1754) * R.h;
  const px = (fx) => R.x + fx * R.w;

  // تمييز: سحب على سطر واحد
  await page.click('[data-tool=hl]');
  await page.mouse.move(px(0.80), lineY(2));
  await page.mouse.down();
  await page.mouse.move(px(0.60), lineY(2), { steps: 8 });
  await page.screenshot({ path: path.join(OUT, 's2-preview.png') });
  await page.mouse.up();
  let n = await page.evaluate(() => window.__app.getBook().marks.length);
  ok(n === 1, 'single-line highlight created (marks=' + n + ')');

  // تمييز متعدد الأسطر
  await page.mouse.move(px(0.70), lineY(4));
  await page.mouse.down();
  await page.mouse.move(px(0.50), lineY(7), { steps: 10 });
  await page.mouse.up();
  n = await page.evaluate(() => window.__app.getBook().marks.length);
  ok(n >= 4, 'multi-line highlight added more marks (marks=' + n + ')');
  const m0 = await page.evaluate(() => JSON.stringify(window.__app.getBook().marks[0]));
  console.log('mark sample', m0);

  // السحب من مكان فاضي = تحريك مش تمييز
  const before = await page.evaluate(() => ({ n: window.__app.getBook().marks.length, top: document.getElementById('scroller').scrollTop }));
  await page.mouse.move(px(0.03), R.y + 500);
  await page.mouse.down();
  await page.mouse.move(px(0.03), R.y + 300, { steps: 6 });
  await page.mouse.up();
  const after = await page.evaluate(() => ({ n: window.__app.getBook().marks.length, top: document.getElementById('scroller').scrollTop }));
  ok(after.n === before.n, 'drag on empty margin did not highlight');
  ok(after.top > before.top + 100, 'drag on empty margin scrolled (' + before.top + ' -> ' + after.top + ')');
  await page.evaluate(() => (document.getElementById('scroller').scrollTop = 0));
  await page.waitForTimeout(300);

  // فرشاة
  await page.click('[data-tool=brush]');
  await page.mouse.move(px(0.85), lineY(10));
  await page.mouse.down();
  await page.mouse.move(px(0.55), lineY(10) + 4, { steps: 6 });
  await page.mouse.up();
  const brush = await page.evaluate(() => window.__app.getBook().marks.filter((m) => m.brush));
  ok(brush.length === 1 && brush[0].t === brush[0].b, 'brush straight horizontal line');

  // تراجع
  const cnt = await page.evaluate(() => window.__app.getBook().marks.length);
  await page.click('#btnUndo');
  ok((await page.evaluate(() => window.__app.getBook().marks.length)) === cnt - 1, 'undo removed last mark');
  await page.click('#btnRedo');
  ok((await page.evaluate(() => window.__app.getBook().marks.length)) === cnt, 'redo restored');

  // ممحاة
  await page.click('[data-tool=eraser]');
  await page.mouse.click(px(0.70), lineY(2));
  ok((await page.evaluate(() => window.__app.getBook().marks.length)) === cnt - 1, 'eraser deleted the clicked mark');
  await page.screenshot({ path: path.join(OUT, 's3-marks.png') });

  // وسم الصفحة
  await page.click('#btnTag');
  await page.fill('.modal input[type=text]', 'الفصل الأول - مقدمة');
  await page.check('.modal .checks label:nth-child(4) input');
  await page.fill('.modal textarea', 'تعليق تجريبي مهم');
  await page.click('.modal .actions .btn.primary');
  const info = await page.evaluate(() => JSON.stringify(window.__app.getBook().info));
  ok(info.includes('الفصل الأول') && info.includes('star'), 'page info saved: ' + info);

  // اللوحة الجانبية والبحث
  await page.click('#btnPanel');
  await page.waitForSelector('.pitem');
  ok((await page.evaluate(() => document.querySelectorAll('.pitem').length)) === 3, 'panel lists 3 pages');
  await page.fill('#search', 'مقدمه'); // بدون تاء مربوطة/همزة: لازم يطابق
  await page.waitForTimeout(400);
  ok((await page.evaluate(() => document.querySelectorAll('.pitem').length)) === 1, 'search (arabic-normalised) finds the page');
  await page.fill('#search', '');
  await page.waitForTimeout(300);
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(OUT, 's4-panel.png') });

  // المراجعة
  await page.click('#btnReview');
  await page.waitForSelector('.modal.wide');
  ok((await page.evaluate(() => document.querySelectorAll('.modal .rowp').length)) >= 1, 'review lists pages');
  await page.screenshot({ path: path.join(OUT, 's5-review.png') });
  const [dl] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    page.click('.modal .actions .btn.primary:nth-child(2)')
  ]);
  await dl.saveAs(path.join(OUT, 'review.pdf'));
  const head = fs.readFileSync(path.join(OUT, 'review.pdf')).slice(0, 5).toString();
  ok(head === '%PDF-', 'exported review PDF is a PDF (' + fs.statSync(path.join(OUT, 'review.pdf')).size + ' bytes)');
  await page.keyboard.press('Escape');

  // نسخة احتياطية
  await page.click('#btnMore');
  const [bk] = await Promise.all([page.waitForEvent('download'), page.click('.modal .menu button:nth-child(3)')]);
  await bk.saveAs(path.join(OUT, 'backup.json'));
  const j = JSON.parse(fs.readFileSync(path.join(OUT, 'backup.json'), 'utf8'));
  ok(j.app === 'SimplePDFReader' && j.books.length === 1, 'backup has the book');
  const b0 = j.books[0];
  ok(b0.fp === 'sample.pdf|' + fs.statSync(path.join(OUT, 'sample.pdf')).size, 'fingerprint = name|size (' + b0.fp + ')');
  ok(Array.isArray(JSON.parse(b0.marks)) && typeof JSON.parse(b0.notes) === 'object' && typeof JSON.parse(b0.info) === 'object', 'backup strings are valid JSON');
  const mk = JSON.parse(b0.marks)[0];
  ok(Number.isInteger(mk.c) && Number.isInteger(mk.a) && 'brush' in mk, 'marks use Android-compatible integer fields');

  // رجوع للمكتبة ثم استرجاع
  await page.click('#btnHome');
  ok(await page.evaluate(() => !document.getElementById('home').hidden), 'back to home');
  await page.evaluate(() => localStorage.clear());
  await page.setInputFiles('#restoreInput', path.join(OUT, 'backup.json'));
  await page.waitForTimeout(500);
  await page.setInputFiles('#fileInput', path.join(OUT, 'sample.pdf'));
  await page.waitForFunction(() => window.__app.getBook() && window.__app.getBook().marks.length > 0, null, { timeout: 15000 });
  ok(true, 'restore brought the highlights back after wiping storage');

  console.log('errors:', errors.length ? errors.join('\n') : 'none');
  ok(errors.length === 0, 'no console errors');
  await browser.close();
  console.log(failed ? `FAILED ${failed}` : 'ALL PASSED');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error('ERR', e); process.exit(2); });
