import Player from 'xbox-xcloud-player/dist/player';
import Gamepad from 'xbox-xcloud-player/dist/input/gamepad';

const secret=location.hash.substring(1);
const title=new URL(location.href).searchParams.get('title');
const label=document.querySelector('#status');
const errors=document.querySelector('#fail');
const toolbar=document.querySelector('#top');
const action=document.querySelector('#disconnect');
let player, timer, stop=false, sessionStarted=false;
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
  const channel=player._channels.input;
  const start=channel.start.bind(channel);
  channel.start=()=>{
    start();
    // Match WII-UU's physical and virtual gamepads after the Xbox handshake.
    const pads=navigator.getGamepads();
    if(Array.from(pads).some(Boolean)){
      new Gamepad(0,{
        enable_keyboard:false,enable_vibration:true,gamepad_deadzone:0.08,
        gamepad_mapping:{A:'0',B:'1',X:'2',Y:'3',LeftShoulder:'4',
          RightShoulder:'5',LeftTrigger:'6',RightTrigger:'7',
          View:'8',Menu:'9',LeftThumb:'10',RightThumb:'11',
          DPadUp:'12',DPadDown:'13',DPadLeft:'14',DPadRight:'15',Nexus:'16'}
      }).attach(player);
    } else {
      // A controller may be connected later. Greenlight's handler detects hot-plug.
      new Gamepad(0,{enable_keyboard:false,enable_vibration:true}).attach(player);
    }
  };
  player.onConnectionStateChange(state=>{
    if(state==='connected'){progress('Streaming');toolbar.classList.add('hidden');errors.textContent='';}
    if(state==='failed'||state==='closed'){progress('Connection lost');toolbar.classList.remove('hidden');}
  });
  const offer=await player.createOffer();
  await request('sdpPost',{sdp:offer.sdp});
  const answer=await exchange('sdpGet');
  await player.setRemoteOffer(answer.sdp);
  const deadline=Date.now()+8000;
  while(player._peerConnection.iceGatheringState!=='complete'&&Date.now()<deadline)await sleep(100);
  const candidates=player.getIceCandidates().map(item=>item.toJSON());
  if(!candidates.length)throw new Error('Chromium could not gather ICE candidates');
  await request('icePost',{candidates});
  const remote=await exchange('iceGet');
  await player.setRemoteIceCandidates(remote.candidates||remote);
  timer=setInterval(()=>{void request('keepalive',{}).catch(fail)},30000);
}
async function disconnect(){
  stop=true;clearInterval(timer);
  if(player){player.destroy();player=undefined;}
  if(sessionStarted){try{await request('stop',{});}catch{}}
  progress('Disconnected');
}
document.querySelector('#fullscreen').onclick=()=>document.fullscreenElement
  ?document.exitFullscreen():document.documentElement.requestFullscreen();
action.onclick=()=>{void disconnect().then(()=>window.close());};
window.addEventListener('pagehide',()=>{void disconnect();},{once:true});
window.addEventListener('keydown',event=>{if(event.key==='Escape')toolbar.classList.toggle('hidden')});
connect().catch(fail);
