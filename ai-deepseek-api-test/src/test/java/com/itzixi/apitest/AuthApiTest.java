package com.itzixi.apitest;

import com.itzixi.apitest.support.ApiTestBase;
import com.itzixi.apitest.support.AuthSession;
import com.itzixi.apitest.support.AuthSupport;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.beans.Transient;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class AuthApiTest extends ApiTestBase {

    @Test
    void registerLoginAndGetCurrentUser() {
        String username = AuthSupport.uniqueUsername();
        String password = "Pwd123456";

        AuthSession registered = AuthSupport.registerUser(username, password);

        String loginToken = given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .when()
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("token");

        assertThat(loginToken).isNotBlank();

        String currentUsername = given()
                .header("Authorization", "Bearer " + registered.token())
                .when()
                .get("/auth/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("username");

        assertThat(currentUsername).isEqualTo(username);
    }

    @Test
    void registerRejectsBlankCredentials() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", "", "password", ""))
                .when()
                .post("/auth/register")
                .then()
                .statusCode(400);
    }

    @Test
    void registerRejectsDuplicateUsername() {
        String username = AuthSupport.uniqueUsername();
        String password = "Pwd123456";

        AuthSupport.registerUser(username, password);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .when()
                .post("/auth/register")
                .then()
                .statusCode(409);
    }

    @Test
    void authenticatedNonAdminCannotListUsers() {
        AuthSession user = AuthSupport.registerRandomUser();

        given()
                .header("Authorization", "Bearer " + user.token())
                .when()
                .get("/auth/users")
                .then()
                .statusCode(403);
    }

    @Test
    void protectedEndpointRejectsMissingToken() {
        given()
                .when()
                .get("/auth/me")
                .then()
                .statusCode(401);
    }

    ///auth/login 是公开接口，不应该因为请求头里带了错误 token 就拒绝登录。
    @Test
    void loginIgnoresInvalidTokenWhenCredentialsAreValid(){
        String username = AuthSupport.uniqueUsername();
        String password = "Pwd123456";

        AuthSession registered = AuthSupport.registerUser(username, password);

        String loginToken = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer invalid-token")//带无效token
                .body(Map.of("username", username, "password", password))
                .when()
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("token");

        assertThat(loginToken).isNotBlank();
    }
    
    //用户已经登录过，再次登录也应该成功。
    @Test
    void loginAcceptsRequestWithExistingValidToken() {
        String username = AuthSupport.uniqueUsername();
        String password = "Pwd123456";

        AuthSession registered = AuthSupport.registerUser(username, password);

        String loginToken = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + registered.token())
                .body(Map.of("username", username, "password", password))
                .when()
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("token");

        assertThat(loginToken).isNotBlank();
    }

    @Test
    void loginRejectsWrongPassword() {
        String username = AuthSupport.uniqueUsername();
        String password = "Pwd123456";

        AuthSupport.registerUser(username, password);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", "WrongPassword123"))
                .when()
                .post("/auth/login")
                .then()
                .statusCode(401);
    }
    
//用户名不存在时，不应该登录成功，也应该返回 401
    @Test
    void loginRejectsUnknownUser() {
        String username = AuthSupport.uniqueUsername();

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", "Pwd123456"))
                .when()
                .post("/auth/login")
                .then()
                .statusCode(401);
    }


}
