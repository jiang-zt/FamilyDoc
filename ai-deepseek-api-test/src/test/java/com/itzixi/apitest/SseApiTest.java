package com.itzixi.apitest;

import com.itzixi.apitest.support.ApiTestBase;
import com.itzixi.apitest.support.AuthSession;
import com.itzixi.apitest.support.AuthSupport;
import com.itzixi.apitest.support.SseTestClient;
import com.itzixi.apitest.support.TestConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SseApiTest extends ApiTestBase {

    @Test
    void onlineCountEndpointIsPublicAndReturnsANumber() {
        String body = given()
                .when()
                .get("/sse/getOnlineCounts")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertThat(body).isNotBlank();
        assertThat(body.trim()).matches("\\d+");
    }

    @Test
    void sseConnectRequiresAuthentication() {
        given()
                .queryParam("userId", "api_test_user")
                .when()
                .get("/sse/connect")
                .then()
                .statusCode(401);
    }

    @Test
    void sseConnectRejectsAuthenticatedUserIdMismatch() {
        AuthSession user = AuthSupport.registerRandomUser();

        given()
                .header("Authorization", "Bearer " + user.token())
                .queryParam("userId", user.username() + "_other")
                .when()
                .get("/sse/connect")
                .then()
                .statusCode(403);
    }

    @Test
    void authenticatedSseConnectReturnsEventStreamAndTracksOnlineCount() throws IOException {
        AuthSession user = AuthSupport.registerRandomUser();
        int initialOnlineCount = onlineCount();

        try (SseTestClient.Connection connection = openSseConnection(user)) {
            awaitOnlineCount(initialOnlineCount + 1, Duration.ofSeconds(3));
            assertThat(connection.contentType()).startsWith("text/event-stream");

            stopSse(user.username());
            connection.awaitClosed(Duration.ofSeconds(3));
            awaitOnlineCount(initialOnlineCount, Duration.ofSeconds(3));
        }
    }

    @Test
    void sendMessageAddPushesTenOrderedAddEvents() throws IOException {
        AuthSession user = AuthSupport.registerRandomUser();
        int initialOnlineCount = onlineCount();

        try (SseTestClient.Connection connection = openSseConnection(user)) {
            awaitOnlineCount(initialOnlineCount + 1, Duration.ofSeconds(3));

            given()
                    .queryParam("userId", user.username())
                    .queryParam("message", "chunk")
                    .when()
                    .get("/sse/sendMessageAdd")
                    .then()
                    .statusCode(200);

            List<SseTestClient.SseEvent> events = connection.awaitEvents(
                    current -> current.size() >= 10,
                    Duration.ofSeconds(5)
            );

            assertThat(events).hasSizeGreaterThanOrEqualTo(10);
            List<SseTestClient.SseEvent> firstTenEvents = events.subList(0, 10);
            for (int i = 0; i < firstTenEvents.size(); i++) {
                SseTestClient.SseEvent event = firstTenEvents.get(i);
                assertThat(event.id()).isEqualTo(user.username());
                assertThat(event.event()).isEqualTo("add");
                assertThat(event.data()).isEqualTo("chunk-" + i);
            }

            stopSse(user.username());
            connection.awaitClosed(Duration.ofSeconds(3));
            awaitOnlineCount(initialOnlineCount, Duration.ofSeconds(3));
        }
    }

    @Test
    void stopServerRemovesOnlyTargetConnection() throws IOException {
        AuthSession userA = AuthSupport.registerRandomUser();
        AuthSession userB = AuthSupport.registerRandomUser();
        int initialOnlineCount = onlineCount();

        try (SseTestClient.Connection ignoredA = openSseConnection(userA);
             SseTestClient.Connection ignoredB = openSseConnection(userB)) {
            awaitOnlineCount(initialOnlineCount + 2, Duration.ofSeconds(3));

            stopSse(userA.username());
            awaitOnlineCount(initialOnlineCount + 1, Duration.ofSeconds(3));
            assertOnlineCountStays(initialOnlineCount + 1, Duration.ofMillis(400));

            stopSse(userB.username());
            awaitOnlineCount(initialOnlineCount, Duration.ofSeconds(3));
        }
    }

    @Test
    void streamChatEmitsAddThenFinishAndPersistsJoinedReply() throws IOException {
        assumeTrue(
                TestConfig.chatTestsEnabled(),
                "Stream chat test is disabled because it calls the model layer. Enable with -Dapi.chat.enabled=true."
        );

        AuthSession user = AuthSupport.registerRandomUser();
        String message = "SSE自动化测试-" + System.currentTimeMillis();
        // 删除历史聊天记录
        given()
                .header("Authorization", "Bearer " + user.token())
                .when()
                .delete("/chat/records")
                .then()
                .statusCode(200);
        // 建立连接并发送请求
        try (SseTestClient.Connection connection = openSseConnection(user)) {
            given()
                    .header("Authorization", "Bearer " + user.token())
                    .contentType(ContentType.JSON)
                    .body(Map.of("message", message))
                    .when()
                    .post("/chat/stream")
                    .then()
                    .statusCode(200);
            // 等待流式响应完成
            List<SseTestClient.SseEvent> finishedEvents = connection.awaitEvents(
                    current -> current.stream().anyMatch(event -> "finish".equals(event.event())),
                    Duration.ofSeconds(20)
            );
            assertThat(finishedEvents).isNotEmpty();
            // 等待全部发送完成
            Thread.sleep(200);
            // 获取响应
            List<SseTestClient.SseEvent> events = connection.snapshot();
            //验证最后一个事件是finish
            assertThat(events).isNotEmpty();
            assertThat(events).last().extracting(SseTestClient.SseEvent::event).isEqualTo("finish");
            //验证finish事件只有1个
            assertThat(events.stream().filter(event -> "finish".equals(event.event())).count()).isEqualTo(1);

            //验证finish事件不是第一个
            int finishIndex = indexOfFinish(events);
            assertThat(finishIndex).isGreaterThan(0);
            //验证finish事件前全是add事件
            List<SseTestClient.SseEvent> addEvents = events.subList(0, finishIndex);
            assertThat(addEvents).allMatch(event -> "add".equals(event.event()));
            // 拼接回复并验证回复内容不为空
            String joinedReply = addEvents.stream()
                    .map(SseTestClient.SseEvent::data)
                    .reduce("", String::concat);
            assertThat(joinedReply).isNotBlank();

            // 从数据库中获取的聊天记录
            List<Map<String, Object>> records = given()
                    .header("Authorization", "Bearer " + user.token())
                    .when()
                    .get("/chat/records")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getList("$");

            assertThat(records).hasSize(2);
            assertThat(records).anySatisfy(record -> {
                assertThat(record.get("chatType")).isEqualTo("user");
                assertThat(record.get("content")).isEqualTo(message);
            });
            //验证聊天记录落库
            assertThat(records).anySatisfy(record -> {
                assertThat(record.get("chatType")).isEqualTo("bot");
                String savedReply = String.valueOf(record.get("content"));
                assertThat(savedReply).isNotBlank();
                assertThat(savedReply).contains(message);
                assertThat(normalizeWhitespace(savedReply)).isEqualTo(normalizeWhitespace(joinedReply));
            });

            stopSse(user.username());
            connection.awaitClosed(Duration.ofSeconds(3));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for finish event", e);
        }
    }

    private SseTestClient.Connection openSseConnection(AuthSession user) throws IOException {
        return SseTestClient.connect(TestConfig.baseUrl(), user.username(), user.token());
    }

    private int onlineCount() {
        return Integer.parseInt(given()
                .when()
                .get("/sse/getOnlineCounts")
                .then()
                .statusCode(200)
                .extract()
                .asString()
                .trim());
    }

    private void stopSse(String userId) {
        given()
                .queryParam("userId", userId)
                .when()
                .get("/sse/stop")
                .then()
                .statusCode(200);
    }
    // 等待状态达成
    private void awaitOnlineCount(int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() <= deadline) {
            if (onlineCount() == expected) {
                return;
            }
            sleepBriefly();
        }
        assertThat(onlineCount()).isEqualTo(expected);
    }
    // 验证状态持续稳定
    private void assertOnlineCountStays(int expected, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() <= deadline) {
            assertThat(onlineCount()).isEqualTo(expected);
            sleepBriefly();
        }
    }

    private int indexOfFinish(List<SseTestClient.SseEvent> events) {
        for (int i = 0; i < events.size(); i++) {
            if ("finish".equals(events.get(i).event())) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling SSE state", e);
        }
    }
}
