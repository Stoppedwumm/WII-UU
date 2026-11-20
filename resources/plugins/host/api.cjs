'use strict';
const vm = require('node:vm');
const path = require('node:path');
const fs = require('node:fs');
const Directories = Object.freeze({ ROMS: 'ROMS', DOWNLOADS: 'DOWNLOADS' });
function siteURL(value) {
    const url = new URL(value);
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password)
        throw new Error('Sites must use HTTP or HTTPS without credentials');
    return url;
}
function evaluate(source, filename = 'plugin.js') {
    const definitions = [];
    function Plugin(name) {
        if (typeof name !== 'string' || !name.trim() || name.length > 48)
            throw new Error('Plugin name must contain 1–48 characters');
        const definition = { name, site: null, restrictNavigation: true, downloadDirectory: null };
        definitions.push(definition);
        const api = Object.freeze({
            loadSite(url) { definition.site = siteURL(url).href; return api; },
            disableRedirectsToOtherWebsites(enabled) {
                if (typeof enabled !== 'boolean') throw new Error('Expected a boolean');
                definition.restrictNavigation = enabled; return api;
            },
            redirectDownloads(enabled, directory = Directories.DOWNLOADS) {
                if (typeof enabled !== 'boolean' || !Object.values(Directories).includes(directory))
                    throw new Error('Expected a boolean and a Directories value');
                definition.downloadDirectory = enabled ? directory : null; return api;
            }
        });
        return api;
    }
    // Bundled source is reviewed code. vm limits accidental loops; it is not a security boundary
    // for arbitrary third-party JavaScript. The remote website never receives these objects.
    new vm.Script(source, { filename }).runInNewContext({ Plugin, Directories }, { timeout: 1000 });
    if (definitions.length !== 1 || !definitions[0].site) throw new Error('Define exactly one Plugin with loadSite');
    return Object.freeze(definitions[0]);
}
function allowed(definition, target) {
    try {
        const url = siteURL(target);
        return !definition.restrictNavigation || url.origin === new URL(definition.site).origin;
    } catch { return false; }
}
function reserveDownload(directory, filename) {
    fs.mkdirSync(directory, { recursive: true });
    // Do not trust Content-Disposition paths, Windows device names or invisible controls.
    let safe = String(filename).split(/[\\/]/).pop().replace(/[<>:"|?*\x00-\x1f\x7f]/g, '_')
        .replace(/[. ]+$/g, '').slice(0, 160);
    if (!safe || /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(safe)) safe = 'download';
    const ext = path.extname(safe), stem = safe.slice(0, safe.length - ext.length);
    for (let i = 0; i < 10000; i++) {
        const target = path.join(directory, i ? `${stem} (${i})${ext}` : safe);
        try { fs.closeSync(fs.openSync(target, 'wx', 0o600)); return target; }
        catch (error) { if (error.code !== 'EEXIST') throw error; }
    }
    throw new Error('Too many files with the same name');
}
function attachPolicy(win, definition, directories, report) {
    const wc = win.webContents;
    // Only top-level navigation is restricted. CDNs, images and embedded site frames still work.
    const navigate = (event, target, _inPlace, mainFrame) => {
        const isMain = event.isMainFrame ?? mainFrame ?? true;
        if (isMain && !allowed(definition, event.url ?? target)) { event.preventDefault(); report('Blocked navigation to another website'); }
    };
    wc.on('will-navigate', navigate);
    wc.on('will-redirect', navigate);
    wc.setWindowOpenHandler(({ url }) => {
        if (allowed(definition, url)) wc.loadURL(url).catch(error => report(error.message));
        else report('Blocked a popup to another website');
        return { action: 'deny' };
    });
    wc.session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
    wc.session.setPermissionCheckHandler(() => false);
    wc.on('will-attach-webview', event => event.preventDefault());
    wc.session.on('will-download', (event, item, contents) => {
        if (contents !== wc) { event.preventDefault(); return; }
        // Browser download redirects (e.g. a CDN) may cross origins. Files never navigate the window.
        if (!definition.downloadDirectory) return; // native Save As dialog
        let target;
        try {
            target = reserveDownload(directories[definition.downloadDirectory], item.getFilename());
            item.setSavePath(target);
            item.once('done', (_event, state) => {
                if (state !== 'completed') fs.rmSync(target, { force: true });
                report(state === 'completed' ? `Saved ${path.basename(target)}` : `Download ${state}`);
            });
        } catch (error) { if (target) fs.rmSync(target, { force: true }); event.preventDefault(); report(error.message); }
    });
}
module.exports = { evaluate, allowed, reserveDownload, attachPolicy, Directories };
