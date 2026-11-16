const { app, BrowserWindow, protocol, ipcMain, Menu } = require('electron');
const path = require('path');
const fs = require('fs');

protocol.registerSchemesAsPrivileged([
  { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }
]);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.wasm': 'application/wasm'
};

let win = null;
let pendingPath = null;
let rendererReady = false;

function pdfFromArgv(argv) {
  for (const a of argv.slice(1)) {
    if (/\.pdf$/i.test(a) && fs.existsSync(a)) return a;
  }
  return null;
}

function sendPath(p) {
  if (!p) return;
  if (win && rendererReady) {
    win.webContents.send('open-path', p);
  } else {
    pendingPath = p;
  }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 760,
    minHeight: 520,
    title: 'Simple PDF Reader',
    icon: path.join(__dirname, 'build', 'icon.png'),
    backgroundColor: '#f4f1ea',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });
  win.setMenuBarVisibility(false);
  win.loadURL('app://local/index.html');
  win.on('closed', () => {
    win = null;
    rendererReady = false;
  });
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', (_e, argv) => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
    sendPath(pdfFromArgv(argv));
  });

  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    const root = path.join(__dirname, 'app');
    protocol.handle('app', async (req) => {
      const u = new URL(req.url);
      let p = decodeURIComponent(u.pathname);
      if (p === '/' || p === '') p = '/index.html';
      const file = path.normalize(path.join(root, p));
      if (!file.startsWith(root)) return new Response('forbidden', { status: 403 });
      try {
        const data = await fs.promises.readFile(file);
        return new Response(data, {
          headers: { 'content-type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream' }
        });
      } catch (e) {
        return new Response('not found', { status: 404 });
      }
    });

    ipcMain.handle('read-file', async (_e, p) => {
      const buf = await fs.promises.readFile(p);
      return new Uint8Array(buf);
    });
    ipcMain.on('renderer-ready', () => {
      rendererReady = true;
      if (pendingPath) {
        const p = pendingPath;
        pendingPath = null;
        win && win.webContents.send('open-path', p);
      }
    });

    pendingPath = pdfFromArgv(process.argv);
    createWindow();
  });

  app.on('open-file', (e, p) => {
    e.preventDefault();
    sendPath(p);
  });
  app.on('window-all-closed', () => app.quit());
}
