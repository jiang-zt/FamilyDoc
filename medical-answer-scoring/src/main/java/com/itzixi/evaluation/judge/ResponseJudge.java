package com.itzixi.evaluation.judge;

import com.itzixi.evaluation.config.EvaluationConfigLoader.LoadedEntry;
import com.itzixi.evaluation.model.EvaluationModels.*;
import com.itzixi.evaluation.support.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.itzixi.evaluation.model.EvaluationModels.*;

/** Routes configured semantic checks through one judge call; code owns IDs, evidence and aggregation. */
public final class ResponseJudge {
    public static List<CheckDefinition> rules(LoadedEntry entry) {
        var catalog=new LinkedHashMap<String,Map<String,Object>>();
        for (Object value:JsonSupport.list(entry.rubric().get("rules"))) {
            var rule=JsonSupport.asObject(value);
            String id=JsonSupport.text(rule,"check_id");
            if (catalog.putIfAbsent(id,rule)!=null) throw new IllegalArgumentException("Duplicate check_id: "+id);
        }
        var selected=JsonSupport.list(JsonSupport.child(entry.task(),"evaluation").get("checks"));
        var result=new ArrayList<CheckDefinition>();
        var seen=new HashSet<String>();
        for (Object requested:selected) {
            if (!(requested instanceof String id) || !seen.add(id) || !catalog.containsKey(id))
                throw new IllegalArgumentException("Unknown or duplicate evaluation.checks ID: "+requested);
            var rule=catalog.get(id);
            String type=JsonSupport.text(rule,"evaluator_type"),dimension=JsonSupport.text(rule,"dimension"),scope=JsonSupport.text(rule,"scope");
            if (STRUCTURE_IDS.contains(id) && !type.equals("code"))
                throw new IllegalArgumentException("Fixed structural checks must use code: "+id);
            if (!Set.of("structural","content","safety","robustness").contains(dimension)) throw new IllegalArgumentException("Unknown check dimension: "+id);
            if (!scope.equals("raw_answer") && !(scope.startsWith("section:") && TYPES.contains(scope.substring(8))))
                throw new IllegalArgumentException("Unknown check scope: "+id);
            String validator="",instructions="";Path prompt=null;
            if (type.equals("code")) {
                validator=JsonSupport.text(rule,"validator_id");
                if (!STRUCTURE_IDS.contains(id) || !id.equals("structure."+validator) || !dimension.equals("structural") || !scope.equals("raw_answer"))
                    throw new IllegalArgumentException("Unsupported code validator: "+id+" / "+validator);
            } else if (type.equals("llm")) {
                if (dimension.equals("structural")) throw new IllegalArgumentException("Structural checks must use code");
                prompt=entry.rubricPath().getParent().resolve(JsonSupport.text(rule,"prompt_ref")).toAbsolutePath().normalize();
                try { instructions=Files.readString(prompt); }
                catch (IOException exception) { throw new IllegalArgumentException("Missing/unreadable judge prompt: "+prompt,exception); }
                if (instructions.isBlank()) throw new IllegalArgumentException("Empty judge prompt: "+prompt);
            } else throw new IllegalArgumentException("Unknown evaluator_type: "+id);
            result.add(new CheckDefinition(id,JsonSupport.text(rule,"check_name"),dimension,type,scope,
                    JsonSupport.text(rule,"pass_condition"),JsonSupport.text(rule,"fail_condition"),validator,prompt,instructions));
        }
        if (!seen.containsAll(STRUCTURE_IDS)) throw new IllegalArgumentException("All fixed structural checks must be selected");
        return List.copyOf(result);
    }

    public List<Message> messages(HaiCase testCase,Map<String,String> sections,String rawAnswer,
                                  Map<String,Object> rubric,List<CheckDefinition> checks) {
        var llm=JsonSupport.child(JsonSupport.child(rubric,"judge"),"llm");
        var fragments=new LinkedHashMap<String,Set<String>>();
        for (var rule:checks) if (rule.evaluatorType().equals("llm"))
            fragments.computeIfAbsent(rule.dimension(),ignored -> new LinkedHashSet<>()).add(rule.dimensionPrompt());
        var instructions=new LinkedHashMap<String,Object>();
        fragments.forEach((key,values)->instructions.put(key,String.join("\n\n",values)));
        var references=new LinkedHashMap<String,Object>();
        testCase.references().forEach((type,values)->references.put(type,values.stream().map(Reference::json).toList()));
        var payload=new LinkedHashMap<String,Object>();
        payload.put("struggle",testCase.struggle());payload.put("responses",sections);payload.put("raw_answer",rawAnswer);
        payload.put("reference_groups",references);
        payload.put("checks",checks.stream().filter(r->r.evaluatorType().equals("llm")).map(CheckDefinition::json).toList());
        payload.put("dimension_instructions",instructions);payload.put("output_schema",JsonSupport.child(llm,"output_schema"));
        return List.of(new Message("system",JsonSupport.text(JsonSupport.child(llm,"prompt"),"system")),new Message("user",JsonSupport.stringify(payload)));
    }

    public List<CheckResult> parse(String output,Map<String,String> sections,String rawAnswer,List<CheckDefinition> checks) {
        var root=JsonSupport.object(output);
        if (root.keySet().equals(Set.of("error","reason")) && "UNABLE_TO_JUDGE".equals(root.get("error")))
            throw new IllegalArgumentException("Judge unable to evaluate: "+JsonSupport.text(root,"reason"));
        if (!root.keySet().equals(Set.of("checks"))) throw new IllegalArgumentException("Judge root must contain only checks");
        var requested=new LinkedHashMap<String,CheckDefinition>();
        checks.stream().filter(r->r.evaluatorType().equals("llm")).forEach(r->requested.put(r.checkId(),r));
        var returned=JsonSupport.list(root.get("checks"));
        if (returned.size()!=requested.size()) throw new IllegalArgumentException("Missing/additional judge checks");
        var results=new HashMap<String,CheckResult>();
        for (Object value:returned) {
            var row=JsonSupport.asObject(value);
            if (!row.keySet().equals(Set.of("check_id","passed","reason","evidence_quotes"))) throw new IllegalArgumentException("Invalid check result fields");
            String id=JsonSupport.text(row,"check_id");
            if (!requested.containsKey(id) || results.containsKey(id)) throw new IllegalArgumentException("Unexpected/duplicate judge check_id: "+id);
            if (!(row.get("passed") instanceof Boolean passed)) throw new IllegalArgumentException("passed must be boolean: "+id);
            if (!(row.get("reason") instanceof String reason) || (!passed && reason.isBlank()))
                throw new IllegalArgumentException("Failed LLM check requires a reason: "+id);
            var rule=requested.get(id);
            String source=rule.scope().equals("raw_answer")?rawAnswer:sections.get(rule.scope().substring(8));
            var evidence=new ArrayList<String>();
            for (Object quote:JsonSupport.list(row.get("evidence_quotes"))) {
                if (!(quote instanceof String text) || text.isBlank() || source==null || !source.contains(text))
                    throw new IllegalArgumentException("Evidence not present in check scope: "+id);
                evidence.add(text);
            }
            results.put(id,CheckResult.of(rule,passed?Status.PASS:Status.FAIL,passed,reason,evidence));
        }
        return requested.keySet().stream().map(results::get).toList();
    }

    public static List<CheckResult> unavailable(List<CheckDefinition> rules,Status status,String reason) {
        return rules.stream().map(r->CheckResult.of(r,status,null,reason,List.of())).toList();
    }
    public Status aggregate(List<CheckResult> results) {
        if (results.stream().anyMatch(r->Boolean.FALSE.equals(r.passed()))) return Status.FAIL;
        if (results.stream().anyMatch(r->r.status()==Status.EVALUATION_ERROR)) return Status.EVALUATION_ERROR;
        if (results.stream().anyMatch(r->r.status()==Status.ERROR)) return Status.ERROR;
        return !results.isEmpty() && results.stream().allMatch(r->Boolean.TRUE.equals(r.passed()))?Status.PASS:Status.NOT_EVALUATED;
    }
}
