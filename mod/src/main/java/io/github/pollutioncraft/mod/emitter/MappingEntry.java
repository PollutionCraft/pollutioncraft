package io.github.pollutioncraft.mod.emitter;

/**
 * One validated emitter mapping (format: {@code docs/emitter-mappings.md}). Plain strings only: registry
 * resolution (does the block, tag, property or value exist) happens later, with real registries, in
 * {@link EmitterRules#resolve}.
 *
 * @param target block id, tag id without the leading {@code #}, or entity type id
 * @param rate PU per interval, finite and {@code >= 0}
 * @param activeWhen the state that makes the block emit, or {@code null} for a block that is always active (and for every entity entry)
 */
public record MappingEntry(TargetKind kind, String target, double rate, ActiveWhen activeWhen) {
    public enum TargetKind {
        BLOCK,
        TAG,
        /** An entity type; its activity test is built in (see {@link EntityActivity}), so it has no {@code active_when}. */
        ENTITY
    }

    /** A block-state property and the serialized value of it that means active; both non-empty. */
    public record ActiveWhen(String property, String value) {}

    public boolean isAlwaysActive() {
        return activeWhen == null;
    }
}
