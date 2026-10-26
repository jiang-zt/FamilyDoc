const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = { window: {}, TextDecoder };
vm.runInNewContext(fs.readFileSync('family-doctor/js/chatStream.js', 'utf8'), context);
const read = context.window.chatStream.read;
function response(text, bytewise = false) {
    const bytes = new TextEncoder().encode(text);
    return new Response(new ReadableStream({ start(controller) {
        if (bytewise) for (const b of bytes) controller.enqueue(Uint8Array.of(b));
        else controller.enqueue(bytes);
        controller.close();
    }}), { headers: { 'Content-Type': 'text/event-stream' } });
}
const frame = (event, data) => `event: ${event}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`;
test('preserves Chinese, whitespace and newlines across every byte boundary', async () => {
    const events = [];
    await read(response(': ping\r\n\r\n' + frame('delta', { requestId: 'r', text: '你好 \n世界' }) + frame('done', { requestId: 'r' }), true), e => events.push(e));
    assert.equal(events[0].data.text, '你好 \n世界');
    assert.equal(events[1].event, 'done');
});
test('premature EOF is not success', async () => {
    await assert.rejects(read(response(frame('delta', { requestId: 'r', text: 'partial' })), () => {}), /结果未确认/);
});
test('failed is distinct from transport failure', async () => {
    const events = [];
    await read(response(frame('failed', { requestId: 'r', code: 'MODEL_ERROR', message: '失败' })), e => events.push(e));
    assert.equal(events[0].event, 'failed');
});
test('mismatched IDs and invalid JSON are rejected', async () => {
    await assert.rejects(read(response(frame('delta', { requestId: 'a', text: 'a' }) + frame('done', { requestId: 'b' })), () => {}));
    await assert.rejects(read(response('event: done\ndata: invalid\n\n'), () => {}));
});
test('HTTP errors do not enter the event parser', async () => {
    await assert.rejects(read(new Response('busy', { status: 503 }), () => {}), /繁忙/);
});
test('multiple data lines and unknown events', async () => {
    const events = [];
    await read(response('event: future\ndata: {}\n\nevent: delta\ndata: {"requestId":"r",\ndata: "text":"x"}\n\n' + frame('done', { requestId: 'r' })), e => events.push(e));
    assert.equal(events.length, 2);
    assert.equal(events[0].data.text, 'x');
});
