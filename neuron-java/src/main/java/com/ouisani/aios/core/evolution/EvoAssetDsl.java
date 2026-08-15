package com.ouisani.aios.core.evolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON DSL codec and required-field validator for EvoAssets. */
public final class EvoAssetDsl {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private EvoAssetDsl() { }

    public static String toJson(EvoAsset asset) {
        if (asset == null) throw new IllegalArgumentException("asset must not be null");
        JsonObject root = new JsonObject();
        root.addProperty("id", asset.id());
        root.addProperty("failure_class", asset.failureClass());
        root.addProperty("target", asset.target());
        root.add("match", GSON.toJsonTree(asset.match()));
        root.add("action", GSON.toJsonTree(asset.action()));
        root.add("created_from_runs", GSON.toJsonTree(asset.createdFromRuns()));
        root.addProperty("evaluation_split", asset.evaluationSplit());
        root.add("gate_results", gateResults(asset.gateResults()));
        root.add("compatible_assets", GSON.toJsonTree(asset.compatibleAssets()));
        root.addProperty("status", asset.status().jsonName());
        root.addProperty("version", asset.version());
        root.addProperty("source_split_ordinal", asset.sourceSplitOrdinal());
        root.addProperty("effective_from_split_ordinal", asset.effectiveFromSplitOrdinal());
        root.addProperty("created_at_epoch_ms", asset.createdAtEpochMs());
        root.addProperty("rollback_reason", asset.rollbackReason());
        return GSON.toJson(root);
    }

    public static EvoAsset fromJson(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("asset JSON is blank");
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) throw new IllegalArgumentException("asset JSON must be an object");
            return fromJson(element.getAsJsonObject());
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException error) {
            throw new IllegalArgumentException("invalid EvoAsset JSON", error);
        }
    }

    public static EvoAsset fromJson(JsonObject root) {
        if (root == null) throw new IllegalArgumentException("asset JSON must not be null");
        String id = required(root, "id");
        String failureClass = required(root, "failure_class");
        String target = required(root, "target");
        Map<String, Object> match = requiredObject(root, "match");
        Map<String, Object> action = requiredObject(root, "action");
        List<String> runs = requiredStrings(root, "created_from_runs");
        String split = required(root, "evaluation_split");
        List<String> compatible = requiredStrings(root, "compatible_assets");
        EvoAsset.Status status = EvoAsset.Status.fromJson(required(root, "status"));
        int sourceOrdinal = integer(root, "source_split_ordinal", 0);
        int effectiveOrdinal = integer(root, "effective_from_split_ordinal", sourceOrdinal + 1);
        JsonElement gateElement = root.get("gate_results");
        if (gateElement == null || gateElement.isJsonNull() || !gateElement.isJsonObject()) {
            throw new IllegalArgumentException("missing EvoAsset field: gate_results");
        }
        EvoAsset.GateResults gates = parseGateResults(gateElement.getAsJsonObject());
        return new EvoAsset(id, failureClass, target, match, action, runs, split, gates,
                compatible, status, integer(root, "version", 1), sourceOrdinal,
                effectiveOrdinal, longValue(root, "created_at_epoch_ms", 0),
                optional(root, "rollback_reason", ""));
    }

    public static String toJson(List<EvoAsset> assets) {
        JsonArray array = new JsonArray();
        if (assets != null) {
            for (EvoAsset asset : assets) array.add(JsonParser.parseString(toJson(asset)));
        }
        return GSON.toJson(array);
    }

    private static JsonObject gateResults(EvoAsset.GateResults gates) {
        JsonObject object = new JsonObject();
        object.addProperty("targeted_regression_passed", gates.targetedRegressionPassed());
        object.addProperty("global_regression_passed", gates.globalRegressionPassed());
        object.addProperty("stack_confirmation_passed", gates.stackConfirmationPassed());
        object.addProperty("shadow_passed", gates.shadowPassed());
        object.addProperty("canary_passed", gates.canaryPassed());
        object.add("details", GSON.toJsonTree(gates.details()));
        object.add("failures", GSON.toJsonTree(gates.failures()));
        return object;
    }

    private static EvoAsset.GateResults parseGateResults(JsonObject object) {
        if (object == null) return EvoAsset.GateResults.empty();
        return new EvoAsset.GateResults(
                bool(object, "targeted_regression_passed"),
                bool(object, "global_regression_passed"),
                bool(object, "stack_confirmation_passed"),
                bool(object, "shadow_passed"),
                bool(object, "canary_passed"),
                object.has("details") ? object(object, "details") : Map.of(),
                strings(object, "failures"));
    }

    private static String required(JsonObject object, String field) {
        String value = optional(object, field, "");
        if (value.isBlank()) throw new IllegalArgumentException("missing EvoAsset field: " + field);
        return value;
    }

    private static String optional(JsonObject object, String field, String fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int integer(JsonObject object, String field, int fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static long longValue(JsonObject object, String field, long fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static Map<String, Object> object(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || value.isJsonNull()) return Map.of();
        if (!value.isJsonObject()) throw new IllegalArgumentException(field + " must be a JSON object");
        Map<String, Object> parsed = GSON.fromJson(value, MAP_TYPE);
        return parsed == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
    }

    private static Map<String, Object> requiredObject(JsonObject root, String field) {
        if (!root.has(field) || root.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing EvoAsset field: " + field);
        }
        return object(root, field);
    }

    private static List<String> strings(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) throw new IllegalArgumentException(field + " must be a JSON array");
        ArrayList<String> result = new ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonNull()) result.add(entry.getAsString());
        }
        return List.copyOf(result);
    }

    private static List<String> requiredStrings(JsonObject root, String field) {
        if (!root.has(field) || root.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing EvoAsset field: " + field);
        }
        return strings(root, field);
    }
}
