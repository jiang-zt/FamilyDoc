package com.itzixi.apitest;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractFileTest {

    @Test
    void manualContractContainsCoreDeepseekDoctorEndpoints() throws IOException {
        Path contract = Path.of("contracts/deepseek-doctor.openapi.yaml");
        assertThat(contract).exists();

        Map<String, Object> openApi;
        try (InputStream inputStream = Files.newInputStream(contract)) {
            openApi = new Yaml().load(inputStream);
        }

        assertThat(openApi).containsEntry("openapi", "3.0.3");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");

        assertThat(paths)
                .containsKeys(
                        "/auth/register",
                        "/auth/login",
                        "/auth/me",
                        "/auth/chat-metrics",
                        "/chat",
                        "/chat/records",
                        "/sse/connect",
                        "/sse/getOnlineCounts"
                );
    }
}
