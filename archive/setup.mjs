import fs from 'node:fs/promises';
import { randomBytes } from 'node:crypto';

const origin = process.argv[2] ?? 'https://wii-uu-archive.stoppedwumm.net';
const env = `HOST=127.0.0.1\nPORT=8081\nPUBLIC_URL=${origin}\nDATA_DIR=./data\nARCHIVE_TOKEN=${randomBytes(48).toString('hex')}\n`;
try { await fs.writeFile('.env', env, { flag: 'wx', mode: 0o600 }); console.log('Created .env. Run npm start. Preserve .env and data/ when updating.'); }
catch (error) { if (error.code !== 'EEXIST') throw error; console.log('.env already exists; preserved existing token and settings.'); }
