package com.itzixi.evaluation.model;

import com.itzixi.evaluation.config.EvaluationConfigLoader.LoadedEntry;
import com.itzixi.evaluation.config.EvaluationConfigLoader.LoadedSuite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Data shared by the offline pipeline; credentials never belong in these
 * records.
 */
public final class EvaluationModels {
    private EvaluationModels() {
    }

    public static final List<String> TYPES = List.of("reflection", "comfort", "reframing", "suggestion");
    public static final List<String> STRUCTURE_IDS = List.of("structure.html_fragment", "structure.four_slots",
            "structure.slot_order", "structure.nonempty_body");

    public enum Status {
        PASS, FAIL, ERROR, EVALUATION_ERROR, NEEDS_REVIEW, NOT_RUN, NOT_EVALUATED, SKIPPED
    }

    public record Message(String role, String content) {
        public Map<String, Object> json() {
            return Map.of("role", role, "content", content);
        }
    }

    public record Reference(int candidateIndex, String text) {
        public Map<String, Object> json() {
            return Map.of("candidate_index", candidateIndex, "text", text);
        }
    }

    public record HaiCase(String caseId, String struggle, Map<String, List<Reference>> references,
            Map<String, Object> source) {
    }

    public record BuiltPrompt(Path sourcePath, String sourceHash, String system, String sourceModel,
            double temperature, List<Message> messages) {
    }

    public record Completion(String content, String requestId, String model, long totalMs, Map<String, Object> usage) {
    }

    public record PreparedCase(LoadedEntry entry, HaiCase testCase, BuiltPrompt prompt, List<CheckDefinition> checks) {
    }

    public record PreparedSuite(LoadedSuite configuration, List<PreparedCase> cases, long startedNanos) {
    }

    public record ResponseResult(String responseType, Status status, List<String> evidenceQuotes,
            List<Integer> matchedReferenceIndices, String reason) {
        public Map<String, Object> json() {
            return Map.of("response_type", responseType, "status", status.name(),
                    "evidence_quotes", evidenceQuotes, "matched_reference_indices", matchedReferenceIndices, "reason",
                    reason);
        }
    }

    public record CheckDefinition(String checkId, String checkName, String dimension, String evaluatorType,
            String scope, String passCondition, String failCondition, String validatorId,
            Path promptPath, String dimensionPrompt) {
        public Map<String,Object> json() {
            return Map.of("check_id",checkId,"check_name",checkName,"dimension",dimension,
                    "evaluator_type",evaluatorType,"scope",scope,"pass_condition",passCondition,"fail_condition",failCondition);
        }
    }

    public record CheckResult(String checkId, String checkName, String dimension, String evaluatorType,
            String scope, Status status, Boolean passed, String reason, List<String> evidenceQuotes) {
        public Map<String,Object> json() {
            var value=new java.util.LinkedHashMap<String,Object>();
            value.put("check_id",checkId);value.put("check_name",checkName);value.put("dimension",dimension);
            value.put("evaluator_type",evaluatorType);value.put("scope",scope);value.put("status",status.name());
            value.put("passed",passed);value.put("reason",reason);value.put("evidence_quotes",evidenceQuotes);
            return value;
        }
        public static CheckResult of(CheckDefinition rule, Status status, Boolean passed, String reason, List<String> quotes) {
            return new CheckResult(rule.checkId(),rule.checkName(),rule.dimension(),rule.evaluatorType(),rule.scope(),status,passed,reason,List.copyOf(quotes));
        }
    }

    public record StructuralResult(Map<String,String> sections,List<CheckResult> checks) {}

    public record CaseResult(String taskId, String caseId, Status status, String rawAnswer,
            Map<String, String> sections, List<ResponseResult> responses, String error,List<CheckResult> checkResults) {
        public Map<String, Object> json() {
            return Map.of("task_id", taskId, "case_id", caseId, "status", status.name(),
                    "raw_answer", rawAnswer, "sections", sections, "responses",
                    responses.stream().map(ResponseResult::json).toList(), "error", error,
                    "evaluation_schema_version",2,"check_results",checkResults.stream().map(CheckResult::json).toList());
        }
    }

    public record SuiteResult(String runId, Status status, Path runDirectory, List<CaseResult> cases,
            int generationCalls, int judgeCalls) {
    }
}
