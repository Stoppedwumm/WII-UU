import fs from 'node:fs/promises';
import path from 'node:path';
import { randomBytes, randomUUID, scryptSync, createHash, timingSafeEqual } from 'node:crypto';

const PIN = /^\d{4}$/;
const ID = /^[a-f0-9-]{36}$/;
const MAX_BYTES = 48_000, MAX_BATCHES = 30;
const sha = text => createHash('sha256').update(text).digest('hex');
const esc = text => String(text).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const equal = (a,b) => { a=Buffer.from(a); b=Buffer.from(b); return a.length === b.length && timingSafeEqual(a,b); };
const fail = (status, message) => { throw Object.assign(new Error(message), {status}); };
function redact(s) {
  return String(s)
    .replace(/(authorization\s*[:=]\s*(?:bearer|basic)\s+)[^\s"']+/gi,'$1[REDACTED]')
    .replace(/((?:token|api[_-]?key|password|secret|cookie|authorization)\s*[:=]\s*)[^\s,;]+/gi,'$1[REDACTED]')
    .replace(/https?:\/\/[^\s]+/gi,'[URL]')
    .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g,'[IP]')
    .replace(/(?:\/home\/|\/Users\/|C:\\Users\\)[^\/\\\s]+/g,'[HOME]');
}
async function readJson(req, max=MAX_BYTES+2048) {
  let n=0; const a=[];
  for await (const c of req) { n+=c.length; if(n>max) fail(413,'Payload too large'); a.push(c); }
  try {return JSON.parse(Buffer.concat(a).toString('utf8'));}catch{fail(400,'Invalid JSON');}
}
function json(res,status,data) {
  const body=JSON.stringify(data)+'\n';
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',
    'X-Content-Type-Options':'nosniff','Content-Length':Buffer.byteLength(body)});res.end(body);
}
function page(res,title,content) {
  const body='<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
    +'<title>'+esc(title)+' · WII-UU Archive</title><style>body{font:16px system-ui;background:#111924;color:#edf3f8;max-width:1150px;margin:45px auto;padding:20px}'
    +'a{color:#78caff}table{width:100%;border-collapse:collapse}td,th{padding:12px;text-align:left;border-bottom:1px solid #3e4b55}'
    +'pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#1f2c38;padding:16px;border-radius:8px}</style>'
    +'<h1>'+esc(title)+'</h1><p>Restricted opt-in diagnostics. PINs are identifiers, not passwords.</p>'+content+'</html>';
  res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store',
    'Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
    'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','X-Frame-Options':'DENY'});res.end(body);
}
export function passwordHash(password) {
  if (password.length < 16) throw new Error('Admin password must have at least 16 characters');
  const salt=randomBytes(16);
  return salt.toString('hex')+':'+scryptSync(password,salt,64).toString('hex');
}
export function createDiagnostics({dataDir,adminUser='',adminPasswordHash=''}) {
  const root=path.join(path.resolve(dataDir),'diagnostics'), devices=path.join(root,'devices'), uploads=path.join(root,'uploads');
  let enrollments=[];
  async function readDevice(id) {
    if(!ID.test(id)) fail(400,'Invalid device id');
    try {return JSON.parse(await fs.readFile(path.join(devices,id+'.json'),'utf8'));}
    catch(e){if(e.code==='ENOENT')fail(401,'Unknown diagnostic device');throw e;}
  }
  async function writeDevice(d) {
    await fs.mkdir(devices,{recursive:true});
    const temp=path.join(devices,'.'+randomUUID()+'.tmp');
    try {await fs.writeFile(temp,JSON.stringify(d),{flag:'wx',mode:0o600});
      await fs.rename(temp,path.join(devices,d.id+'.json'));}finally{await fs.rm(temp,{force:true});}
  }
  function checkAdmin(req) {
    if(!adminUser||!adminPasswordHash)fail(503,'Admin account not configured');
    const raw=req.headers.authorization||'';
    if(!raw.startsWith('Basic '))fail(401,'Admin login required');
    let user,pass;
    try {const value=Buffer.from(raw.slice(6),'base64').toString('utf8');const colon=value.indexOf(':');
      if(colon<0)fail(401,'Invalid admin login');user=value.slice(0,colon);pass=value.slice(colon+1);}
    catch(e){if(e.status)throw e;fail(401,'Invalid admin login');}
    const [salt,expected]=adminPasswordHash.split(':');
    if(!/^[a-f0-9]{32}$/.test(salt||'')||!/^[a-f0-9]{128}$/.test(expected||''))fail(503,'Invalid admin hash');
    const candidate=scryptSync(pass,Buffer.from(salt,'hex'),64).toString('hex');
    if(!equal(user,adminUser)||!equal(candidate,expected))fail(401,'Invalid admin login');
  }
  function checkDevice(req,d) {
    const raw=req.headers.authorization||'';
    if(!raw.startsWith('Bearer ')||!equal(sha(raw.slice(7)),d.tokenHash))fail(401,'Invalid diagnostic token');
  }
  async function allDevices() {
    await fs.mkdir(devices,{recursive:true});
    const out=[];
    for(const n of await fs.readdir(devices))if(n.endsWith('.json')&&ID.test(n.slice(0,-5))){
      try{out.push(JSON.parse(await fs.readFile(path.join(devices,n),'utf8')));}catch{} }
    return out.sort((a,b)=>(b.lastSeen||b.createdAt).localeCompare(a.lastSeen||a.createdAt));
  }
  return async function handleDiagnostics(req,res,url) {
    const parts=url.pathname.split('/').filter(Boolean);
    const api=parts[0]==='api'&&parts[1]==='diagnostics';
    const admin=parts[0]==='admin'&&parts[1]==='diagnostics';
    if(!api&&!admin)return false;
    try {
      if(api&&parts[2]==='enroll'&&parts.length===3&&req.method==='POST') {
        const body=await readJson(req,2048);
        if(!body||!PIN.test(body.pin)||body.consent!==true||body.consentVersion!==1)
          fail(400,'Four-digit PIN and explicit consent required');
        const now=Date.now();enrollments=enrollments.filter(t=>now-t<3600000);
        if(enrollments.length>=30)fail(429,'Enrollment rate limit exceeded');enrollments.push(now);
        const id=randomUUID(),token=randomBytes(32).toString('hex');
        await writeDevice({id,pin:body.pin,tokenHash:sha(token),createdAt:new Date().toISOString(),
          lastSeen:null,lastUpload:0,uploads:0,consentVersion:1});
        json(res,201,{deviceId:id,token,intervalSeconds:900});return true;
      }
      if(api&&parts[2]==='logs'&&parts.length===3&&req.method==='POST') {
        const d=await readDevice(typeof req.headers['x-device-id']==='string'?req.headers['x-device-id']:'');
        checkDevice(req,d);
        const now=Date.now();
        if(now-d.lastUpload<10*60*1000)fail(429,'Too many uploads');
        const body=await readJson(req);
        if(!body||!Array.isArray(body.entries)||body.entries.length>150||body.entries.some(x=>typeof x!=='string'||x.length>1024))
          fail(400,'Invalid log entries');
        const entries=body.entries.map(redact);
        if(Buffer.byteLength(entries.join('\n'))>MAX_BYTES)fail(413,'Logs too large');
        const directory=path.join(uploads,d.id);
        await fs.mkdir(directory,{recursive:true});
        const name=String(now)+'-'+randomUUID()+'.json';
        const batch={at:new Date().toISOString(),version:String(body.version||'').slice(0,80),
          pin:d.pin,deviceId:d.id,entries};
        await fs.writeFile(path.join(directory,name),JSON.stringify(batch),{flag:'wx',mode:0o600});
        d.lastSeen=batch.at;d.lastUpload=now;d.uploads++;
        await writeDevice(d);
        const keep=(await fs.readdir(directory)).sort().reverse();
        await Promise.all(keep.slice(MAX_BATCHES).map(f=>fs.rm(path.join(directory,f),{force:true})));
        await Promise.all(keep.slice(0,MAX_BATCHES).filter(f=>now-Number(f.split('-')[0])>30*86400000)
          .map(f=>fs.rm(path.join(directory,f),{force:true})));
        json(res,201,{received:entries.length});return true;
      }
      if(api&&parts[2]==='device'&&parts.length===3&&req.method==='DELETE') {
        const d=await readDevice(typeof req.headers['x-device-id']==='string'?req.headers['x-device-id']:'');
        checkDevice(req,d);
        await fs.rm(path.join(devices,d.id+'.json'),{force:true});
        await fs.rm(path.join(uploads,d.id),{recursive:true,force:true});
        json(res,200,{revoked:true});return true;
      }
      if(admin&&req.method==='GET') {
        checkAdmin(req);
        if(parts.length===2) {
          const list=await allDevices();
          const rows=list.map(d=>'<tr><td><a href="/admin/diagnostics/'+d.id+'">'+esc(d.pin)+'</a></td>'
            +'<td>'+esc(d.id)+'</td><td>'+esc(d.lastSeen||'Never')+'</td><td>'+d.uploads+'</td></tr>').join('');
          page(res,'Diagnostics dashboard','<p>'+list.length+' consenting devices. Maximum 30 uploads and 30 days of logs per device.</p>'
            +'<table><tr><th>PIN</th><th>Device</th><th>Last upload</th><th>Uploads</th></tr>'+rows+'</table>');return true;
        }
        if(parts.length===3) {
          const d=await readDevice(parts[2]);let names=[];
          const dir=path.join(uploads,d.id);
          try{names=(await fs.readdir(dir)).filter(n=>n.endsWith('.json')).sort().reverse();}
          catch(e){if(e.code!=='ENOENT')throw e;}
          const blocks=[];
          for(const n of names.slice(0,MAX_BATCHES)){
            const batch=JSON.parse(await fs.readFile(path.join(dir,n),'utf8'));
            blocks.push('<h3>'+esc(batch.at)+' · '+esc(batch.version)+'</h3><pre>'+esc(batch.entries.join('\n'))+'</pre>');
          }
          page(res,'PIN '+d.pin,'<p><a href="/admin/diagnostics">All devices</a> · '+esc(d.id)
            +' · Enrolled '+esc(d.createdAt)+'</p>'+(blocks.join('')||'<p>No logs yet.</p>'));return true;
        }
      }
      fail(404,'Not found');
    }catch(e){
      const status=e.status||500;
      if(!e.status)console.error('Diagnostics request failed:',e.message);
      if(admin&&status===401)res.setHeader('WWW-Authenticate','Basic realm="WII-UU diagnostics"');
      json(res,status,{error:e.status?e.message:'Internal server error'});req.resume();return true;
    }
  };
}
