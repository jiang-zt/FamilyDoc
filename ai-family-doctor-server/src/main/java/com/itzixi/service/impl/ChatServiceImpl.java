package com.itzixi.service.impl;

import com.itzixi.bean.ChatMetric;
import com.itzixi.scoring.MedicalAnswerScorer;
import com.itzixi.service.ChatMetricService;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.ChatService;
import com.itzixi.utils.ChatTypeEnum;
import com.itzixi.utils.DashscopeChatClient;
import com.itzixi.utils.PromptLoader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.itzixi.bean.ChatRecord;
import com.itzixi.service.ChatGenerationGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @ClassName ChatServiceImpl
 * @Author jzt
 * @Version 1.0
 * @Description ChatServiceImpl
 **/
//多实现、跨模块契约、需要 mock、需要 AOP 接口代理
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

    @Value("${chat.context.max-turns:10}")
    private int ctxMaxTurns; // 最近 N 轮完整问答  

    @Value("${chat.context.max-chars:12000}")
    private int ctxMaxChars;      // 历史内容上限

    @Value("${chat.stream.model-timeout-ms:120000}")
    private long streamTimeoutMs;

    @Resource
    private ChatTurnSaver chatTurnSaver;

    @Resource
    private ChatGenerationGuard chatGenerationGuard;

    @Override
    public String chat(String userName, String msg) {
        if (!chatGenerationGuard.tryAcquire(userName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有生成任务进行中，请稍候");
        }
        try {
            long startNs = System.nanoTime();
            // 统一上下文组装：system + 历史(user/assistant) + 当前 user
            List<Map<String, String>> messages = buildMessages(userName, msg);
            log.info("【会话开始-同步】 user={} questionChars={} questionPreview={}",
                    userName, msg.length(), abbreviate(msg, 80));

            String answer = sanitizeChunk(dashscopeChatClient.chat(messages));
            long totalMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

            // 短事务保存本轮完整问答（user + bot 一起落库，序号由 saveChatRecord 内部自动分配）
            chatTurnSaver.saveCompletedTurn(userName, msg, answer);

            //评分并记录落库（旁路：评分失败不影响回答成功判定）
            saveMetric(userName, msg, answer, totalMs, totalMs, null);

            log.info("【会话结束-同步】 user={} totalMs={} answerChars={} answerPreview={}",
                    userName, totalMs, safeLength(answer), abbreviate(answer, 120));
            return answer;
        } finally {
            chatGenerationGuard.release(userName);
        }
    }

    /** 仅由 ChatStreamWorker 异步调用；生成占用在 Controller 提交任务前获取。 */
    @Override
    public void chatStream(String userName, String message, com.itzixi.stream.ChatStreamSession session) {
        long startNs = System.nanoTime();
        java.util.concurrent.atomic.AtomicLong firstNs = new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicLong lastNs = new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicLong intervalSum = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong intervalCount = new java.util.concurrent.atomic.AtomicLong();
        String stage = "INTERNAL_ERROR";
        try {
            if (session.isCancelled()) return;//获取会话状态
            List<Map<String, String>> messages = buildMessages(userName, message);//组织对话消息+上下文
            stage = "MODEL_ERROR";
            List<String> chunks = dashscopeChatClient.stream(messages)
                    .map(this::sanitizeChunk)
                    .filter(content -> !content.isEmpty())
                    .doOnNext(content -> {
                        long now = System.nanoTime();
                        firstNs.compareAndSet(-1, now);
                        long previous = lastNs.getAndSet(now);
                        if (previous >= 0) {
                            intervalSum.addAndGet(now - previous);
                            intervalCount.incrementAndGet();
                        }
                        session.delta(content);//流式推送
                    })
                    .collectList()
                    .takeUntilOther(session.cancellation())
                    .block(Duration.ofMillis(streamTimeoutMs));
            if (session.isCancelled()) return;
            String answer = chunks == null ? "" : String.join("", chunks);
            if (answer.isBlank()) {
                session.failed("EMPTY_RESPONSE", "模型未返回有效回答，请重试");
                return;
            }
            long totalMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            long firstTokenMs = Duration.ofNanos(firstNs.get() - startNs).toMillis();
            Double avg = intervalCount.get() == 0 ? null
                    : roundTo2(intervalSum.get() / 1_000_000.0 / intervalCount.get());
            stage = "SAVE_FAILED";
            if (session.saveAndDone(() -> chatTurnSaver.saveCompletedTurn(userName, message, answer))) {
                session.close(); // 提交完成后释放响应和占用，指标不阻塞下一轮。
                // 评分/指标是旁路，不能把已提交回答改判失败。
                try { saveMetric(userName, message, answer, firstTokenMs, totalMs, avg); }
                catch (Exception metricEx) { log.warn("流式指标记录失败 user={}", userName, metricEx); }
            }
        } catch (Exception ex) {
            if (!session.isCancelled()) {
                String code = reactor.core.Exceptions.unwrap(ex) instanceof java.util.concurrent.TimeoutException
                        || ex.getCause() instanceof java.util.concurrent.TimeoutException
                        ? "MODEL_TIMEOUT" : stage;
                if ("MODEL_TIMEOUT".equals(code)) session.releaseGeneration();
                log.warn("流式回答失败 user={} code={}", userName, code, ex);
                try {
                    session.failed(code, switch (code) {
                        case "SAVE_FAILED" -> "保存聊天记录失败，请稍后重试";
                        case "MODEL_TIMEOUT" -> "模型响应超时，请稍后重试";
                        default -> "回答生成失败，请稍后重试";
                    });
                } catch (com.itzixi.stream.ChatStreamSession.StreamDisconnectedException ignored) {
                    // 已断开连接，仅执行 finally 清理。
                }
            }
        } finally {
            session.close();
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] parts = text.trim().split("\\s+");//一个或多个空格
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
        
        //ChatService 里接入评分
        MedicalAnswerScorer.ScoreResult scoreResult = medicalAnswerScorer.score(question, answer);
        
        //不仅保存耗时指标，也会把回答质量评分落到 chat_metric 表里
        // 方便后续看某次回答是否通过、命中了哪条规则、扣分原因是什么

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
            //入库
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

    //字符串缩写
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

    private List<Map<String,String>> buildMessages(String userName, String currentQuestion) {
        List<Map<String,String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role","system","content", promptLoader.getSystemPrompt()));
        // 取最近上下文（受 max-turns 限制：limit = 最近 N 轮 * 2 条记录）
        List<ChatRecord> history = chatRecordService.getRecentMessages(userName, ctxMaxTurns * 2);
        List<ChatRecord> completeTurns = onlyCompleteTurns(history);   // 只留 完整 user→bot 对

        // 受 max-chars 限制：从最新完整轮向旧累加字符，一旦超过上限就裁掉该轮及更旧的轮
        List<ChatRecord> kept = new ArrayList<>();
        int usedChars = 0;
        for (int i = completeTurns.size() - 1; i >= 0; i -= 2) {
            ChatRecord u = completeTurns.get(i - 1);
            ChatRecord b = completeTurns.get(i);
            int turnChars = (u.getContent() == null ? 0 : u.getContent().length())
                    + (b.getContent() == null ? 0 : b.getContent().length());
            if (usedChars + turnChars > ctxMaxChars) {
                break;   // 超限：丢弃这一整轮（含更旧的），不留下孤立回答
            }
            usedChars += turnChars;
            kept.add(0, u);   // 插到头部，保持 旧→新 顺序
            kept.add(1, b);
        }
        for (ChatRecord r : kept) {
            String role = ChatTypeEnum.BOT.type.equals(r.getChatType()) ? "assistant" : "user";
            msgs.add(Map.of("role", role, "content", r.getContent()));
        }
        msgs.add(Map.of("role","user","content", currentQuestion)); // 当前问题只追加一次
        return msgs;
    }

    /** 只保留相邻且完整的 user→bot 对；孤立 user（无配对 bot）和孤立 bot 都丢弃 */
    private List<ChatRecord> onlyCompleteTurns(List<ChatRecord> history) {
        List<ChatRecord> complete = new ArrayList<>();
        for (int i = 0; i < history.size(); ) {
            if (isUser(history.get(i))) {                    // 当前是 user
                if (i + 1 < history.size() && isBot(history.get(i + 1))) {
                    complete.add(history.get(i));            // user 后面紧跟 bot → 完整对，保留
                    complete.add(history.get(i + 1));
                    i += 2;
                } else {
                    i += 1;                                  // 末尾孤立 user，丢弃
                }
            } else {
                i += 1;                                      // 开头/中间孤立 bot，丢弃
            }
        }
        return complete;
    }
    private boolean isUser(ChatRecord r){ return ChatTypeEnum.USER.type.equals(r.getChatType()); }
    private boolean isBot(ChatRecord r) { return ChatTypeEnum.BOT.type.equals(r.getChatType()); }

    

}
