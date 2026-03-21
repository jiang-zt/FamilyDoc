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
 * @Author 风间影月
 * @Version 1.0
 * @Description SSEServer
 **/
@Slf4j
public class SSEServer {

    /**
     * 使用map对象，关联用户id和sse的服务连接
     * 进阶提问1：SseEmitter 能不能放在Redis中和userId进行关联？
     * 直接存储SseEmitter对象是不可行的，原因如下：
         * SseEmitter不是可序列化对象：
         * SseEmitter与特定HTTP连接绑定
         * 包含Servlet容器特定的资源（如响应输出流）
     * 无法跨JVM或网络传输
         * 技术生命周期不匹配：
         * SseEmitter生命周期与HTTP请求相同
         * Redis存储需要持久化能力
     *
     /**
     * 进阶提问2：SseEmitter 如何在集群SpringBoot中存在
     * 核心问题：如何跨多个服务实例跟踪和管理客户端连接
     * 1连接状态分散：SseEmitter 绑定到单个 JVM 实例
     * 2消息广播需求：需要通知所有实例向特定客户端推送消息
     * 3连接清理：跨实例的失效连接检测
         * 1. Redis 发布/订阅配置
         * 2. 集群服务实现
         * 3. 控制器实现
     */
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
        log.info("当前创建新的SSE连接，用户ID为: {}", userId);
        onlineCounts.getAndIncrement();
        return sseEmitter;
    }

    /**
     * @Description: 发送单条消息
     * @Author 风间影月
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
     * @Author 风间影月
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
     * @Author 风间影月
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
            log.error("用户[{}]的消息推送发生异常！", userId);
            removeConnection(userId);
        }

    }

    /**
     * @Description: 主动切断，停止sse服务和客户端的连接
     * @Author 风间影月
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
            log.info("连接关闭成功，被关闭的用户为 {}", userId);
        } else{
            log.warn("当前连接无需关闭，请不要重复操作");
        }

    }

    /**
     * @Description: SSE连接完成后的回调方法（关闭连接的时候调用）
     * @Author 风间影月
     * @param userId
     * @return Runnable
     */
    private static Runnable completionCallback(String userId) {
        return () -> {
            log.info("SSE连接完成并结束，用户ID为: {}", userId);
            removeConnection(userId);
        };
    }

    /**
     * @Description: SSE连接超时的时候进行调用
     * @Author 风间影月
     * @param userId
     * @return Runnable
     */
    private static Runnable timeoutCallback(String userId) {
        return () -> {
            log.info("SSE连接超时，用户ID为: {}", userId);
            removeConnection(userId);
        };
    }

    /**
     * @Description: SSE连接发生错误的时候进行调用
     * @Author 风间影月
     * @param userId
     * @return Runnable
     */
    private static Consumer<Throwable> errorCallback(String userId) {
        return Throwable -> {
            log.info("SSE连接发生错误，用户ID为: {}", userId);
            removeConnection(userId);
        };
    }

    /**
     * @Description: 从整个SSE服务中移除用户连接
     * @Author 风间影月
     * @param userId
     */
    public static void removeConnection(String userId) {
        sseClients.remove(userId);
        log.info("SSE连接被移除，移除的用户ID为: {}", userId);

        onlineCounts.getAndDecrement();
    }

    /**
     * @Description: 获得当前所有的会话总连接数（在线人数）
     * @Author 风间影月
     * @param
     * @return int
     */
    public static int getOnlineCounts() {
        return onlineCounts.intValue();
    }

}
