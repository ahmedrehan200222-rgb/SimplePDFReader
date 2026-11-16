const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  isElectron: true,
  readFile: (p) => ipcRenderer.invoke('read-file', p),
  pathForFile: (f) => {
    try {
      return webUtils.getPathForFile(f);
    } catch (e) {
      return '';
    }
  },
  onOpenPath: (cb) => ipcRenderer.on('open-path', (_e, p) => cb(p)),
  ready: () => ipcRenderer.send('renderer-ready')
});
