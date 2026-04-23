package com.itzixi.apitest.support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public abstract class ApiTestBase {

    @BeforeAll
    void configureRestAssured() {
        assumeTrue(
                TestConfig.apiTestsEnabled(),
                "API tests are disabled. Run with -Dapi.tests.enabled=true when deepseek-doctor is running."
        );
        RestAssured.baseURI = TestConfig.baseUrl();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }
}
