package com.itzixi.apitest;

import com.itzixi.apitest.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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
}
