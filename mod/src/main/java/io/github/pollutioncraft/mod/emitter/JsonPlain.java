package io.github.pollutioncraft.mod.emitter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns parsed JSON into plain maps, lists, strings, numbers and booleans, which {@link MappingValidator} reads. */
final class JsonPlain {
    private JsonPlain() {}

    static Object of(JsonElement element) {
        if (element instanceof JsonObject object) {
            Map<String, Object> map = new LinkedHashMap<>();
            object.entrySet().forEach(entry -> map.put(entry.getKey(), of(entry.getValue())));
            return map;
        }
        if (element instanceof JsonArray array) {
            List<Object> list = new ArrayList<>();
            array.forEach(item -> list.add(of(item)));
            return list;
        }
        if (element instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            return primitive.isNumber() ? (Object) primitive.getAsDouble() : primitive.getAsString();
        }
        return null;
    }
}
