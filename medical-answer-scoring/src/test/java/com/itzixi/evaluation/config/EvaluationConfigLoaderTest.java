package com.itzixi.evaluation.config;

import com.itzixi.evaluation.EvaluationConfigFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationConfigLoaderTest {
    @TempDir Path directory;
    private final EvaluationConfigLoader loader = new EvaluationConfigLoader();

    @Test
    void resolvesEachReferenceAgainstItsDeclaringFileAndPreservesSelectionOrder() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        var loaded = loader.load(suite);
        var entry = loaded.entries().getFirst();
        assertThat(loaded.suite().get("suite_id")).isEqualTo("smoke");
        assertThat(entry.taskPath()).isEqualTo(directory.resolve("tasks/basic-qa.json"));
        assertThat(entry.promptPath()).isEqualTo(directory.resolve("prompts/template.txt"));
        assertThat(entry.rubricPath()).isEqualTo(directory.resolve("tasks/rubrics/basic-qa-v2.json"));
        assertThat(entry.casesPath()).isEqualTo(directory.resolve("cases/source.jsonl"));
        assertThat(entry.caseIds()).containsExactly("0002", "0001");
        assertThat(loaded.pendingChecks()).contains("case_content_validation", "executor_and_evaluator_available",
                "task_version_and_case_contract_supported");
    }

    @Test
    void missingTaskRubricCasesAndPromptReferencesAreRejected() throws Exception {
        for (String reference : new String[]{"tasks/basic-qa.json", "tasks/rubrics/basic-qa-v2.json",
                "cases/source.jsonl", "prompts/template.txt"}) {
            Path fixture = directory.resolve(reference.replace('/', '_'));
            Path suite = EvaluationConfigFixture.create(fixture);
            Files.delete(fixture.resolve(reference));
            assertThatThrownBy(() -> loader.load(suite)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(reference.substring(reference.lastIndexOf('/') + 1));
        }
    }

    @Test
    void rejectsDuplicateOrEmptySelections() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        String original = Files.readString(suite);
        Files.writeString(suite, original.replace("[\"0002\", \"0001\"]", "[\"0001\", \"0001\"]"));
        assertThatThrownBy(() -> loader.load(suite)).hasMessageContaining("ids");
        Files.writeString(suite, original.replace("[\"0002\", \"0001\"]", "[]"));
        assertThatThrownBy(() -> loader.load(suite)).hasMessageContaining("ids");
    }

    @Test
    void rejectsUnsupportedSchemaAndConflictingSmokePolicy() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        String original = Files.readString(suite);
        Files.writeString(suite, original.replace("\"schema_version\": 1", "\"schema_version\": 99"));
        assertThatThrownBy(() -> loader.load(suite)).hasMessageContaining("schema_version");
        Files.writeString(suite, original.replace("\"allow_case_failures\": 0", "\"allow_case_failures\": 1"));
        assertThatThrownBy(() -> loader.load(suite)).hasMessageContaining("allow_case_failures");
    }

    @Test
    void rejectsNegativeRetriesAndNonpositiveTimeout() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        String original = Files.readString(suite);
        Files.writeString(suite, original.replace("\"suite_timeout_seconds\": 180", "\"suite_timeout_seconds\": 0"));
        assertThatThrownBy(() -> loader.load(suite)).hasMessageContaining("suite_timeout_seconds");
        Files.writeString(suite, original.replace("\"retries_on_infra_error\": 1", "\"retries_on_infra_error\": -1"));
        assertThatThrownBy(() -> loader.load(suite)).hasMessageContaining("retries_on_infra_error");
    }

    @Test
    void rubricVersionRevisionCheckAndResponseTypesMustAgreeWithTask() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        Path rubric = directory.resolve("tasks/rubrics/basic-qa-v2.json");
        String original = Files.readString(rubric);
        for (String[] change : new String[][]{{"basic-qa-v2", "other-v1"}, {"1.0.0", "1.1.0"},
                {"content.relevance", "other_check"}, {"reflection", "comfort"}}) {
            Files.writeString(rubric, original.replace(change[0], change[1]));
            assertThatThrownBy(() -> loader.load(suite)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMalformedNonObjectAndDuplicateKeyConfigurations() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        for (String invalid : new String[]{"{\"suite_id\":", "[]", "{} {}",
                "{\"suite_id\":\"one\",\"suite_id\":\"two\"}"}) {
            Files.writeString(suite, invalid);
            assertThatThrownBy(() -> loader.load(suite)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsAnUnsupportedPathBaseInsteadOfSilentlyUsingTheWorkingDirectory() throws Exception {
        Path suite = EvaluationConfigFixture.create(directory);
        Files.writeString(suite, Files.readString(suite).replace("suite_file_directory", "repository_root"));
        assertThatThrownBy(() -> loader.load(suite)).hasMessageContaining("path_base");
    }
}
