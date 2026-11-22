/* NSW 20d6:a713 -> W3C standard Gamepad mapping. Based on the supplied v2 controller mapper. */
(function (root) {
  'use strict';
  const DEFAULTS=Object.freeze({enabled:true,faceMode:'position',deadzone:0.08,spoofId:true});
  const HAT_VALUES=[-1,-0.714,-0.429,-0.143,0.143,0.429,0.714,1];
  const HAT_DIRECTIONS=[[1,0,0,0],[1,0,0,1],[0,0,0,1],[0,1,0,1],[0,1,0,0],[0,1,1,0],[0,0,1,0],[1,0,1,0]];
  const clamp=(v,low,high)=>Math.min(high,Math.max(low,v));
  function matches(pad) {return !!pad && typeof pad.id==='string' && /20d6/i.test(pad.id) && /a713/i.test(pad.id);}
  function settings(input){
    const c=Object.assign({},DEFAULTS,input||{});
    c.enabled=c.enabled!==false;c.faceMode=c.faceMode==='letters'?'letters':'position';
    c.deadzone=clamp(Number(c.deadzone)||0,0,0.35);c.spoofId=c.spoofId!==false;return c;
  }
  function decodeHat(raw){
    const v=Number(raw);
    if(!Number.isFinite(v)||v>1.2||v< -1.2) return {up:false,down:false,left:false,right:false,step:-1};
    let nearest=-1,distance=Infinity;
    HAT_VALUES.forEach((candidate,i)=>{const d=Math.abs(v-candidate);if(d<distance){distance=d;nearest=i;}});
    if(distance>0.11) return {up:false,down:false,left:false,right:false,step:-1};
    const [up,down,left,right]=HAT_DIRECTIONS[nearest];
    return {up:!!up,down:!!down,left:!!left,right:!!right,step:nearest};
  }
  function button(raw,i){
    const source=raw?.buttons?.[i];
    let value=clamp(Number(source?.value)||0,0,1);
    if(source?.pressed&&value<0.5)value=1;
    return {pressed:!!source?.pressed||value>0.5,touched:!!source?.touched||value>0,value};
  }
  function digitalButton(pressed) {return {pressed:!!pressed,touched:!!pressed,value:pressed?1:0};}
  function mappedButtons(raw,config){
    const c=settings(config);
    const face=c.faceMode==='letters'?[2,1,3,0]:[1,2,0,3];
    const indices=[...face,4,5,6,7,8,9,10,11],result=indices.map(i=>button(raw,i));
    const hat=decodeHat(raw?.axes?.[9]);
    result.push(digitalButton(hat.up),digitalButton(hat.down),digitalButton(hat.left),
      digitalButton(hat.right),button(raw,12),button(raw,13));
    return result;
  }
  function mappedAxes(raw,config){
    const c=settings(config);
    return [0,1,2,5].map(i=>{const v=clamp(Number(raw?.axes?.[i])||0,-1,1);return Math.abs(v)<=c.deadzone?0:v;});
  }
  function remap(raw,config){
    const c=settings(config);
    if(!c.enabled||!matches(raw))return raw;
    return new Proxy(raw,{get(target,key){
      switch(key){
        case 'id': return c.spoofId?'Xbox 360 Controller (XInput STANDARD GAMEPAD)':target.id;
        case 'mapping':return 'standard';
        case 'axes':return mappedAxes(target,c);
        case 'buttons':return mappedButtons(target,c);
        default:{const v=Reflect.get(target,key,target);return typeof v==='function'?v.bind(target):v;}
      }
    }});
  }
  const api=Object.freeze({DEFAULTS,matches,settings,decodeHat,mappedButtons,mappedAxes,remap});
  root.NSWXMapper=api;
  if(typeof module!=='undefined'&&module.exports) module.exports=api;
})(globalThis);
