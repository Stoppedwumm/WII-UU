'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { once } = require('node:events');
const { createRuntime } = require('../../resources/plugins/host/native-api.cjs');
const tick = () => new Promise(resolve => setImmediate(resolve));
async function setup(t) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'wiiuu-native-'));
    const events = [];
    const runtime = createRuntime({ emit: e => events.push(e), directories: { ROMS: directory, DOWNLOADS: directory } });
    t.after(async () => { await runtime.close(); fs.rmSync(directory, { recursive: true, force: true }); });
    return { runtime, events, directory, model: () => events.filter(e => e.type === 'view').at(-1)?.view };
}
test('requested native API: awaited fetch, game panels, callback and real collision-safe download', async t => {
    const { runtime, model, directory } = await setup(t);
    const server = http.createServer((req, res) => {
        if (req.url === '/games') { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify([{ gameTitle: 'Fixture Game', imageURL: `${base}/cover.png`, downloadURL: `${base}/redirect` }])); }
        else if (req.url === '/redirect') { res.writeHead(302, { Location: '/rom' }); res.end(); }
        else { res.writeHead(200, { 'Content-Disposition': 'attachment; filename="../../fixture.nds"' }); res.end('fixture bytes'); }
    });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    t.after(() => server.close());
    const base = `http://127.0.0.1:${server.address().port}`;
    await runtime.run(`
        const nativePlugin = NPlugin('XYZ');
        const view = nativePlugin.newView(ViewType.PANELS).title('Browse Games').closeButton(true);
        const response = await fetch('${base}/games');
        if (!response.ok) throw new Error('Games API failed');
        const games = await response.json();
        for (const game of games) {
            view.addEntry(game.gameTitle, game.imageURL, [NButton('Download', async () => {
                await downloadManager.download(Directories.ROMS, game.downloadURL);
            })]);
        }
    `);
    await tick();
    assert.equal(model().title, 'Browse Games');
    assert.equal(model().entries[0].title, 'Fixture Game');
    const button = model().entries[0].buttons[0].id;
    await runtime.dispatch(button); await tick();
    assert.equal(fs.readFileSync(path.join(directory, 'fixture.nds'), 'utf8'), 'fixture bytes');
    await runtime.dispatch(button); await tick();
    assert.equal(fs.readFileSync(path.join(directory, 'fixture (1).nds'), 'utf8'), 'fixture bytes');
    assert.match(model().status, /Saved fixture \(1\).nds/);
    assert.equal(model().entries[0].buttons[0].busy, false);
});
test('multiple native views, async callbacks, stale button protection and close requests', async t => {
    const { runtime, events, model } = await setup(t);
    await runtime.run(`
        const plugin = NPlugin('Views');
        const home = plugin.newView(ViewType.PANELS).title('Home').closeButton(false);
        home.addEntry('Entry', '', [NButton('Details', async () => {
            const other = plugin.newView(ViewType.PANELS).title('Details');
            other.addEntry('More', '', [NButton('Back', () => home.show()), NButton('Close', () => other.close())]);
            other.show();
        })]);
    `);
    await tick(); assert.equal(model().closeButton, false);
    const original = model().entries[0].buttons[0].id;
    await runtime.dispatch(original); await tick(); assert.equal(model().title, 'Details');
    await runtime.dispatch(original); await tick(); assert.equal(model().title, 'Details');
    await runtime.dispatch(model().entries[0].buttons[1].id);
    assert.equal(events.at(-1).type, 'close');
});
test('callback errors are visible and synchronous infinite callbacks time out', async t => {
    const { runtime, model } = await setup(t);
    await runtime.run(`const view = NPlugin('Errors').newView(ViewType.PANELS);
        view.addEntry('Oops', '', [NButton('Fail', () => { throw new Error('visible failure'); }), NButton('Loop', () => { while(true){} })]);`);
    await tick();
    await runtime.dispatch(model().entries[0].buttons[0].id); await tick(); assert.match(model().status, /visible failure/);
    await runtime.dispatch(model().entries[0].buttons[1].id); await tick(); assert.match(model().status, /timed out/);
});
test('closing aborts downloads, clears partial files and suppresses later view events', async t => {
    const { runtime, events, model, directory } = await setup(t);
    let start;
    const started = new Promise(resolve => { start = resolve; });
    const server = http.createServer((_req, res) => { res.setHeader('Content-Disposition', 'attachment; filename="partial.nds"'); res.write('start'); start(); });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    t.after(() => { server.closeAllConnections(); server.close(); });
    await runtime.run(`const view = NPlugin('Cancel').newView(ViewType.PANELS);
        view.addEntry('Cancel', '', [NButton('Download', () => { downloadManager.download(Directories.ROMS, 'http://127.0.0.1:${server.address().port}/slow'); })]);`);
    await tick(); const task = runtime.dispatch(model().entries[0].buttons[0].id);
    await started;
    while (!fs.existsSync(path.join(directory, 'partial.nds'))) await tick();
    await tick();
    assert.equal(model().entries[0].buttons[0].busy, true, 'Downloads keep a non-async callback busy');
    const count = events.length;
    await runtime.close(); await task; await tick();
    assert.deepEqual(fs.readdirSync(directory), []);
    assert.equal(events.length, count);
});
test('invalid view definitions, URL protocols and directory names are rejected', async t => {
    for (const source of [`NPlugin('X').newView('HTML')`, `NPlugin('X'); NPlugin('Y')`,
        `NPlugin('X').newView(ViewType.PANELS).addEntry('X','file:///tmp/image',[])`,
        `NPlugin('X').newView(ViewType.PANELS); await fetch('file:///tmp/no')`,
        `NPlugin('X').newView(ViewType.PANELS); await downloadManager.download('/tmp','https://example.com')`]) {
        const { runtime } = await setup(t);
        await assert.rejects(runtime.run(source));
    }
});

test('native text field submits typed values, updates model and rejects invalid input', async t => {
    const { runtime, model } = await setup(t);
    await runtime.run(`
        const view = NPlugin('Search').newView(ViewType.PANELS);
        view.addTextField('Game title', 'Type a game...', '', value => {
            view.status('Searched: ' + value);
        });
    `);
    await tick();
    const field = model().entries[0].textField;
    assert.match(field.id, /^t[0-9]+$/);
    assert.equal(field.placeholder, 'Type a game...');
    await runtime.submitText(field.id, 'Paper Mario');
    await tick();
    assert.equal(model().status, 'Searched: Paper Mario');
    assert.equal(model().entries[0].textField.value, 'Paper Mario');
    await runtime.submitText(field.id, 'x'.repeat(257));
    await tick();
    assert.equal(model().entries[0].textField.value, 'Paper Mario');
    await runtime.submitText('t99999', 'ignored');
    await tick();
    assert.equal(model().status, 'Searched: Paper Mario');
});

test('native logger emits diagnostics and HTTP requests include browser headers', async t => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wiiuu-native-logging-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const messages = [];
    let ua, accept;
    const runtime = createRuntime({
        emit: () => {},
        directories: { ROMS: dir, DOWNLOADS: dir },
        log: (level, message) => messages.push([level, message]),
        fetchImpl: async (_url, options) => {
            ua = options.headers.get('user-agent');
            accept = options.headers.get('accept');
            return new Response('<html>denied</html>', { status: 403,
                headers: { 'content-type': 'text/html' } });
        }
    });
    t.after(() => runtime.close());
    await runtime.run(`
        const view = NPlugin('Logging').newView(ViewType.PANELS);
        logger.info('Starting test');
        const response = await fetch('https://example.com/roms');
        logger.warn('HTTP', response.status);
        await response.text();
    `);
    assert.ok(ua.includes('Chrome/'));
    assert.ok(accept.includes('text/html'));
    assert.ok(messages.some(([level, msg]) => level === 'info' && msg.includes('Starting test')));
    assert.ok(messages.some(([level, msg]) => level === 'warn' && msg.includes('403')));
    assert.ok(messages.some(([level, msg]) => level === 'info' && msg.includes('HTTP 403')));
});

test('browserAuth uses isolated host callbacks, validates URLs, and never exposes cookies', async t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'wiiuu-browser-auth-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const opened = [];
    let closed = 0, confirmed = false;
    const runtime = createRuntime({
        emit: () => {},
        directories: { ROMS: directory, DOWNLOADS: directory },
        browserAuth: {
            open: async url => { opened.push(url); },
            close: () => { closed++; },
            isAuthenticated: () => confirmed
        }
    });
    t.after(() => runtime.close());
    await runtime.run(`
        const view = NPlugin('Auth').newView(ViewType.PANELS);
        view.addEntry('Login', '', [
            NButton('Open', async () => {
                await browserAuth.open('https://example.com/login');
                view.status(browserAuth.isAuthenticated() ? 'Confirmed' : 'Browser opened');
            }),
            NButton('Close', () => browserAuth.close())
        ]);
    `);
    await tick();
    const model = [];
    // A real URL is handed to the host; session state is not exposed to JS.
    const current = events => events.filter(e => e.type === 'view').at(-1)?.view;
    // Verify the mock host receives the URL after native activation.
    // A second runtime with events provides the control IDs for dispatch.
    await runtime.close();
    const events = [];
    const other = createRuntime({
        emit: e => events.push(e),
        directories: { ROMS: directory, DOWNLOADS: directory },
        browserAuth: {
            open: async url => { opened.push(url); },
            close: () => { closed++; },
            isAuthenticated: () => confirmed
        }
    });
    t.after(() => other.close());
    await other.run(`
        const view = NPlugin('Auth').newView(ViewType.PANELS);
        view.addEntry('Login', '', [
            NButton('Open', async () => { await browserAuth.open('https://example.com/login'); view.status('Opened'); }),
            NButton('Close', () => browserAuth.close())
        ]);
    `);
    await tick();
    let buttons = current(events).entries[0].buttons;
    await other.dispatch(buttons[0].id);
    assert.deepEqual(opened, ['https://example.com/login']);
    await other.dispatch(buttons[1].id);
    assert.equal(closed, 1);
});
