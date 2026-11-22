/* Runs before the local Xbox WebRTC player in the same JS world. */
(() => {
  'use strict';
  if(window.__NSWXMapperInstalled||!window.NSWXMapper)return;
  window.__NSWXMapperInstalled=true;
  const mapper=window.NSWXMapper;
  let config={...mapper.DEFAULTS};
  const original=typeof navigator.getGamepads==='function'?navigator.getGamepads.bind(navigator):null;
  if(!original)return;
  const mapped=pad=>mapper.remap(pad,config);
  const getGamepads=()=>Array.from(original(),mapped);
  try{Object.defineProperty(navigator,'getGamepads',{configurable:true,writable:true,value:getGamepads});}
  catch{try{Object.defineProperty(Navigator.prototype,'getGamepads',{configurable:true,writable:true,value:getGamepads});}catch{}}
  try{
    if(typeof GamepadEvent!=='undefined'){
      const descriptor=Object.getOwnPropertyDescriptor(GamepadEvent.prototype,'gamepad');
      if(descriptor?.get&&descriptor.configurable)
        Object.defineProperty(GamepadEvent.prototype,'gamepad',{
          configurable:true,enumerable:descriptor.enumerable,
          get(){return mapped(descriptor.get.call(this));}
        });
    }
  }catch{}
  window.addEventListener('message',event=>{
    if(event.source!==window||event.origin!==location.origin)return;
    const data=event.data;
    if(data?.type!=='NSWX_MAPPER_CONFIG'||typeof data.config!=='object')return;
    config=mapper.settings(data.config);
  });
  Object.defineProperty(window,'__NSWXMapperStatus',{configurable:true,value:()=>{
    const raw=Array.from(original()).filter(Boolean),transformed=raw.map(mapped);
    return{active:config.enabled,config:{...config},gamepads:raw.map((pad,i)=>({
      rawId:pad.id,rawMapping:pad.mapping,mappedId:transformed[i].id,mappedMapping:transformed[i].mapping,
      rawAxes:[...pad.axes],mappedAxes:[...transformed[i].axes],
      mappedButtons:transformed[i].buttons.map((b,j)=>b.pressed?j:null).filter(j=>j!==null)}))};
  }});
})();
