package com.itzixi.evaluation.client;

import com.itzixi.evaluation.model.EvaluationModels.*;
import com.itzixi.evaluation.support.JsonSupport;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * One non-streaming OpenAI-compatible chat-completions request. No implicit
 * retries.
 */
@FunctionalInterface
public interface ModelClient {
    Completion complete(List<Message> messages, Duration timeout);

    default Map<String, Object> provenance() {
        return Map.of("transport", "injected_client");
    }

    static ModelClient http(Settings settings) {
        return new HttpModelClient(settings);
    }

    record Settings(URI endpoint, String model, String apiKey, double temperature, int maxTokens) {
        public Settings {
            if (endpoint == null || !List.of("http", "https").contains(endpoint.getScheme())
                    || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null)
                throw new IllegalArgumentException(
                        "Invalid model endpoint; use a full chat/completions URL without credentials/query");
            if (model == null || model.isBlank() || apiKey == null || !Double.isFinite(temperature)
                    || temperature < 0 || temperature > 2 || maxTokens <= 0)
                throw new IllegalArgumentException("Invalid model configuration");
            if (apiKey.isBlank() && !List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(endpoint.getHost()))
                throw new IllegalArgumentException("API key required for remote model endpoint");
        }

        public static Settings fromEnvironment(Map<String, String> env, String role, double defaultTemperature) {
            String prefix = "EVAL_" + role + "_";
            String endpoint = required(env, prefix + "ENDPOINT"), model = required(env, prefix + "MODEL");
            try {
                return new Settings(URI.create(endpoint), model, env.getOrDefault(prefix + "API_KEY", ""),
                        Double.parseDouble(
                                env.getOrDefault(prefix + "TEMPERATURE", Double.toString(defaultTemperature))),
                        Integer.parseInt(
                                env.getOrDefault(prefix + "MAX_TOKENS", role.equals("GENERATOR") ? "1024" : "2048")));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Invalid " + prefix + " configuration (endpoint/model/key/temperature/max_tokens)");
            }
        }

        private static String required(Map<String, String> env, String key) {
            if (!env.containsKey(key) || env.get(key).isBlank())
                throw new IllegalArgumentException("Missing environment variable " + key);
            return env.get(key);
        }

        public Map<String, Object> provenance() {
            return Map.of("endpoint", endpoint.toString(), "model", model,
                    "temperature", temperature, "max_tokens", maxTokens, "transport", "chat_completions_http");
        }

        @Override
        public String toString() {
            return "ModelSettings[model=" + model + ", apiKey=REDACTED]";
        }
    }

    final class ModelException extends RuntimeException {
        private final boolean retryable, safeToRetry;

        public ModelException(String code, boolean retryable, boolean safeToRetry) {
            super(code);
            this.retryable = retryable;
            this.safeToRetry = safeToRetry;
        }

        public boolean retryable() {
            return retryable;
        }

        public boolean safeToRetry() {
            return safeToRetry;
        }
    }

    final class HttpModelClient implements ModelClient {
        private final Settings settings;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        private HttpModelClient(Settings settings) {
            this.settings = settings;
        }

        @Override
        public Map<String, Object> provenance() {
            return settings.provenance();
        }

        @Override
        public Completion complete(List<Message> messages, Duration timeout) {
            if (timeout.isNegative() || timeout.isZero())
                throw new ModelException("SUITE_TIMEOUT", false, false);
            String body = JsonSupport.stringify(Map.of("model", settings.model(), "messages",
                    messages.stream().map(Message::json).toList(),
                    "temperature", settings.temperature(), "max_tokens", settings.maxTokens(), "stream", false));
            var builder = HttpRequest.newBuilder(settings.endpoint()).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (!settings.apiKey().isBlank())
                builder.header("Authorization", "Bearer " + settings.apiKey());
            long start = System.nanoTime();
            var future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final HttpResponse<String> response;
            try {
                response = future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw new ModelException("REQUEST_TIMEOUT", true, false);
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new ModelException("INTERRUPTED", false, false);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                boolean beforeAcceptance = cause instanceof ConnectException
                        || cause instanceof HttpConnectTimeoutException;
                throw new ModelException(beforeAcceptance ? "CONNECT_ERROR" : "TRANSPORT_ERROR", true,
                        beforeAcceptance);
            }
            int status = response.statusCode();
            if (status != 200)
                throw new ModelException("HTTP_" + status,
                        status == 429 || status == 502 || status == 503 || status == 504, status == 429);
            try {
                if (response.body().length() > 2_097_152)
                    throw new IllegalArgumentException("Oversized response");
                var envelope = JsonSupport.object(response.body());
                var choices = JsonSupport.list(envelope.get("choices"));
                if (choices.size() != 1)
                    throw new IllegalArgumentException("Expected one completion");
                var choice = JsonSupport.asObject(choices.getFirst());
                if (!"stop".equals(choice.get("finish_reason")))
                    throw new ModelException("INCOMPLETE_RESPONSE", false, false);
                String content = JsonSupport.text(JsonSupport.child(choice, "message"), "content");
                String id = envelope.get("id") instanceof String value ? value
                        : response.headers().firstValue("x-request-id").orElse("");
                String model = envelope.get("model") instanceof String value ? value : settings.model();
                Map<String, Object> usage = envelope.get("usage") instanceof Map<?, ?>
                        ? JsonSupport.asObject(envelope.get("usage"))
                        : Map.of();
                return new Completion(content, id, model, Duration.ofNanos(System.nanoTime() - start).toMillis(),
                        usage);
            } catch (IllegalArgumentException exception) {
                throw new ModelException("INVALID_PROVIDER_RESPONSE", false, false);
            }
        }
    }
}
