package com.itzixi.service.impl;

import com.itzixi.bean.ChatMetric;
import com.itzixi.scoring.MedicalAnswerScorer;
import com.itzixi.service.ChatMetricService;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.ChatService;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @ClassName ChatServiceImpl
 * @Author jzt
 * @Version 1.0
 * @Description ChatServiceImpl
 **/

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    @Resource
    private DashscopeChatClient dashscopeChatClient;

    @Resource
    private ChatRecordService chatRecordService;

    @Resource
    private ChatMetricService chatMetricService;

    @Resource
    private PromptLoader promptLoader;

    @Resource
    private MedicalAnswerScorer medicalAnswerScorer;

    @Override
    public String chat(String userName, String msg) {
        long startNs = System.nanoTime();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptLoader.getSystemPrompt()));
        messages.add(Map.of("role", "user", "content", msg));
        log.info("【会话开始-同步】 user={} questionChars={} questionPreview={}",
                userName, msg.length(), abbreviate(msg, 80));
        String answer = sanitizeChunk(dashscopeChatClient.chat(messages));
        long totalMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
        saveMetric(userName, msg, answer, totalMs, totalMs, null);
        log.info("【会话结束-同步】 user={} totalMs={} answerChars={} answerPreview={}",
                userName, totalMs, safeLength(answer), abbreviate(answer, 120));
        return answer;
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
        final int[] chunkCount = { 0 };
        log.info("【会话开始-流式】 user={} questionChars={} questionPreview={}",
                userName, message.length(), abbreviate(message, 80));
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
                        chunkCount[0]++;
                        SSEServer.sendMessage(userName, content, SSEMsgType.ADD);
                        log.info("【流式分片】 user={} chunkIndex={} chunkChars={} chunkPreview={}",
                                userName, chunkCount[0], content.length(), abbreviate(content, 60));
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
                    log.warn("【流式降级】 user={} reason=empty_stream fallbackChars={} fallbackPreview={}",
                            userName, fallback.length(), abbreviate(fallback, 100));
                } else {
                    log.warn("【流式异常】 user={} reason=empty_stream_and_empty_fallback", userName);
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

            saveMetric(userName, message, htmlResult, firstTokenMs, totalMs, avgTokenIntervalMs);
            log.info("【会话结束-流式】 user={} totalMs={} firstTokenMs={} avgTokenIntervalMs={} chunkCount={} answerChars={} answerPreview={}",
                    userName,
                    totalMs,
                    firstTokenMs,
                    avgTokenIntervalMs,
                    chunkCount[0],
                    htmlResult.length(),
                    abbreviate(htmlResult, 120));
        } catch (Exception ex) {
            log.error("【会话异常】 user={} questionPreview={} error={}",
                    userName, abbreviate(message, 80), ex.getMessage(), ex);
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

    private void saveMetric(String userName,
                            String question,
                            String answer,
                            Long firstTokenMs,
                            Long totalMs,
                            Double avgTokenIntervalMs) {
        MedicalAnswerScorer.ScoreResult scoreResult = medicalAnswerScorer.score(question, answer);

        ChatMetric metric = new ChatMetric();
        metric.setUserName(userName);
        metric.setQuestion(question);
        metric.setModel(dashscopeChatClient.getModel());
        metric.setPromptVersion(promptLoader.getPromptVersion());
        metric.setFirstTokenMs(firstTokenMs);
        metric.setTotalMs(totalMs);
        metric.setAvgTokenIntervalMs(avgTokenIntervalMs);
        metric.setOutputChars(answer == null ? 0 : answer.length());
        metric.setOutputTokens(estimateTokens(answer));
        metric.setAccuracyScore(scoreResult.totalScore());
        metric.setMatchedRuleId(scoreResult.matchedRuleId());
        metric.setSafetyScore(scoreResult.safetyScore());
        metric.setFactualScore(scoreResult.factualScore());
        metric.setCoverageScore(scoreResult.coverageScore());
        metric.setSemanticScore(scoreResult.semanticScore());
        metric.setExpressionScore(scoreResult.expressionScore());
        metric.setScorePass(scoreResult.pass());
        metric.setScoreIssues(String.join(" | ", scoreResult.issues()));
        metric.setCreatedAt(LocalDateTime.now());

        try {
            chatMetricService.saveMetric(metric);
        } catch (Exception metricEx) {
            log.error("【评分入库异常】 user={} error={}", userName, metricEx.getMessage(), metricEx);
        }
        log.info("【评分结果】 user={} total={} pass={} rule={} safety={} factual={} coverage={} semantic={} expression={} issues={}",
                userName,
                scoreResult.totalScore(),
                scoreResult.pass(),
                scoreResult.matchedRuleId(),
                scoreResult.safetyScore(),
                scoreResult.factualScore(),
                scoreResult.coverageScore(),
                scoreResult.semanticScore(),
                scoreResult.expressionScore(),
                summarizeIssues(scoreResult.issues()));
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

    private int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "-";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= maxLength) {
            return singleLine;
        }
        return singleLine.substring(0, maxLength) + "...";
    }

    private String summarizeIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return "[]";
        }
        return Arrays.toString(issues.toArray());
    }

}
