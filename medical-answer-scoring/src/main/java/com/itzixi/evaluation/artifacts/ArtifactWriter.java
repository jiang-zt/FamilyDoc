package com.itzixi.evaluation.artifacts;

import com.itzixi.evaluation.model.EvaluationModels.*;
import com.itzixi.evaluation.prompt.PromptBuilder;
import com.itzixi.evaluation.support.JsonSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Writes recoverable append-only attempt records and configuration snapshots. */
public final class ArtifactWriter {
    private final Path directory;
    public ArtifactWriter(Path directory) {
        this.directory=directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory.getParent());
            Files.createDirectory(this.directory); // Never reuse/overwrite a previous run.
            Files.createDirectory(this.directory.resolve("snapshots"));
            for (String name:List.of("attempts.jsonl","generations.jsonl","evaluations.jsonl","results.jsonl"))
                Files.createFile(this.directory.resolve(name));
        } catch (IOException exception) { throw new UncheckedIOException("Cannot create run directory",exception); }
    }
    public Path directory() { return directory; }
    public void append(String file,Object value) { write(file,JsonSupport.stringify(value)+"\n",true); }
    public void json(String file,Object value) { write(file,JsonSupport.pretty(value)+"\n",false); }
    public void text(String file,String value) { write(file,value,false); }
    private void write(String file,String value,boolean append) {
        try {
            if (append) Files.writeString(directory.resolve(file),value,StandardCharsets.UTF_8,StandardOpenOption.APPEND);
            else Files.writeString(directory.resolve(file),value,StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new UncheckedIOException("Cannot write evaluation artifact",exception); }
    }
    public Map<String,Object> snapshots(PreparedSuite prepared,Path renderer) {
        var hashes=new LinkedHashMap<String,Object>();
        // Record renderer provenance without copying executable sources into run artifacts.
        for (Path source:List.of(renderer,renderer.resolveSibling("report_template.html"))) {
            try { hashes.put(source.getFileName().toString(),PromptBuilder.sha256(Files.readAllBytes(source))); }
            catch (IOException exception) { throw new UncheckedIOException("Cannot hash report source",exception); }
        }
        copy(prepared.configuration().suitePath(),"suite.json",hashes);
        verifySnapshot("suite.json",prepared.configuration().suite());
        for (int i=0;i<prepared.configuration().entries().size();i++) {
            var entry=prepared.configuration().entries().get(i);
            copy(entry.taskPath(),"task-"+i+".json",hashes);
            copy(entry.rubricPath(),"rubric-"+i+".json",hashes);
            copy(entry.promptPath(),"prompt-"+i+".txt",hashes);
            verifySnapshot("task-"+i+".json",entry.task());
            verifySnapshot("rubric-"+i+".json",entry.rubric());
            String promptHash=(String)hashes.get("prompt-"+i+".txt");
            if (prepared.cases().stream().filter(c -> c.entry().taskPath().equals(entry.taskPath()))
                    .anyMatch(c -> !c.prompt().sourceHash().equals(promptHash)))
                throw new IllegalArgumentException("Prompt source changed after preflight");
        }
        var dimensionPrompts=new LinkedHashMap<Path,String>();
        for (var item:prepared.cases()) for (var rule:item.checks()) {
            if (rule.promptPath()==null) continue;
            String previous=dimensionPrompts.putIfAbsent(rule.promptPath(),rule.dimensionPrompt());
            if (previous!=null && !previous.equals(rule.dimensionPrompt()))
                throw new IllegalArgumentException("Judge prompt changed during preflight");
        }
        int promptIndex=0;
        for (var source:dimensionPrompts.entrySet()) {
            String name="judge-dimension-"+(promptIndex++)+".txt";
            copy(source.getKey(),name,hashes);
            if (!hashes.get(name).equals(PromptBuilder.sha256(source.getValue().getBytes(StandardCharsets.UTF_8))))
                throw new IllegalArgumentException("Judge prompt changed after preflight: "+source.getKey());
        }
        String cases=prepared.cases().stream().map(c -> JsonSupport.stringify(Map.of("task_id",c.entry().task().get("task_id"),"case",c.testCase().source())))
                .collect(java.util.stream.Collectors.joining("\n","","\n"));
        text("snapshots/cases.jsonl",cases);
        hashes.put("cases.jsonl",PromptBuilder.sha256(cases.getBytes(StandardCharsets.UTF_8)));
        for (int i=0;i<prepared.cases().size();i++) {
            var item=prepared.cases().get(i);
            String file="messages-"+i+".json";
            String messages=JsonSupport.stringify(item.prompt().messages().stream().map(Message::json).toList());
            text("snapshots/"+file,messages+"\n");
            hashes.put(file,PromptBuilder.sha256((messages+"\n").getBytes(StandardCharsets.UTF_8)));
        }
        return hashes;
    }
    private void verifySnapshot(String name,Map<String,Object> expected) {
        try {
            if (!JsonSupport.object(Files.readString(directory.resolve("snapshots").resolve(name))).equals(expected))
                throw new IllegalArgumentException("Configuration changed after preflight: "+name);
        } catch (IOException exception) { throw new UncheckedIOException("Cannot verify snapshot",exception); }
    }
    private void copy(Path source,String name,Map<String,Object> hashes) {
        try {
            byte[] bytes=Files.readAllBytes(source);
            Files.write(directory.resolve("snapshots").resolve(name),bytes);
            hashes.put(name,PromptBuilder.sha256(bytes));
        } catch (IOException exception) { throw new UncheckedIOException("Cannot snapshot source",exception); }
    }
}
