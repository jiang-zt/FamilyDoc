package com.itzixi.evaluation;

import com.itzixi.evaluation.client.ModelClient;
import com.itzixi.evaluation.config.EvaluationConfigLoader;
import com.itzixi.evaluation.runner.SuiteRunner;
import com.itzixi.evaluation.model.EvaluationModels.Status;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Offline CLI: explicit preflight or a real single-generation evaluation run.
 */
public final class EvaluationCli {
    public static final int SUCCESS = 0, FAILURE = 1, USAGE_ERROR = 2;
    private final Map<String, String> environment;

    public EvaluationCli() {
        this(System.getenv());
    }

    public EvaluationCli(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    public static void main(String[] args) {
        System.exit(new EvaluationCli().run(args, System.out, System.err));
    }

    public int run(String[] args, PrintStream out, PrintStream err) {
        long started = System.nanoTime();
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            usage(out);
            return SUCCESS;
        }
        Path suite = null, output = null;
        boolean validate = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--suite", "--output-dir" -> {
                        String key = args[i];
                        if (i + 1 == args.length || args[i + 1].startsWith("-") || args[i + 1].isBlank())
                            throw new IllegalArgumentException(key + " needs a path");
                        Path value = Path.of(args[++i]);
                        if (key.equals("--suite")) {
                            if (suite != null)
                                throw new IllegalArgumentException("Repeated --suite");
                            suite = value;
                        } else {
                            if (output != null)
                                throw new IllegalArgumentException("Repeated --output-dir");
                            output = value;
                        }
                    }
                    case "--validate-only" -> {
                        if (validate)
                            throw new IllegalArgumentException("Repeated --validate-only");
                        validate = true;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (suite == null)
                throw new IllegalArgumentException("--suite is required");
        } catch (IllegalArgumentException exception) {
            err.println("USAGE_ERROR: " + exception.getMessage());
            usage(err);
            return USAGE_ERROR;
        }
        final com.itzixi.evaluation.model.EvaluationModels.PreparedSuite prepared;
        try {
            prepared = SuiteRunner.prepare(new EvaluationConfigLoader().load(suite), started);
        } catch (IllegalArgumentException exception) {
            err.println("CONFIG_ERROR: " + exception.getMessage());
            return FAILURE;
        }
        if (validate) {
            out.println("CONFIG_VALID suite=" + prepared.configuration().suite().get("suite_id") + " selected_cases="
                    + prepared.cases().size());
            out.println("scope=offline_preflight evaluation_executed=false");
            var shown=new java.util.HashSet<String>();
            for (var item:prepared.cases()) {
                String taskId=(String)item.entry().task().get("task_id");
                if (shown.add(taskId)) out.println("task="+taskId+" code_checks="+item.checks().stream().filter(r->r.evaluatorType().equals("code")).count()
                        +" llm_checks="+item.checks().stream().filter(r->r.evaluatorType().equals("llm")).count()+" total_checks="+item.checks().size());
            }
            out.println("pending_checks=runtime_model_configuration");
            return SUCCESS;
        }
        final ModelClient generator, judge;
        try {
            generator = ModelClient.http(ModelClient.Settings.fromEnvironment(environment, "GENERATOR",
                    prepared.cases().getFirst().prompt().temperature()));
            judge = ModelClient.http(ModelClient.Settings.fromEnvironment(environment, "JUDGE", 0));
        } catch (IllegalArgumentException exception) {
            err.println("RUNTIME_CONFIG_ERROR: " + exception.getMessage());
            return FAILURE;
        }
        try {
            var result = new SuiteRunner(generator, judge).run(prepared, output);
            out.println("suite_status=" + result.status() + " generation_calls=" + result.generationCalls()
                    + " judge_calls=" + result.judgeCalls());
            out.println("run_directory=" + result.runDirectory());
            out.println("report=" + result.runDirectory().resolve("report.html"));
            return result.status() == Status.PASS ? SUCCESS : FAILURE;
        } catch (java.io.UncheckedIOException | IllegalArgumentException exception) {
            err.println("RUN_ERROR: " + exception.getMessage());
            return FAILURE;
        }
    }

    private static void usage(PrintStream stream) {
        stream.println(
                "Usage: EvaluationCli --suite <suite.json> [--validate-only] [--output-dir <new-run-directory>]");
        stream.println(
                "--validate-only validates selected cases, prompt and configuration without model calls or artifacts.");
        stream.println("Execution requires EVAL_GENERATOR_ENDPOINT, EVAL_GENERATOR_MODEL, EVAL_GENERATOR_API_KEY");
        stream.println(
                "and EVAL_JUDGE_ENDPOINT, EVAL_JUDGE_MODEL, EVAL_JUDGE_API_KEY. ENDPOINT is the full chat/completions URL.");
        stream.println(
                "Optional: EVAL_GENERATOR_TEMPERATURE (default from prompt), EVAL_JUDGE_TEMPERATURE (default 0),");
        stream.println(
                "EVAL_GENERATOR_MAX_TOKENS (default 1024), EVAL_JUDGE_MAX_TOKENS (default 2048). Keys may be empty only for localhost.");
        stream.println("Report rendering requires Python 3.9+; optionally set EVAL_PYTHON to its executable path.");
        stream.println(
                "Exit codes: 0=help/config valid/suite PASS; 1=config/runtime/evaluation failure; 2=usage error.");
    }
}
