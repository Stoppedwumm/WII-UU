import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createArchive} from '../server.mjs';
import {passwordHash} from '../diagnostics.mjs';

test('opt-in diagnostics: PIN validation, token authentication, redaction, admin login, revocation', async t => {
  const dataDir=await fs.mkdtemp(path.join(os.tmpdir(),'wiiuu-telemetry-test-'));
  const server=await createArchive({dataDir,token:'archive-publisher-token-12345678901234567890',
    publicUrl:'https://archive.example.test',adminUser:'admin',
    adminPasswordHash:passwordHash('a very secure test password')});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+server.address().port;
  t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
    await fs.rm(dataDir,{recursive:true,force:true});});
  const call=(route,method='GET',body,headers={})=>fetch(base+route,{method,
    headers:{'Content-Type':'application/json',...headers},body:body===undefined?undefined:JSON.stringify(body)});
  assert.equal((await call('/admin/diagnostics')).status,401);
  assert.equal((await call('/api/diagnostics/enroll','POST',{pin:'1234',consent:false,consentVersion:1})).status,400);
  assert.equal((await call('/api/diagnostics/enroll','POST',{pin:'12345',consent:true,consentVersion:1})).status,400);
  assert.equal((await call('/api/diagnostics/enroll','POST',{pin:'1234',consent:true,consentVersion:0})).status,400);
  const enrolled=await call('/api/diagnostics/enroll','POST',{pin:'0123',consent:true,consentVersion:1});
  assert.equal(enrolled.status,201);
  const d=await enrolled.json();
  assert.match(d.deviceId,/^[a-f0-9-]{36}$/);assert.ok(d.token.length>=32);
  const headers={'X-Device-Id':d.deviceId,'Authorization':'Bearer '+d.token};
  assert.equal((await call('/api/diagnostics/logs','POST',{entries:['hello']},
    {'X-Device-Id':d.deviceId,'Authorization':'Bearer wrong'})).status,401);
  const message='Auth token=secret456 password=abc123 https://example.com/path?token=xyz 192.168.4.1';
  assert.equal((await call('/api/diagnostics/logs','POST',{version:'2.1.4',
    entries:[message,'error on /Users/bernhard/Desktop']},headers)).status,201);
  assert.equal((await call('/api/diagnostics/logs','POST',{entries:['repeat']},headers)).status,429);
  const basic=(user,pass)=>({Authorization:'Basic '+Buffer.from(user+':'+pass).toString('base64')});
  assert.equal((await call('/admin/diagnostics','GET',undefined,basic('admin','wrong'))).status,401);
  const panel=await call('/admin/diagnostics','GET',undefined,basic('admin','a very secure test password'));
  assert.equal(panel.status,200);
  const page=await panel.text();assert.match(page,/0123/);assert.match(page,/\/admin\/diagnostics\//);
  const logs=await call('/admin/diagnostics/'+d.deviceId,'GET',undefined,basic('admin','a very secure test password'));
  assert.equal(logs.status,200);
  const content=await logs.text();
  assert.match(content,/\[REDACTED\]/);assert.doesNotMatch(content,/secret456|abc123|example.com|192.168.4.1|bernhard/);
  assert.equal((await call('/api/diagnostics/device','DELETE',undefined,headers)).status,200);
  assert.equal((await call('/api/diagnostics/logs','POST',{entries:['gone']},headers)).status,401);
  const after=await call('/admin/diagnostics','GET',undefined,basic('admin','a very secure test password'));
  assert.doesNotMatch(await after.text(),/0123/);
  assert.equal((await call('/version.json')).status,404); // Diagnostics must not affect release feeds.
});
