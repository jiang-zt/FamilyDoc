package com.itzixi.apitest;

import com.itzixi.apitest.support.ApiTestBase;
import com.itzixi.apitest.support.AuthSession;
import com.itzixi.apitest.support.AuthSupport;
import com.itzixi.apitest.support.TestConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChatApiTest extends ApiTestBase {

    @Test
    void chatRequiresAuthentication() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("message", "你好"))
                .when()
                .post("/chat")
                .then()
                .statusCode(401);
    }

    @Test
    void chatRejectsBlankMessageWhenAuthenticated() {
        AuthSession user = AuthSupport.registerRandomUser();

        given()
                .header("Authorization", "Bearer " + user.token())
                .contentType(ContentType.JSON)
                .body(Map.of("message", "   "))
                .when()
                .post("/chat")
                .then()
                .statusCode(400);
    }

    @Test
    void chatReturnsTextWhenChatTestIsExplicitlyEnabled() {
        assumeTrue(
                TestConfig.chatTestsEnabled(),
                "Chat success test is disabled because it calls the model layer. Enable with -Dapi.chat.enabled=true."
        );
        AuthSession user = AuthSupport.registerRandomUser();

        String response = given()
                .header("Authorization", "Bearer " + user.token())
                .contentType(ContentType.JSON)
                .body(Map.of("message", "我有点头疼，应该怎么办？"))
                .when()
                .post("/chat")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertThat(response).isNotBlank();
    }

    @Test
    void recordsRequireAuthentication() {
        given()
                .when()
                .get("/chat/records")
                .then()
                .statusCode(401);

        given()
                .when()
                .delete("/chat/records")
                .then()
                .statusCode(401);
    }
}
