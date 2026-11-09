package com.itzixi.evaluation.runner;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import com.itzixi.evaluation.model.EvaluationModels.*;
import static com.itzixi.evaluation.model.EvaluationModels.TYPES;

/** Extracts one response per data-slot from the single generated HTML fragment. */
public final class ResponseExecutor {
    private static final Map<String,String> TITLES=Map.of("reflection","反映与澄清","comfort","正常化与宽慰","reframing","积极重构","suggestion","建议");
    public Map<String,String> extract(String html) {
        StructuralResult result=inspect(html);
        result.checks().stream().filter(c -> !Boolean.TRUE.equals(c.passed())).findFirst().ifPresent(c -> {
            throw new IllegalArgumentException(c.reason());
        });
        return result.sections();
    }

    public StructuralResult inspect(String answer) {
        String html=answer==null?"":answer;
        boolean fragment=!html.isBlank() && html.length()<=2_097_152 && !html.contains("```")
                && !java.util.regex.Pattern.compile("(?is)<\\s*(?:html|head|body)\\b").matcher(html).find()
                && java.util.regex.Pattern.compile("(?is)<\\s*(?:div|p|strong|ul|li|section)\\b").matcher(html).find();
        var parser=new Sections();
        if (html.length()<=2_097_152) {
            try { new ParserDelegator().parse(new StringReader(html),parser,true); }
            catch (IOException exception) { parser.error="HTML parsing failed"; }
        }
        var sections=new LinkedHashMap<String,String>();
        parser.sections.forEach((type,body) -> {
            String text=body.toString().replaceAll("[\\s\\p{Z}]+"," ").strip();
            String title=TITLES.get(type);
            if (title!=null && text.startsWith(title)) text=text.substring(title.length()).strip();
            sections.put(type,text);
        });
        boolean complete=parser.error==null && parser.current==null && sections.keySet().equals(new HashSet<>(TYPES));
        boolean ordered=complete && new ArrayList<>(sections.keySet()).equals(TYPES);
        List<String> empty=TYPES.stream().filter(type -> !sections.containsKey(type) || sections.get(type).isBlank()).toList();
        var checks=List.of(
            code("html_fragment","HTML 片段格式",fragment,fragment?"HTML fragment accepted":"Invalid HTML answer: fragment, size or document wrapper"),
            code("four_slots","四个 slot 完整且唯一",complete,complete?"Four unique slots present":parser.error==null?"Missing or unclosed data-slot sections":parser.error),
            code("slot_order","四个 slot 顺序",ordered,ordered?"Slot order accepted":"Missing, duplicate or reordered data-slot sections"),
            code("nonempty_body","各部分正文非空",empty.isEmpty(),empty.isEmpty()?"All section bodies are nonempty":"Empty section body: "+String.join(",",empty)));
        return new StructuralResult(Collections.unmodifiableMap(sections),checks);
    }
    private CheckResult code(String id,String name,boolean passed,String reason) {
        return new CheckResult("structure."+id,name,"structural","code","raw_answer",passed?Status.PASS:Status.FAIL,passed,reason,List.of());
    }
    private static final class Sections extends HTMLEditorKit.ParserCallback {
        private final Map<String,StringBuilder> sections=new LinkedHashMap<>();
        private String current,error;
        private int divDepth, sectionDepth, ignoredDepth;
        @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int position) {
            if (tag==HTML.Tag.SCRIPT || tag==HTML.Tag.STYLE) ignoredDepth++;
            if (tag==HTML.Tag.DIV) {
                divDepth++;
                Object slot=attrs.getAttribute("data-slot");
                if (slot!=null) {
                    String type=slot.toString();
                    if (current!=null || !TYPES.contains(type) || sections.containsKey(type)) { error="Nested, unknown or duplicate data-slot";return; }
                    current=type;sectionDepth=divDepth;sections.put(type,new StringBuilder());
                }
            }
            separator(tag);
        }
        @Override public void handleEndTag(HTML.Tag tag,int position) {
            separator(tag);
            if (tag==HTML.Tag.DIV) { if (current!=null && divDepth==sectionDepth) current=null;divDepth--; }
            if ((tag==HTML.Tag.SCRIPT || tag==HTML.Tag.STYLE) && ignoredDepth>0) ignoredDepth--;
        }
        @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int position) { separator(tag); }
        @Override public void handleText(char[] data,int position) {
            if (current!=null && ignoredDepth==0) sections.get(current).append(data);
        }
        private void separator(HTML.Tag tag) {
            if (current!=null && (tag==HTML.Tag.P || tag==HTML.Tag.LI || tag==HTML.Tag.BR || tag==HTML.Tag.STRONG || tag==HTML.Tag.DIV))
                sections.get(current).append(' ');
        }
    }
}
