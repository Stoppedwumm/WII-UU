'use strict';
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const { spawn } = require('node:child_process');
const { createRuntime } = require('./native-api.cjs');
const [id, home, roms] = process.argv.slice(2);
const emit = value => process.stdout.write(JSON.stringify(value) + '\n');
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, 'catalog.json'), 'utf8'));
const entry = catalog.find(item => item.id === id && item.type === 'native');
if (!entry || !home || !roms) throw new Error('Unknown native plugin or missing directories');
// Separate bounded diagnostic log from the JSON-lines stdout protocol.
const logDir = path.join(home, 'logs');
fs.mkdirSync(logDir, { recursive: true });
const logFile = path.join(logDir, 'native-plugin-' + id.replace(/[^a-z0-9-]/gi, '_') + '.log');
function log(level, message) {
    const line = new Date().toISOString() + ' [' + level + '] ' +
        String(message).replace(/[\r\n]/g, ' ').slice(0, 1500) + '\n';
    try {
        if (fs.existsSync(logFile) && fs.statSync(logFile).size > 1024 * 1024)
            fs.renameSync(logFile, logFile + '.1');
        fs.appendFileSync(logFile, line, { encoding: 'utf8', mode: 0o600 });
    } catch (error) { process.stderr.write('[native plugin log] ' + error.message + '\n'); }
}
let authChild = null;
let authConfirmed = false;
function openAuthBrowser(url) {
    const target = new URL(url);
    if (target.protocol !== 'https:' || target.hostname !== 'romsfun.com' ||
        target.username || target.password) throw new Error('Authentication site not permitted');
    if (authChild && authChild.exitCode === null) return;
    authConfirmed = false;
    const env = { ...process.env };
    delete env.ELECTRON_RUN_AS_NODE;
    delete env.NODE_OPTIONS;
    const child = spawn(process.execPath,
        [path.join(__dirname, 'auth-window.cjs'), id, home, target.href], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    authChild = child;
    let buffer = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => {
        buffer += chunk;
        if (buffer.length > 8192) buffer = '';
        let index;
        while ((index = buffer.indexOf('\\n')) >= 0) {
            const line = buffer.slice(0, index); buffer = buffer.slice(index + 1);
            try { if (JSON.parse(line).type === 'confirmed') { authConfirmed = true; log('info', 'Browser session acknowledged'); } }
            catch (_) {}
        }
    });
    child.stderr.on('data', data => log('warn', String(data).slice(0, 500)));
    child.on('error', error => log('error', error.message));
    child.on('exit', () => { if (authChild === child) authChild = null; });
    log('info', 'Opening isolated authentication browser');
}
function closeAuthBrowser() {
    if (authChild && authChild.exitCode === null) authChild.kill();
    authChild = null;
}
const browserAuth = Object.freeze({
    open: url => { openAuthBrowser(url); return Promise.resolve(); },
    close: () => { closeAuthBrowser(); },
    isAuthenticated: () => authConfirmed
});
const runtime = createRuntime({ emit, log, browserAuth,
    directories: { ROMS: roms, DOWNLOADS: path.join(home, 'downloads') } });
const input = readline.createInterface({ input: process.stdin, terminal: false });
input.on('line', line => {
    if (line.length > 1024) return;
    try { const event = JSON.parse(line); if (event.type === 'activate' && typeof event.id === 'string') runtime.dispatch(event.id);
        else if (event.type === 'text' && typeof event.id === 'string' && typeof event.value === 'string') runtime.submitText(event.id, event.value); }
    catch (error) { emit({ type: 'error', message: error.message }); }
});
let stopping = false;
async function stop() {
    if (stopping) return;
    stopping = true; input.close();
    closeAuthBrowser();
    await runtime.close();
    process.exit(0);
}
input.on('close', stop);
process.on('SIGTERM', stop);
process.on('unhandledRejection', error => emit({ type: 'error', message: String(error?.message || error).slice(0, 500) }));
log('info', 'Starting native plugin ' + id);
runtime.run(fs.readFileSync(path.join(__dirname, 'store', entry.script), 'utf8'), entry.script)
    .catch(error => { log('error', String(error.message)); emit({ type: 'error', message: String(error.message).slice(0, 500) }); stop(); });
