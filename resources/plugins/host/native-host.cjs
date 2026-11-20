'use strict';
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const { createRuntime } = require('./native-api.cjs');
const [id, home, roms] = process.argv.slice(2);
const emit = value => process.stdout.write(JSON.stringify(value) + '\n');
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, 'catalog.json'), 'utf8'));
const entry = catalog.find(item => item.id === id && item.type === 'native');
if (!entry || !home || !roms) throw new Error('Unknown native plugin or missing directories');
const runtime = createRuntime({ emit, directories: { ROMS: roms, DOWNLOADS: path.join(home, 'downloads') } });
const input = readline.createInterface({ input: process.stdin, terminal: false });
input.on('line', line => {
    if (line.length > 1024) return;
    try { const event = JSON.parse(line); if (event.type === 'activate' && typeof event.id === 'string') runtime.dispatch(event.id); }
    catch (error) { emit({ type: 'error', message: error.message }); }
});
let stopping = false;
async function stop() {
    if (stopping) return;
    stopping = true; input.close();
    await runtime.close();
    process.exit(0);
}
input.on('close', stop);
process.on('SIGTERM', stop);
process.on('unhandledRejection', error => emit({ type: 'error', message: String(error?.message || error).slice(0, 500) }));
runtime.run(fs.readFileSync(path.join(__dirname, 'store', entry.script), 'utf8'), entry.script)
    .catch(error => { emit({ type: 'error', message: String(error.message).slice(0, 500) }); stop(); });
