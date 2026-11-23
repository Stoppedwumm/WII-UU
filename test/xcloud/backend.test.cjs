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
    '  removeAll() { throw new Error("Inherited removeAll must not run: missing _filepath"); }',
    '}',
    'class Msal {',
    '  constructor(store) { this.store=store; }',
    '  async doDeviceCodeAuth() { return {device_code:"private-device-code", user_code:"ABCD-EFGH",',
    '    verification_uri:"https://www.microsoft.com/link",expires_in:900}; }',
    '  async doPollForDeviceCodeAuth(code) {',
    '    if(code!=="private-device-code")throw new Error("Invalid pairing code");',
    '    this.store.removeAll();',
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
  let diagnostics='';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data',chunk=>{diagnostics+=chunk;});
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
  const managedRoute=async()=>fetch(url+'/api/controllers',{headers:{
    'X-Wiiuu-Session':ready.secret
  }}).then(r=>r.json());
  assert.deepEqual((await managedRoute()).pads,[]);
  const managedButtons=['A','B','X','Y','LeftShoulder','RightShoulder',
    'LeftTrigger','RightTrigger','View','Menu','LeftThumb','RightThumb',
    'DPadUp','DPadDown','DPadLeft','DPadRight','Nexus'];
  const managedAxes=['LeftThumbXAxis','LeftThumbYAxis','RightThumbXAxis','RightThumbYAxis'];
  const managedPad={GamepadIndex:2};
  for(const key of managedButtons.concat(managedAxes))managedPad[key]=0;
  managedPad.A=1;managedPad.RightThumbYAxis=-0.3;
  child.stdin.write(JSON.stringify({event:'managedPads',pads:[managedPad]})+'\n');
  let observed;
  for(let attempt=0;attempt<30;attempt++){
    observed=await managedRoute();
    if(observed.pads.length)break;
    await new Promise(r=>setTimeout(r,20));
  }
  assert.equal(observed.pads.length,1,'WII-UU controller IPC reaches the player session');
  assert.equal(observed.pads[0].GamepadIndex,2);
  assert.equal(observed.pads[0].A,1);
  assert.equal(observed.pads[0].RightThumbYAxis,-0.3);
  child.stdin.write(JSON.stringify({event:'managedPads',pads:[]})+'\n');
  for(let attempt=0;attempt<30;attempt++){
    if((await managedRoute()).pads.length===0)break;
    await new Promise(r=>setTimeout(r,20));
  }
  assert.deepEqual((await managedRoute()).pads,[],'Leaving managed mode clears slots');

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
  // stderr is the only diagnostic stream; stdout stays machine-readable.
  await new Promise(resolve=>setImmediate(resolve));
  assert.match(diagnostics,/device-flow-issued/);
  assert.match(diagnostics,/device-poll-resolved/);
  assert.match(diagnostics,/user-token-saved/);
  assert.match(diagnostics,/signed-in-event-sent/);
  assert.match(diagnostics,/native-status-check/);
  assert.match(diagnostics,/catalog-error/);
  assert.doesNotMatch(diagnostics,/private-device-code|ABCD-EFGH|secret-access|secret-refresh/);
});

test('player filters null and malformed remote ICE records before invoking xcloud-player',()=>{
  const vm=require('node:vm');
  const source=fs.readFileSync(path.join(__dirname,'../../resources/xcloud/player-entry.js'),'utf8');
  const begin=source.indexOf('function validRemoteIce(data){');
  const end=source.indexOf('\nasync function fetchRemoteIce(){',begin);
  assert.ok(begin>=0&&end>begin,'ICE sanitizer must be present');
  const sanitize=vm.runInNewContext('('+source.slice(begin,end).trim()+')');
  const filtered=sanitize({iceCandidates:[
    null,undefined,{},'bad',{candidate:null},{candidate:'a=end-of-candidates'},
    {candidate:'candidate:1 1 UDP 1 192.0.2.1 1234 typ host',sdpMid:'0',sdpMLineIndex:0}
  ]});
  assert.equal(filtered.length,1);
  assert.equal(filtered[0].sdpMid,'0');
  assert.equal(filtered[0].sdpMLineIndex,0);
  assert.equal(sanitize({candidates:[null]}).length,0);
  assert.equal(sanitize(null).length,0);
  const wrapped=sanitize({exchangeResponse:JSON.stringify([
    {candidate:'a=candidate:2 1 UDP 100 192.0.2.55 9002 typ host',sdpMid:'0',sdpMLineIndex:'0'},
    {candidate:'a=end-of-candidates',sdpMid:'0',sdpMLineIndex:'0'},
    null
  ])});
  assert.equal(wrapped.length,1);
  assert.equal(wrapped[0].candidate,'candidate:2 1 UDP 100 192.0.2.55 9002 typ host');
  assert.equal(wrapped[0].sdpMLineIndex,0);
  const service=fs.readFileSync(path.join(__dirname,'../../resources/xcloud/service.cjs'),'utf8');
  assert.ok(service.includes('iceCandidates:payload.candidates'));
});

test('xCloud gamepad bridge maps buttons, flushes frames, and supports hotplug',async()=>{
  const vm=require('node:vm');
  const source=fs.readFileSync(path.join(__dirname,'../../resources/xcloud/player-entry.js'),'utf8');
  const from=source.indexOf('const GAMEPAD_FIELDS=');
  const to=source.indexOf('\nasync function connect(){',from);
  assert.ok(from>=0&&to>from,'Gamepad bridge should be part of the bundled player');
  let pads=[null];
  let tick,managerTick;
  let injectedManagedPads=[];
  const calls=[],changes=[];
  class FakeGamepad {
    constructor(index){this._index=index;this._isFocused=true;}
    attach(player){
      this._player=player;
      player._channels.control.sendGamepadState(this._index,true,this);
      this.detectActiveGamepad();
    }
    detach(){this._player._channels.control.sendGamepadState(this._index,false);}
    getPhysicalGamepadId(){return this._physicalGamepadId;}
  }
  const ctx={
    Gamepad:FakeGamepad,
    navigator:{getGamepads:()=>pads},
    stop:false,
    console:{info:()=>{},warn:()=>{}},
    setInterval:(callback,ms)=>{if(ms===500){tick=callback;return 1;}managerTick=callback;return 2;},
    clearInterval:id=>{if(id===1)tick=null;},
    request:async()=>({pads:injectedManagedPads})
  };
  const {gamepadFrame,controllerBridge}=vm.runInNewContext(
    source.slice(from,to)+'\n({gamepadFrame,controllerBridge})',ctx);
  const standard={
    index:0,mapping:'standard',
    buttons:Array.from({length:16},()=>({value:0})),
    axes:[0.5,-0.5,0.25,-0.25]
  };
  standard.buttons[0]={value:1}; // A
  standard.buttons[7]={value:0.4}; // right trigger analog
  standard.buttons[12]={value:1}; // dpad up
  const frame=gamepadFrame(standard,2);
  assert.equal(frame.GamepadIndex,2);
  assert.equal(frame.A,1);
  assert.equal(frame.Nexus,0,'missing button 16 is allowed');
  assert.equal(frame.RightTrigger,0.4);
  assert.equal(frame.DPadUp,1);
  assert.ok(frame.LeftThumbXAxis>0 && frame.LeftThumbXAxis<1);
  assert.ok(frame.LeftThumbYAxis<0 && frame.RightThumbYAxis<0);
  assert.equal(gamepadFrame({buttons:[],axes:[NaN,Infinity]},0).LeftThumbXAxis,0);
  const combo={buttons:Array.from({length:17},()=>({value:0})),axes:[]};
  combo.buttons[8]={value:1};combo.buttons[9]={value:1};
  const special=gamepadFrame(combo,0);
  assert.equal(special.Nexus,1);
  assert.equal(special.View,0);
  assert.equal(special.Menu,0);
  const handlers={};
  const input={
    _dataChannel:{readyState:'connecting'},
    start:()=>{},
    queueGamepadFrames:(frames,force)=>calls.push({frames,force}),
    gamepadStateLoop:()=>{}
  };
  const player={_peerConnection:{connectionState:'connected'},_channels:{
    input,control:{_dataChannel:{readyState:'connecting'},sendGamepadState:(slot,added,handler)=>{
      changes.push({slot,added});if(added)handlers[slot]=handler;else delete handlers[slot];
    }}
  }};
  const dispose=controllerBridge(player);
  input.start();
  assert.equal(typeof tick,'function');
  assert.equal(Object.keys(handlers).length,0);
  pads=[standard,null,{...standard,index:2}];
  tick();
  assert.equal(Object.keys(handlers).length,0,'do not announce controllers before channels open');
  input._dataChannel.readyState='open';
  player._channels.control._dataChannel.readyState='open';
  tick();
  assert.equal(Object.keys(handlers).length,2);
  assert.equal(handlers[0].getPhysicalGamepadId(),0);
  assert.equal(handlers[1].getPhysicalGamepadId(),2);
  assert.equal(handlers[0].getGamepadState().A,1);
  input.queueGamepadFrames([handlers[0].getGamepadState()]);
  assert.equal(calls[0].force,true,'gamepad frames must be sent without metadata');
  pads=[null,null,{...standard,index:2}];
  tick();
  assert.equal(Object.keys(handlers).length,1);
  assert.equal(handlers[1].getGamepadState().GamepadIndex,1);
  await new Promise(r=>setImmediate(r));
  injectedManagedPads=[{...gamepadFrame(standard,0),A:0,B:1}];
  managerTick();
  await new Promise(r=>setImmediate(r));
  assert.equal(Object.keys(handlers).length,1,'WII-UU-managed input replaces raw browser gamepad');
  assert.equal(handlers[0].getGamepadState().B,1,'Xbox receives the pre-remapped WII-UU frame');
  assert.equal(handlers[0].getGamepadState().A,0);
  injectedManagedPads=[];
  managerTick();
  await new Promise(r=>setImmediate(r));
  assert.equal(Object.keys(handlers).length,1,'Browser gamepad restored after manager deactivates');
  assert.equal(handlers[0].getPhysicalGamepadId(),2);
  dispose();
  assert.equal(Object.keys(handlers).length,0);
  assert.equal(tick,null);
});
