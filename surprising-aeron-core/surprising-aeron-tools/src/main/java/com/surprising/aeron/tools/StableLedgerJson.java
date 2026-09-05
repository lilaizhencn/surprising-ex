package com.surprising.aeron.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StableLedgerJson {

    private static final Pattern FIELD = Pattern.compile(
            "\\\"([A-Za-z]+)\\\":(\\\"(?:\\\\.|[^\\\"])*\\\"|-?[0-9]+)");

    private StableLedgerJson() {
    }

    static Map<String, String> fields(String json) {
        if (json == null || json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
            throw new IllegalStateException("not a JSON object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) {
            String raw = matcher.group(2);
            result.put(matcher.group(1), raw.charAt(0) == '"'
                    ? unescape(raw.substring(1, raw.length() - 1)) : raw);
        }
        if (result.isEmpty()) throw new IllegalStateException("empty JSON object");
        return result;
    }

    static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) throw new IllegalStateException("missing ledger field " + name);
        return value;
    }

    static long number(Map<String, String> values, String name) {
        return Long.parseLong(required(values, name));
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                result.append(current == 'n' ? '\n' : current == 'r' ? '\r' : current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else {
                result.append(current);
            }
        }
        if (escaped) throw new IllegalStateException("unterminated JSON escape");
        return result.toString();
    }
}
