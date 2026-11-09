const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
// Legacy helper is retained for old consumers; the shared chat page no longer imports it.
test('context helper trims complete turns and snapshots do not alias',()=>{
    const window={};vm.runInNewContext(fs.readFileSync('family-doctor/js/chatContext.js','utf8'),{window});
    let history=[];for(let i=0;i<12;i++)history=window.chatContext.append(history,'q'+i,'a'+i);
    assert.equal(history.length,20);assert.equal(history[0].content,'q2');
    const snapshot=window.chatContext.snapshot(history);snapshot[0].content='changed';assert.equal(history[0].content,'q2');
    assert.equal(window.chatContext.append(history,'large','x'.repeat(12001)).length,0);
});
function legacy(store) {
    const window={sessionStorage:store};vm.runInNewContext(fs.readFileSync('family-doctor/js/chatContext.js','utf8'),{window});return window.chatContext;
}
function storage(){const values=new Map();return {values,getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};}
test('legacy cache still preserves display beyond trimmed context and isolates user ids',()=>{
    const store=storage(),helper=legacy(store);let history=[],messages=[];
    for(let i=0;i<12;i++){history=helper.append(history,'q'+i,'a'+i);messages.push({role:'user',content:'q'+i},{role:'assistant',content:'a'+i});}
    assert.equal(helper.save('alice',history,messages),true);const saved=helper.load('alice');assert.equal(saved.history.length,20);assert.equal(saved.messages.length,24);
    assert.equal(helper.load('bob'),null);assert.equal(store.values.size,0);
});
test('legacy malformed, unavailable, quota-failed caches cannot restore stale or unsafe data',()=>{
    const store=storage(),helper=legacy(store),turn=[{role:'user',content:'q'},{role:'assistant',content:'a'}];helper.save('alice',turn,turn);const key=[...store.values.keys()][0];
    for(const value of ['{broken',JSON.stringify({version:1,userId:'alice',history:[{role:'system',content:'bad'}],messages:[]})]){store.setItem(key,value);assert.equal(helper.load('alice'),null);assert.equal(store.values.size,0);}
    helper.save('alice',turn,turn);store.setItem=()=>{throw Error('quota');};assert.equal(helper.save('alice',turn,turn),false);assert.equal(store.values.size,0);
    const blocked=legacy({getItem(){throw Error('denied');},setItem(){throw Error('quota');},removeItem(){throw Error('denied');}});assert.equal(blocked.load('alice'),null);assert.equal(blocked.save('alice',turn,turn),false);blocked.clear();
});
