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
import java.util.stream.Collectors;

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

    @Override
    public void chatStream(String userName, String message) {
        if (!chatGenerationGuard.tryAcquire(userName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有生成任务进行中，请稍候");
        }
        try {
        //组装上下文
        List<Map<String, String>> messages = buildMessages(userName, message);
        /* 
        这些统计变量需要在 lambda 回调里修改，而 Java 的 lambda 只能捕获 final 或 effectively final 的局部变量。
        数组引用本身是 final 的，但数组元素可以修改，所以用 firstTokenNs[0] 这种方式保存状态
        它不是为了存多个值，而是把数组当成一个可变容器。
        更规范的写法可以用 AtomicLong、AtomicInteger 或者封装一个状态对象。
        */
        long startNs = System.nanoTime();
        final long[] firstTokenNs = { -1L };//借数组实现“可变变量”
        final long[] lastTokenNs = { -1L };
        final long[] tokenIntervalNsSum = { 0L };
        final int[] tokenIntervalCount = { 0 };
        final int[] chunkCount = { 0 };
        log.info("【会话开始-流式】 user={} questionChars={} questionPreview={}",
                userName, message.length(), abbreviate(message, 80));
        try {
            //0~N 个字符串的异步序列//获取返回信息流
            //下游消费不过来时，上游可以减速，不会把内存撑爆
            Flux<String> streamResponse = dashscopeChatClient.stream(messages);
            List<String> list = streamResponse
                    .map(this::sanitizeChunk)//对每个分片做清洗
                    .filter(content -> content != null && !content.isBlank())//丢掉 null 和空白分片
                    .doOnNext(content -> {//"每个分片到达时触发一次推送"的钩子
                        long nowNs = System.nanoTime();
                        if (firstTokenNs[0] < 0) {
                            firstTokenNs[0] = nowNs;//记录首次chunk到达时间
                        }
                        if (lastTokenNs[0] > 0) {
                            tokenIntervalNsSum[0] += (nowNs - lastTokenNs[0]);//记录总间隔
                            tokenIntervalCount[0]++;//总间隔个数，用于评价
                        }
                        lastTokenNs[0] = nowNs;//上一个chunk到达时间，用于间隔计算
                        chunkCount[0]++;//总chunk次数
                        SSEServer.sendMessage(userName, content, SSEMsgType.ADD);
                        log.info("【流式分片】 user={} chunkIndex={} chunkChars={} chunkPreview={}",
                                userName, chunkCount[0], content.length(), abbreviate(content, 60));
                    })
                    .collect(Collectors.toList())//最后转为list<string>返回
                    .block();
                    //SSE 消息是边生成边推送
                    //但 /chat/stream 这个 HTTP 请求会等流结束后才返回
                    //生产环境可以改成异步任务，让接口快速返回，模型流式消费放到 线程池 里执行，减少请求线程占用。

            if (list == null) {
                list = new ArrayList<>();
            }

            // 兜底：如果流式没有拿到任何有效内容，降级为一次性返回，避免前端无回复
            boolean hasAnyContent = list.stream().anyMatch(s -> s != null && !s.isBlank());
            boolean validAnswer = hasAnyContent;
            if (!hasAnyContent) {
                String fallback = sanitizeChunk(dashscopeChatClient.chat(messages));
                if (fallback != null && !fallback.isBlank()) {
                    SSEServer.sendMessage(userName, fallback, SSEMsgType.ADD);
                    list = new ArrayList<>();
                    list.add(fallback);
                    validAnswer = true;
                    if (firstTokenNs[0] < 0) {
                        firstTokenNs[0] = System.nanoTime();
                    }
                    log.warn("【流式降级】 user={} reason=empty_stream fallbackChars={} fallbackPreview={}",
                            userName, fallback.length(), abbreviate(fallback, 100));
                } else {
                    //chat也无返回内容：不发成功信号，发明确失败事件后退出（不保存）
                    log.warn("【异常】 user={} reason=empty_stream_and_empty_fallback", userName);
                    SSEServer.sendMessage(userName, "模型未返回有效回答，请重试", SSEMsgType.ERROR);
                    return;
                }
            }

            String htmlResult = String.join("", list);
            long endNs = System.nanoTime();
            long firstTokenMs = firstTokenNs[0] < 0 ? -1L : Duration.ofNanos(firstTokenNs[0] - startNs).toMillis();
            long totalMs = Duration.ofNanos(endNs - startNs).toMillis();
            Double avgTokenIntervalMs = null;
            if (tokenIntervalCount[0] > 0) {
                avgTokenIntervalMs = roundTo2((tokenIntervalNsSum[0] / 1_000_000.0) / tokenIntervalCount[0]);
            }

            if (validAnswer) {
                // 有有效回答：短事务保存完整问答，保存成功才发 FINISH
                try {
                    chatTurnSaver.saveCompletedTurn(userName, message, htmlResult);
                    SSEServer.sendMessage(userName, "bye", SSEMsgType.FINISH);
                } catch (Exception saveEx) {
                    // 保存失败：不发成功结束信号，发明确失败事件
                    log.error("【流式保存问答失败】 user={} error={}", userName, saveEx.getMessage(), saveEx);
                    SSEServer.sendMessage(userName, "保存聊天记录失败，请重试", SSEMsgType.ERROR);
                }
                // 评分入库（旁路：失败不影响回答成功判定）
                saveMetric(userName, message, htmlResult, firstTokenMs, totalMs, avgTokenIntervalMs);
            } else {
                // 空回答：不保存为成功轮次，发明确失败事件
                log.warn("【流式空回答】 user={} reason=no_valid_content", userName);
                SSEServer.sendMessage(userName, "模型未返回有效回答，请重试", SSEMsgType.ERROR);
            }

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
            // 明确失败事件，不发成功结束信号
            SSEServer.sendMessage(userName, "模型调用失败，请检查 API Key 或模型权限", SSEMsgType.ERROR);
        } 
        }
        finally {
            chatGenerationGuard.release(userName);
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
