package com.itzixi.service.impl;

import com.itzixi.bean.ChatMetric;
import com.itzixi.context.PreparedChat;
import com.itzixi.service.ChatGenerationGuard;
import com.itzixi.service.ChatService;
import com.itzixi.stream.ChatStreamSession;
import com.itzixi.utils.DashscopeChatClient;
import com.itzixi.utils.PromptLoader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {
    @Resource private DashscopeChatClient dashscopeChatClient;
    @Resource private PromptLoader promptLoader;
    @Resource private ChatGenerationGuard chatGenerationGuard;
    @Resource private ChatMetricRecorder metricRecorder;
    @Value("${chat.stream.model-timeout-ms:120000}") private long streamTimeoutMs = 120000;

    @Override public String chat(String userName, PreparedChat input) {
        if (!chatGenerationGuard.tryAcquire(userName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"已有生成任务进行中，请稍候");
        }
        long start = System.nanoTime();
        ChatMetric metric = metric(userName,UUID.randomUUID().toString(),input);
        String answer = "";
        try {
            answer = sanitizeChunk(dashscopeChatClient.chat(buildMessages(input)));
            if (answer.isBlank()) {
                metric.setErrorCode("EMPTY_RESPONSE");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"模型未返回有效回答");
            }
            metric.setStatus("SUCCESS");
            metric.setFirstTokenMs(elapsed(start));
            return answer;
        } catch (RuntimeException ex) {
            metric.setStatus("FAILED");
            if (metric.getErrorCode() == null) metric.setErrorCode("MODEL_ERROR");
            throw ex;
        } finally {
            metric.setTotalMs(elapsed(start));
            chatGenerationGuard.release(userName);
            record(metric,input,answer);
        }
    }

    /** 仅由异步 worker 调用；占用在 Controller 提交之前获取。 */
    @Override public void chatStream(String userName, PreparedChat input, ChatStreamSession session) {
        long start = System.nanoTime();
        ChatMetric metric = metric(userName,session.requestId(),input);
        AtomicLong first = new AtomicLong(-1), last = new AtomicLong(-1);
        AtomicLong intervals = new AtomicLong(), count = new AtomicLong();
        StringBuffer answer = new StringBuffer();
        var recorded = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable finish = () -> {
            if (!recorded.compareAndSet(false,true)) return;//设置保存标志
            metric.setTotalMs(elapsed(start));
            if (first.get() >= 0) metric.setFirstTokenMs(Duration.ofNanos(first.get()-start).toMillis());
            if (count.get() > 0) metric.setAvgTokenIntervalMs(Math.round(intervals.get()/1_000_000.0/count.get()*100.0)/100.0);
            session.releaseGeneration();
            record(metric,input,answer.toString());//保存记录
        };
        try {
            if (session.isCancelled()) return;
            dashscopeChatClient.stream(buildMessages(input))
                .map(this::sanitizeChunk).filter(chunk -> !chunk.isEmpty())
                .doOnNext(chunk -> {
                    long now = System.nanoTime();
                    first.compareAndSet(-1,now);
                    long previous = last.getAndSet(now);
                    if (previous >= 0) { intervals.addAndGet(now-previous); count.incrementAndGet(); }
                    answer.append(chunk);
                    session.delta(chunk);//推流
                })
                .then().takeUntilOther(session.cancellation())//收到信号立马中断
                .block(Duration.ofMillis(streamTimeoutMs));//最多等待streamTimeoutMs否则停止
            if (session.isCancelled()) return;
            if (answer.toString().isBlank()) {
                metric.setStatus("FAILED");
                metric.setErrorCode("EMPTY_RESPONSE");
                finish.run();
                session.failed("EMPTY_RESPONSE","模型未返回有效回答，请重试");
                return;
            }
            metric.setStatus("SUCCESS");
            if (!session.done(finish)) metric.setStatus("CANCELLED");//保存
        } catch (Exception ex) {
            if ("SUCCESS".equals(metric.getStatus())) {
                // 模型已经完成，仅终止通知发送失败；保留生成成功指标。
                log.debug("终止通知未送达 requestId={}",session.requestId());
            } else if (!session.isCancelled()) {
                boolean timeout = ex.getCause() instanceof java.util.concurrent.TimeoutException
                        || reactor.core.Exceptions.unwrap(ex) instanceof java.util.concurrent.TimeoutException;
                String code = timeout ? "MODEL_TIMEOUT" : "MODEL_ERROR";
                metric.setStatus(timeout ? "TIMEOUT" : "FAILED");
                metric.setErrorCode(code);
                finish.run();
                try { session.failed(code,timeout ? "模型响应超时，请稍后重试" : "回答生成失败，请稍后重试"); }
                catch (ChatStreamSession.StreamDisconnectedException ignored) { }
            }
        } finally {
            if (!recorded.get()) {//没有保存成功
                if (session.isCancelled() && ("RUNNING".equals(metric.getStatus()) || "CANCELLED".equals(metric.getStatus()))) {
                    metric.setStatus("CANCELLED"); metric.setErrorCode("CLIENT_CANCELLED");
                } else if ("RUNNING".equals(metric.getStatus())) {
                    metric.setStatus("FAILED"); metric.setErrorCode("INTERNAL_ERROR");
                }
                finish.run();
            }
            session.close();
        }
    }
    private ChatMetric metric(String userName,String requestId,PreparedChat input) {
        ChatMetric metric = new ChatMetric();
        metric.setRequestId(requestId); metric.setUserName(userName); metric.setQuestion(input.message());
        metric.setStatus("RUNNING");
        try {
            metric.setModel(dashscopeChatClient.getModel());
            metric.setPromptVersion(promptLoader.getPromptVersion());
        } catch (Exception ex) { log.warn("指标元数据读取失败 requestId={}",requestId,ex); }
        metric.setContextMessageCount(input.history().size());
        metric.setContextChars(input.history().stream().mapToInt(m -> m.get("content").length()).sum());
        return metric;
    }
    private void record(ChatMetric metric,PreparedChat input,String answer) {
        try { metricRecorder.record(metric,input.history(),answer); }
        catch (Exception ex) { log.error("指标记录失败 requestId={}",metric.getRequestId(),ex); }
    }
    private List<Map<String,String>> buildMessages(PreparedChat input) {
        List<Map<String,String>> messages = new ArrayList<>();
        messages.add(Map.of("role","system","content",promptLoader.getSystemPrompt()));
        messages.addAll(input.history());
        messages.add(Map.of("role","user","content",input.message()));
        return messages;
    }
    private long elapsed(long start) { return Duration.ofNanos(System.nanoTime()-start).toMillis(); }
    private String sanitizeChunk(String value) {
        return value == null ? "" : value.replace("```html","").replace("```HTML","").replace("```","").replace("`","");
    }
}
