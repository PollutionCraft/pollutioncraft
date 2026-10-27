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
        boolean isBlock = raw.containsKey("block");
        MappingEntry.ActiveWhen activeWhen = raw.containsKey("active_when")
                ? new MappingEntry.ActiveWhen(
                        (String) ((Map<?, ?>) raw.get("active_when")).get("property"),
                        (String) ((Map<?, ?>) raw.get("active_when")).get("value"))
                : null;
        return Optional.of(new MappingEntry(
                isBlock ? MappingEntry.TargetKind.BLOCK : MappingEntry.TargetKind.TAG,
                (String) raw.get(isBlock ? "block" : "tag"),
                ((Number) raw.get("rate")).doubleValue(),
                activeWhen));
    }

    /** The first thing wrong with the entry, or {@code null} if it is valid. */
    private static String problemWith(Map<String, ?> raw) {
        boolean hasBlock = raw.containsKey("block");
        boolean hasTag = raw.containsKey("tag");
        if (hasBlock == hasTag) {
            return hasBlock ? "has both 'block' and 'tag'; exactly one is allowed" : "needs exactly one of 'block' or 'tag'";
        }
        String targetKey = hasBlock ? "block" : "tag";
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
