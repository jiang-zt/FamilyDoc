package com.itzixi.evaluation.cases;

import com.itzixi.evaluation.config.EvaluationConfigLoader.LoadedEntry;
import com.itzixi.evaluation.model.EvaluationModels.*;
import com.itzixi.evaluation.support.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import static com.itzixi.evaluation.model.EvaluationModels.TYPES;

public final class HaiCoachingCaseLoader {
    public List<HaiCase> load(LoadedEntry entry) {
        var selected = new HashMap<String,HaiCase>();
        var seen = new HashSet<String>();
        try (var reader = Files.newBufferedReader(entry.casesPath())) {
            String line;
            int number=0;
            while ((line=reader.readLine())!=null) {
                number++;
                final Map<String,Object> row;
                try { row=JsonSupport.object(line); }
                catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid case JSON at line " + number); }
                String id=JsonSupport.text(row,"case_id");
                if (!id.matches("(?!0000)[0-9]{4}")) throw new IllegalArgumentException("Invalid case_id at line "+number);
                if (!seen.add(id)) throw new IllegalArgumentException("duplicate case_id: "+id);
                if (entry.caseIds().contains(id)) selected.put(id,toCase(row));
            }
        } catch (IOException exception) { throw new IllegalArgumentException("Cannot read cases: "+entry.casesPath(),exception); }
        return entry.caseIds().stream().map(id -> {
            if (!selected.containsKey(id)) throw new IllegalArgumentException("Selected case missing: "+id);
            return selected.get(id);
        }).toList();
    }
    private HaiCase toCase(Map<String,Object> row) {
        String id=JsonSupport.text(row,"case_id");
        Map<String,Object> input=JsonSupport.child(row,"input");
        String struggle=JsonSupport.text(input,"struggle");
        if (input.containsKey("required_response_types")) {
            List<?> requested=JsonSupport.list(input.get("required_response_types"));
            if (requested.size()!=4 || !new HashSet<>(requested).equals(new HashSet<>(TYPES)))
                throw new IllegalArgumentException(id+": invalid required_response_types");
        }
        var groups=new LinkedHashMap<String,List<Reference>>();
        for (String type:TYPES) {
            List<?> candidates=JsonSupport.list(row.get(type+"_candidates"));
            List<?> annotations=JsonSupport.list(row.get(type+"_annotation"));
            if (candidates.size()!=annotations.size()) throw new IllegalArgumentException(id+": "+type+" annotation length mismatch");
            var references=new ArrayList<Reference>();
            for (int i=0;i<candidates.size();i++) {
                Object text=candidates.get(i), label=annotations.get(i);
                if (text!=null && !(text instanceof String)) throw new IllegalArgumentException(id+": invalid candidate");
                if (label!=null && !label.equals("Y") && !label.equals("N")) throw new IllegalArgumentException(id+": invalid annotation");
                if ("Y".equals(label) && text instanceof String value && !value.isBlank()) references.add(new Reference(i,value));
            }
            if (references.isEmpty()) throw new IllegalArgumentException(id+": no accepted references for "+type);
            groups.put(type,List.copyOf(references));
        }
        return new HaiCase(id,struggle,Collections.unmodifiableMap(groups),Collections.unmodifiableMap(row));
    }
}
