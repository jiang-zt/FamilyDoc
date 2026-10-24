package com.itzixi.utils;

import lombok.extern.slf4j.Slf4j;
//import org.mockito.internal.junit.StrictStubsRunnerTestListener;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * @ClassName SSEServer
 * @Author 
 * @Version 1.0
 * @Description SSEServer
 **/
@Slf4j
public class SSEServer {


    private static Map<String, SseEmitter> sseClients = new ConcurrentHashMap<>();

    /**
     * 用于统计当前总在线人数
     */
    private static AtomicInteger onlineCounts = new AtomicInteger(0);

    /**
     * 建立连接
     * @param userId
     * @return
     */
    public static SseEmitter connect(String userId) {
        // 设置超时时间，0代表永不过期；默认30秒，超时未完成任务则会抛出异常
        SseEmitter sseEmitter = new SseEmitter(0L);

        // 注册SSE的回调方法
        sseEmitter.onCompletion(completionCallback(userId));//完成
        sseEmitter.onError(errorCallback(userId));//出错
        sseEmitter.onTimeout(timeoutCallback(userId));//超时

        sseClients.put(userId, sseEmitter);
        onlineCounts.getAndIncrement();
        log.info("【SSE连接建立】 user={} onlineCount={}", userId, onlineCounts.get());
        try {
            sseEmitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            log.error("【SSE连接初始化异常】 user={} error={}", userId, e.getMessage(), e);
            removeConnection(userId);
        }
        return sseEmitter;
    }

    /**
     * @Description: 发送单条消息
     * @Author 
     * @param userId
     * @param message
     * @param msgType
     */
    public static void sendMessage(String userId, String message, SSEMsgType msgType) {
        if (CollectionUtils.isEmpty(sseClients)) {
            return;
        }

        if (sseClients.containsKey(userId)) {
            SseEmitter sseEmitter = sseClients.get(userId);
            sendEmitterMessage(sseEmitter, userId, message, msgType);
        }
    }

    /**
     * @Description: 发送消息给所有人
     * @param message
     */
    public static void sendMessageToAllUsers(String message) {
        if (CollectionUtils.isEmpty(sseClients)) {
            return;
        }

        sseClients.forEach((userId, sseEmitter) -> {
                sendEmitterMessage(sseEmitter, userId, message, SSEMsgType.MESSAGE);
            }
        );
    }

    /**
     * @Description: 使用SseEmitter推送消息
     * @Author 
     * @param sseEmitter
     * @param userId
     * @param message
     * @param msgType
     */
    public static void sendEmitterMessage(SseEmitter sseEmitter,
                                           String userId,
                                           String message,
                                           SSEMsgType msgType) {

        try {
            
            SseEmitter.SseEventBuilder msg = SseEmitter.event()
                    .id(userId)
                    .name(msgType.type)
                    .data(message);
            sseEmitter.send(msg);
        } catch (IOException e) {
            log.error("【SSE推送异常】 user={} eventType={} error={}", userId, msgType.type, e.getMessage(), e);
            removeConnection(userId);
        }

    }

    /**
     * @Description: 主动切断，停止sse服务和客户端的连接
     * @param userId
     */
    public static void stopServer(String userId) {
        if (CollectionUtils.isEmpty(sseClients)) {
            return;
        }

        SseEmitter sseEmitter = sseClients.get(userId);
        if (sseEmitter != null){
            // complete 表示执行完毕，断开连接
            sseEmitter.complete();
            removeConnection(userId);
        } else{
            log.warn("【SSE关闭跳过】 user={} reason=connection_not_found", userId);
        }

    }

    /**
     * @Description: SSE连接完成后的回调方法（关闭连接的时候调用）
     * @param userId
     * @return Runnable
     */
    private static Runnable completionCallback(String userId) {
        return () -> {
            log.info("【SSE连接完成】 user={}", userId);
            removeConnection(userId);
        };
    }

    /**
     * @Description: SSE连接超时的时候进行调用
     * @param userId
     * @return Runnable
     */
    private static Runnable timeoutCallback(String userId) {
        return () -> {
            log.warn("【SSE连接超时】 user={}", userId);
            removeConnection(userId);
        };
    }

    /**
     * @Description: SSE连接发生错误的时候进行调用
     * @param userId
     * @return Runnable
     */
    private static Consumer<Throwable> errorCallback(String userId) {
        return Throwable -> {
            log.error("【SSE连接错误】 user={} error={}", userId, Throwable.getMessage(), Throwable);
            removeConnection(userId);
        };
    }

    /**
     * @Description: 从整个SSE服务中移除用户连接
     * @param userId
     */
    public static void removeConnection(String userId) {
        SseEmitter removed = sseClients.remove(userId);
        if (removed == null) {
            log.info("【SSE连接移除跳过】 user={} reason=connection_not_found onlineCount={}", userId, onlineCounts.get());
            return;
        }
        int onlineCount = onlineCounts.updateAndGet(current -> Math.max(0, current - 1));
        log.info("【SSE连接移除】 user={} onlineCount={}", userId, onlineCount);
    }

    /**
     * @Description: 获得当前所有的会话总连接数（在线人数）
     * @Author 
     * @param
     * @return int
     */
    public static int getOnlineCounts() {
        return onlineCounts.intValue();
    }

}
