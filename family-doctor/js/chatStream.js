/* 每次 fetch 响应独享的 SSE 解析器，无重连或全局连接状态。 */
window.chatStream = {
    async read(response, onEvent) {
        if (!response.ok) {
            const messages = { 400: '消息格式不正确', 401: '登录已失效，请重新登录',
                409: '正在生成中，请稍候再试', 503: '服务繁忙，请稍后重试' };
            const error = new Error(messages[response.status] || '发送失败，请稍后重试');
            error.status = response.status;
            throw error;
        }
        if (!(response.headers.get('Content-Type') || '').includes('text/event-stream') || !response.body) {
            throw new Error('服务器未返回有效的事件流');
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8', { fatal: true });
        let buffer = '', eventName = '', data = [], requestId = null, terminal = false;
        function line(value) {
            if (value === '') {
                if (!data.length) { eventName = ''; return; }
                const name = eventName || 'message';
                const raw = data.join('\n');
                eventName = ''; data = [];
                if (!['delta', 'done', 'failed'].includes(name)) return;
                if (terminal) throw new Error('终止事件后收到业务事件');
                const payload = JSON.parse(raw);
                if (!payload || typeof payload.requestId !== 'string' || !payload.requestId ||
                    (requestId !== null && payload.requestId !== requestId)) {
                    throw new Error('流式请求标识不一致');
                }
                requestId = payload.requestId;
                if (name === 'delta' && (typeof payload.text !== 'string' || !payload.text.length)) {
                    throw new Error('无效的增量文本');
                }
                if (name === 'failed' && (typeof payload.code !== 'string' || !payload.code ||
                    typeof payload.message !== 'string' || !payload.message)) {
                    throw new Error('无效的失败事件');
                }
                if (name === 'done' && payload.messageId !== undefined &&
                    (typeof payload.messageId !== 'string' || !payload.messageId)) {
                    throw new Error('无效的消息标识');
                }
                terminal = name === 'done' || name === 'failed';
                onEvent({ event: name, data: payload });
                return;
            }
            if (value.startsWith(':')) return;
            const colon = value.indexOf(':');
            const field = colon < 0 ? value : value.slice(0, colon);
            let fieldValue = colon < 0 ? '' : value.slice(colon + 1);
            if (fieldValue.startsWith(' ')) fieldValue = fieldValue.slice(1);
            if (field === 'event') eventName = fieldValue;
            if (field === 'data') data.push(fieldValue);
        }
        function drain(eof) {
            let index;
            while ((index = buffer.search(/[\r\n]/)) >= 0) {
                if (!eof && buffer[index] === '\r' && index === buffer.length - 1) break;
                const width = buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1;
                const value = buffer.slice(0, index);
                buffer = buffer.slice(index + width);
                line(value);
            }
        }
        try {
            while (true) {
                const chunk = await reader.read();
                buffer += decoder.decode(chunk.value, { stream: !chunk.done });
                drain(chunk.done);
                if (chunk.done) break;
            }
            if (!terminal) throw new Error('连接中断，结果未确认，请刷新查看记录');
        } finally {
            try { await reader.cancel(); } catch (_) { /* 网络可能已断开 */ }
            reader.releaseLock();
        }
    }
};
