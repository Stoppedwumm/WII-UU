'use strict';
// From the repo root, after installing the pinned runtime in build/plugin-host:
// build/plugin-host/node_modules/.bin/electron test/plugins/browser-smoke.cjs --ozone-platform=headless
// Root-only containers also require --no-sandbox for this test; production never passes that flag.
const { app, BrowserWindow } = require('electron');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { once } = require('node:events');
const { evaluate, attachPolicy } = require('../../resources/plugins/host/api.cjs');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'wiiuu-browser-smoke-'));
app.setPath('userData', path.join(temporary, 'profile'));
let server, win;
const deadline = setTimeout(() => { console.error('Browser smoke timed out'); app.exit(1); }, 30000);
async function run() {
    await app.whenReady();
    let offOriginRequests = 0;
    server = http.createServer((req, res) => {
        if (req.url === '/outside') offOriginRequests++;
        if (req.url === '/redirect') { res.writeHead(302, { Location: `http://localhost:${server.address().port}/outside` }); res.end(); }
        else if (req.url === '/download') {
            res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Disposition': 'attachment; filename="game.nds"' });
            res.end('fixture ROM bytes');
        } else { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end('<!doctype html><title>Fixture</title><h1>Plugin fixture</h1>'); }
    });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    const site = `http://127.0.0.1:${server.address().port}`;
    const definition = evaluate(`Plugin('Fixture').loadSite('${site}').redirectDownloads(true, Directories.ROMS)`);
    win = new BrowserWindow({ show: false, webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true,
        partition: 'test-plugin' } });
    const reports = [];
    const signals = new (require('node:events').EventEmitter)();
    attachPolicy(win, definition, { ROMS: path.join(temporary, 'roms') }, message => { reports.push(message); signals.emit('report', message); });
    await win.loadURL(site);
    assert.deepEqual(await win.webContents.executeJavaScript('[typeof require, typeof process]'), ['undefined', 'undefined']);
    let blocked = once(signals, 'report');
    await win.webContents.executeJavaScript(`location.href='http://localhost:${server.address().port}/outside'; undefined`);
    assert.match((await blocked)[0], /Blocked/);
    assert.equal(win.webContents.getURL(), `${site}/`);
    blocked = once(signals, 'report');
    await win.webContents.executeJavaScript(`location.href='/redirect'; undefined`);
    assert.match((await blocked)[0], /Blocked/);
    assert.equal(win.webContents.getURL(), `${site}/`);
    blocked = once(signals, 'report');
    await win.webContents.executeJavaScript(`window.open('http://localhost:${server.address().port}/outside'); undefined`);
    assert.match((await blocked)[0], /Blocked/);
    assert.equal(BrowserWindow.getAllWindows().length, 1);
    let navigated = once(win.webContents, 'did-finish-load');
    await win.webContents.executeJavaScript(`window.open('${site}/inside'); undefined`);
    await navigated;
    assert.equal(win.webContents.getURL(), `${site}/inside`);
    assert.equal(offOriginRequests, 0);
    for (let i = 0; i < 2; i++) {
        const saved = once(signals, 'report');
        win.webContents.downloadURL(`${site}/download`);
        assert.match((await saved)[0], /^Saved /);
    }
    assert.equal(fs.readFileSync(path.join(temporary, 'roms', 'game.nds'), 'utf8'), 'fixture ROM bytes');
    assert.equal(fs.readFileSync(path.join(temporary, 'roms', 'game (1).nds'), 'utf8'), 'fixture ROM bytes');
    console.log('Real Chromium: isolated page, navigation/HTTP redirect/popup blocking, same-origin popup and collision-safe downloads passed');
}
run().then(() => finish(0), error => { console.error(error); finish(1); });
function finish(code) {
    clearTimeout(deadline);
    if (win && !win.isDestroyed()) win.destroy();
    if (server) server.close();
    fs.rmSync(temporary, { recursive: true, force: true });
    app.exit(code);
}
