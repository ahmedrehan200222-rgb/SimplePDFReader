// بينسخ مكتبات pdf.js وpdf-lib جوه app/vendor عشان التطبيق يشتغل من غير إنترنت.
const fs = require('fs');
const path = require('path');

const out = path.join(__dirname, '..', 'app', 'vendor');
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

const pdfjs = path.dirname(require.resolve('pdfjs-dist/package.json'));
const pdflib = path.dirname(require.resolve('pdf-lib/package.json'));

fs.copyFileSync(path.join(pdfjs, 'build', 'pdf.min.mjs'), path.join(out, 'pdf.min.mjs'));
fs.copyFileSync(path.join(pdfjs, 'build', 'pdf.worker.min.mjs'), path.join(out, 'pdf.worker.min.mjs'));
fs.cpSync(path.join(pdfjs, 'cmaps'), path.join(out, 'cmaps'), { recursive: true });
fs.cpSync(path.join(pdfjs, 'standard_fonts'), path.join(out, 'standard_fonts'), { recursive: true });
fs.copyFileSync(path.join(pdflib, 'dist', 'pdf-lib.min.js'), path.join(out, 'pdf-lib.min.js'));
console.log('vendor ready:', out);
