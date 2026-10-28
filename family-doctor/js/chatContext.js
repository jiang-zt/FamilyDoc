/* 只维护已完成的问答，页面刷新后释放，不使用浏览器持久化存储。 */
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
    snapshot(history) { return history.map(item => ({ role: item.role, content: item.content })); }
};
