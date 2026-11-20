'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { evaluate, allowed, reserveDownload, attachPolicy } = require('../../resources/plugins/host/api.cjs');
const sample = `const pluginMainScreen = Plugin("XYZ");
pluginMainScreen.loadSite("https://xyz.com")
    .disableRedirectsToOtherWebsites(true).redirectDownloads(true, Directories.ROMS);`;
test('requested fluent API evaluates real JavaScript', () => {
    const plugin = evaluate(sample);
    assert.equal(plugin.name, 'XYZ');
    assert.equal(plugin.downloadDirectory, 'ROMS');
    assert.equal(allowed(plugin, 'https://xyz.com/games?q=1'), true);
    for (const url of ['https://xyz.com.evil.test', 'https://sub.xyz.com', 'http://xyz.com',
        'https://xyz.com:444', 'javascript:alert(1)', 'file:///tmp/x', 'https://user@xyz.com'])
        assert.equal(allowed(plugin, url), false, url);
    assert.equal(allowed(evaluate(`Plugin('X').loadSite('https://xyz.com').disableRedirectsToOtherWebsites(false)`), 'https://other.test'), true);
});
test('invalid or runaway definitions fail; bundled catalog scripts all execute', () => {
    for (const code of ['', `Plugin('X')`, `Plugin('X').loadSite('file:///x')`,
        `Plugin('X').loadSite('https://x').redirectDownloads(true, '/tmp')`,
        `Plugin('X').loadSite('https://x').disableRedirectsToOtherWebsites('false')`,
        `Plugin('X').loadSite('https://x'); Plugin('Y').loadSite('https://y')`, `while(true){}`])
        assert.throws(() => evaluate(code));
    const catalog = require('../../resources/plugins/catalog.json');
    for (const entry of catalog) assert.ok(evaluate(fs.readFileSync(path.join(__dirname, '../../resources/plugins/store', entry.script), 'utf8')).site);
});
test('download paths stay in selected directory, reserve collisions and protect existing files', t => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wiiuu-download-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const first = reserveDownload(dir, '../../game.nds');
    fs.writeFileSync(first, 'keep me');
    const second = reserveDownload(dir, '..\\..\\game.nds');
    assert.equal(path.dirname(first), dir);
    assert.notEqual(first, second);
    assert.equal(fs.readFileSync(first, 'utf8'), 'keep me');
    for (const name of ['', '.', '..', 'CON', 'NUL.txt', 'COM1.zip', 'bad:name?.nds', 'a\x00b']) {
        const result = reserveDownload(dir, name);
        assert.equal(path.dirname(result), dir);
        assert.ok(!/[<>:"|?*\x00-\x1f]/.test(path.basename(result)));
    }
});
function fixture(definition, directories) {
    const wc = new EventEmitter(); wc.session = new EventEmitter();
    wc.session.setPermissionRequestHandler = fn => { wc.permission = fn; };
    wc.session.setPermissionCheckHandler = fn => { wc.check = fn; };
    wc.setWindowOpenHandler = fn => { wc.popup = fn; };
    wc.loadURL = async url => { wc.loaded = url; };
    const reports = []; attachPolicy({ webContents: wc }, definition, directories, message => reports.push(message));
    return { wc, reports };
}
test('navigation, server redirects and popups use the same origin rule', async () => {
    const { wc, reports } = fixture(evaluate(sample), {});
    for (const event of ['will-navigate', 'will-redirect']) {
        let prevented = false;
        wc.emit(event, { url: 'https://other.test', isMainFrame: true, preventDefault() { prevented = true; } });
        assert.equal(prevented, true);
        prevented = false;
        wc.emit(event, { preventDefault() { prevented = true; } }, 'https://other.test', false, true);
        assert.equal(prevented, true, 'legacy Electron event arguments');
        prevented = false;
        wc.emit(event, { url: 'https://xyz.com/ok', isMainFrame: true, preventDefault() { prevented = true; } });
        assert.equal(prevented, false);
    }
    assert.deepEqual(wc.popup({ url: 'https://other.test' }), { action: 'deny' });
    assert.equal(wc.loaded, undefined);
    assert.deepEqual(wc.popup({ url: 'https://xyz.com/ok' }), { action: 'deny' });
    await Promise.resolve(); assert.equal(wc.loaded, 'https://xyz.com/ok');
    assert.ok(reports.length >= 3);
    wc.permission(null, 'camera', result => assert.equal(result, false));
    assert.equal(wc.check(), false);
});
test('only redirected downloads get an automatic path; failed downloads clean up reservations', t => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wiiuu-host-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const { wc } = fixture(evaluate(sample), { ROMS: dir });
    const item = new EventEmitter(); item.getFilename = () => '../game.nds';
    item.setSavePath = target => { item.target = target; };
    let prevented = false;
    wc.session.emit('will-download', { preventDefault() { prevented = true; } }, item, wc);
    assert.equal(prevented, false);
    assert.equal(path.dirname(item.target), dir);
    item.emit('done', {}, 'cancelled');
    assert.equal(fs.existsSync(item.target), false);
    const normal = fixture(evaluate(`Plugin('X').loadSite('https://x')`), {});
    normal.wc.session.emit('will-download', { preventDefault() { assert.fail('regular download should keep browser save dialog'); } },
        { setSavePath() { assert.fail('unexpected automatic save path'); } }, normal.wc);
});
