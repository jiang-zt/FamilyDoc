package com.itzixi.apitest.support;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public final class AuthSupport {

    private AuthSupport() {
    }

    public static AuthSession registerRandomUser() {
        String username = uniqueUsername();
        return registerUser(username, "Pwd123456");
    }

    public static AuthSession registerUser(String username, String password) {
        Response response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .when()
                .post("/auth/register")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String token = response.jsonPath().getString("token");
        String returnedUsername = response.jsonPath().getString("user.username");
        String userId = response.jsonPath().getString("user.id");

        assertThat(token).isNotBlank();
        assertThat(returnedUsername).isEqualTo(username);
        assertThat(userId).isNotBlank();

        AuthSession session = new AuthSession(token, returnedUsername, userId);
        TestDataCleaner.remember(session);
        return session;
    }

    public static String uniqueUsername() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "api_test_" + suffix;
    }
}
