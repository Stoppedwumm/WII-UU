import Player from 'xbox-xcloud-player/dist/player';
import Gamepad from 'xbox-xcloud-player/dist/input/gamepad';

const secret=location.hash.substring(1);
const title=new URL(location.href).searchParams.get('title');
const label=document.querySelector('#status');
const errors=document.querySelector('#fail');
const toolbar=document.querySelector('#top');
const action=document.querySelector('#disconnect');
let player, timer, stateTimer, audioTimer, stop=false, sessionStarted=false, stopControllers=null;
const autoAudio=new URL(location.href).searchParams.get('autoAudio')!=='0';
let soundStarted=false, audioStarting=false, streamConnected=false, unavailableChecks=0;
async function enableAudio() {
  if(audioStarting||soundStarted||stop)return;
  const audio=player?.getAudioElement();
  if(!audio)return;
  audioStarting=true;
  try {
    audio.muted=false;
    await audio.play();
    soundStarted=true;
    errors.textContent='';
    document.querySelector('#enable-audio').textContent='Audio playing';
    console.info('[xcloud-audio] playback enabled');
  } catch(error) {
    if(!autoAudio)errors.textContent='Audio playback blocked: '+(error?.message||'unknown');
    toolbar.classList.remove('hidden');
  } finally { audioStarting=false; }
}
async function sessionEnded() {
  if(stop)return;
  progress('Xbox session ended');
  await disconnect();
  // Chrome app windows are launched by Java, so Java kills the process when
  // /api/stop emits streamEnded. window.close() alone is not reliable here.
  window.close();
}
async function checkServerState() {
  if(stop||!streamConnected)return;
  try {
    const response=await request('state');
    unavailableChecks=0;
    if(['Failed','Stopped','Closed','Ended','Terminated','Cancelled'].includes(response.state))
      await sessionEnded();
  } catch(error) {
    if(stop)return;
    // Brief service outages should not immediately discard an active game.
    if(++unavailableChecks>=3) {
      console.warn('[xcloud-webrtc] Xbox session state unavailable three times');
      await sessionEnded();
    }
  }
}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function progress(s){label.textContent=s;}
function fail(error){const message=error instanceof Error?error.message:String(error);
  errors.textContent='Could not start Xbox Cloud Gaming: '+message;progress('Connection failed');}
async function request(action, data){
  const timeout=AbortSignal.timeout(action==='start'?90000:35000);
  const response=await fetch('/api/'+action,{
    method:data===undefined?'GET':'POST',signal:timeout,
    headers:{'X-Wiiuu-Session':secret,...(data===undefined?{}:{'Content-Type':'application/json'})},
    body:data===undefined?undefined:JSON.stringify(data),cache:'no-store'});
  const body=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(body.error||'Stream request failed ('+response.status+')');
  return body;
}
async function exchange(endpoint){
  for(let attempt=0;attempt<60&&!stop;attempt++){
    const data=await request(endpoint);
    if(!data.pending){
      if(typeof data.exchangeResponse==='string')return JSON.parse(data.exchangeResponse);
      return data;
    }
    await sleep(500);
  }
  throw new Error('Xbox signaling timed out');
}
// Some Xbox ICE exchange responses contain null entries or a nested
// iceCandidates list. xbox-xcloud-player assumes every array slot is an
// object and dereferences .candidate without checking.
function validRemoteIce(data){
  // Xbox returns the candidate array as JSON embedded in exchangeResponse.
  // During negotiation that property may be null/empty even with HTTP 200.
  if(typeof data?.exchangeResponse==='string'){
    try{data=JSON.parse(data.exchangeResponse);}catch{return [];}
  }
  const entries=Array.isArray(data)?data
    :Array.isArray(data?.iceCandidates)?data.iceCandidates
    :Array.isArray(data?.candidates)?data.candidates:[];
  return entries.filter(item=>item&&typeof item==='object'
      &&typeof item.candidate==='string'
      &&item.candidate.trim()!==''&&item.candidate!=='a=end-of-candidates'
      &&item.candidate!=='end-of-candidates'
      &&(typeof item.sdpMid==='string'||Number.isInteger(Number(item.sdpMLineIndex))))
    .map(item=>{
      const candidate=item.candidate.trim().replace(/^a=candidate:/,'candidate:');
      const index=Number(item.sdpMLineIndex);
      return {candidate,sdpMid:typeof item.sdpMid==='string'?item.sdpMid:null,
        sdpMLineIndex:Number.isInteger(index)&&index>=0?index:null};
    });
}
async function fetchRemoteIce(){
  let lastShape='';
  for(let attempt=0;attempt<60&&!stop;attempt++){
    const response=await request('iceGet');
    const candidates=validRemoteIce(response);
    if(candidates.length){
      console.info('[xcloud-webrtc] Remote ICE ready: count='+candidates.length+' attempts='+(attempt+1));
      return candidates;
    }
    const shape=Array.isArray(response)?'array'
      :typeof response?.exchangeResponse==='string'?'exchangeResponse-string'
      :response?.pending?'pending'
      :response&&typeof response==='object'?Object.keys(response).sort().join(','):'empty';
    if(shape!==lastShape||attempt%10===0){
      console.info('[xcloud-webrtc] Waiting for remote ICE: attempt='+(attempt+1)+' shape='+shape);
      lastShape=shape;
    }
    await sleep(500);
  }
  throw new Error('Xbox returned no usable remote ICE candidates after polling');
}

// Chromium exposes physical controllers and WII-UU's Linux uinput gamepads
// through the W3C Gamepad API. The earlier mapper normalizes 20d6:a713
// devices to the standard Xbox button/axis layout.
const GAMEPAD_FIELDS=Object.freeze({
  A:0,B:1,X:2,Y:3,LeftShoulder:4,RightShoulder:5,
  LeftTrigger:6,RightTrigger:7,View:8,Menu:9,
  LeftThumb:10,RightThumb:11,DPadUp:12,DPadDown:13,
  DPadLeft:14,DPadRight:15,Nexus:16
});
function gamepadFrame(pad,slot,deadzone=0.08){
  const pressed=index=>{
    const b=pad?.buttons?.[index];
    const v=typeof b==='number'?b:b?.value;
    const n=Number(v);
    if(Number.isFinite(n))return Math.max(0,Math.min(1,n));
    return b?.pressed?1:0;
  };
  const axis=index=>{
    const v=Number(pad?.axes?.[index]);
    if(!Number.isFinite(v))return 0;
    const n=Math.max(-1,Math.min(1,v));
    return Math.abs(n)<deadzone?0:(n-Math.sign(n)*deadzone)/(1-deadzone);
  };
  const frame={
    GamepadIndex:slot,
    LeftThumbXAxis:axis(0),LeftThumbYAxis:axis(1),
    RightThumbXAxis:axis(2),RightThumbYAxis:axis(3)
  };
  for(const [key,index] of Object.entries(GAMEPAD_FIELDS))frame[key]=pressed(index);
  // Supports controllers that don't expose a dedicated Xbox/Home button.
  if(frame.Menu>0&&frame.View>0){frame.Menu=0;frame.View=0;frame.Nexus=1;}
  return frame;
}
class WIIUUGamepad extends Gamepad {
  constructor(slot,physicalIndex){
    super(slot,{enable_keyboard:false,enable_vibration:true,
      gamepad_deadzone:0.08,gamepad_force_capture:false});
    this._physicalGamepadId=physicalIndex;
  }
  // The upstream autodetection selects the LAST connected controller and
  // steals other players' devices. We assign stable slots ourselves.
  detectActiveGamepad(){}
  onGamepadConnected(){}
  onGamepadDisconnected(){}
  getGamepadState(){
    const pad=navigator.getGamepads?.()?.[this._physicalGamepadId];
    if(!pad||this._isFocused===false)return undefined;
    return gamepadFrame(pad,this._index);
  }
  handleVibration(report){
    const pad=navigator.getGamepads?.()?.[this._physicalGamepadId];
    const actuator=pad?.vibrationActuator;
    if(!actuator||typeof actuator.playEffect!=='function')return;
    void Promise.resolve(actuator.playEffect('dual-rumble',{
      startDelay:0,duration:Math.max(0,report.durationMs||0),
      weakMagnitude:Math.max(0,Math.min(1,report.rightMotorPercent||0)),
      strongMagnitude:Math.max(0,Math.min(1,report.leftMotorPercent||0))
    })).catch(()=>{});
  }
}
class WIIUUManagedGamepad extends Gamepad {
  constructor(slot,frames){
    super(slot,{enable_keyboard:false,enable_gamepad:false,enable_vibration:false});
    this.frames=frames;
  }
  detectActiveGamepad(){}
  getGamepadState(){
    if(this._isFocused===false)return undefined;
    return this.frames.get(this._index);
  }
  handleVibration(){}
}
// Keyboard/mouse is an opt-in local virtual Xbox pad. No OS-level hooks.
const KEYBOARD_BUTTONS=Object.freeze({
  Space:'A',KeyC:'B',KeyR:'X',KeyF:'Y',KeyQ:'LeftShoulder',KeyE:'RightShoulder',
  KeyZ:'LeftTrigger',KeyX:'RightTrigger',Tab:'View',Enter:'Menu',
  ShiftLeft:'LeftThumb',ShiftRight:'LeftThumb',ControlLeft:'RightThumb',
  ArrowUp:'DPadUp',ArrowDown:'DPadDown',ArrowLeft:'DPadLeft',ArrowRight:'DPadRight'
});
function keyboardMousePad(){
  const keys=new Set(),buttons=new Set();
  let dx=0,dy=0;
  const clamp=n=>Math.max(-1,Math.min(1,n));
  return {
    keys,buttons,
    move(x,y){dx=clamp(dx+x/45);dy=clamp(dy+y/45);},
    reset(){keys.clear();buttons.clear();dx=dy=0;},
    frame(slot){
      const down=key=>keys.has(key)?1:0;
      let x=down('KeyD')-down('KeyA'),y=down('KeyS')-down('KeyW');
      const length=Math.hypot(x,y);if(length>1){x/=length;y/=length;}
      const frame=gamepadFrame(null,slot,0);
      frame.LeftThumbXAxis=x;frame.LeftThumbYAxis=y;
      frame.RightThumbXAxis=dx;frame.RightThumbYAxis=dy;
      dx=dy=0;
      for(const [key,field] of Object.entries(KEYBOARD_BUTTONS))
        if(keys.has(key))frame[field]=1;
      if(buttons.has(0))frame.RightTrigger=1;
      if(buttons.has(2))frame.LeftTrigger=1;
      if(buttons.has(1))frame.RightThumb=1;
      return frame;
    }
  };
}
class WIIUUKeyboardGamepad extends Gamepad {
  constructor(slot,adapter){
    super(slot,{enable_keyboard:false,enable_gamepad:false,enable_vibration:false});
    this.adapter=adapter;
  }
  detectActiveGamepad(){}
  getGamepadState(){return this.adapter.frame(this._index);}
  handleVibration(){}
}
function controllerBridge(activePlayer){
  const input=activePlayer._channels.input;
  const physical=new Map();
  const managed=new Map();
  const managerFrames=new Map();
  const keyboard=keyboardMousePad();
  let keyboardEnabled=false,keyboardHandler=null;
  const doc=typeof document==='undefined'?null:document;
  const win=typeof window==='undefined'?null:window;
  const surface=doc?.querySelector('#player');
  let toggle=null;
  const listeners=[];
  const listen=(target,type,fn,options)=>{
    if(!target)return;
    target.addEventListener(type,fn,options);
    listeners.push(()=>target.removeEventListener(type,fn,options));
  };
  let scanTimer=null, managerTimer=null, managerBusy=false;
  let activeMode='browser';
  let firstFrameReported=false;
  const queue=input.queueGamepadFrames.bind(input);
  // xbox-xcloud-player's InputQueue does not flush gamepad-only frames by
  // default. Force a send so movement works even without video metadata.
  input.queueGamepadFrames=(frames)=>{
    if(!frames?.length)return;
    if(!firstFrameReported && input._dataChannel?.readyState==='open'){
      console.info('[xcloud-gamepad] forwarding input frames to Xbox');
      firstFrameReported=true;
    }
    queue(frames,true);
  };
  const previousLoop=input.gamepadStateLoop.bind(input);
  input.gamepadStateLoop=()=>{
    if(stop||activePlayer._peerConnection.connectionState==='closed')return;
    previousLoop();
  };
  const channelsReady=()=>activePlayer._channels.control._dataChannel?.readyState==='open'
    &&input._dataChannel?.readyState==='open';
  const detach=registry=>{
    for(const [key,entry] of registry){
      entry.handler.detach();
      registry.delete(key);
    }
  };
  const syncKeyboard=()=>{
    if(!channelsReady())return;
    const used=new Set([...physical.values(),...managed.values()].map(v=>v.slot));
    if(keyboardHandler && (!keyboardEnabled || used.has(keyboardHandler.slot))){
      keyboardHandler.handler.detach();keyboardHandler=null;
    }
    if(keyboardEnabled&&!keyboardHandler){
      const slot=[0,1,2,3].find(i=>!used.has(i));
      if(slot!==undefined){
        const handler=new WIIUUKeyboardGamepad(slot,keyboard);
        handler.attach(activePlayer);keyboardHandler={slot,handler};
      }
    }
    if(toggle)toggle.textContent=keyboardEnabled
      ?(keyboardHandler?'Keyboard/mouse: On (F8)':'Keyboard/mouse: No free slot')
      :'Keyboard/mouse: Off (F8)';
  };
  const setKeyboardEnabled=enabled=>{
    keyboardEnabled=enabled;keyboard.reset();
    if(!enabled&&doc&&surface&&doc.pointerLockElement===surface)doc.exitPointerLock?.();
    syncKeyboard();
  };
  if(doc&&win&&surface){
    toggle=doc.createElement('button');
    toggle.type='button';toggle.textContent='Keyboard/mouse: Off (F8)';
    toggle.title='WASD move, mouse aim, click capture, Esc release, F8 toggle';
    doc.querySelector('#top')?.appendChild(toggle);
    listen(toggle,'click',()=>setKeyboardEnabled(!keyboardEnabled));
    const captured=()=>keyboardEnabled&&doc.pointerLockElement===surface;
    listen(surface,'click',()=>{
      if(keyboardEnabled&&!captured()){
        try{Promise.resolve(surface.requestPointerLock?.()).catch(()=>{});}catch{}
      }
    });
    listen(win,'keydown',event=>{
      if(event.code==='F8'&&!event.repeat){
        event.preventDefault();setKeyboardEnabled(!keyboardEnabled);return;
      }
      if(!keyboardEnabled||!captured())return;
      if(Object.hasOwn(KEYBOARD_BUTTONS,event.code)||['KeyW','KeyA','KeyS','KeyD'].includes(event.code)){
        event.preventDefault();keyboard.keys.add(event.code);
      }
    },true);
    listen(win,'keyup',event=>{keyboard.keys.delete(event.code);if(captured()&&
      (Object.hasOwn(KEYBOARD_BUTTONS,event.code)||['KeyW','KeyA','KeyS','KeyD'].includes(event.code)))event.preventDefault();},true);
    listen(doc,'mousemove',event=>{if(captured())keyboard.move(event.movementX||0,event.movementY||0);});
    listen(doc,'mousedown',event=>{if(captured()){event.preventDefault();keyboard.buttons.add(event.button);}});
    listen(doc,'mouseup',event=>{keyboard.buttons.delete(event.button);if(captured())event.preventDefault();});
    listen(doc,'contextmenu',event=>{if(captured())event.preventDefault();});
    listen(doc,'pointerlockchange',()=>{if(!captured())keyboard.reset();});
    listen(win,'blur',()=>keyboard.reset());
    listen(doc,'visibilitychange',()=>{if(doc.hidden)keyboard.reset();});
  }
  const sync=()=>{
    // Attaching before both WebRTC channels open causes Xbox to miss the
    // gamepadChanged message permanently. Retry on the next scan instead.
    if(!channelsReady()||managerFrames.size)return;
    let pads=[];
    try{pads=Array.from(navigator.getGamepads?.()||[]).filter(Boolean);}
    catch(error){console.warn('[xcloud-gamepad] Gamepad API unavailable',error?.name);return;}
    const connected=new Set(pads.map(p=>p.index));
    for(const [index,entry] of physical){
      if(!connected.has(index)){
        entry.handler.detach();
        physical.delete(index);
        console.info('[xcloud-gamepad] disconnected physical='+index+' slot='+entry.slot);
      }
    }
    for(const pad of pads){
      if(physical.has(pad.index))continue;
      const occupied=new Set(Array.from(physical.values(),v=>v.slot));
      if(keyboardHandler)occupied.add(keyboardHandler.slot);
      const slot=[0,1,2,3].find(i=>!occupied.has(i));
      if(slot===undefined)break;
      const handler=new WIIUUGamepad(slot,pad.index);
      handler.attach(activePlayer);
      physical.set(pad.index,{slot,handler});
      console.info('[xcloud-gamepad] connected physical='+pad.index+' slot='+slot+
        ' mapping='+(pad.mapping||'unknown'));
    }
    syncKeyboard();
  };
  const syncManaged=()=>{
    if(!channelsReady())return;
    if(managerFrames.size) {
      if(activeMode!=='managed'){
        detach(physical);
        activeMode='managed';
        console.info('[xcloud-gamepad] WII-UU controller manager active');
      }
      for(const [slot,entry] of managed){
        if(!managerFrames.has(slot)){
          entry.handler.detach();
          managed.delete(slot);
        }
      }
      if(keyboardHandler&&managerFrames.has(keyboardHandler.slot)){
        keyboardHandler.handler.detach();keyboardHandler=null;
      }
      for(const slot of managerFrames.keys()){
        if(managed.has(slot))continue;
        const handler=new WIIUUManagedGamepad(slot,managerFrames);
        handler.attach(activePlayer);
        managed.set(slot,{slot,handler});
        console.info('[xcloud-gamepad] WII-UU player '+(slot+1)+' attached to Xbox slot '+slot);
      }
    }else if(activeMode==='managed'){
      detach(managed);
      activeMode='browser';
      console.info('[xcloud-gamepad] WII-UU controller manager idle, using browser gamepads');
      sync();
    }
    syncKeyboard();
  };
  const refreshManager=async()=>{
    if(managerBusy||stop)return;
    managerBusy=true;
    try{
      const result=await request('controllers');
      const next=new Map();
      for(const frame of Array.isArray(result?.pads)?result.pads:[]){
        const slot=frame?.GamepadIndex;
        if(Number.isInteger(slot)&&slot>=0&&slot<4)next.set(slot,frame);
      }
      managerFrames.clear();
      for(const [slot,frame] of next)managerFrames.set(slot,frame);
      syncManaged();
    }catch(error){
      // On a temporary backend failure preserve the last good frames until
      // a successful poll returns. The connection itself remains alive.
    }finally{managerBusy=false;}
  };
  const start=input.start.bind(input);
  input.start=()=>{
    start();
    if(scanTimer!==null)return;
    console.info('[xcloud-gamepad] input handshake complete, controller bridge started');
    sync();
    scanTimer=setInterval(sync,500);
    managerTimer=setInterval(()=>{void refreshManager();},50);
    void refreshManager();
  };
  return ()=>{
    if(scanTimer!==null)clearInterval(scanTimer);
    if(managerTimer!==null)clearInterval(managerTimer);
    scanTimer=null;managerTimer=null;
    setKeyboardEnabled(false);
    if(keyboardHandler){keyboardHandler.handler.detach();keyboardHandler=null;}
    listeners.forEach(remove=>remove());toggle?.remove();
    detach(physical);
    detach(managed);
    managerFrames.clear();
  };
}

async function connect(){
  if(!/^[a-f0-9]{64}$/i.test(secret)||!title)throw new Error('Invalid stream launcher URL');
  if(typeof RTCPeerConnection==='undefined')throw new Error('This Chromium build does not support WebRTC');
  progress('Requesting Xbox session…');
  await request('start',{titleId:title});sessionStarted=true;
  progress('Waiting for Xbox server…');
  let ready=false,transferred=false;
  for(let attempt=0;attempt<240&&!stop;attempt++){
    const result=await request('state');
    if(result.state==='Provisioned'){ready=true;break;}
    if(result.state==='ReadyToConnect'&&!transferred){await request('connect',{});transferred=true;}
    else if(result.state==='Failed')throw new Error('Xbox could not provision a cloud session');
    await sleep(1000);
  }
  if(!ready||stop)throw new Error('Provisioning timed out or cancelled');
  progress('Negotiating video, sound and gamepad…');
  player=new Player('player');
  stopControllers=controllerBridge(player);
  player.onConnectionStateChange(state=>{
    if(stop)return;
    if(state==='connected'){
      streamConnected=true;
      progress('Streaming');toolbar.classList.add('hidden');errors.textContent='';
      if(autoAudio)void enableAudio();
    }
    if(state==='failed'||state==='closed'){
      progress('Connection lost');toolbar.classList.remove('hidden');
      void sessionEnded();
    }
  });
  const offer=await player.createOffer();
  await request('sdpPost',{sdp:offer.sdp});
  const answer=await exchange('sdpGet');
  await player.setRemoteOffer(answer.sdp);
  const deadline=Date.now()+8000;
  while(player._peerConnection.iceGatheringState!=='complete'&&Date.now()<deadline)await sleep(100);
  const candidates=player.getIceCandidates().filter(Boolean)
    .map(item=>typeof item.toJSON==='function'?item.toJSON():item)
    .filter(item=>item&&typeof item.candidate==='string'&&item.candidate.trim()!=='');
  if(!candidates.length)throw new Error('Chromium could not gather ICE candidates');
  await request('icePost',{candidates});
  const remoteCandidates=await fetchRemoteIce();
  console.info('[xcloud-webrtc] ICE candidates: local='+candidates.length+
    ' remote='+remoteCandidates.length);
  player.setRemoteIceCandidates(remoteCandidates);
  timer=setInterval(()=>{
    if(!stop)void request('keepalive',{}).catch(error=>{
      if(!stop)console.warn('[xcloud-webrtc] keepalive failed:',error?.message||'unknown');
    });
  },30000);
  stateTimer=setInterval(()=>{void checkServerState();},10000);
  if(autoAudio){
    // The media element can be created AFTER the data channels are ready.
    // Keep trying until it exists, instead of requiring the mouse button.
    audioTimer=setInterval(()=>{void enableAudio();},1200);
    void enableAudio();
  }
}
async function disconnect(){
  stop=true;clearInterval(timer);clearInterval(stateTimer);clearInterval(audioTimer);
  if(stopControllers){stopControllers();stopControllers=null;}
  if(player){player.destroy();player=undefined;}
  if(sessionStarted){try{await request('stop',{});}catch{}}
  progress('Disconnected');
}
document.querySelector('#enable-audio').onclick=()=>{
  if(!player?.getAudioElement())errors.textContent='Audio is not ready yet.';
  void enableAudio();
};
document.querySelector('#fullscreen').onclick=()=>document.fullscreenElement
  ?document.exitFullscreen():document.documentElement.requestFullscreen();
action.onclick=()=>{void disconnect().then(()=>window.close());};
window.addEventListener('pagehide',()=>{void disconnect();},{once:true});
window.addEventListener('keydown',event=>{if(event.key==='Escape')toolbar.classList.toggle('hidden')});
connect().catch(fail);
