package com.itzixi.service.impl;

import com.itzixi.bean.ChatMetric;
import com.itzixi.utils.PromptLoader;
import com.itzixi.service.ChatMetricService;
import com.itzixi.service.ChatRecordService;
import com.itzixi.service.OllamaService;
import com.itzixi.utils.ChatTypeEnum;
import com.itzixi.utils.SSEMsgType;
import com.itzixi.utils.SSEServer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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
    private OllamaChatClient ollamaChatClient;

    @Resource
    private ChatRecordService chatRecordService;

    @Resource
    private ChatMetricService chatMetricService;

    @Resource
    private PromptLoader promptLoader;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.ollama.chat.model:unknown}")
    private String modelName;

    @Override
    public Object aiOllamaChat(String msg) {
        return ollamaChatClient.call(msg);
    }

    @Override
    public Flux<ChatResponse> aiOllamaStream1(String msg) {
        // 代码执行到此处的时间  22:00:00 - 开始时间

        Prompt prompt = new Prompt(new UserMessage(msg));
        Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);

        // 代码执行到此处的时间  22:01:30 - 结束时间
        // 两个时间的时间差为1分30秒，则总计90秒

        return streamResponse;
    }

    @Override
    public List<String> aiOllamaStream2(String msg) {
        Prompt prompt = new Prompt(new UserMessage(msg));
        Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);

        List<String> list = streamResponse.toStream().map(chatResponse -> {
            String content = chatResponse.getResult().getOutput().getContent();
//            System.out.println(content);
            log.info(content);
            return content;
        }).collect(Collectors.toList());

        return list;
    }

    @Override
    public void doDoctorStreamV3(String userName, String message) {

        // 保存用户发送的记录到数据库
        chatRecordService.saveChatRecord(userName, message, ChatTypeEnum.USER);

        if (isGreeting(message)) {
            String htmlResult = buildGreetingHtml();
            SSEServer.sendMessage(userName, htmlResult, SSEMsgType.ADD);
            SSEServer.sendMessage(userName, "GG", SSEMsgType.FINISH);
            chatRecordService.saveChatRecord(userName, htmlResult, ChatTypeEnum.BOT);

            ChatMetric metric = new ChatMetric();
            metric.setUserName(userName);
            metric.setQuestion(message);
            metric.setModel("greeting");
            metric.setPromptVersion(promptLoader.getPromptVersion());
            metric.setFirstTokenMs(0L);
            metric.setTotalMs(0L);
            metric.setOutputChars(htmlResult.length());
            metric.setOutputTokens(estimateTokens(htmlResult));
            metric.setAccuracyScore(null);
            metric.setCreatedAt(LocalDateTime.now());
            chatMetricService.saveMetric(metric);
            return;
        }

        // 构造 prompt（系统指令 + 用户问题）
        String systemContent = promptLoader.getSystemPrompt();
        Prompt prompt = new Prompt(
                List.of(
                        new org.springframework.ai.chat.messages.SystemMessage(systemContent),
                        new UserMessage(message)
                )
        );
        //获取返回信息流
        long startNs = System.nanoTime();
        final long[] firstTokenNs = { -1L };

        Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);
        //提取信息，转为List<String>类型
        List<String> list = streamResponse.toStream().map(chatResponse -> {
            String content = chatResponse.getResult().getOutput().getContent();
            String safeContent = sanitizeChunk(content);
            if (safeContent != null && !safeContent.isEmpty() && firstTokenNs[0] < 0) {
                firstTokenNs[0] = System.nanoTime();
            }
            SSEServer.sendMessage(userName, safeContent, SSEMsgType.ADD);//调用sseServer类向客户端主动推送结果
            log.info(safeContent);
            return safeContent;
        }).collect(Collectors.toList());

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
        metric.setModel(modelName);
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
        String[] greetings = new String[] {
                "你好", "您好", "在吗", "hello", "hi", "hey", "早上好", "晚上好"
        };
        for (String g : greetings) {
            if (normalized.equals(g)) {
                return true;
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
