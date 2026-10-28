const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function page(api) {
    let options;
    const input = { value: 'question' };
    const window = {};
    const sandbox = { window, AbortController, console, alert() {}, confirm:()=>true,
        document: { getElementById:()=>input }, doctorApi:api,
        Vue: function(config) { options=config; } };
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync('family-doctor/js/chatContext.js','utf8'),sandbox);
    const html=fs.readFileSync('family-doctor/pages/chat-doctor.html','utf8');
    vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],sandbox);
    const state=options.data();
    for(const [key,fn] of Object.entries(options.methods)) state[key]=fn.bind(state);
    state.scrollToBottom=()=>{};
    state.currentUserName='alice';
    return {state,input};
}
test('second request carries only successful completed turns',async()=>{
    const requests=[];
    const {state,input}=page({doChat:async(body,event)=>{
        requests.push(JSON.parse(JSON.stringify(body)));
        event({event:'delta',data:{text:'answer'}});event({event:'done',data:{}});
    }});
    await state.doChat(); input.value='followup'; await state.doChat();
    assert.equal(requests[0].history.length,0);
    assert.deepEqual(requests[1].history,[{role:'user',content:'question'},{role:'assistant',content:'answer'}]);
    assert.equal(requests[1].message,'followup');
});
test('failed and interrupted answers never enter future context',async()=>{
    for (const mode of ['failed','disconnect']) {
        const {state}=page({doChat:async(body,event)=>{
            event({event:'delta',data:{text:'partial'}});
            if(mode==='failed')event({event:'failed',data:{message:'error'}});else throw new Error('disconnect');
        }});
        await state.doChat(); assert.equal(state.history.length,0);assert.equal(state.isGenerating,false);
    }
});
test('clear is local and clears context without API calls',async()=>{
    const {state}=page({doChat:async(body,event)=>{event({event:'delta',data:{text:'a'}});event({event:'done',data:{}});}});
    await state.doChat();state.clearChatRecords();
    assert.equal(state.history.length,0);assert.equal(state.chatList.length,0);
});
test('reset while old request is active cannot leak history into new identity',async()=>{
    let emit,finish;
    const {state}=page({doChat:(body,event)=>{emit=event;return new Promise(resolve=>finish=resolve);}});
    const pending=state.doChat();state.resetLocalConversation();state.currentUserName='bob';
    emit({event:'delta',data:{text:'old answer'}});emit({event:'done',data:{}});finish();await pending;
    assert.equal(state.history.length,0);assert.equal(state.chatList.length,0);
});
test('context helper trims complete turns and snapshots do not alias',()=>{
    const window={};vm.runInNewContext(fs.readFileSync('family-doctor/js/chatContext.js','utf8'),{window});
    let history=[];for(let i=0;i<12;i++)history=window.chatContext.append(history,'q'+i,'a'+i);
    assert.equal(history.length,20);assert.equal(history[0].content,'q2');
    const snapshot=window.chatContext.snapshot(history);snapshot[0].content='changed';assert.equal(history[0].content,'q2');
    assert.equal(window.chatContext.append(history,'large','x'.repeat(12001)).length,0);
});
