package dev.merlionos.customerservice.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** The list columns are jsonb: a phrase may contain a comma, so no separator would do. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private Json() {
    }

    static String write(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> read(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : MAPPER.readValue(json, STRINGS);
        }
        catch (Exception e) {
            throw new IllegalStateException("not a JSON list of strings: " + json, e);
        }
    }
}
