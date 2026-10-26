package io.github.pollutioncraft.mod.emitter;

/**
 * One validated emitter mapping (format: {@code docs/emitter-mappings.md}). Plain
 * strings only: registry resolution (does the block, tag, property or value exist) happens later, with
 * real registries, in {@link EmitterRules#resolve}.
 *
 * @param target block id, or tag id without the leading {@code #}
 * @param rate PU per interval, finite and {@code >= 0}
 * @param property the block-state property that decides whether the block is active
 * @param value the serialized value of {@code property} that means active
 */
public record MappingEntry(TargetKind kind, String target, double rate, String property, String value) {
    public enum TargetKind {
        BLOCK,
        TAG
    }
}
