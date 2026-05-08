package com.itzixi.apitest.support;

public final class TestConfig {

    private TestConfig() {
    }

    public static String baseUrl() {
        //操作系统环境变量 系统属性
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

    public static boolean cleanupEnabled() {
        String value = firstText(System.getProperty("api.cleanup.enabled"), System.getenv("API_CLEANUP_ENABLED"));
        return value == null || booleanValue(value);
    }

    public static String cleanupJdbcUrl() {
        String value = firstText(System.getProperty("api.cleanup.jdbcUrl"), System.getenv("API_CLEANUP_JDBC_URL"));
        return value == null
                ? "jdbc:mysql://127.0.0.1:3306/deepseek_doctor?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                : value;
    }

    public static String cleanupUsername() {
        String value = firstText(System.getProperty("api.cleanup.username"), System.getenv("API_CLEANUP_USERNAME"));
        return value == null ? "root" : value;
    }

    public static String cleanupPassword() {
        String value = firstText(System.getProperty("api.cleanup.password"), System.getenv("API_CLEANUP_PASSWORD"));
        return value == null ? "12345678" : value;
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
