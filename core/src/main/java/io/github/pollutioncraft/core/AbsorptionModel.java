package io.github.pollutioncraft.core;

/** How much absorbable pollution a chunk can remove per interval. Curve and sampling arrive in task 013. */
public interface AbsorptionModel {
    /** Capacity of the chunk for one interval; finite and {@code >= 0}. */
    double capacity(long chunkKey);
}
