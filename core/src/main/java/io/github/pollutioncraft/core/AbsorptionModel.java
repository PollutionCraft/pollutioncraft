package io.github.pollutioncraft.core;

/**
 * How much absorbable pollution a chunk can remove per interval. The seam the scheduler consumes: a functional
 * interface, so a method reference to it can be passed wherever {@link DiffusionJob} takes a
 * {@code LongToDoubleFunction}. The curve is {@link SoftCapAbsorption}; looking up a chunk's counts (sampling and
 * the per-chunk cache) is task 014 and implements this interface.
 */
@FunctionalInterface
public interface AbsorptionModel {
    /** Capacity of the chunk for one interval; finite and {@code >= 0}. */
    double capacity(long chunkKey);
}
