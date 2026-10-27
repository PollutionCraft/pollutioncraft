package io.github.pollutioncraft.mod.emitter;

/**
 * One validated emitter mapping (format: {@code docs/emitter-mappings.md}). Plain strings only: registry
 * resolution (does the block, tag, property or value exist) happens later, with real registries, in
 * {@link EmitterRules#resolve}.
 *
 * @param target block id, or tag id without the leading {@code #}
 * @param rate PU per interval, finite and {@code >= 0}
 * @param activeWhen the state that makes the block emit, or {@code null} for a block that is always active
 */
public record MappingEntry(TargetKind kind, String target, double rate, ActiveWhen activeWhen) {
    public enum TargetKind {
        BLOCK,
        TAG
    }

    /** A block-state property and the serialized value of it that means active; both non-empty. */
    public record ActiveWhen(String property, String value) {}

    public boolean isAlwaysActive() {
        return activeWhen == null;
    }
}
