'use strict';
const { app, BrowserWindow, Menu, dialog } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { evaluate, attachPolicy } = require('./api.cjs');
const [id, home, roms] = process.argv.slice(2);
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, 'catalog.json'), 'utf8'));
const entry = catalog.find(plugin => plugin.id === id && plugin.type !== 'native');
if (!entry || !home || !roms) throw new Error('Unknown plugin or missing WII-UU directories');
app.setPath('userData', path.join(home, 'plugin-data', id));
app.whenReady().then(async () => {
    const definition = evaluate(fs.readFileSync(path.join(__dirname, 'store', entry.script), 'utf8'), entry.script);
    const win = new BrowserWindow({ width: 1200, height: 800, title: entry.name,
        webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true,
            webSecurity: true, webviewTag: false, partition: `persist:plugin-${id}` } });
    const report = message => { console.log(`[${id}] ${message}`); win.setTitle(`${entry.name} — ${message}`); };
    attachPolicy(win, definition, { ROMS: roms, DOWNLOADS: path.join(home, 'downloads') }, report);
    win.webContents.on('before-input-event', (_event, input) => {
        if (input.key === 'Escape' && win.isFullScreen()) win.setFullScreen(false);
    });
    win.webContents.on('page-title-updated', event => event.preventDefault());
    Menu.setApplicationMenu(Menu.buildFromTemplate([
        ...(process.platform === 'darwin' ? [{ role: 'appMenu' }] : []),
        { label: 'Plugin', submenu: [
            { label: 'Back', accelerator: 'Alt+Left', click: () => { if (win.webContents.navigationHistory.canGoBack()) win.webContents.navigationHistory.goBack(); } },
            { label: 'Home', click: () => win.loadURL(definition.site).catch(error => report(error.message)) },
            { role: 'reload' }, { role: 'togglefullscreen' }, { role: 'close' }
        ] }, { role: 'editMenu' }
    ]));
    await win.loadURL(definition.site);
}).catch(error => { dialog.showErrorBox('WII-UU plugin', error.message); app.quit(); });
app.on('window-all-closed', () => app.quit());
