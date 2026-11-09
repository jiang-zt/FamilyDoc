/* 上下文只含已完成问答；sessionStorage 仅用于当前标签页的刷新恢复。 */
const chatSessionKey = 'familyDoctor.chatSession.v1';
window.chatContext = {
    append(history, question, answer) {
        const turns = history.concat([{ role: 'user', content: question }, { role: 'assistant', content: answer }]);
        let start = turns.length, count = 0, chars = 0;
        while (start >= 2 && count < 10) {
            const size = turns[start - 2].content.length + turns[start - 1].content.length;
            if (chars + size > 12000) break;
            chars += size; count++; start -= 2;
        }
        return turns.slice(start).map(item => ({ role: item.role, content: item.content }));
    },
    snapshot(history) { return history.map(item => ({ role: item.role, content: item.content })); },
    save(userId, history, messages) {
        if (typeof userId !== 'string' || !userId) return false;
        try {
            const raw = JSON.stringify({ version: 1, userId, history: this.snapshot(history),
                messages: this.snapshot(messages) });
            // 限制缓存读取和写入规模；超限时仍可在当前页面继续聊天。
            if (raw.length > 1000000) throw new Error('conversation too large');
            window.sessionStorage.setItem(chatSessionKey, raw);
            return true;
        } catch (_) {
            this.clear(); // 避免下次刷新恢复到上一次保存的旧版本。
            return false;
        }
    },
    load(userId) {
        try {
            const raw = window.sessionStorage.getItem(chatSessionKey);
            if (!raw) return null;
            if (raw.length > 1000000) throw new Error('conversation too large');
            const saved = JSON.parse(raw);
            const validTurns = items => Array.isArray(items) && items.length % 2 === 0 &&
                items.every((item, index) => item && typeof item.content === 'string' &&
                    item.role === (index % 2 === 0 ? 'user' : 'assistant'));
            if (!saved || saved.version !== 1 || typeof userId !== 'string' || !userId ||
                saved.userId !== userId || !validTurns(saved.history) || !validTurns(saved.messages) ||
                saved.history.length > 20 ||
                saved.history.reduce((size, item) => size + item.content.length, 0) > 12000) {
                throw new Error('invalid conversation');
            }
            return { history: this.snapshot(saved.history), messages: this.snapshot(saved.messages) };
        } catch (_) {
            this.clear();
            return null;
        }
    },
    clear() {
        try { window.sessionStorage.removeItem(chatSessionKey); } catch (_) { /* 存储受限不影响内存对话。 */ }
    }
};
