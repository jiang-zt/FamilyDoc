package com.itzixi.service.impl;

import com.itzixi.bean.ChatMetric;
import com.itzixi.service.ChatMetricService;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.OllamaService;
import com.itzixi.utils.ChatTypeEnum;
import com.itzixi.utils.DashscopeChatClient;
import com.itzixi.utils.PromptLoader;
import com.itzixi.utils.SSEMsgType;
import com.itzixi.utils.SSEServer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @ClassName ServiceImpl
 * @Authorm jzt
 * @Version 1.0
 * @Description ServiceImpl
 **/

@Slf4j
@Service
public class OllamaServiceImpl implements OllamaService {

    @Resource
    private DashscopeChatClient dashscopeChatClient;

    @Resource
    private ChatRecordService chatRecordService;

    @Resource
    private ChatMetricService chatMetricService;

    @Resource
    private PromptLoader promptLoader;

    @Override
    public String chat(String msg) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptLoader.getSystemPrompt()));
        messages.add(Map.of("role", "user", "content", msg));
        return dashscopeChatClient.chat(messages);
    }

    @Override
    public void chatStream(String userName, String message) {

        // 保存用户发送的记录到数据库
        chatRecordService.saveChatRecord(userName, message, ChatTypeEnum.USER);

        // 构造消息（系统指令 + 用户问题）
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptLoader.getSystemPrompt()));
        messages.add(Map.of("role", "user", "content", message));
        //获取返回信息流
        long startNs = System.nanoTime();
        final long[] firstTokenNs = { -1L };
        final long[] lastTokenNs = { -1L };
        final long[] tokenIntervalNsSum = { 0L };
        final int[] tokenIntervalCount = { 0 };
        try {
            Flux<String> streamResponse = dashscopeChatClient.stream(messages);
            List<String> list = streamResponse
                    .map(this::sanitizeChunk)
                    .filter(content -> content != null && !content.isBlank())
                    .doOnNext(content -> {
                        long nowNs = System.nanoTime();
                        if (firstTokenNs[0] < 0) {
                            firstTokenNs[0] = nowNs;
                        }
                        if (lastTokenNs[0] > 0) {
                            tokenIntervalNsSum[0] += (nowNs - lastTokenNs[0]);
                            tokenIntervalCount[0]++;
                        }
                        lastTokenNs[0] = nowNs;
                        SSEServer.sendMessage(userName, content, SSEMsgType.ADD);
                        log.info("收到块: {}", content);
                    })
                    .collect(Collectors.toList())
                    .block();

            if (list == null) {
                list = new ArrayList<>();
            }

            // 兜底：如果流式没有拿到任何有效内容，降级为一次性返回，避免前端无回复
            boolean hasAnyContent = list.stream().anyMatch(s -> s != null && !s.isBlank());
            if (!hasAnyContent) {
                String fallback = sanitizeChunk(dashscopeChatClient.chat(messages));
                if (fallback != null && !fallback.isBlank()) {
                    SSEServer.sendMessage(userName, fallback, SSEMsgType.ADD);
                    list = new ArrayList<>();
                    list.add(fallback);
                    if (firstTokenNs[0] < 0) {
                        firstTokenNs[0] = System.nanoTime();
                    }
                    log.warn("Stream response was empty, fallback to non-stream chat for user: {}", userName);
                } else {
                    log.warn("Both stream and fallback chat returned empty content for user: {}", userName);
                }
            }

            SSEServer.sendMessage(userName, "bye", SSEMsgType.FINISH);

            // 保存AI回复的记录到数据库
            String htmlResult = String.join("", list);
            chatRecordService.saveChatRecord(userName, htmlResult, ChatTypeEnum.BOT);

            // 保存指标
            long endNs = System.nanoTime();
            long firstTokenMs = firstTokenNs[0] < 0 ? -1L : Duration.ofNanos(firstTokenNs[0] - startNs).toMillis();
            long totalMs = Duration.ofNanos(endNs - startNs).toMillis();
            Double avgTokenIntervalMs = null;
            if (tokenIntervalCount[0] > 0) {
                avgTokenIntervalMs = roundTo2((tokenIntervalNsSum[0] / 1_000_000.0) / tokenIntervalCount[0]);
            }

            ChatMetric metric = new ChatMetric();
            metric.setUserName(userName);
            metric.setQuestion(message);
            metric.setModel(dashscopeChatClient.getModel());
            metric.setPromptVersion(promptLoader.getPromptVersion());
            metric.setFirstTokenMs(firstTokenMs);
            metric.setTotalMs(totalMs);
            metric.setAvgTokenIntervalMs(avgTokenIntervalMs);
            metric.setOutputChars(htmlResult.length());
            metric.setOutputTokens(estimateTokens(htmlResult));
            metric.setAccuracyScore(null);
            metric.setCreatedAt(LocalDateTime.now());
            try {
                chatMetricService.saveMetric(metric);
            } catch (Exception metricEx) {
                log.error("保存会话Metric失败，建议检查chat_metric表结构: {}", metricEx.getMessage(), metricEx);
            }
            log.info("会话Metric: {}", metric);
        } catch (Exception ex) {
            log.error("DashScope chat request failed for user {}: {}", userName, ex.getMessage(), ex);
            SSEServer.sendMessage(userName, "模型调用失败，请检查 API Key 或模型权限", SSEMsgType.ADD);
            SSEServer.sendMessage(userName, "bye", SSEMsgType.FINISH);
        }

    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] parts = text.trim().split("\\s+");
        return parts.length;
    }

    private double roundTo2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
    
    private String sanitizeChunk(String content) {
        if (content == null) {
            return null;
        }
        String cleaned = content;
        cleaned = cleaned.replace("```html", "");
        cleaned = cleaned.replace("```HTML", "");
        cleaned = cleaned.replace("```", "");
        cleaned = cleaned.replace("`", "");
        return cleaned;
    }

}
