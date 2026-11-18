// بيجمّع نسخة الويب في ملف HTML واحد (للنشر كصفحة واحدة): الكود والمكتبات والعامل كلهم جواه.
const fs = require('fs');
const path = require('path');
const esbuild = require('esbuild');

const root = path.join(__dirname, '..');
const out = path.join(root, 'web');
fs.mkdirSync(out, { recursive: true });

const pdfjs = path.dirname(require.resolve('pdfjs-dist/package.json'));
const pdflib = path.dirname(require.resolve('pdf-lib/package.json'));

const res = esbuild.buildSync({
  entryPoints: [path.join(root, 'app', 'js', 'main.js')],
  bundle: true,
  minify: true,
  format: 'esm',
  target: 'es2022',
  write: false,
  external: ['fs', 'http', 'https', 'url', 'path', 'canvas', 'module', 'zlib'],
  logLevel: 'warning'
});
const bundle = res.outputFiles[0].text.replace(/<\/script/gi, '<\\/script');
const worker = fs.readFileSync(path.join(pdfjs, 'build', 'pdf.worker.min.mjs')).toString('base64');
const pdflibSrc = fs.readFileSync(path.join(pdflib, 'dist', 'pdf-lib.min.js'), 'utf8').replace(/<\/script/gi, '<\\/script');

let html = fs.readFileSync(path.join(root, 'app', 'index.html'), 'utf8');
let css = fs.readFileSync(path.join(root, 'app', 'style.css'), 'utf8');
const body = html.slice(html.indexOf('<body>') + 6, html.indexOf('<script src="vendor'));

// ألوان الوضع الداكن حسب ثيم المشاهد (بدل الاعتماد على زر الوضع الليلي بس)
const dark = '--paper:#121212;--surface:#1c1c1e;--ink:#ececec;--muted:#9a9aa0;--line:#3a3a3c;--pressed:#2c2c2e;--accent:#ececec;--canvas:#000;--danger:#f2b8b5;color-scheme:dark;';
css +=
  `\n@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){${dark}}}\n:root[data-theme="dark"]{${dark}}\n` +
  'html,body{height:100%}\n';

const page = `<title>Simple PDF Reader</title>
<meta name="description" content="قارئ PDF مع تمييز وملاحظات، متوافق مع تطبيق التابلت">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700&display=swap">
<style>
${css}
body { font-family: "Cairo", "Segoe UI", Tahoma, "Noto Naskh Arabic", system-ui, sans-serif; }
</style>
<script>document.documentElement.setAttribute('dir','rtl');document.documentElement.setAttribute('lang','ar');window.__WEB_BUILD__=true;</script>
${body}
<script>${pdflibSrc}</script>
<script>
(function(){
  var b=atob("${worker}"),n=b.length,u=new Uint8Array(n);
  for(var i=0;i<n;i++)u[i]=b.charCodeAt(i);
  window.__WORKER_URL__=URL.createObjectURL(new Blob([u],{type:'text/javascript'}));
})();
</script>
<script type="module">${bundle}</script>
`;
fs.writeFileSync(path.join(out, 'page.html'), page);
console.log('web page:', (page.length / 1e6).toFixed(2), 'MB');
