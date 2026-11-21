import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const installer = fileURLToPath(new URL('../install.sh', import.meta.url));

test('archive installer parses as Bash and provisions the admin account once', () => {
  const check = spawnSync('bash', ['-n', installer], { encoding: 'utf8' });
  assert.equal(check.status, 0, check.stderr || 'bash -n failed');

  const content = fs.readFileSync(installer, 'utf8');
  assert.equal((content.match(/^cat > \/etc\/systemd\/system\/wiiuu-archive\.service <<SERVICE$/gm) ?? []).length, 1);
  assert.equal((content.match(/^SERVICE$/gm) ?? []).length, 1);
  assert.equal((content.match(/^if ! grep -q '\^ARCHIVE_ADMIN_PASSWORD_HASH=' \/etc\/wiiuu-archive\.env; then$/gm) ?? []).length, 1);
  assert.match(content, /IFS=\$'\\t' read -r admin_password admin_hash/);
  assert.match(content, /scryptSync\(password,salt,64\)/);
  assert.match(content, /install -m 644 server\.mjs diagnostics\.mjs client\.mjs package\.json/);
});
