'use strict';
// WII-UU's localhost-only Xbox Cloud Gaming backend. Microsoft/Xbox credentials
// never enter the Chromium player page or Java logs.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const readline = require('node:readline');

// Third-party package is installed only on explicit user action. Node's stdout
// is reserved for structured messages to Java; library diagnostics are suppressed.
console.log = () => {};
const { Msal, TokenStore } = require('xal-node');

const root = path.resolve(process.argv[2] || '.');
const userDir = path.resolve(process.argv[3] || root);
const secret = crypto.randomBytes(32).toString('hex');
const tokenFile = path.join(userDir, 'xcloud-tokens.json');
let activeSession = null;
let cloudToken = null;
let allowedTitles = new Map();
let authenticating = false;
let port = 0;
let managedPads = [];
let managedAt = 0;
const FRAME_BUTTONS = ['A','B','X','Y','LeftShoulder','RightShoulder','LeftTrigger',
  'RightTrigger','View','Menu','LeftThumb','RightThumb','DPadUp','DPadDown','DPadLeft',
  'DPadRight','Nexus'];
const FRAME_AXES = ['LeftThumbXAxis','LeftThumbYAxis','RightThumbXAxis','RightThumbYAxis'];
function receiveManagedPads(pads) {
  if (!Array.isArray(pads) || pads.length > 4) return;
  const clean = new Map();
  for (const input of pads) {
    const slot = input?.GamepadIndex;
    if (!Number.isInteger(slot) || slot < 0 || slot > 3 || clean.has(slot)) return;
    const frame = {GamepadIndex:slot};
    for (const key of FRAME_BUTTONS.concat(FRAME_AXES)) {
      const value = input[key];
      if (typeof value !== 'number' || !Number.isFinite(value)) return;
      frame[key] = Math.max(key.includes('Axis') ? -1 : 0, Math.min(1,value));
    }
    clean.set(slot,frame);
  }
  managedPads = [...clean.values()].sort((a,b)=>a.GamepadIndex-b.GamepadIndex);
  managedAt = Date.now();
}
function currentManagedPads() {
  return Date.now() - managedAt <= 700 ? managedPads : [];
}

class StoredTokens extends TokenStore {
  constructor() {
    super();
    try { this.loadJson(fs.readFileSync(tokenFile, 'utf8')); } catch { /* First sign-in */ }
  }
  save() {
    fs.mkdirSync(userDir, { recursive: true, mode: 0o700 });
    const body = JSON.stringify({
      userToken: this._userToken?.data, sisuToken: this._sisuToken?.data, jwtKeys: this._jwtKeys
    });
    const tmp = tokenFile + '.pending-' + crypto.randomBytes(4).toString('hex');
    fs.writeFileSync(tmp, body, { mode: 0o600, flag: 'wx' });
    fs.renameSync(tmp, tokenFile);
    if (process.platform !== 'win32') fs.chmodSync(tokenFile, 0o600);
  }
  // xal-node calls removeAll() after successful device-code exchange,
  // before setUserToken() and save(). Its default implementation writes to
  // _filepath, which this store deliberately does not use. Clear only the
  // in-memory state here: save() atomically replaces the persisted token later.
  removeAll() {
    this._userToken = undefined; this._sisuToken = undefined; this._jwtKeys = undefined;
  }
  clear() {
    this._userToken = undefined; this._sisuToken = undefined; this._jwtKeys = undefined;
    try { fs.unlinkSync(tokenFile); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}
const store = new StoredTokens();
let msal = new Msal(store);

function emit(message) { process.stdout.write(JSON.stringify(message) + '\n'); }
// Diagnostic events are written to stderr (backend.log), never to the JSON
// protocol on stdout. Keep OAuth codes, secrets, tokens and raw responses out.
const diagnosticStart = Date.now();
function debug(stage, detail = '') {
  const elapsed = Math.floor((Date.now() - diagnosticStart) / 1000);
  const suffix = detail ? ' ' + detail : '';
  process.stderr.write('[xcloud-auth] +' + elapsed + 's ' + stage + suffix + '\n');
}
function errorKind(error) {
  const kind = error?.name;
  return typeof kind === 'string' && /^[A-Za-z]{1,40}$/.test(kind) ? kind : 'Error';
}
// xal-node retries all device-code token HTTP failures without exposing their
// type. Instrument only that endpoint to distinguish OAuth pending, terminal
// responses and transport errors without ever printing response bodies.
function monitorMicrosoftPolling(instance) {
  const client = instance?._httpClient;
  if (!client || typeof client.postRequest !== 'function') {
    debug('device-http-monitor-unavailable');
    return;
  }
  const original = client.postRequest.bind(client);
  let lastStatus = '';
  let lastReportAt = 0;
  client.postRequest = function(host, pathname, headers, body) {
    if (host !== 'login.microsoftonline.com' || pathname !== '/consumers/oauth2/v2.0/token'
        || typeof body !== 'string' || !body.includes('urn:ietf:params:oauth:grant-type:device_code'))
      return original(host, pathname, headers, body);
    return Promise.resolve().then(() => original(host, pathname, headers, body))
      .then(response => {
        debug('device-http-token-success');
        return response;
      }, error => {
        // xal-node embeds the HTTP status and OAuth error code in a JSON
        // string inside Error.message. Extract only explicitly allowlisted
        // status/error labels. Never print the original message or body.
        const message = typeof error?.message === 'string' ? error.message : '';
        const status = /"statuscode"\s*:\s*(\d{3})/.exec(message)?.[1] || 'unknown';
        const code = /\\"error\\"\s*:\s*\\"([a-z_]+)\\"/.exec(message)?.[1] || 'unknown';
        const allowed = new Set(['authorization_pending','slow_down','authorization_declined',
          'expired_token','bad_verification_code','invalid_grant','invalid_client','unauthorized_client']);
        const oauth = allowed.has(code) ? code : 'unknown';
        const label = status + '/' + oauth;
        if (label !== lastStatus || Date.now() - lastReportAt > 30000) {
          debug('device-http-retry', 'http=' + status + ' oauth=' + oauth
            + ' errorType=' + errorKind(error));
          lastStatus = label;
          lastReportAt = Date.now();
        }
        throw error;
      });
  };
}
monitorMicrosoftPolling(msal);
debug('backend-start', 'savedUserToken=' + Boolean(store.getUserToken()));
let lastTokenState = Boolean(store.getUserToken());
let lastStatusLog = 0;
function safeError(error) {
  // Do not accidentally log access tokens included in upstream HTTP responses.
  const msg = typeof error?.message === 'string' ? error.message : String(error);
  return msg.slice(0, 250).replace(/(access_token|refresh_token|authorization|bearer)\s*[:=]\s*[^\s,}]+/gi, '$1=[hidden]');
}
function regionHost() {
  const regions = cloudToken?.data?.regions || [];
  const chosen = regions.find?.(x => x.isDefault) || regions[0];
  const raw = chosen?.baseUri || chosen?.url || chosen?.uri || 'https://weu.core.gssv-play-prod.xboxlive.com';
  let url;
  try { url = new URL(raw); } catch { url = new URL('https://weu.core.gssv-play-prod.xboxlive.com'); }
  if (url.protocol !== 'https:' || !/^[a-z0-9-]+\.core\.gssv-play-prod\.xboxlive\.com$/.test(url.hostname))
    throw new Error('Unexpected Xbox streaming region');
  return url.origin;
}
function deviceInfo() {
  return JSON.stringify({
    appInfo: { env: { clientAppId:'www.xbox.com', clientAppType:'browser', clientAppVersion:'21.1.98', clientSdkVersion:'8.5.3', httpEnvironment:'prod', sdkInstallId:'' } },
    dev: { hw:{ make:'Microsoft', model:'unknown', sdktype:'web' },
      os:{name:'windows',ver:'22631.2715',platform:'desktop'},
      displayInfo:{dimensions:{widthInPixels:1920,heightInPixels:1080},pixelDensity:{dpiX:2,dpiY:2}},
      browser:{browserName:'chrome',browserVersion:'119.0'} }
  });
}
async function streamingTokens() {
  if (!store.getUserToken()) throw new Error('Sign in first');
  // xCloud should not depend on xHome authentication. getStreamingTokens()
  // also requests an xHome grant which may fail even with a valid xCloud account.
  const gssv = await msal.getGssvToken();
  if (!gssv?.data?.Token) throw new Error('Xbox GSSV authorization was unavailable');
  try {
    cloudToken = await msal.getStreamToken(gssv.data.Token, 'xgpuweb');
  } catch (paidOfferingError) {
    cloudToken = await msal.getStreamToken(gssv.data.Token, 'xgpuwebf2p');
  }
  if (!cloudToken?.data?.gsToken) throw new Error('Xbox Cloud Gaming access is unavailable for this account');
  return cloudToken;
}
async function xbox(pathname, method='GET', data, hostOverride) {
  if (!cloudToken) await streamingTokens();
  const host = hostOverride || regionHost();
  const url = new URL(pathname, host);
  if (url.origin !== host || !url.pathname.startsWith('/v'))
    throw new Error('Invalid Xbox API path');
  const result = await fetch(url, {method, signal:AbortSignal.timeout(30000), headers:{
    'Accept':'application/json', 'Content-Type':'application/json',
    'X-Gssv-Client':'XboxComBrowser', 'X-MS-Device-Info':deviceInfo(),
    'Authorization':'Bearer ' + cloudToken.data.gsToken
  }, body:data === undefined ? undefined : JSON.stringify(data), redirect:'error'});
  if (!result.ok) {
    if (result.status === 204) return {pending:true};
    throw new Error('Xbox service returned HTTP ' + result.status);
  }
  if (result.status === 204) return {pending:true};
  return result.json().catch(() => ({}));
}
async function catalog() {
  await streamingTokens();
  const body = await xbox('/v2/titles');
  if (!Array.isArray(body.results)) throw new Error('Xbox did not return a game catalog');
  const titles = body.results.flatMap(row => {
    if (typeof row.titleId !== 'string' || !row.titleId || row.titleId.length > 300) return [];
    const details = row.details || {};
    if (details.hasEntitlement !== true) return [];
    return [{id:row.titleId, name:String(details.titleName || details.name || row.titleId), productId:details.productId || ''}];
  });
  allowedTitles = new Map(titles.map(x => [x.id, x]));
  // Resolve product names separately; the catalog stays usable if this lookup fails.
  const products = [...new Set(titles.map(x => x.productId).filter(x => typeof x === 'string' && /^[A-Z0-9]{5,20}$/i.test(x)))];
  for (let i = 0; i < products.length; i += 100) {
    try {
      const response = await fetch('https://catalog.gamepass.com/v3/products?market=DE&language=de-DE&hydration=RemoteHighSapphire0',
        {method:'POST', signal:AbortSignal.timeout(12000),
         headers:{'Content-Type':'application/json','ms-cv':'0.0','calling-app-name':'WII-UU','calling-app-version':'2.1.8'},
         body:JSON.stringify({Products:products.slice(i,i+100)})});
      if (!response.ok) continue;
      const data = (await response.json()).Products || {};
      for (const game of titles) {
        const item = data[game.productId];
        if (item?.ProductTitle) game.name = String(item.ProductTitle).slice(0, 120);
        const cover = item?.Image_Tile?.URL || item?.Image_Poster?.URL;
        if (typeof cover === 'string') {
          try {
            const address = new URL(cover, 'https://store-images.s-microsoft.com');
            if (address.protocol === 'https:' && address.hostname === 'store-images.s-microsoft.com'
                && !address.username && !address.password) game.cover = address.href;
          } catch { /* Optional art may be absent. */ }
        }
      }
    } catch { /* Metadata is optional */ }
  }
  return titles.sort((a,b) => a.name.localeCompare(b.name)).map(({id,name,cover})=>({id,name,cover:cover||''}));
}
async function deviceAuth() {
  if (authenticating) throw new Error('Device authorization is already in progress');
  authenticating = true;
  debug('device-flow-request');
  let flow;
  try { flow = await msal.doDeviceCodeAuth(); }
  catch (error) { authenticating = false; debug('device-flow-error', 'type=' + errorKind(error)); throw error; }
  debug('device-flow-issued', 'expiresSeconds=' + (Number(flow.expires_in) || 900));
  emit({event:'code', code:flow.user_code, url:flow.verification_uri || flow.verification_url || 'https://www.microsoft.com/link',
      expiresIn:flow.expires_in || 900, message:flow.message});
  // Microsoft's approval and Xbox cloud entitlement are separate milestones.
  // Notify Java as soon as the device-code exchange has produced a user token.
  // Do not block the sign-in UI on xHome/xCloud provisioning or catalog access.
  debug('device-poll-start');
  const pollStarted = Date.now();
  const pollHeartbeat = setInterval(() => {
    debug('device-poll-waiting', 'elapsedSeconds=' + Math.floor((Date.now() - pollStarted) / 1000)
      + ' userTokenPresent=' + Boolean(store.getUserToken()));
  }, 30000);
  pollHeartbeat.unref?.();
  void Promise.resolve().then(() => msal.doPollForDeviceCodeAuth(flow.device_code,
    Math.max(1, Number(flow.expires_in) || 900) * 1000)).then(() => {
    clearInterval(pollHeartbeat);
    debug('device-poll-resolved', 'userTokenPresent=' + Boolean(store.getUserToken()));
    if (!store.getUserToken()) throw new Error('Microsoft approved the device but no user token was returned');
    store.save();
    debug('user-token-saved');
    authenticating = false;
    lastTokenState = true;
    emit({event:'signedIn'});
    debug('signed-in-event-sent');
  }).catch(error => {
    clearInterval(pollHeartbeat);
    authenticating = false;
    debug('device-poll-error', 'type=' + errorKind(error) + ' userTokenPresent=' + Boolean(store.getUserToken()));
    emit({event:'authError', message:safeError(error)});
  });
  return {pending:true};
}
function validSessionId(id) { return typeof id === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(id); }
function sessionPath() {
  if (!activeSession || !validSessionId(activeSession.id)) throw new Error('No active Xbox stream');
  return '/v5/sessions/cloud/' + activeSession.id;
}
async function endSession(notify=false) {
  if (!activeSession) return false;
  const current = activeSession;
  activeSession = null;
  if (notify) emit({event:'streamEnded'});
  try { await xbox('/v5/sessions/cloud/' + encodeURIComponent(current.id),'DELETE'); }
  catch { /* Best-effort cleanup */ }
  return true;
}
// Only terminal server states are treated as final. Provisioning/transition
// states and temporary network errors must not close a healthy stream.
const terminalStates = new Set(['Failed','Stopped','Closed','Ended','Terminated','Cancelled']);
async function terminateIfEnded(result) {
  if (terminalStates.has(result?.state)) await endSession(true);
  return result;
}
async function currentStreamState() {
  try { return await terminateIfEnded(await xbox(sessionPath()+'/state')); }
  catch (error) {
    if (/\bHTTP (404|410)\b/.test(String(error?.message||''))) await endSession(true);
    throw error;
  }
}
async function api(action, payload={}) {
  switch (action) {
    case 'controllers': return {pads:currentManagedPads()};
    case 'auth': return deviceAuth();
    case 'signedIn': {
      const signedIn = !!store.getUserToken();
      // The native screen checks every 3 seconds. Only log a change or
      // an occasional heartbeat so the backend log stays readable.
      if (signedIn !== lastTokenState || Date.now() - lastStatusLog > 30000) {
        debug('native-status-check', 'signedIn=' + signedIn + ' pollActive=' + authenticating);
        lastStatusLog = Date.now();
        lastTokenState = signedIn;
      }
      return {signedIn};
    }
    case 'games': {
      debug('catalog-request');
      try {
        const games = await catalog();
        debug('catalog-ready', 'count=' + games.length);
        return {games};
      } catch (error) {
        debug('catalog-error', 'type=' + errorKind(error));
        throw error;
      }
    }
    case 'logout':
      await endSession(); cloudToken=null; allowedTitles.clear(); store.clear(); msal = new Msal(store); monitorMicrosoftPolling(msal);
      lastTokenState = false;
      debug('signed-out');
      return {signedIn:false};
    case 'start': {
      const id=payload.titleId;
      if (typeof id !== 'string' || !allowedTitles.has(id)) throw new Error('Choose an entitled title from the catalog');
      await endSession();
      const session=await xbox('/v5/sessions/cloud/play','POST',{
        clientSessionId:'', titleId:id, systemUpdateGroup:'',
        settings:{nanoVersion:'V3;WebrtcTransport.dll',enableOptionalDataCollection:false,
          enableTextToSpeech:false,highContrast:0,locale:'de-DE',useIceConnection:false,
          timezoneOffsetMinutes:new Date().getTimezoneOffset(),sdkType:'web',osName:'windows'},
        serverId:'',fallbackRegionNames:[] });
      const sid=session.sessionId || String(session.sessionPath || '').split('/').pop();
      if (!validSessionId(sid)) throw new Error('Xbox returned an invalid stream identifier');
      activeSession={id:sid,titleId:id};
      return {sessionId:sid};
    }
    case 'state': return currentStreamState();
    case 'connect': {
      const refresh=store.getUserToken()?.data?.refresh_token;
      if (!refresh) throw new Error('Microsoft session expired');
      const response=await fetch('https://login.live.com/oauth20_token.srf',{
        method:'POST',signal:AbortSignal.timeout(20000),
        headers:{'Content-Type':'application/x-www-form-urlencoded'},
        body:new URLSearchParams({client_id:'1f907974-e22b-4810-a9de-d9647380c97e',
          scope:'service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN',
          grant_type:'refresh_token',refresh_token:refresh}) });
      if (!response.ok) throw new Error('Microsoft console authorization failed: HTTP ' + response.status);
      const token=(await response.json()).access_token;
      if (!token) throw new Error('Missing Microsoft console transfer grant');
      return xbox(sessionPath()+'/connect','POST',{userToken:token});
    }
    case 'sdpPost': {
      if (typeof payload.sdp !== 'string' || payload.sdp.length > 150000) throw new Error('Invalid SDP');
      return xbox(sessionPath()+'/sdp','POST',{messageType:'offer',sdp:payload.sdp,requestId:'1',
        configuration:{chatConfiguration:{bytesPerSample:2,expectedClipDurationMs:20,
           format:{codec:'opus',container:'webm'},numChannels:1,sampleFrequencyHz:24000},
          chat:{minVersion:1,maxVersion:1},control:{minVersion:1,maxVersion:3},
          input:{minVersion:1,maxVersion:9},message:{minVersion:1,maxVersion:1},
          reliableinput:{minVersion:9,maxVersion:9},unreliableinput:{minVersion:9,maxVersion:9}}});
    }
    case 'sdpGet': return xbox(sessionPath()+'/sdp');
    case 'icePost': {
      if (!Array.isArray(payload.candidates) || payload.candidates.length > 256)
        throw new Error('Invalid ICE candidates');
      return xbox(sessionPath()+'/ice','POST',{iceCandidates:payload.candidates});
    }
    case 'iceGet': return xbox(sessionPath()+'/ice');
    case 'keepalive': {
      try {
        return await terminateIfEnded(await xbox(sessionPath()+'/keepalive','POST',{}));
      } catch (error) {
        if (/\bHTTP (404|410)\b/.test(String(error?.message||''))) await endSession(true);
        throw error;
      }
    }
    case 'stop': await endSession(true); return {stopped:true};
    default: throw new Error('Unknown command');
  }
}
const httpTypes = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8'};
const files = {'/':'player.html','/player.js':'player.bundle.js',
  '/mapping-core.js':'mapping-core.js','/page-hook.js':'page-hook.js'};
const server=http.createServer(async (req,res)=>{
  const headers={'Cache-Control':'no-store','Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff',
    'Cross-Origin-Resource-Policy':'same-origin',
    'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'"};
  const reply=(code,obj)=>{
    res.writeHead(code,{...headers,'Content-Type':'application/json; charset=utf-8'});
    res.end(JSON.stringify(obj));
  };
  try {
    const url=new URL(req.url,'http://127.0.0.1:'+port);
    if (req.headers.host !== '127.0.0.1:'+port) return reply(403,{error:'Invalid host'});
    if (url.pathname in files && req.method === 'GET') {
      const file=files[url.pathname], buffer=fs.readFileSync(path.join(root,file));
      res.writeHead(200,{...headers,'Content-Type':httpTypes[path.extname(file)] || 'application/javascript'});
      res.end(buffer); return;
    }
    if (!url.pathname.startsWith('/api/') || !['GET','POST'].includes(req.method)) return reply(404,{error:'Not found'});
    if(req.headers['x-wiiuu-session'] !== secret || req.headers.origin &&
       req.headers.origin !== 'http://127.0.0.1:'+port) return reply(403,{error:'Forbidden'});
    let data={};
    if(req.method==='POST'){
      let size=0, body='';
      for await (const chunk of req) {
        size+=chunk.length;
        if (size>240000) return reply(413,{error:'Request too large'});
        body+=chunk.toString('utf8');
      }
      data=JSON.parse(body||'{}');
    }
    const action=url.pathname.slice(5);
    if (!['start','state','connect','sdpPost','sdpGet','icePost','iceGet','controllers','keepalive','stop'].includes(action))
      return reply(404,{error:'Unknown route'});
    reply(200,await api(action,data));
  } catch(error) {reply(502,{error:safeError(error)});}
});
const reader=readline.createInterface({input:process.stdin});
reader.on('line',async line=>{
  let command;
  try {command=JSON.parse(line);} catch {return;}
  if (command?.event === 'managedPads') {
    receiveManagedPads(command.pads);
    return;
  }
  if (!Number.isSafeInteger(command.id)) return;
  try {emit({id:command.id,ok:true,result:await api(command.cmd,command.data)});}
  catch(error){emit({id:command.id,ok:false,error:safeError(error)});}
});
reader.on('close',()=>{void endSession().finally(()=>{server.close();process.exit(0);});});
server.listen(0,'127.0.0.1',()=>{
  port=server.address().port;
  emit({event:'ready',port,secret,signedIn:!!store.getUserToken()});
  debug('backend-ready', 'signedIn=' + Boolean(store.getUserToken()));
});
