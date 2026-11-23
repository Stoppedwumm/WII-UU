const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const html = fs.readFileSync('resources/web/pad.html','utf8');
const from = html.indexOf('    const gameChanged = j.mode !== S.mode');
const to = html.indexOf('\n  } catch (e) {',from);
assert.ok(from >= 0 && to > from, 'GamePad status-to-view transition exists');
const statusTransition = html.slice(from,to);

function transition(initial, reply) {
  const calls = [];
  const elements = new Map();
  const $ = id => {
    if(!elements.has(id))elements.set(id,{
      textContent:'',style:{},classList:{add(){},remove(){},toggle(){}}
    });
    return elements.get(id);
  };
  const S = {mode:initial.mode,game:initial.game,view:initial.view,
    streamMode:initial.streamMode||'tv',second:null,tv:initial.tv};
  const j = {mode:reply.mode,game:reply.game,xboxCloud:reply.xboxCloud||false};
  vm.runInNewContext(statusTransition,{
    S,j,secondChanged:false,$,
    releasePadTouches(){},
    updateControlLayout(){},
    show(view){calls.push('show:'+view);S.view=view;},
    openScreen(mode){calls.push('screen:'+mode);S.view='screen';}
  });
  return {calls,view:S.view};
}
const xbox = {name:'Xbox Cloud Gaming',system:'Xbox Cloud',color:'#107C10'};
assert.deepEqual(transition({mode:'menu',game:null,view:'lib',tv:true},
  {mode:'game',game:xbox,xboxCloud:true}).calls,['screen:tv'],
  'Starting an external Xbox stream should show its TV mirror on the GamePad');
assert.deepEqual(transition({mode:'menu',game:null,view:'lib',tv:false},
  {mode:'game',game:xbox,xboxCloud:true}).calls,['show:play'],
  'Without screen capture, show Now Playing instead of an empty screen');
assert.deepEqual(transition({mode:'game',game:xbox,view:'lib',tv:true},
  {mode:'game',game:xbox,xboxCloud:true}).calls,[],
  'Manual library navigation must not be overridden every status poll');
assert.deepEqual(transition({mode:'game',game:xbox,view:'screen',tv:true},
  {mode:'menu',game:null,xboxCloud:false}).calls,['show:lib'],
  'Closing the Xbox player should restore Home');
assert.deepEqual(transition({mode:'menu',game:null,view:'lib',tv:true},
  {mode:'game',game:{name:'NES',system:'NES',color:'#000'},xboxCloud:false}).calls,
  ['show:play'],'Regular emulators keep their existing Now Playing behavior');
assert.deepEqual(transition({mode:'game',
    game:{name:'NES',system:'NES',color:'#000'},view:'screen',tv:true},
  {mode:'menu',game:null,xboxCloud:false}).calls,[],
  'Closing a regular emulator must keep a manually selected TV mirror');
console.log('Xbox GamePad screen switching tests passed');
