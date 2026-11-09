/* 服务端会话是唯一数据源；订阅断开只重连和读取快照，绝不重发消息。 */
window.chatSync = (() => {
    function httpError(response) {
        const messages = {400:'消息格式不正确',401:'登录已失效，请重新登录',409:'正在生成中，请稍候再试',
            503:'会话同步服务尚未就绪，请检查数据库迁移或稍后重试'};
        const error = new Error(messages[response.status] || '会话同步失败，请稍后重试');
        error.status = response.status;
        return error;
    }
    function validate(snapshot) {
        if (!snapshot || typeof snapshot.conversationId !== 'string' || !Number.isSafeInteger(snapshot.version) ||
            snapshot.version < 0 || typeof snapshot.generating !== 'boolean' ||
            !(snapshot.requestId === null || typeof snapshot.requestId === 'string') || !Array.isArray(snapshot.messages) ||
            snapshot.messages.some(m => !m || typeof m.id !== 'string' || !['user','assistant'].includes(m.role) ||
                typeof m.content !== 'string' || !['COMPLETE','GENERATING','FAILED','INTERRUPTED'].includes(m.status))) {
            throw new Error('服务器返回了无效的会话快照');
        }
        return snapshot;
    }
    async function read(response, onSnapshot, signal) {
        if (!response.ok) throw httpError(response);
        if (!(response.headers.get('Content-Type') || '').includes('text/event-stream') || !response.body) {
            throw new Error('服务器未返回有效的同步事件流');
        }
        const reader = response.body.getReader(), decoder = new TextDecoder('utf-8', {fatal:true});
        let buffer = '', name = '', data = [];
        const cancel = () => { reader.cancel().catch(() => {}); };
        if (signal) { signal.addEventListener('abort', cancel, {once:true}); if (signal.aborted) cancel(); }
        function line(value) {
            if (value === '') {
                const event = name; const raw = data.join('\n'); name = ''; data = [];
                if (raw && ['state','delta','done','failed','conversation_cleared'].includes(event)) onSnapshot(validate(JSON.parse(raw)));
                return;
            }
            if (value.startsWith(':')) return;
            const colon = value.indexOf(':');
            const field = colon < 0 ? value : value.slice(0,colon);
            let text = colon < 0 ? '' : value.slice(colon+1);
            if (text.startsWith(' ')) text = text.slice(1);
            if (field === 'event') name = text;
            if (field === 'data') data.push(text);
        }
        try {
            while (!signal || !signal.aborted) {
                const chunk = await reader.read();
                buffer += decoder.decode(chunk.value, {stream:!chunk.done});
                let index;
                while ((index = buffer.search(/[\r\n]/)) >= 0) {
                    if (!chunk.done && buffer[index] === '\r' && index === buffer.length-1) break;
                    const width = buffer[index] === '\r' && buffer[index+1] === '\n' ? 2 : 1;
                    const value = buffer.slice(0,index); buffer = buffer.slice(index+width); line(value);
                }
                if (chunk.done) return;
            }
        } finally {
            if (signal) signal.removeEventListener('abort',cancel);
            try { await reader.cancel(); } catch (_) { /* 已断开 */ }
            reader.releaseLock();
        }
    }
    function wait(ms, signal) {
        return new Promise(resolve => {
            const done = () => { clearTimeout(timer); signal.removeEventListener('abort',done); resolve(); };
            const timer = setTimeout(done,ms);
            signal.addEventListener('abort',done,{once:true});
            if (signal.aborted) done();
        });
    }
    function create(options) {
        const fetcher = options.fetch || window.fetch.bind(window);
        const delays = options.retryDelays || [1000,2000,5000,10000];
        const pause = options.wait || wait;
        let controller = null, snapshot = null, connected = false, pending = false, started = false;
        function connection(ready, error) {
            connected = ready;
            if (options.onConnection) options.onConnection(ready,error || '');
        }
        function apply(value) {
            validate(value);
            if (controller.signal.aborted) return;
            if (!snapshot || value.version > snapshot.version) {
                snapshot = value;
                options.onSnapshot(value);
            }
        }
        async function request(path, method='GET', body) {
            const token = options.getToken();
            if (!token) { const error = new Error('登录已失效，请重新登录'); error.status=401; throw error; }
            const response = await fetcher(options.baseURL + '/chat/' + path, {
                method,credentials:'include',signal:controller.signal,
                headers:{Authorization:'Bearer '+token,Accept:path==='events'?'text/event-stream':'application/json',
                    ...(body ? {'Content-Type':'application/json'} : {})},
                ...(body ? {body:JSON.stringify(body)} : {})
            });
            if (!response.ok) throw httpError(response);
            return response;
        }
        function stop() { if (controller) controller.abort(); connected=false; }
        function unauthorized(error) {
            if (error.status !== 401) return false;
            connection(false,error.message); stop();
            if (options.onAuthError) options.onAuthError();
            return true;
        }
        async function refresh() { apply(await (await request('state')).json()); }
        async function loop(firstReady) {
            let attempt = 0;
            while (!controller.signal.aborted) {
                try {
                    await refresh(); firstReady();
                    const response = await request('events');
                    await read(response, value => { apply(value); connection(true); attempt=0; }, controller.signal);
                    if (controller.signal.aborted) break;
                    connection(false,'同步连接已断开，正在重新连接…');
                } catch (error) {
                    firstReady();
                    if (controller.signal.aborted) break;
                    if (unauthorized(error)) break;
                    connection(false,error.message || '同步连接失败，正在重新连接…');
                }
                if (!controller.signal.aborted) await pause(delays[Math.min(attempt++,delays.length-1)],controller.signal);
            }
        }
        function start() {
            if (started) return Promise.resolve();
            started=true; controller=new AbortController(); connection(false,'正在同步服务端会话…');
            return new Promise(resolve => { loop(resolve); });
        }
        async function mutate(method, body) {
            if (!connected || !controller || controller.signal.aborted) throw new Error('会话尚未同步，请等待连接恢复');
            if (pending || snapshot.generating) throw new Error('正在生成中，请稍候再试');
            pending=true;
            try {
                const result = await (await request('messages',method,body)).json();
                if (method === 'DELETE') apply(result); else await refresh();
                return result;
            } catch (error) {
                if (!controller.signal.aborted && !unauthorized(error)) {
                    // POST 的结果可能已落库：只查询，不自动重试。
                    try { await refresh(); } catch (syncError) {
                        if (!unauthorized(syncError)) connection(false,syncError.message);
                    }
                }
                throw error;
            } finally { pending=false; }
        }
        return {start,stop,
            send(message) {
                if (typeof message !== 'string' || !message.trim() || message.length > 8000) return Promise.reject(new Error('问题需为 1–8000 字符'));
                const clientRequestId = crypto.randomUUID ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
                return mutate('POST',{message,clientRequestId});
            },
            clear:()=>mutate('DELETE')};
    }
    return {create,read};
})();
