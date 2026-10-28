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
    void streamChatReturnsDeltaThenDoneWithoutPersistingHistory() throws IOException {
        assumeTrue(TestConfig.chatTestsEnabled(), "Enable with -Dapi.chat.enabled=true.");
        AuthSession user = AuthSupport.registerRandomUser();
        String message = "SSE自动化测试-" + System.currentTimeMillis();
        // 无预先建立的 /sse/connect：响应本身就是本轮事件流，asString 等待连接完成。
        var response = given()
                .header("Authorization", "Bearer " + user.token())
                .contentType(ContentType.JSON)
                .body(Map.of("message", message))
                .when().post("/chat/stream")
                .then().statusCode(200).contentType("text/event-stream")
                .extract().response();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var events = new java.util.ArrayList<String>();
        StringBuilder reply = new StringBuilder();
        String requestId = null;
        for (String block : response.asString().replace("\r\n", "\n").split("\n\n")) {
            String name = null;
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) name = line.substring(6).trim();
                if (line.startsWith("data:")) data = line.substring(5).stripLeading();
            }
            if (name == null) continue;
            assertThat(name).isIn("delta", "done");
            var payload = mapper.readTree(data);
            String id = payload.path("requestId").asText();
            assertThat(id).isNotBlank();
            if (requestId == null) requestId = id;
            assertThat(id).isEqualTo(requestId);
            events.add(name);
            if ("delta".equals(name)) reply.append(payload.path("text").asText());
        }
        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1)).isEqualTo("done");
        assertThat(events.stream().filter("done"::equals).count()).isEqualTo(1);
        assertThat(reply.toString()).isNotBlank();
        List<Map<String, Object>> records = given()
                .header("Authorization", "Bearer " + user.token())
                .when().get("/chat/records")
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(records).isEmpty(); // 聊天历史仅在客户端，指标仍独立落库。
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

    private void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling SSE state", e);
        }
    }
}
