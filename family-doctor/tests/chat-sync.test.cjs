const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function library() {
    const window = {};
    const path = 'family-doctor/js/chatSync.js';
    if (fs.existsSync(path)) vm.runInNewContext(fs.readFileSync(path,'utf8'), {window,TextDecoder,AbortController,setTimeout,clearTimeout,crypto:require('node:crypto').webcrypto});
    assert.ok(window.chatSync, 'shared chat synchronization helper must exist');
    return window.chatSync;
}
const state = (version, generating=false, content='answer') => ({conversationId:'c1',version,generating,requestId:generating?'r1':null,messages:content?[{id:'m1',role:'assistant',content,status:generating?'GENERATING':'COMPLETE'}]:[]});
const frame = (event,data)=>`event: ${event}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`;
function stream() {
    let controller;
    return {response:new Response(new ReadableStream({start(c){controller=c;}}),{headers:{'Content-Type':'text/event-stream'}}),
        emit(event,data){controller.enqueue(new TextEncoder().encode(frame(event,data)));},close(){controller.close();}};
}
const tick = async()=>{for(let i=0;i<12;i++) await new Promise(r=>setImmediate(r));};
function harness(overrides={}) {
    const events=stream(), snapshots=[],connections=[],calls=[],retries=[];
    let current=state(1), postError=false;
    const client=library().create({baseURL:'https://backend',getToken:()=> 'secret',onSnapshot:s=>snapshots.push(s),onConnection:(ready,error)=>connections.push({ready,error}),
        retryDelays:[0,0],wait:async(ms,signal)=>{retries.push(ms);if(retries.length>3) await new Promise(resolve=>signal.addEventListener('abort',resolve,{once:true}));},
        fetch:async(url,options)=>{calls.push({url,options});if(url.endsWith('/events'))return events.response;
            if(options.method==='POST'){if(postError)throw Error('network lost');current=state(2,true,'partial');return Response.json({requestId:'r1',status:'GENERATING'},{status:202});}
            if(options.method==='DELETE'){current=state(current.version+1,false,'');return Response.json(current);}
            return Response.json(current);}, ...overrides});
    return {client,events,snapshots,connections,calls,retries,setState:s=>current=s,failPost:()=>postError=true};
}
test('shared SSE supports byte boundaries, heartbeats and several completed requests on one subscription',async()=>{
    const lib=library();const bytes=new TextEncoder().encode(': ping\r\n\r\n'+frame('state',state(1))+frame('done',state(2,false,'你好\n世界'))+frame('delta',state(3,true,'第二次'))+frame('failed',state(4)));
    const response=new Response(new ReadableStream({start(c){for(const b of bytes)c.enqueue(Uint8Array.of(b));c.close();}}),{headers:{'Content-Type':'text/event-stream'}});
    const seen=[];await lib.read(response,s=>seen.push(s));assert.deepEqual(seen.map(s=>s.version),[1,2,3,4]);assert.equal(seen[1].messages[0].content,'你好\n世界');
});
test('version guard rejects late snapshots, done and cleared events remain subscribed',async()=>{
    const h=harness();await h.client.start();await tick();h.events.emit('state',state(4,true));h.events.emit('delta',state(3,true,'stale'));h.events.emit('done',state(5));h.events.emit('conversation_cleared',state(6,false,''));await tick();
    assert.deepEqual(h.snapshots.map(s=>s.version),[1,4,5,6]);assert.equal(h.snapshots.at(-1).messages.length,0);h.client.stop();
});
test('only sender posts once with idempotency key and no client history; generating blocks send and clear',async()=>{
    const h=harness();await h.client.start();await assert.rejects(h.client.send('before subscription'),/同步/);h.events.emit('state',state(1));await tick();
    await h.client.send('question');await assert.rejects(h.client.send('duplicate'),/生成/);await assert.rejects(h.client.clear(),/生成/);
    const posts=h.calls.filter(c=>c.options.method==='POST');assert.equal(posts.length,1);const body=JSON.parse(posts[0].options.body);assert.equal(body.message,'question');assert.equal(typeof body.clientRequestId,'string');assert.equal(body.history,undefined);
    assert.ok(h.calls.every(c=>c.options.headers.Authorization==='Bearer secret'));h.client.stop();
});
test('disconnect re-fetches server state without resubmitting a model request and stop only aborts subscription',async()=>{
    let second;let subscriptions=0;const calls=[],seen=[],ready=[];
    const first=stream(),next=stream();
    const h=harness({onSnapshot:s=>seen.push(s),onConnection:r=>ready.push(r),fetch:async(url,o)=>{calls.push({url,o});if(url.endsWith('/events')){subscriptions++;return subscriptions===1?first.response:next.response;}return Response.json(subscriptions?state(3):state(1));}});
    await h.client.start();first.emit('state',state(2,true));await tick();first.close();await tick();next.emit('state',state(3));await tick();
    assert.equal(subscriptions,2);assert.deepEqual(seen.map(s=>s.version),[1,2,3]);assert.ok(ready.includes(false));assert.equal(calls.filter(c=>c.o.method==='POST').length,0);h.client.stop();assert.ok(calls.filter(c=>c.url.endsWith('/events')).at(-1).o.signal.aborted);
});
test('uncertain POST is never retried and reports error while reconciling server history',async()=>{
    const h=harness();await h.client.start();h.events.emit('state',state(1));await tick();h.failPost();await assert.rejects(h.client.send('question'),/network lost/);await tick();assert.equal(h.calls.filter(c=>c.options.method==='POST').length,1);h.client.stop();
});
test('503 schema failure stays visible and disconnected, 401 stops reconnecting',async()=>{
    for(const status of [503,401]){
        let auth=0;const h=harness({fetch:async()=>new Response('',{status}),onAuthError:()=>auth++});await h.client.start();await tick();
        assert.ok(h.connections.some(c=>!c.ready && c.error));assert.equal(h.connections.at(-1).ready,false);if(status===401){assert.equal(auth,1);assert.equal(h.retries.length,0);}h.client.stop();
    }
});
test('server clear replaces all messages and an older snapshot cannot resurrect them',async()=>{
    const h=harness();await h.client.start();h.events.emit('state',state(1));await tick();await h.client.clear();h.events.emit('delta',state(1,true));await tick();assert.equal(h.snapshots.at(-1).messages.length,0);assert.equal(h.snapshots.at(-1).version,2);h.client.stop();
});
test('late GET response after a newer SSE snapshot cannot overwrite shared state',async()=>{
    const events=stream(),seen=[];let reads=0,resolveState;
    const h=harness({onSnapshot:s=>seen.push(s),fetch:async(url,o)=>{
        if(url.endsWith('/events'))return events.response;
        if(o.method==='POST')return Response.json({requestId:'r1',status:'GENERATING'},{status:202});
        if(++reads===1)return Response.json(state(1));
        return new Promise(resolve=>resolveState=resolve);
    }});
    await h.client.start();events.emit('state',state(1));await tick();const pending=h.client.send('question');await tick();events.emit('delta',state(5,true,'latest'));await tick();resolveState(Response.json(state(2,true,'older')));await pending;
    assert.deepEqual(seen.map(s=>s.version),[1,5]);assert.equal(seen.at(-1).messages[0].content,'latest');h.client.stop();
});
test('repeated failures use a bounded retry delay and abort terminates retries',async()=>{
    let calls=0;const seen=[];
    const h=harness({retryDelays:[1,2,5],fetch:async()=>{calls++;throw Error('offline');},wait:async(ms,signal)=>{seen.push(ms);if(seen.length===5)await new Promise(r=>signal.addEventListener('abort',r,{once:true}));}});
    await h.client.start();await tick();assert.deepEqual(seen,[1,2,5,5,5]);h.client.stop();await tick();assert.equal(calls,5);
});
