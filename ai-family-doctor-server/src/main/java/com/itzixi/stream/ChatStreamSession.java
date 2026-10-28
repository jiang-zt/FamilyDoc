package com.itzixi.stream;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每次 POST 独享的响应、取消信号和清理操作，不放入全局连接池。 */
//是一个为了保证“流式对话安全、有序、不泄漏资源”而人为抽象出来的对象
public final class ChatStreamSession {
    private final String requestId;
    private final SseEmitter emitter;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final Sinks.Empty<Void> cancellation = Sinks.empty();//只能发出“完成”或“错误”信号的轻量级数据结构
    private boolean cancelled;
    private boolean terminal;

    public ChatStreamSession(SseEmitter emitter, Runnable release) {
        this(emitter,release,UUID.randomUUID().toString());
    }
    public ChatStreamSession(SseEmitter emitter, Runnable release, String requestId) {
        this.requestId = java.util.Objects.requireNonNull(requestId);
        this.emitter = emitter;
        this.release = release;//释放锁的函数
        emitter.onTimeout(this::cancel);//when an async request times out
        emitter.onError(error -> cancel());
        emitter.onCompletion(this::cancel);
    }
    public synchronized boolean isCancelled() { return cancelled; }
    public Mono<Void> cancellation() { return cancellation.asMono(); }

    public void cancel() {
        synchronized (this) {//同一个 session 内部，多个线程(Tomcat、业务、回调）可能同时触碰它的状态
            if (terminal) return;//幂等
            cancelled = true;
        }
        cancellation.tryEmitEmpty();//向下游发取消状态,取消大模型输出
    }
    public void delta(String text) {
        synchronized (this) {
            if (cancelled || terminal) return;
        }
        // 网络写出不能持有取消/提交状态锁，慢客户端不应阻塞超时回调。
        send("delta", Map.of("requestId", requestId, "text", text));
    }
    public String requestId() { return requestId; }
    /** done 仅表示回答生成完成；历史由客户端保存。 */
    public boolean done() { return done(() -> {}); }
    public boolean done(Runnable beforeNotify) {
        synchronized (this) {
            if (cancelled || terminal) return false;
            terminal = true;
        }
        beforeNotify.run();
        send("done", Map.of("requestId",requestId));
        return true;
    }
    public void failed(String code, String message) {
        synchronized (this) {
            if (cancelled || terminal) return;
            terminal = true;
        }
        send("failed", Map.of("requestId", requestId, "code", code, "message", message));
    }
    private void send(String event, Map<String, String> payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            synchronized (this) { cancelled = true; }
            cancellation.tryEmitEmpty();
            throw new StreamDisconnectedException(ex);
        }
    }
    /** 模型超时后先释放业务占用，再尽力通知客户端；不能依赖网络写出完成。 */
    public void releaseGeneration() {
        if (released.compareAndSet(false, true)) release.run();
    }
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try { releaseGeneration(); } finally { emitter.complete(); }
        }
    }
    public static final class StreamDisconnectedException extends RuntimeException {
        StreamDisconnectedException(Throwable cause) { super(cause); }
    }
}
