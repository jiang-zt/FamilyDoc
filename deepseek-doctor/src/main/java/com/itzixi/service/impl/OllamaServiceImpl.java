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
import org.springframework.ai.chat.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @ClassName OllamaServiceImpl
 * @Author 风间影月
 * @Version 1.0
 * @Description OllamaServiceImpl
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
    public Object aiOllamaChat(String msg) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptLoader.getSystemPrompt()));
        messages.add(Map.of("role", "user", "content", msg));
        return dashscopeChatClient.chat(messages);
    }

    @Override
    public Flux<ChatResponse> aiOllamaStream1(String msg) {
        throw new UnsupportedOperationException("DashScope compatible client does not return ChatResponse stream");
    }

    @Override
    public List<String> aiOllamaStream2(String msg) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptLoader.getSystemPrompt()));
        messages.add(Map.of("role", "user", "content", msg));
        String content = dashscopeChatClient.chat(messages);
        List<String> list = new ArrayList<>();
        list.add(content);
        return list;
    }

    @Override
    public void doDoctorStreamV3(String userName, String message) {

        // 保存用户发送的记录到数据库
        chatRecordService.saveChatRecord(userName, message, ChatTypeEnum.USER);

        // 构造消息（系统指令 + 用户问题）
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptLoader.getSystemPrompt()));
        messages.add(Map.of("role", "user", "content", message));
        //获取返回信息流
        long startNs = System.nanoTime();
        final long[] firstTokenNs = { -1L };

        Flux<String> streamResponse = dashscopeChatClient.stream(messages);
    
        streamResponse
            .doOnNext(content -> System.out.println("收到块: " + content))
            .doOnComplete(() -> System.out.println("流式响应完成"))
            .doOnError(error -> System.err.println("错误: " + error.getMessage()))
            .subscribe();
        //提取信息，转为List<String>类型
        List<String> list = streamResponse.toStream().map(content -> {
            String safeContent = sanitizeChunk(content);
            if (safeContent != null && !safeContent.isEmpty() && firstTokenNs[0] < 0) {
                firstTokenNs[0] = System.nanoTime();
            }
            SSEServer.sendMessage(userName, safeContent, SSEMsgType.ADD);//调用sseServer类向客户端主动推送结果
            log.info(safeContent);
            return safeContent;
        }).collect(Collectors.toList());

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

        SSEServer.sendMessage(userName, "GG", SSEMsgType.FINISH);

        // 保存AI回复的记录到数据库
        String htmlResult = "";
        for (String s : list) {
            htmlResult += s;
        }
        chatRecordService.saveChatRecord(userName, htmlResult, ChatTypeEnum.BOT);

        // 保存指标
        long endNs = System.nanoTime();
        long firstTokenMs = firstTokenNs[0] < 0 ? -1L : Duration.ofNanos(firstTokenNs[0] - startNs).toMillis();
        long totalMs = Duration.ofNanos(endNs - startNs).toMillis();

        ChatMetric metric = new ChatMetric();
        metric.setUserName(userName);
        metric.setQuestion(message);
        metric.setModel(dashscopeChatClient.getModel());
        metric.setPromptVersion(promptLoader.getPromptVersion());
        metric.setFirstTokenMs(firstTokenMs);
        metric.setTotalMs(totalMs);
        metric.setOutputChars(htmlResult.length());
        metric.setOutputTokens(estimateTokens(htmlResult));
        metric.setAccuracyScore(null);
        metric.setCreatedAt(LocalDateTime.now());
        chatMetricService.saveMetric(metric);

    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] parts = text.trim().split("\\s+");
        return parts.length;
    }

    private boolean isGreeting(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        normalized = normalized.replaceAll("[\\p{Punct}\\s]", "");
        if (normalized.isEmpty()) {
            return false;
        }
        String[] greetings = new String[] {
                "你好", "您好", "在吗", "hello", "hi", "hey", "哈喽", "嗨", "早上好", "晚上好", "早安", "晚安"
        };
        for (String g : greetings) {
            if (normalized.contains(g)) {
                if (normalized.length() <= 6) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildGreetingHtml() {
        return "<div class=\"section\">" +
                "<strong>你好，我是家庭医生。</strong>" +
                "<p>请描述你的主要症状、持续时间、是否伴随发热/疼痛/咳嗽等情况，我会给出初步建议。</p>" +
                "</div>";
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
