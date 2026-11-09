package com.itzixi.evaluation.runner;

import com.itzixi.evaluation.artifacts.*;
import com.itzixi.evaluation.cases.HaiCoachingCaseLoader;
import com.itzixi.evaluation.client.ModelClient;
import com.itzixi.evaluation.config.EvaluationConfigLoader.LoadedSuite;
import com.itzixi.evaluation.judge.ResponseJudge;
import com.itzixi.evaluation.model.EvaluationModels.*;
import com.itzixi.evaluation.prompt.PromptBuilder;
import com.itzixi.evaluation.support.JsonSupport;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.itzixi.evaluation.model.EvaluationModels.TYPES;

/**
 * One generated answer + one combined judge request per case, with a shared
 * suite deadline.
 */
public final class SuiteRunner {
    private final ModelClient generator, judge;
    private final ResponseExecutor extractor = new ResponseExecutor();
    private final ResponseJudge evaluator = new ResponseJudge();

    public SuiteRunner(ModelClient generator, ModelClient judge) {
        this.generator = generator;
        this.judge = judge;
    }
    //加载配置即可
    public static PreparedSuite prepare(LoadedSuite loaded) {
        return prepare(loaded, System.nanoTime());
    }

    public static PreparedSuite prepare(LoadedSuite loaded, long startedNanos) {
        ReportRenderer.sourcePath(loaded);
        var cases = new ArrayList<PreparedCase>();
        var selectedKeys=new HashSet<String>();
        for (var entry : loaded.entries()) {
            if (!"0.4.0".equals(entry.task().get("task_version"))
                    || !"single_html_sections".equals(entry.task().get("executor"))
                    || !"model_chat_completion".equals(entry.task().get("target")))
                throw new IllegalArgumentException("Unsupported task version/target/executor: " + entry.taskPath());
            var contract = JsonSupport.child(entry.task(), "case_contract");
            if (!"hai-coaching-four-response-v1".equals(contract.get("version")))
                throw new IllegalArgumentException("Unsupported case contract");
            var config = JsonSupport.child(entry.task(), "executor_config");
            if (!"input.struggle".equals(config.get("input_field"))
                    || !JsonSupport.list(config.get("response_types")).equals(TYPES))
                throw new IllegalArgumentException("Unsupported task input or response_types");
            positiveTimeout(JsonSupport.child(entry.task(), "execution"), "timeout_seconds");
            var llm = JsonSupport.child(JsonSupport.child(entry.rubric(), "judge"), "llm");
            positiveTimeout(llm, "timeout_seconds");
            JsonSupport.text(JsonSupport.child(llm, "prompt"), "system");
            JsonSupport.child(llm, "output_schema");
            var rules=ResponseJudge.rules(entry);
            var outputContract=JsonSupport.child(config,"output_contract");
            if (!"four-slots-html-v1".equals(outputContract.get("contract_id")))
                throw new IllegalArgumentException("Unsupported fixed output contract");
            var fixedFlags=Map.<String,Object>of("format","html_fragment","require_order",true,
                    "require_nonempty",true,"missing_sections","fail_case","duplicates","fail_case");
            fixedFlags.forEach((name,value)->{
                if (outputContract.containsKey(name) && !value.equals(outputContract.get(name)))
                    throw new IllegalArgumentException("Fixed output contract cannot change "+name);
            });
            if (outputContract.containsKey("required_slots") && !TYPES.equals(outputContract.get("required_slots")))
                throw new IllegalArgumentException("Fixed output contract cannot change required_slots");
            for (var testCase : new HaiCoachingCaseLoader().load(entry)) {
                if (!selectedKeys.add(JsonSupport.stringify(List.of(entry.task().get("task_id"),testCase.caseId()))))
                    throw new IllegalArgumentException("Duplicate selected task/case: "+testCase.caseId());
                cases.add(new PreparedCase(entry,testCase,new PromptBuilder().build(entry,testCase),rules));
            }
        }
        if (cases.isEmpty())
            throw new IllegalArgumentException("Empty selected cases");
        return new PreparedSuite(loaded, List.copyOf(cases), startedNanos);
    }

    public SuiteResult run(PreparedSuite prepared, Path outputOverride) {
        var suite = prepared.configuration().suite();
        var execution = JsonSupport.child(suite, "execution");
        long deadline = prepared.startedNanos()
                + Duration.ofSeconds(positiveTimeout(execution, "suite_timeout_seconds")).toNanos();
        String runId = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path output = outputOverride;
        if (output == null) {
            var artifacts = JsonSupport.child(suite, "artifacts");
            if (!"evaluation_directory".equals(artifacts.get("path_base")))
                throw new IllegalArgumentException("Unsupported artifact path_base");
            String relative = JsonSupport.text(artifacts, "output_dir");
            if (!relative.contains("<run_id>"))
                throw new IllegalArgumentException("artifact output_dir must include <run_id>");
            output = prepared.configuration().suitePath().getParent().getParent()
                    .resolve(relative.replace("<run_id>", runId));
        }
        var writer = new ArtifactWriter(output);
        Map<String, Object> hashes = writer.snapshots(prepared, ReportRenderer.sourcePath(prepared.configuration()));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("run_id", runId);
        manifest.put("started_at", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        manifest.put("suite_id", suite.get("suite_id"));
        manifest.put("suite_version", suite.get("suite_version"));
        manifest.put("source_hashes", hashes);
        manifest.put("external_sources",List.of("render_report.py","report_template.html"));
        manifest.put("generator", generator.provenance());
        manifest.put("judge", judge.provenance());
        manifest.put("prompt_sources", prepared.cases().stream().map(item -> Map.of(
                "source_sha256", item.prompt().sourceHash(), "source_from", item.prompt().sourceModel(),
                "source_temperature", item.prompt().temperature())).distinct().toList());
        manifest.put("normal_calls_per_case", Map.of("generation", 1, "judge", 1));
        manifest.put("evaluation_schema_version",2);
        var taskChecks=new LinkedHashMap<String,Object>();
        var taskTotals=new LinkedHashMap<String,Long>();
        for (var item:prepared.cases()) {
            String taskId=(String)item.entry().task().get("task_id");
            taskChecks.put(taskId,item.checks().stream().map(CheckDefinition::checkId).toList());
            taskTotals.merge(taskId,1L,Long::sum);
        }
        manifest.put("selected_checks",taskChecks);
        manifest.put("selected_cases",
                prepared.cases().stream()
                        .map(c -> Map.of("task_id", c.entry().task().get("task_id"), "case_id", c.testCase().caseId()))
                        .toList());
        writer.json("manifest.json", manifest);
        var results = new ArrayList<CaseResult>();
        int[] calls = { 0, 0 };
        boolean stopped = false;
        String suiteError = "";
        for (PreparedCase item : prepared.cases()) {
            String taskId = JsonSupport.text(item.entry().task(), "task_id");
            String id = item.testCase().caseId();
            if (System.nanoTime() >= deadline) {
                stopped = true;
                suiteError = "SUITE_TIMEOUT";
            }
            CaseResult result;
            if (stopped)
                result = new CaseResult(taskId, id, Status.NOT_RUN, "", Map.of(), List.of(),
                        suiteError.isBlank() ? "Stopped after previous case failure" : suiteError,
                        ResponseJudge.unavailable(item.checks(),Status.NOT_RUN,"Case was not executed"));
            else {
                int retries = Math.toIntExact(JsonSupport.integer(execution, "retries_on_infra_error"));
                var budget = new RetryBudget(retries);
                result = execute(item, taskId, deadline, budget, writer, calls);
                boolean failFast = !Boolean.FALSE.equals(execution.get("fail_fast"));
                if (failFast && result.status() != Status.PASS)
                    stopped = true;
            }
            results.add(result);
            writer.append("results.jsonl", result.json());
            writer.json("summary.json", summary(runId, results, prepared.cases().size(), calls, suiteError, false,taskTotals));
        }
        Status status = results.stream().allMatch(r -> r.status() == Status.PASS) ? Status.PASS : Status.FAIL;
        var result = new SuiteResult(runId, status, writer.directory(), List.copyOf(results), calls[0], calls[1]);
        manifest.put("finished_at", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        writer.json("manifest.json", manifest);
        writer.json("summary.json", summary(runId, results, prepared.cases().size(), calls, suiteError, true,taskTotals));
        try {
            new ReportRenderer().generate(writer.directory(), ReportRenderer.sourcePath(prepared.configuration()));
        } catch (java.io.UncheckedIOException exception) {
            writer.json("summary.json",
                    summary(runId, results, prepared.cases().size(), calls, "REPORT_WRITE_FAILED", true,taskTotals));
            throw exception;
        }
        return result;
    }

    private CaseResult execute(PreparedCase item,String taskId,long deadline,RetryBudget budget,ArtifactWriter writer,int[] calls) {
        String id=item.testCase().caseId(),raw="";
        Map<String,String> sections=Map.of();
        List<CheckResult> codeResults=List.of();
        var llmRules=item.checks().stream().filter(r -> r.evaluatorType().equals("llm")).toList();
        try {
            long generationTimeout=positiveTimeout(JsonSupport.child(item.entry().task(),"execution"),"timeout_seconds");
            Completion generated=call(generator,item.prompt().messages(),"generation",taskId,id,deadline,generationTimeout,budget,writer,calls);
            raw=generated.content();
            StructuralResult inspected=extractor.inspect(raw);sections=inspected.sections();
            var structural=new HashMap<String,CheckResult>();
            inspected.checks().forEach(c -> structural.put(c.checkId(),c));
            codeResults=item.checks().stream().filter(r -> r.evaluatorType().equals("code")).map(r -> {
                var actual=structural.get(r.checkId());
                return CheckResult.of(r,actual.status(),actual.passed(),actual.reason(),actual.evidenceQuotes());
            }).toList();
            if (codeResults.stream().anyMatch(r -> Boolean.FALSE.equals(r.passed()))) {
                var all=new ArrayList<>(codeResults);
                all.addAll(ResponseJudge.unavailable(llmRules,Status.SKIPPED,"Fixed structural contract failed; judge was not called"));
                return new CaseResult(taskId,id,Status.FAIL,raw,sections,List.of(),"INVALID_SECTIONS: fixed output contract failed",List.copyOf(all));
            }
            if (llmRules.isEmpty()) return new CaseResult(taskId,id,Status.PASS,raw,sections,List.of(),"",codeResults);
            var llm=JsonSupport.child(JsonSupport.child(item.entry().rubric(),"judge"),"llm");
            var messages=evaluator.messages(item.testCase(),sections,raw,item.entry().rubric(),item.checks());
            try {
                Completion judged=call(judge,messages,"judge",taskId,id,deadline,positiveTimeout(llm,"timeout_seconds"),budget,writer,calls);
                var all=new ArrayList<>(codeResults);
                all.addAll(evaluator.parse(judged.content(),sections,raw,item.checks()));
                return new CaseResult(taskId,id,evaluator.aggregate(all),raw,sections,List.of(),"",List.copyOf(all));
            } catch (ModelClient.ModelException | IllegalArgumentException exception) {
                var all=new ArrayList<>(codeResults);
                String error=exception instanceof ModelClient.ModelException?exception.getMessage():"INVALID_JUDGE_OUTPUT: "+exception.getMessage();
                all.addAll(ResponseJudge.unavailable(llmRules,Status.EVALUATION_ERROR,error));
                return new CaseResult(taskId,id,Status.EVALUATION_ERROR,raw,sections,List.of(),error,List.copyOf(all));
            }
        } catch (ModelClient.ModelException exception) {
            return new CaseResult(taskId,id,Status.ERROR,raw,sections,List.of(),exception.getMessage(),
                    ResponseJudge.unavailable(item.checks(),Status.ERROR,exception.getMessage()));
        }
    }

    private Completion call(ModelClient client, List<Message> messages, String phase, String taskId, String id,
            long deadline,
            long perCallSeconds, RetryBudget budget, ArtifactWriter writer, int[] calls) {
        int attempt = 0;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                throw new ModelClient.ModelException("SUITE_TIMEOUT", false, false);
            Duration timeout = Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(perCallSeconds).toNanos()));
            attempt++;
            calls[phase.equals("generation") ? 0 : 1]++;
            var event = new LinkedHashMap<String, Object>();
            event.put("task_id", taskId);
            event.put("case_id", id);
            event.put("phase", phase);
            event.put("attempt", attempt);
            event.put("messages", messages.stream().map(Message::json).toList());
            writer.append("attempts.jsonl",
                    Map.of("case_id", id, "task_id", taskId, "phase", phase, "attempt", attempt, "event", "started"));
            try {
                Completion completion = client.complete(messages, timeout);
                event.put("content", completion.content());
                event.put("request_id", completion.requestId());
                event.put("model", completion.model());
                event.put("usage", completion.usage());
                event.put("total_ms", completion.totalMs());
                if (System.nanoTime() >= deadline)
                    throw new ModelClient.ModelException("SUITE_TIMEOUT", false, false);
                event.put("status", "SUCCESS");
                writer.append(phase.equals("generation") ? "generations.jsonl" : "evaluations.jsonl", event);
                writer.append("attempts.jsonl", Map.of("case_id", id, "task_id", taskId, "phase", phase, "attempt",
                        attempt, "event", "succeeded"));
                return completion;
            } catch (ModelClient.ModelException exception) {
                event.put("status", "ERROR");
                event.put("error", exception.getMessage());
                writer.append(phase.equals("generation") ? "generations.jsonl" : "evaluations.jsonl", event);
                writer.append("attempts.jsonl", Map.of("case_id", id, "task_id", taskId, "phase", phase, "attempt",
                        attempt, "event", "failed", "error", exception.getMessage()));
                boolean safe = phase.equals("judge") || exception.safeToRetry();
                if (!exception.retryable() || !safe || budget.remaining == 0 || System.nanoTime() >= deadline)
                    throw exception;
                budget.remaining--;
            }
        }
    }

    private static long positiveTimeout(Map<String, Object> object, String key) {
        long value = JsonSupport.integer(object, key);
        if (value <= 0 || value > 86400)
            throw new IllegalArgumentException("Invalid " + key + ": expected 1..86400 seconds");
        return value;
    }

    private static Map<String,Object> summary(String runId,List<CaseResult> cases,int total,int[] calls,String error,
                                               boolean complete,Map<String,Long> taskTotals) {
        boolean all=cases.size()==total && cases.stream().allMatch(c->c.status()==Status.PASS);
        var value=new LinkedHashMap<String,Object>();
        value.put("run_id",runId);value.put("status",!complete?"RUNNING":all && error.isBlank()?"PASS":"FAIL");
        value.put("total",total);value.put("passed",cases.stream().filter(c->c.status()==Status.PASS).count());
        value.put("recorded",cases.size());value.put("generation_calls",calls[0]);value.put("judge_calls",calls[1]);value.put("error",error);
        var checks=cases.stream().flatMap(c->c.checkResults().stream()).toList();
        value.put("checks_passed",checks.stream().filter(c->Boolean.TRUE.equals(c.passed())).count());
        value.put("checks_failed",checks.stream().filter(c->Boolean.FALSE.equals(c.passed())).count());
        value.put("checks_unavailable",checks.stream().filter(c->c.passed()==null).count());
        var tasks=new ArrayList<Map<String,Object>>();
        taskTotals.forEach((taskId,planned)->{
            var rows=cases.stream().filter(c->c.taskId().equals(taskId)).toList();
            boolean taskPass=rows.size()==planned && rows.stream().allMatch(c->c.status()==Status.PASS);
            tasks.add(Map.of("task_id",taskId,"total",planned,"recorded",rows.size(),
                    "passed",rows.stream().filter(c->c.status()==Status.PASS).count(),
                    "status",taskPass?"PASS":!complete && rows.size()<planned?"RUNNING":"FAIL"));
        });
        value.put("task_results",tasks);
        return value;
    }

    private static final class RetryBudget {
        private int remaining;

        private RetryBudget(int remaining) {
            this.remaining = remaining;
        }
    }
}
