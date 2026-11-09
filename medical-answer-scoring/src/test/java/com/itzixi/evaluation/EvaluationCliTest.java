package com.itzixi.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationCliTest {
    @TempDir Path directory;

    @Test
    void helpDoesNotRequireAConfiguration() {
        var result = invoke("--help");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("--suite", "--validate-only");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void missingUnknownAndRepeatedArgumentsAreUsageErrors() {
        assertThat(invoke().code()).isEqualTo(2);
        assertThat(invoke("--suite").code()).isEqualTo(2);
        assertThat(invoke("--unknown").code()).isEqualTo(2);
        assertThat(invoke("--suite", "a", "--suite", "b").code()).isEqualTo(2);
        assertThat(invoke("--validate-only", "--validate-only").code()).isEqualTo(2);
    }

    @Test
    void missingConfigurationFailsInsteadOfReturningAValidationSuccess() {
        var result = invoke("--suite", directory.resolve("missing.json").toString(), "--validate-only");
        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("CONFIG_ERROR", "missing.json");
        assertThat(result.out()).doesNotContain("CONFIG_VALID");
    }

    @Test
    void validationIsExplicitlyDifferentFromAnExecutedSuite() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        var result = invoke("--suite", suite.toString(), "--validate-only");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("CONFIG_VALID", "smoke", "selected_cases=2",
                "evaluation_executed=false", "runtime_model_configuration");
        assertThat(result.out()).doesNotContain("suite_status=PASS");
        assertThat(Files.exists(directory.resolve("runs"))).isFalse();
    }

    @Test
    void executionRequiresExplicitRuntimeConfiguration() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        var result = invoke("--suite", suite.toString());
        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("RUNTIME_CONFIG_ERROR");
    }

    private Result invoke(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = new EvaluationCli(java.util.Map.of()).run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {}
}
