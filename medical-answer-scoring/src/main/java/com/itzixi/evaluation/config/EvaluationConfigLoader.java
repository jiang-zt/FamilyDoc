package com.itzixi.evaluation.config;

import com.itzixi.evaluation.support.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the suite -> task -> rubric configuration graph without running models.
 * Performs strict JSON parsing and explicit field/reference validation.
 * SuiteRunner.prepare additionally validates cases, prompt and runtime support.
 */
public final class EvaluationConfigLoader {
    public LoadedSuite load(Path suitePath) {
        Path path = suitePath.toAbsolutePath().normalize();
        Map<String, Object> suite = readObject(path);
        schemaVersion(suite, path);
        text(suite, "suite_id", path);
        text(suite, "suite_version", path);
        Map<String, Object> references = object(suite.get("reference_policy"), path, "reference_policy");
        expected(references, "path_base", "suite_file_directory", path);
        expected(references, "on_invalid_reference", "fail_suite", path);
        validateSuitePolicy(suite, path);

        var entries = new ArrayList<LoadedEntry>();
        for (Object value : array(suite.get("entries"), path, "entries")) {
            Map<String, Object> entry = object(value, path, "entries[]");
            Path taskPath = reference(path, text(entry, "task_path", path));
            Map<String, Object> task = readObject(taskPath);
            schemaVersion(task, taskPath);
            text(task, "task_id", taskPath);
            text(task, "task_version", taskPath);
            text(task, "target", taskPath);
            text(task, "executor", taskPath);
            Map<String, Object> contract = object(task.get("case_contract"), taskPath, "case_contract");
            text(contract, "version", taskPath);
            expected(contract, "selection_owner", "suite", taskPath);

            Map<String, Object> cases = object(entry.get("cases"), path, "cases");
            Path casesPath = reference(path, text(cases, "path", path));
            Map<String, Object> select = object(cases.get("select"), path, "select");
            expected(select, "order", "listed_ids", path);
            List<String> ids = strings(select.get("ids"), path, "select.ids");

            Map<String, Object> evaluation = object(task.get("evaluation"), taskPath, "evaluation");
            expected(evaluation, "path_base", "task_file_directory", taskPath);
            Path rubricPath = reference(taskPath, text(evaluation, "rubric_path", taskPath));
            Map<String, Object> rubric = readObject(rubricPath);
            schemaVersion(rubric, rubricPath);
            for (String field : List.of("rubric_version", "rubric_revision")) {
                String requested = text(evaluation, field, taskPath);
                if (!requested.equals(text(rubric, field, rubricPath))) {
                    throw invalid(taskPath, field, "does not match " + rubricPath);
                }
            }
            strings(evaluation.get("checks"), taskPath, "evaluation.checks");
            expected(evaluation,"result_contract","check-results-v1",taskPath);
            expected(rubric,"result_contract","check-results-v1",rubricPath);
            expected(evaluation,"pass_policy","all_checks_pass",taskPath);
            var aggregation=object(rubric.get("aggregation"),rubricPath,"aggregation");
            expected(aggregation,"mode","all_checks_pass",rubricPath);
            if (!Boolean.FALSE.equals(aggregation.get("weights_enabled")))
                throw invalid(rubricPath,"weights_enabled","weighted grading is not enabled");
            Map<String, Object> executor = object(task.get("executor_config"), taskPath, "executor_config");
            List<String> responseTypes = strings(executor.get("response_types"), taskPath, "response_types");
            Map<String, Object> inputs = object(rubric.get("inputs"), rubricPath, "inputs");
            if (!new HashSet<>(responseTypes).equals(new HashSet<>(
                    strings(inputs.get("response_types"), rubricPath, "inputs.response_types")))) {
                throw invalid(rubricPath, "inputs.response_types", "must match task response_types");
            }
            Map<String, Object> prompt = object(executor.get("prompt"), taskPath, "executor_config.prompt");
            expected(prompt, "path_base", "task_file_directory", taskPath);
            expected(prompt, "format", "modelfile", taskPath);
            Path promptPath = reference(taskPath, text(prompt, "source_path", taskPath));
            var loadedEntry=new LoadedEntry(taskPath,task,rubricPath,rubric,casesPath,ids,promptPath);
            com.itzixi.evaluation.judge.ResponseJudge.rules(loadedEntry);
            entries.add(loadedEntry);
        }
        return new LoadedSuite(path, suite, entries, List.of(
                "case_content_validation", "selected_case_ids_exist_in_source", "prompt_render_validation",
                "task_version_and_case_contract_supported", "executor_and_evaluator_available",
                "runtime_model_configuration"));
    }

    private static void validateSuitePolicy(Map<String, Object> suite, Path path) {
        Map<String, Object> policy = object(suite.get("pass_policy"), path, "pass_policy");
        expected(policy, "mode", "all_cases_pass", path);
        if (integer(policy, "allow_case_failures", path) != 0) {
            throw invalid(path, "allow_case_failures", "all_cases_pass requires zero allowed failures");
        }
        if (!Boolean.TRUE.equals(policy.get("require_all_selected_cases_executed"))) {
            throw invalid(path, "require_all_selected_cases_executed", "must be true");
        }
        expected(policy, "required_case_status", "PASS", path);
        expected(suite, "on_failure", "block_deploy", path);
        Map<String, Object> execution = object(suite.get("execution"), path, "execution");
        if (integer(execution, "suite_timeout_seconds", path) <= 0) {
            throw invalid(path, "suite_timeout_seconds", "must be positive");
        }
        if (integer(execution, "retries_on_infra_error", path) < 0) {
            throw invalid(path, "retries_on_infra_error", "must be nonnegative");
        }
    }

    private static Map<String, Object> readObject(Path path) {
        requireFile(path);
        try {
            return JsonSupport.object(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException exception) {
            // Do not echo parser snippets: configuration may eventually contain
            // credentials.
            throw new IllegalArgumentException("Cannot parse configuration: " + path, exception);
        }
    }

    private static Path reference(Path declaringFile, String value) {
        final Path result;
        try {
            result = declaringFile.getParent().resolve(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException exception) {
            throw invalid(declaringFile, "reference", "invalid local file path");
        }
        requireFile(result);
        return result;
    }

    private static void requireFile(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("Configuration/reference file is missing or unreadable: " + path);
        }
    }

    private static void schemaVersion(Map<String, Object> value, Path path) {
        if (integer(value, "schema_version", path) != 1) {
            throw invalid(path, "schema_version", "only version 1 is supported");
        }
    }

    private static Map<String, Object> object(Object value, Path path, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(path, field, "must be an object");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(path, field, "object keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String text(Map<String, Object> value, String field, Path path) {
        if (!(value.get(field) instanceof String text) || text.isBlank()) {
            throw invalid(path, field, "must be a nonblank string");
        }
        return text;
    }

    private static long integer(Map<String, Object> value, String field, Path path) {
        Object number = value.get(field);
        if (number instanceof Number numeric) {
            try {
                return new java.math.BigDecimal(numeric.toString()).longValueExact();
            } catch (ArithmeticException exception) {
                throw invalid(path, field, "must be an integer");
            }
        }
        throw invalid(path, field, "must be an integer");
    }

    private static List<?> array(Object value, Path path, String field) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw invalid(path, field, "must be a nonempty array");
        }
        return list;
    }

    private static List<String> strings(Object value, Path path, String field) {
        var result = new ArrayList<String>();
        Set<String> seen = new HashSet<>();
        for (Object item : array(value, path, field)) {
            if (!(item instanceof String text) || text.isBlank() || !seen.add(text)) {
                throw invalid(path, field, "must contain unique nonblank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static void expected(Map<String, Object> value, String field, String expected, Path path) {
        if (!expected.equals(text(value, field, path))) {
            throw invalid(path, field, "expected " + expected);
        }
    }

    private static IllegalArgumentException invalid(Path path, String field, String explanation) {
        return new IllegalArgumentException(path + " [" + field + "]: " + explanation);
    }

    public record LoadedSuite(Path suitePath, Map<String, Object> suite, List<LoadedEntry> entries,
            List<String> pendingChecks) {
        public LoadedSuite {
            suite = Collections.unmodifiableMap(new LinkedHashMap<>(suite));
            entries = List.copyOf(entries);
            pendingChecks = List.copyOf(pendingChecks);
        }
    }

    public record LoadedEntry(Path taskPath, Map<String, Object> task, Path rubricPath,
            Map<String, Object> rubric, Path casesPath, List<String> caseIds, Path promptPath) {
        public LoadedEntry {
            task = Collections.unmodifiableMap(new LinkedHashMap<>(task));
            rubric = Collections.unmodifiableMap(new LinkedHashMap<>(rubric));
            caseIds = List.copyOf(caseIds);
        }
    }
}
