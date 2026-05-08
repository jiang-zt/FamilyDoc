package com.itzixi.scoring;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class MedicalEvalCaseLoader {

    private static final String CASE_RESOURCE = "scoring/medical-eval-cases.yml";

    public List<MedicalEvalCase> loadCases() {
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(CASE_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing medical eval case resource: " + CASE_RESOURCE);
            }
            Object raw = new Yaml().load(inputStream);
            if (!(raw instanceof List<?> items)) {
                throw new IllegalStateException("Invalid medical eval case resource: " + CASE_RESOURCE);
            }
            return items.stream()
                    .map(item -> toCase(castMap(item)))
                    .toList();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load medical eval cases", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private MedicalEvalCase toCase(Map<String, Object> raw) {
        return new MedicalEvalCase(
                requiredText(raw, "id"),
                requiredText(raw, "category"),
                requiredText(raw, "rule_id"),
                requiredText(raw, "question")
        );
    }

    private String requiredText(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing medical eval case field: " + key);
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            throw new IllegalStateException("Blank medical eval case field: " + key);
        }
        return text;
    }
}
