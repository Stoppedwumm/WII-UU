'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawn}=require('node:child_process');
const readline=require('node:readline');
const http=require('node:http');

test('local Xbox device-code bridge hides tokens and binds streaming API to a private session',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'wiiuu-xcloud-bridge-'));
  const dir=path.join(root,'runtime'),user=path.join(root,'user');
  const fake=path.join(dir,'node_modules','xal-node');
  fs.mkdirSync(fake,{recursive:true});
  fs.mkdirSync(user);
  fs.copyFileSync(path.join(__dirname,'../../resources/xcloud/service.cjs'),path.join(dir,'service.cjs'));
  fs.writeFileSync(path.join(dir,'player.html'),'LOCAL_PLAYER');
  fs.writeFileSync(path.join(dir,'player.bundle.js'),'window.playerLoaded=true');
  const fakeCode=[
    'class TokenStore {',
    '  constructor() { this._userToken=undefined;this._sisuToken=undefined;this._jwtKeys=undefined; }',
    '  loadJson(s) { const d=JSON.parse(s);if(d.userToken)this._userToken={data:d.userToken};return true; }',
    '  getUserToken() { return this._userToken; }',
    '}',
    'class Msal {',
    '  constructor(store) { this.store=store; }',
    '  async doDeviceCodeAuth() { return {device_code:"private-device-code", user_code:"ABCD-EFGH",',
    '    verification_uri:"https://www.microsoft.com/link",expires_in:900}; }',
    '  async doPollForDeviceCodeAuth(code) {',
    '    if(code!=="private-device-code")throw new Error("Invalid pairing code");',
    '    this.store._userToken={data:{access_token:"secret-access", refresh_token:"secret-refresh",',
    '      token_type:"Bearer",scope:"XboxLive.signin"}};',
    '    return this.store.getUserToken();',
    '  }',
    '  async getStreamingTokens() { throw new Error("Should not ask for xHome token"); }',
    '  async getGssvToken() { throw new Error("Xbox cloud entitlement unavailable in fixture"); }',
    '}',
    'module.exports={Msal,TokenStore};'
  ].join('\n');
  fs.writeFileSync(path.join(fake,'index.js'),fakeCode);
  const child=spawn(process.execPath,[path.join(dir,'service.cjs'),dir,user],{stdio:['pipe','pipe','pipe']});
  t.after(()=>{
    child.stdin.end();child.kill('SIGKILL');fs.rmSync(root,{recursive:true,force:true});
  });
  const queue=[],waiters=[];
  const lines=readline.createInterface({input:child.stdout});
  lines.on('line',line=>{
    let message;
    try{message=JSON.parse(line);}catch{return;}
    const match=waiters.findIndex(w=>w.predicate(message));
    if(match>=0){waiters.splice(match,1)[0].resolve(message);}else queue.push(message);
  });
  const receive=(predicate,timeout=12000)=>{
    const found=queue.findIndex(predicate);
    if(found>=0)return Promise.resolve(queue.splice(found,1)[0]);
    return new Promise((resolve,reject)=>{
      const waiter={predicate,resolve};waiters.push(waiter);
      setTimeout(()=>{
        const idx=waiters.indexOf(waiter);
        if(idx>=0){waiters.splice(idx,1);reject(new Error('Timed out waiting for backend message'));}
      },timeout).unref();
    });
  };
  const send=(id,cmd)=>child.stdin.write(JSON.stringify({id,cmd})+'\n');
  const ready=await receive(m=>m.event==='ready');
  assert.ok(Number.isInteger(ready.port)&&ready.port>0);
  assert.match(ready.secret,/^[0-9a-f]{64}$/);
  assert.equal(ready.signedIn,false);
  const url='http://127.0.0.1:'+ready.port;
  const untrusted=await fetch(url+'/api/start',{method:'POST',body:'{}'});
  assert.equal(untrusted.status,403);
  const spoofedStatus=await new Promise((resolve,reject)=>{
    const request=http.request(url+'/',{headers:{Host:'not-local.example'}},res=>{
      res.resume();resolve(res.statusCode);
    });
    request.on('error',reject);request.end();
  });
  assert.equal(spoofedStatus,403);
  const page=await (await fetch(url+'/')).text();
  assert.equal(page,'LOCAL_PLAYER');
  assert.ok(!page.includes('secret'));

  send(1,'auth');
  const code=await receive(m=>m.event==='code');
  assert.equal(code.code,'ABCD-EFGH');
  assert.equal(code.url,'https://www.microsoft.com/link');
  await receive(m=>m.event==='signedIn');
  const authResult=await receive(m=>m.id===1);
  assert.equal(authResult.ok,true);
  assert.ok(fs.readFileSync(path.join(user,'xcloud-tokens.json'),'utf8').includes('secret-refresh'));
  if(process.platform!=='win32')
    assert.equal(fs.statSync(path.join(user,'xcloud-tokens.json')).mode & 0o777,0o600);
  send(2,'signedIn');
  assert.equal((await receive(m=>m.id===2)).result.signedIn,true);

  // Microsoft approval must move the UI past pairing even when Xbox entitlement
  // has not yet been fetched, is unavailable, or the Xbox service is offline.
  send(5,'games');
  const catalogueFailure=await receive(m=>m.id===5);
  assert.equal(catalogueFailure.ok,false);
  assert.match(catalogueFailure.error,/Xbox cloud entitlement unavailable/);
  send(6,'signedIn');
  assert.equal((await receive(m=>m.id===6)).result.signedIn,true);


  const invalid=await fetch(url+'/api/start',{method:'POST',headers:{
    'X-Wiiuu-Session':ready.secret,'Content-Type':'application/json'
  },body:JSON.stringify({titleId:'NOT-IN-CATALOG'})});
  assert.equal(invalid.status,502);
  assert.match((await invalid.json()).error,/entitled title/);
  send(3,'logout');
  assert.equal((await receive(m=>m.id===3)).result.signedIn,false);
  assert.equal(fs.existsSync(path.join(user,'xcloud-tokens.json')),false);
  send(4,'signedIn');
  assert.equal((await receive(m=>m.id===4)).result.signedIn,false);
});
