package com.itzixi.evaluation.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict JSON for API responses, judge outputs and artifacts, without a new dependency. */
public final class JsonSupport {
    private JsonSupport() {}
    public static Map<String,Object> object(String text) {
        return asObject(parse(text));
    }
    public static Object parse(String text) {
        var parser = new Parser(text);
        Object value = parser.value(0);
        parser.space();
        if (parser.index != text.length()) throw parser.error();
        return value;
    }
    public static Map<String,Object> asObject(Object value) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Expected JSON object");
        var copy = new LinkedHashMap<String,Object>();
        map.forEach((k,v) -> { if (!(k instanceof String)) throw new IllegalArgumentException("Invalid JSON key"); copy.put((String)k,v); });
        return copy;
    }
    public static Map<String,Object> child(Map<String,Object> map, String name) { return asObject(map.get(name)); }
    public static String text(Map<String,Object> map, String name) {
        if (!(map.get(name) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing/nonblank string: " + name);
        return text;
    }
    public static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected JSON array");
        return list;
    }
    public static long integer(Map<String,Object> map, String key) {
        if (!(map.get(key) instanceof Number number)) throw new IllegalArgumentException("Expected integer: " + key);
        try { return new BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("Expected integer: " + key); }
    }
    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            var out = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                    default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            new BigDecimal(value.toString()); // Reject NaN and infinities.
            return value.toString();
        }
        if (value instanceof Map<?,?> map) {
            var parts = new ArrayList<String>();
            map.forEach((k,v) -> { if (!(k instanceof String)) throw new IllegalArgumentException("Invalid JSON key"); parts.add(stringify(k)+":"+stringify(v)); });
            return "{"+String.join(",",parts)+"}";
        }
        if (value instanceof Iterable<?> values) {
            var parts = new ArrayList<String>();
            values.forEach(v -> parts.add(stringify(v)));
            return "["+String.join(",",parts)+"]";
        }
        throw new IllegalArgumentException("Unsupported JSON value type");
    }

    public static String pretty(Object value) { return pretty(value,0); }
    private static String pretty(Object value,int depth) {
        String pad="  ".repeat(depth+1),end="  ".repeat(depth);
        var parts=new ArrayList<String>();
        if (value instanceof Map<?,?> map) {
            map.forEach((key,item) -> {
                if (!(key instanceof String)) throw new IllegalArgumentException("Invalid JSON key");
                parts.add(pad+stringify(key)+": "+pretty(item,depth+1));
            });
            return parts.isEmpty()?"{}":"{\n"+String.join(",\n",parts)+"\n"+end+"}";
        }
        if (value instanceof Iterable<?> values) {
            values.forEach(item -> parts.add(pad+pretty(item,depth+1)));
            return parts.isEmpty()?"[]":"[\n"+String.join(",\n",parts)+"\n"+end+"]";
        }
        return stringify(value);
    }

    private static final class Parser {
        private final String text;
        private int index;
        private Parser(String text) { this.text = text; }
        private IllegalArgumentException error() { return new IllegalArgumentException("Invalid JSON at offset " + index); }
        private void space() { while (index < text.length() && " \t\r\n".indexOf(text.charAt(index)) >= 0) index++; }
        private boolean take(char c) { space(); if (index < text.length() && text.charAt(index)==c) { index++; return true; } return false; }
        private Object value(int depth) {
            if (depth > 64) throw error();
            space();
            if (index >= text.length()) throw error();
            char c = text.charAt(index);
            if (c=='"') return string();
            if (take('{')) {
                var map = new LinkedHashMap<String,Object>();
                if (take('}')) return map;
                do {
                    space();
                    String key = string();
                    if (map.containsKey(key) || !take(':')) throw error();
                    map.put(key,value(depth+1));
                    if (take('}')) return map;
                } while (take(','));
                throw error();
            }
            if (take('[')) {
                var array = new ArrayList<Object>();
                if (take(']')) return array;
                do { array.add(value(depth+1)); if (take(']')) return array; } while (take(','));
                throw error();
            }
            for (String literal : List.of("true","false","null")) {
                if (text.startsWith(literal,index)) {
                    index+=literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = index;
            while (index < text.length() && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
            String number = text.substring(start,index);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error();
            return new BigDecimal(number);
        }
        private String string() {
            if (index >= text.length() || text.charAt(index++)!='"') throw error();
            var out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c=='"') return out.toString();
                if (c < 32) throw error();
                if (c!='\\') { out.append(c); continue; }
                if (index>=text.length()) throw error();
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"','\\','/' -> out.append(escaped);
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index+4>text.length()) throw error();
                        String hex=text.substring(index,index+4);
                        if (!hex.matches("[0-9a-fA-F]{4}")) throw error();
                        out.append((char)Integer.parseInt(hex,16)); index+=4;
                    }
                    default -> throw error();
                }
            }
            throw error();
        }
    }
}
