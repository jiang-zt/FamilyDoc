package com.itzixi.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DashscopeChatClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.model}")
    private String model;

    @Value("${dashscope.temperature:0.2}")
    private double temperature;

    public DashscopeChatClient(WebClient dashscopeWebClient) {
        this.webClient = dashscopeWebClient;
    }

    public String getModel() {
        return model;
    }

    public String chat(List<Map<String, String>> messages) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("stream", false);

        String response = webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return extractContent(response);
    }

    public Flux<String> stream(List<Map<String, String>> messages) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("stream", true);

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(this::parseSseChunk);
    }

    private Flux<String> parseSseChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return Flux.empty();
        }
        String[] lines = chunk.split("\\r?\\n");
        return Flux.fromArray(lines)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .filter(data -> !data.isEmpty())
                .flatMap(data -> {
                    if ("[DONE]".equals(data)) {
                        return Flux.empty();
                    }
                    String delta = extractDelta(data);
                    if (delta == null || delta.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.just(delta);
                });
    }

    private String extractContent(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("message").path("content").asText("");
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }

    private String extractDelta(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String delta = choices.get(0).path("delta").path("content").asText("");
                if (delta != null && !delta.isEmpty()) {
                    return delta;
                }
                return choices.get(0).path("message").path("content").asText("");
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }
}
