const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const tick=async()=>{for(let i=0;i<12;i++)await new Promise(r=>setImmediate(r));};
function server() {
    let snapshot={conversationId:'c1',version:1,generating:false,requestId:null,messages:[]};
    const calls=[], subscribers=[];
    function emit() {for(const s of subscribers){if(!s.signal.aborted)s.controller.enqueue(new TextEncoder().encode('event: state\ndata: '+JSON.stringify(snapshot)+'\n\n'));}}
    return {calls,get snapshot(){return snapshot;},set(value){snapshot=value;emit();},
        async fetch(url,options){calls.push({url,options});
            if(url.endsWith('/events'))return new Response(new ReadableStream({start(controller){subscribers.push({controller,signal:options.signal});controller.enqueue(new TextEncoder().encode('event: state\ndata: '+JSON.stringify(snapshot)+'\n\n'));}}),{headers:{'Content-Type':'text/event-stream'}});
            if(options.method==='POST') {const body=JSON.parse(options.body);snapshot={...snapshot,version:snapshot.version+1,generating:true,requestId:'r1',messages:snapshot.messages.concat([{id:'u1',role:'user',content:body.message,status:'COMPLETE'},{id:'a1',role:'assistant',content:'partial',status:'GENERATING'}])};emit();return Response.json({requestId:'r1',status:'GENERATING'},{status:202});}
            if(options.method==='DELETE'){snapshot={...snapshot,version:snapshot.version+1,messages:[]};emit();}
            return Response.json(snapshot);
        }};
}
function page(remote,user={id:'1',username:'alice'},storage) {
    let options;
    const input={value:'question'}, cookies={user,token:'token'};
    const window={location:{},appConfig:{backendBaseURL:'https://backend'},fetch:remote.fetch,
        sessionStorage:storage||{getItem(){throw Error('legacy storage must not be read');},setItem(){throw Error('legacy storage must not be written');}}};
    const sandbox={window,AbortController,TextDecoder,setTimeout,clearTimeout,crypto:require('node:crypto').webcrypto,console,alert(){},confirm:()=>{throw Error("native confirm must not be used");},
        document:{getElementById:()=>input},
        cookieUtils:{getUserInfo:()=>cookies.user,getToken:()=>cookies.token,setToken:t=>cookies.token=t,setUserInfo:u=>cookies.user=u,removeToken:()=>cookies.token=null,removeUserInfo:()=>cookies.user=null},
        authApi:{me:async()=>user,login:async()=>({token:'token',user})},Vue:function(config){options=config;}};
    vm.createContext(sandbox);vm.runInContext(fs.readFileSync('family-doctor/js/chatSync.js','utf8'),sandbox);
    vm.runInContext(fs.readFileSync('family-doctor/pages/chat-doctor.html','utf8').match(/<script>([\s\S]*?)<\/script>/)[1],sandbox);
    const state=options.data();for(const [key,fn]of Object.entries(options.methods))state[key]=fn.bind(state);state.scrollToBottom=()=>{};
    return {state,input,cookies,options};
}
async function login(p){await p.state.tryAutoLogin();await tick();}
function complete(remote,content='answer',status='COMPLETE'){remote.set({...remote.snapshot,version:remote.snapshot.version+1,generating:false,requestId:null,messages:remote.snapshot.messages.map(m=>m.role==='assistant'?{...m,content,status}:m)});}
test('another device and refresh restore server messages without submitting a model request',async(t)=>{
    const remote=server(),first=page(remote),second=page(remote);t.after(()=>{first.state.cancelChat();second.state.cancelChat();});
    await login(first);await first.state.doChat();complete(remote);await login(second);
    assert.equal(second.state.chatList.length,2);assert.equal(second.state.chatList[1].content,'answer');assert.equal(remote.calls.filter(c=>c.options.method==='POST').length,1);
});
test('all open pages show partial answers and disable send and clear while server generates',async(t)=>{
    const remote=server(),first=page(remote),second=page(remote);t.after(()=>{first.state.cancelChat();second.state.cancelChat();});await login(first);await login(second);await first.state.doChat();await tick();
    assert.equal(second.state.chatList[1].content,'partial');assert.equal(second.state.isGenerating,true);second.input.value='duplicate';await second.state.doChat();await second.state.clearChatRecords();assert.equal(remote.calls.filter(c=>c.options.method==='POST').length,1);assert.equal(remote.calls.filter(c=>c.options.method==='DELETE').length,0);
    complete(remote);await tick();assert.equal(second.state.isGenerating,false);assert.equal(second.state.chatList[1].content,'answer');
});
test('clear removes server conversation on all devices; logout only disconnects current page',async(t)=>{
    const remote=server(),first=page(remote),second=page(remote);t.after(()=>{first.state.cancelChat();second.state.cancelChat();});await login(first);await login(second);await first.state.doChat();complete(remote);await tick();
    first.state.doLogout();assert.equal(remote.snapshot.messages.length,2);assert.equal(first.state.chatList.length,0);assert.equal(second.state.chatList.length,2);
    await second.state.clearChatRecords();assert.equal(remote.snapshot.messages.length,2);assert.equal(second.state.showClearConfirm,true);await second.state.confirmClearChat();await tick();assert.equal(remote.snapshot.messages.length,0);assert.equal(second.state.chatList.length,0);
});
test('refresh during generation resumes shared partial state and closing a page does not cancel generation',async(t)=>{
    const remote=server(),first=page(remote),next=page(remote);t.after(()=>{first.state.cancelChat();next.state.cancelChat();});await login(first);await first.state.doChat();first.state.cancelChat();await login(next);
    assert.equal(next.state.isGenerating,true);assert.equal(next.state.chatList[1].content,'partial');complete(remote);await tick();assert.equal(next.state.chatList[1].content,'answer');assert.equal(remote.calls.filter(c=>c.options.method==='POST').length,1);
});
test('legacy session history is ignored; failed and interrupted messages remain visible from server',async(t)=>{
    const remote=server(),first=page(remote,undefined,{getItem:()=>JSON.stringify({messages:[{role:'user',content:'local only'}]})});t.after(()=>first.state.cancelChat());await login(first);assert.equal(first.state.chatList.length,0);
    await first.state.doChat();complete(remote,'partial','INTERRUPTED');await tick();assert.equal(first.state.chatList[1].failed,true);assert.ok(first.state.chatList[1].statusText);first.input.value='followup';await first.state.doChat();
    assert.deepEqual(Object.keys(JSON.parse(remote.calls.filter(c=>c.options.method==='POST').at(-1).options.body)).sort(),['clientRequestId','message']);
});
test('503 synchronization errors stay visible and prevent sending',async(t)=>{
    const remote=server();remote.fetch=async()=>new Response('',{status:503});const first=page(remote);t.after(()=>first.state.cancelChat());await login(first);
    assert.equal(first.state.isConnected,false);assert.ok(first.state.syncError);await first.state.doChat();assert.equal(first.state.chatList.length,0);
});
test('cookie logout in another tab clears private messages on visibility check',async(t)=>{
    const remote=server(),first=page(remote);t.after(()=>first.state.cancelChat());await login(first);await first.state.doChat();first.cookies.token=null;first.cookies.user=null;await first.state.checkAuthSession();assert.equal(first.state.currentUserId,null);assert.equal(first.state.chatList.length,0);assert.equal(first.state.isConnected,false);assert.equal(remote.snapshot.generating,true);
});
test('late snapshot after changing identity cannot restore prior messages',async(t)=>{
    const remote=server(),first=page(remote);t.after(()=>first.state.cancelChat());await login(first);await first.state.doChat();first.state.resetLocalConversation();first.state.currentUserName='bob';complete(remote,'old answer');await tick();assert.equal(first.state.chatList.length,0);
});

test('clear confirmation is in-page, cancellable, and does not reset session',async(t)=>{
    const remote=server(),p=page(remote);t.after(()=>p.state.cancelChat());await login(p);await p.state.doChat();complete(remote);await tick();
    const client=p.state.syncClient,version=p.state.contextVersion;
    p.state.clearChatRecords();assert.equal(p.state.showClearConfirm,true);assert.equal(remote.calls.filter(c=>c.options.method==='DELETE').length,0);
    p.state.cancelClearChat();assert.equal(p.state.showClearConfirm,false);await p.state.confirmClearChat();
    assert.equal(remote.calls.filter(c=>c.options.method==='DELETE').length,0);assert.equal(p.state.syncClient,client);assert.equal(p.state.contextVersion,version);
    p.state.clearChatRecords();await p.state.confirmClearChat();assert.equal(remote.calls.filter(c=>c.options.method==='DELETE').length,1);assert.equal(p.state.syncClient,client);assert.equal(p.state.contextVersion,version);
});
