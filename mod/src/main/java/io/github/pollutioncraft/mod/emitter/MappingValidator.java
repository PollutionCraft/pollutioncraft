package io.github.pollutioncraft.mod.emitter;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Validates one raw mapping file (already turned from JSON into plain maps, strings and numbers) the way
 * {@code ConfigValues.load} validates config: an invalid entry is skipped and costs exactly one log line
 * naming the file and the reason. Never throws. Contains no Minecraft or JSON-library types.
 */
public final class MappingValidator {
    private MappingValidator() {}

    /**
     * @param fileId the resource id of the file, for the log line
     * @return the entry, or empty after logging why it was rejected
     */
    public static Optional<MappingEntry> validate(String fileId, Map<String, ?> raw, Consumer<String> log) {
        String problem = problemWith(raw);
        if (problem != null) {
            log.accept("Skipping emitter mapping " + fileId + ": " + problem);
            return Optional.empty();
        }
        MappingEntry.TargetKind kind = raw.containsKey("block")
                ? MappingEntry.TargetKind.BLOCK
                : raw.containsKey("tag") ? MappingEntry.TargetKind.TAG : MappingEntry.TargetKind.ENTITY;
        MappingEntry.ActiveWhen activeWhen = raw.containsKey("active_when")
                ? new MappingEntry.ActiveWhen(
                        (String) ((Map<?, ?>) raw.get("active_when")).get("property"),
                        (String) ((Map<?, ?>) raw.get("active_when")).get("value"))
                : null;
        return Optional.of(new MappingEntry(
                kind,
                (String) raw.get(targetKey(kind)),
                ((Number) raw.get("rate")).doubleValue(),
                activeWhen));
    }

    private static String targetKey(MappingEntry.TargetKind kind) {
        return switch (kind) {
            case BLOCK -> "block";
            case TAG -> "tag";
            case ENTITY -> "entity";
        };
    }

    /** The first thing wrong with the entry, or {@code null} if it is valid. */
    private static String problemWith(Map<String, ?> raw) {
        int targets = (raw.containsKey("block") ? 1 : 0) + (raw.containsKey("tag") ? 1 : 0) + (raw.containsKey("entity") ? 1 : 0);
        if (targets != 1) {
            return targets == 0
                    ? "needs exactly one of 'block', 'tag' or 'entity'"
                    : "has more than one of 'block', 'tag' and 'entity'; exactly one is allowed";
        }
        String targetKey = raw.containsKey("block") ? "block" : raw.containsKey("tag") ? "tag" : "entity";
        if (!(raw.get(targetKey) instanceof String id) || id.isBlank()) {
            return "'" + targetKey + "' must be a non-empty string";
        }
        if (!(raw.get("rate") instanceof Number rate)) {
            return "'rate' is required and must be a number";
        }
        double value = rate.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            return "'rate' must be finite and >= 0, was " + value;
        }
        if (targetKey.equals("entity") && raw.containsKey("active_when")) {
            return "an 'entity' mapping has no 'active_when'; the activity test of each supported entity is built in";
        }
        // No active_when means always active. One that is present must be complete: the mod does not guess.
        if (!raw.containsKey("active_when")) {
            return null;
        }
        if (!(raw.get("active_when") instanceof Map<?, ?> activeWhen)) {
            return "'active_when' must be an object with 'property' and 'value' (or be left out for an always-active block)";
        }
        if (!(activeWhen.get("property") instanceof String property) || property.isBlank()) {
            return "'active_when.property' is required and must be a non-empty string";
        }
        if (!(activeWhen.get("value") instanceof String activeValue) || activeValue.isBlank()) {
            return "'active_when.value' is required and must be a non-empty string (the serialized property value)";
        }
        return null;
    }
}
