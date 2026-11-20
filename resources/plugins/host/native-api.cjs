'use strict';
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const { once } = require('node:events');
const { Directories, reserveDownload } = require('./api.cjs');
const ViewType = Object.freeze({ PANELS: 'PANELS' });
function httpURL(value) {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
        throw new Error('Expected an HTTP(S) URL without credentials');
    return url;
}
function text(value, max = 160) {
    if (typeof value !== 'string' || value.length > max) throw new Error(`Expected text of at most ${max} characters`);
    return value;
}
function createRuntime({ emit, directories, fetchImpl = fetch, log = () => {} }) {
    const logger = Object.freeze({
        debug: (...args) => log('debug', args.map(String).join(' ')),
        info: (...args) => log('info', args.map(String).join(' ')),
        warn: (...args) => log('warn', args.map(String).join(' ')),
        error: (...args) => log('error', args.map(String).join(' '))
    });
    const callbacks = new Map(), buttons = new Map(), inputs = new Map(), controllers = new Set();
    const views = [], downloads = new Set();
    let active, definition, sequence = 0, scheduled = false, disposed = false;
    function update() {
        if (disposed || scheduled) return;
        scheduled = true;
        setImmediate(() => {
            scheduled = false;
            if (disposed || !active) return;
            emit({ type: 'view', view: { id: active.id, title: active.title, closeButton: active.closeButton,
                status: active.status, entries: active.entries.map(entry => ({ ...entry,
                    buttons: entry.buttons.map(id => ({ id, label: buttons.get(id).label, busy: buttons.get(id).busy })) })) } });
        });
    }
    function status(message) { if (active) { active.status = text(message, 500); update(); } }
    function NButton(label, callback) {
        if (typeof callback !== 'function') throw new Error('NButton needs a callback');
        text(label, 48);
        if (buttons.size >= 4000) throw new Error('Too many native buttons');
        const id = `b${++sequence}`;
        buttons.set(id, { label, busy: false }); callbacks.set(id, callback);
        return Object.freeze({ id });
    }
    function NPlugin(name) {
        if (definition) throw new Error('Define exactly one NPlugin per script');
        definition = text(name, 48);
        return Object.freeze({ newView(type) {
            if (type !== ViewType.PANELS) throw new Error('Unsupported ViewType');
            if (views.length >= 16) throw new Error('Too many native views');
            const model = { id: `v${++sequence}`, title: name, closeButton: true, status: '', entries: [] };
            views.push(model); if (!active) active = model;
            const view = Object.freeze({
                title(value) { model.title = text(value); update(); return view; },
                closeButton(value) {
                    if (typeof value !== 'boolean') throw new Error('closeButton expects a boolean');
                    model.closeButton = value; update(); return view;
                },
                status(value) { model.status = text(value, 500); update(); return view; },
                show() { active = model; update(); return view; },
                close() { if (!disposed) emit({ type: 'close' }); },
                clear() {
                    model.entries = [];
                    const inputRefs = new Set(views.flatMap(v => v.entries.map(e => e.textField?.id).filter(Boolean)));
                    for (const id of inputs.keys()) if (!inputRefs.has(id)) inputs.delete(id);
                    const referenced = new Set(views.flatMap(v => v.entries.flatMap(e => e.buttons)));
                    for (const id of buttons.keys()) if (!referenced.has(id)) { callbacks.delete(id); buttons.delete(id); }
                    update(); return view;
                },
                addTextField(label, placeholder = '', initialValue = '', onSubmit) {
                    text(label); text(placeholder); text(initialValue, 256);
                    if (typeof onSubmit !== 'function') throw new Error('Text field needs onSubmit callback');
                    if (model.entries.length >= 500) throw new Error('A view supports at most 500 entries');
                    const id = `t${++sequence}`;
                    inputs.set(id, { onSubmit, value: initialValue });
                    model.entries.push({ id: `e${++sequence}`, title: label, imageURL: '', buttons: [],
                        textField: { id, placeholder, value: initialValue } });
                    update(); return view;
                },
                addEntry(title, imageURL = '', actions = []) {
                    imageURL ??= '';
                    text(title); text(imageURL, 2048);
                    if (imageURL) imageURL = httpURL(imageURL).href;
                    if (!Array.isArray(actions) || actions.length > 8 || actions.some(b => !buttons.has(b?.id)))
                        throw new Error('Entry actions must be an array of up to eight NButtons');
                    if (model.entries.length >= 500) throw new Error('A view supports at most 500 entries');
                    model.entries.push({ id: `e${++sequence}`, title, imageURL, buttons: actions.map(b => b.id) });
                    update(); return view;
                }
            });
            update(); return view;
        } });
    }
    async function request(url, options = {}, timeout = 30000) {
        httpURL(url);
        const controller = new AbortController(); controllers.add(controller);
        const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(timeout), ...(options.signal ? [options.signal] : [])]);
        try {
            // Validate every redirect so the API cannot be redirected to file:/ or credentials URLs.
            let target = String(url), response;
            const clientHeaders = new Headers(options.headers);
            // Ordinary browser content negotiation. This is not an anti-bot bypass.
            if (!clientHeaders.has('User-Agent')) clientHeaders.set('User-Agent',
                'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36');
            if (!clientHeaders.has('Accept')) clientHeaders.set('Accept', 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8');
            if (!clientHeaders.has('Accept-Language')) clientHeaders.set('Accept-Language', 'en-US,en;q=0.9');
            let currentOptions = { ...options, headers: clientHeaders, signal, redirect: 'manual' };
            for (let count = 0; count < 10; count++) {
                response = await fetchImpl(target, currentOptions);
                logger.info('HTTP', response.status, new URL(target).origin + new URL(target).pathname);
                if (![301, 302, 303, 307, 308].includes(response.status)) break;
                const location = response.headers.get('location');
                await response.body?.cancel();
                if (!location) throw new Error('Redirect has no Location');
                const next = httpURL(new URL(location, target).href);
                const headers = new Headers(currentOptions.headers);
                if (next.origin !== new URL(target).origin)
                    for (const name of ['authorization', 'proxy-authorization', 'cookie', 'host']) headers.delete(name);
                const method = String(currentOptions.method || 'GET').toUpperCase();
                if (response.status === 303 && method !== 'HEAD' || [301, 302].includes(response.status) && method === 'POST') {
                    currentOptions = { ...currentOptions, method: 'GET', body: undefined };
                    for (const name of ['content-type', 'content-length', 'content-encoding']) headers.delete(name);
                }
                currentOptions.headers = headers;
                target = next.href;
                if (count === 9) throw new Error('Too many redirects');
            }
            // Track until the body closes, including when the caller uses response.json().
            if (!response.body) { controllers.delete(controller); return response; }
            const reader = response.body.getReader();
            const body = new ReadableStream({
                async pull(stream) {
                    try { const item = await reader.read(); if (item.done) { controllers.delete(controller); stream.close(); } else stream.enqueue(item.value); }
                    catch (error) { controllers.delete(controller); stream.error(error); }
                },
                cancel(reason) { controllers.delete(controller); controller.abort(reason); return reader.cancel(reason); }
            });
            return new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers });
        } catch (error) { controllers.delete(controller); throw error; }
    }
    async function download(directory, url, filename) {
        if (!Object.values(Directories).includes(directory) || !directories[directory]) throw new Error('Choose a Directories value');
        if (disposed) throw new Error('Plugin is closed');
        let target, output;
        try {
            status('Downloading...');
            const response = await request(url, {}, 2 * 60 * 60 * 1000);
            if (!response.ok) { await response.body?.cancel(); throw new Error(`Download failed: HTTP ${response.status}`); }
            if (!response.body) throw new Error('Empty download response');
            const disposition = response.headers.get('content-disposition') || '';
            let inferred = /filename="([^"]+)"/i.exec(disposition)?.[1] || /filename=([^;]+)/i.exec(disposition)?.[1];
            if (!inferred) { try { inferred = decodeURIComponent(new URL(url).pathname.split('/').pop()); } catch { inferred = 'download'; } }
            target = reserveDownload(directories[directory], filename || inferred || 'download');
            output = fs.createWriteStream(target, { flags: 'w' });
            // Observe write errors immediately, including disk-full errors between body chunks.
            let writeError; output.on('error', error => { writeError = error; });
            let received = 0, lastUpdate = 0;
            for await (const chunk of response.body) {
                if (disposed) throw new Error('Plugin is closed');
                if (writeError) throw writeError;
                if (!output.write(chunk)) await once(output, 'drain');
                received += chunk.length;
                if (Date.now() - lastUpdate > 250) { status(`Downloading ${path.basename(target)} — ${(received / 1048576).toFixed(1)} MiB`); lastUpdate = Date.now(); }
            }
            output.end(); await once(output, 'finish');
            status(`Saved ${path.basename(target)}`);
            return target;
        } catch (error) {
            if (output) { output.destroy(); await new Promise(resolve => output.closed ? resolve() : output.once('close', resolve)); }
            if (target) fs.rmSync(target, { force: true });
            status(`Download failed: ${String(error.message).slice(0, 460)}`);
            throw error;
        }
    }
    const downloadManager = Object.freeze({ download(...args) {
        const task = download(...args); downloads.add(task);
        task.finally(() => downloads.delete(task)).catch(() => {});
        return task;
    } });
    let context;
    async function run(source, filename = 'native-plugin.js') {
        context = vm.createContext({ NPlugin, NButton, ViewType, Directories, downloadManager,
            fetch: request, URL, URLSearchParams, AbortController, AbortSignal,
            logger, console: { log: logger.info, info: logger.info, warn: logger.warn, error: logger.error, debug: logger.debug } });
        // Reviewed bundled JavaScript only. A vm context is not a sandbox for untrusted code.
        await new vm.Script(`(async () => {\n${source}\n})()`, { filename }).runInContext(context, { timeout: 1000 });
        if (!definition || !views.length) throw new Error('Define an NPlugin and at least one newView');
        update();
    }
    async function submitText(id, value) {
        if (disposed || typeof value !== 'string' || value.length > 256) return;
        const input = inputs.get(id);
        if (!input || !active?.entries.some(e => e.textField?.id === id)) return;
        input.value = value;
        for (const entry of active.entries) if (entry.textField?.id === id) entry.textField.value = value;
        update();
        try {
            context.__invokeText = () => input.onSubmit(value);
            await new vm.Script('__invokeText()', { filename: 'native-text-field' })
                .runInContext(context, { timeout: 1000 });
        } catch (error) { status(`Plugin error: ${String(error.message).slice(0, 470)}`); }
        finally { delete context.__invokeText; }
    }
    async function dispatch(id) {
        if (disposed || !callbacks.has(id) || !active?.entries.some(e => e.buttons.includes(id)) || buttons.get(id).busy) return;
        buttons.get(id).busy = true; update();
        const existingDownloads = new Set(downloads);
        try {
            context.__invoke = () => callbacks.get(id)();
            await new vm.Script('__invoke()', { filename: 'native-button' }).runInContext(context, { timeout: 1000 });
            await Promise.allSettled([...downloads].filter(task => !existingDownloads.has(task)));
        } catch (error) { status(`Plugin error: ${String(error.message).slice(0, 470)}`); }
        finally { delete context.__invoke; if (buttons.has(id)) buttons.get(id).busy = false; update(); }
    }
    function close() {
        disposed = true;
        for (const controller of controllers) controller.abort();
        controllers.clear(); callbacks.clear(); buttons.clear(); inputs.clear();
        return Promise.allSettled([...downloads]);
    }
    return { run, dispatch, submitText, close };
}
module.exports = { createRuntime, ViewType };
