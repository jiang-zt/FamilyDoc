package com.itzixi.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class PromptLoader {

    private static final String PROMPT_FILE = "my_doctor";
    private String cachedSystemPrompt;
    private String cachedVersion;

    public String getSystemPrompt() {
        if (cachedSystemPrompt == null) {
            loadPrompt();
        }
        return cachedSystemPrompt;
    }

    public String getPromptVersion() {
        if (cachedVersion == null) {
            loadPrompt();
        }
        return cachedVersion;
    }

    private synchronized void loadPrompt() {
        if (cachedSystemPrompt != null && cachedVersion != null) {
            return;
        }

        String content = readFile();
        cachedSystemPrompt = extractSystemBlock(content);
        cachedVersion = extractFromLine(content);
    }

    private String readFile() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_FILE);
            byte[] bytes = resource.getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt file: " + PROMPT_FILE, e);
        }
    }

    private String extractSystemBlock(String content) {
        String marker = "SYSTEM \"\"\"";
        int start = content.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = content.indexOf("\"\"\"", start);
        if (end < 0) {
            return content.substring(start).trim();
        }
        return content.substring(start, end).trim();
    }

    private String extractFromLine(String content) {
        String[] lines = content.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("FROM ")) {
                return trimmed.substring(5).trim();
            }
        }
        return "unknown";
    }
}
