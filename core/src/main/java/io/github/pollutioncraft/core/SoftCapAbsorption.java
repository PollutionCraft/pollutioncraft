package io.github.pollutioncraft.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The absorber types of a pack and their curves (ADR 0004 B): a chunk's absorption capacity per interval is the
 * sum over types of {@link AbsorberCurve#absorb} for the number of absorbers of that type sampled in the chunk.
 * Built from an ordered list; the position of a type in that list is the index into the {@code counts} array of
 * {@link #capacity}. Immutable, and free of any Minecraft type (sampling and the type list are task 014).
 *
 * <p>Meant to be evaluated <em>when a chunk is sampled</em> (and again after a data reload), with the result kept
 * as a plain {@code double} per chunk; the interval then only reads a number. {@code capacity} allocates nothing
 * and adds in table order, so it is deterministic.
 */
public final class SoftCapAbsorption {
    /** One absorber type. {@code id} is a plain string, for example a block tag id; it must not be blank. */
    public record Type(String id, AbsorberCurve curve) {
        public Type {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("absorber type id must not be blank, was '" + id + "'");
            }
            if (curve == null) {
                throw new IllegalArgumentException("absorber type " + id + " has no curve");
            }
        }
    }

    private final AbsorberCurve[] curves;
    private final Map<String, Integer> indexById = new HashMap<>();

    /** @throws IllegalArgumentException for a duplicate id */
    public SoftCapAbsorption(List<Type> types) {
        curves = new AbsorberCurve[types.size()];
        for (int i = 0; i < curves.length; i++) {
            Type type = types.get(i);
            if (indexById.put(type.id(), i) != null) {
                throw new IllegalArgumentException("duplicate absorber type id " + type.id());
            }
            curves[i] = type.curve();
        }
    }

    /** Number of types. */
    public int size() {
        return curves.length;
    }

    /** Index of the type in the table, or -1 for an unknown id (never an exception, so a removed type can be skipped). */
    public int indexOf(String typeId) {
        return indexById.getOrDefault(typeId, -1);
    }

    /**
     * Absorption capacity of a chunk per interval, in PU.
     *
     * @param counts {@code counts[i]} is the number of absorbers of the i-th type of this table
     * @return finite and {@code >= 0}
     * @throws IllegalArgumentException if the length differs from {@link #size()} or a count is negative
     */
    public double capacity(int[] counts) {
        if (counts.length != curves.length) {
            throw new IllegalArgumentException("expected " + curves.length + " counts, got " + counts.length);
        }
        double total = 0;
        for (int i = 0; i < curves.length; i++) {
            total += curves[i].absorb(counts[i]);
        }
        return total;
    }
}
