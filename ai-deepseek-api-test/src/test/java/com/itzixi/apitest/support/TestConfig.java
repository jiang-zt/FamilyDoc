package com.itzixi.apitest.support;

public final class TestConfig {

    private TestConfig() {
    }

    public static String baseUrl() {
        String value = firstText(System.getProperty("api.baseUrl"), System.getenv("API_BASE_URL"));
        String baseUrl = value == null ? "http://localhost:8080" : value;
        return stripTrailingSlash(baseUrl);
    }

    public static boolean apiTestsEnabled() {
        return booleanValue(firstText(System.getProperty("api.tests.enabled"), System.getenv("API_TESTS_ENABLED")));
    }

    public static boolean chatTestsEnabled() {
        return booleanValue(firstText(System.getProperty("api.chat.enabled"), System.getenv("API_CHAT_ENABLED")));
    }

    public static boolean evalTestsEnabled() {
        return booleanValue(firstText(System.getProperty("api.eval.enabled"), System.getenv("API_EVAL_ENABLED")));
    }

    private static String firstText(String first, String second) {
        if (hasText(first)) {
            return first;
        }
        if (hasText(second)) {
            return second;
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean booleanValue(String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value));
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
