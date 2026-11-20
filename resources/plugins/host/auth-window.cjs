'use strict';
// Interactive, isolated authentication helper for native plugins.
// It never exports cookies, page scripts or clearance tokens to the native host.
const { app, BrowserWindow, Menu, session } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const [id, home, startURL] = process.argv.slice(2);
if (!/^[a-z0-9][a-z0-9-]{0,47}$/.test(id || '') || !home) throw new Error('Invalid plugin identity');
const target = new URL(startURL);
if (target.protocol !== 'https:' || target.username || target.password) throw new Error('HTTPS site required');
const origin = target.origin;
const profile = path.join(home, 'plugin-data', id, 'browser-auth');
fs.mkdirSync(profile, { recursive: true });
app.setPath('userData', profile);
function report(type) { process.stdout.write(JSON.stringify({ type }) + '\n'); }
app.whenReady().then(async () => {
    const partition = 'persist:auth-' + id;
    const site = session.fromPartition(partition);
    site.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
    site.setPermissionCheckHandler(() => false);
    const win = new BrowserWindow({
        width: 1100, height: 780, title: 'Sign in - ' + target.hostname,
        webPreferences: { partition, sandbox: true, contextIsolation: true,
            nodeIntegration: false, webSecurity: true, webviewTag: false }
    });
    const permitted = raw => {
        try {
            const url = new URL(raw);
            return url.protocol === 'https:' && url.origin === origin && !url.username && !url.password;
        } catch { return false; }
    };
    win.webContents.on('will-navigate', (event, url) => { if (!permitted(url)) event.preventDefault(); });
    win.webContents.on('will-redirect', (event, url) => { if (!permitted(url)) event.preventDefault(); });
    win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    Menu.setApplicationMenu(Menu.buildFromTemplate([
        { label: 'Authentication', submenu: [
            { label: 'Return to WII-UU (I finished signing in)', click: () => { report('confirmed'); win.close(); } },
            { role: 'reload' },
            { role: 'close' }
        ] },
        { role: 'editMenu' }
    ]));
    win.on('closed', () => app.quit());
    report('opened');
    await win.loadURL(target.href);
}).catch(error => { process.stderr.write(error.stack || String(error)); app.quit(); });
app.on('window-all-closed', () => app.quit());
