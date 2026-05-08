package com.itzixi.apitest.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TestDataCleaner {

    private static final String TEST_USER_PREFIX = "api_test_";
    private static final Set<String> REGISTERED_USERNAMES = ConcurrentHashMap.newKeySet();

    private TestDataCleaner() {
    }

    public static void remember(AuthSession session) {
        remember(session.username());
    }

    public static void remember(String username) {
        if (isTestUsername(username)) {
            REGISTERED_USERNAMES.add(username);
        }
    }

    public static void cleanupRegisteredUsers() {
        if (!TestConfig.cleanupEnabled() || REGISTERED_USERNAMES.isEmpty()) {
            return;
        }

        Set<String> usernames = new LinkedHashSet<>(REGISTERED_USERNAMES);
        try (Connection connection = DriverManager.getConnection(
                TestConfig.cleanupJdbcUrl(),
                TestConfig.cleanupUsername(),
                TestConfig.cleanupPassword()
        )) {
            connection.setAutoCommit(false);
            for (String username : usernames) {
                deleteForUsername(connection, username);
                REGISTERED_USERNAMES.remove(username);
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clean API test users: " + usernames, e);
        }
    }

    private static void deleteForUsername(Connection connection, String username) throws SQLException {
        if (!isTestUsername(username)) {
            return;
        }

        executeDelete(connection, "DELETE FROM chat_record WHERE family_member = ?", username);
        executeDelete(connection, "DELETE FROM chat_metric WHERE user_name = ?", username);
        executeDelete(connection, "DELETE FROM app_user WHERE username = ?", username);
    }

    private static void executeDelete(Connection connection, String sql, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.executeUpdate();
        }
    }

    private static boolean isTestUsername(String username) {
        return username != null && username.startsWith(TEST_USER_PREFIX);
    }
}
