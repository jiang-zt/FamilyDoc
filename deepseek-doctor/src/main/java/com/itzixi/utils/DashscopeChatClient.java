package com.itzixi.utils;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import io.reactivex.Flowable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DashscopeChatClient {

    private final Generation generation = new Generation();

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.model}")
    private String model;

    @Value("${dashscope.temperature:0.2}")
    private double temperature;

    @Value("${dashscope.mock.enabled:false}")
    private boolean mockEnabled;

    @Value("${dashscope.mock.response:这是测试模式下的固定回复。}")
    private String mockResponse;

    @Value("${dashscope.mock.first-token-delay-ms:80}")
    private long mockFirstTokenDelayMs;

    @Value("${dashscope.mock.chunk-delay-ms:40}")
    private long mockChunkDelayMs;

    @Value("${dashscope.mock.chunk-size:6}")
    private int mockChunkSize;

    public String getModel() {
        return model;
    }

    public String chat(List<Map<String, String>> messages) {
        if (mockEnabled) {
            return buildMockResponse(messages);
        }
        try {
            GenerationParam param = buildParam(messages, false);
            GenerationResult result = generation.call(param);
            return extractContent(result);
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            throw new IllegalStateException("DashScope chat call failed: " + e.getMessage(), e);
        }
    }

    public Flux<String> stream(List<Map<String, String>> messages) {
        if (mockEnabled) {
            return buildMockStream(messages);
        }
        try {
            GenerationParam param = buildParam(messages, true);
            Flowable<GenerationResult> result = generation.streamCall(param);
            return Flux.from(result)
                    .map(this::extractContent)
                    .filter(content -> content != null && !content.isBlank());
        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            return Flux.error(new IllegalStateException("DashScope stream call failed: " + e.getMessage(), e));
        }
    }

    private Flux<String> buildMockStream(List<Map<String, String>> messages) {
        String response = buildMockResponse(messages);
        List<String> chunks = chunkText(response, Math.max(1, mockChunkSize));
        Flux<String> chunkFlux = Flux.fromIterable(chunks);
        if (mockChunkDelayMs > 0) {
            chunkFlux = chunkFlux.delayElements(Duration.ofMillis(mockChunkDelayMs));
        }
        if (mockFirstTokenDelayMs > 0) {
            chunkFlux = chunkFlux.delaySubscription(Duration.ofMillis(mockFirstTokenDelayMs));
        }
        return chunkFlux;
    }

    private String buildMockResponse(List<Map<String, String>> messages) {
        String latestUserInput = messages.stream()
                .filter(m -> Role.USER.getValue().equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .reduce((first, second) -> second)
                .orElse("");
        if (latestUserInput.isBlank()) {
            return mockResponse;
        }
        return mockResponse + " 用户输入摘要: " + latestUserInput;
    }

    private List<String> chunkText(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(text.length(), i + chunkSize);
            chunks.add(text.substring(i, end));
        }
        return chunks;
    }

    private GenerationParam buildParam(List<Map<String, String>> messages, boolean stream) {
        List<Message> sdkMessages = messages.stream()
                .map(this::toMessage)
                .collect(Collectors.toList());

        return GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .messages(sdkMessages)
                .temperature((float) temperature)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .incrementalOutput(stream)
                .build();
    }

    private Message toMessage(Map<String, String> message) {
        String role = message.getOrDefault("role", Role.USER.getValue());
        String content = message.getOrDefault("content", "");
        return Message.builder()
                .role(role)
                .content(content)
                .build();
    }

    private String extractContent(GenerationResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()
                || result.getOutput().getChoices().get(0).getMessage() == null) {
            return "";
        }
        String content = result.getOutput().getChoices().get(0).getMessage().getContent();
        return content == null ? "" : content;
    }
}
