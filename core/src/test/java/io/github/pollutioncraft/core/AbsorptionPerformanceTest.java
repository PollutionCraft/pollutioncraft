package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Cost of the soft-cap capacity (task 013). 014 computes a chunk's capacity when the chunk is sampled and keeps a
 * plain {@code double}, so the interval never calls {@code capacity}; this reports what one call and a full
 * recompute of 2,500 chunks cost. Budget: the full recompute fits one 20-tick interval at 0.05 ms/tick averaged
 * (1 ms). The numbers are printed, not asserted on wall time.
 */
class AbsorptionPerformanceTest {
    private static SoftCapAbsorption table() {
        return new SoftCapAbsorption(List.of(
                new SoftCapAbsorption.Type("leaves", new AbsorberCurve(2.0, 64)),
                new SoftCapAbsorption.Type("grass", new AbsorberCurve(0.5, 128)),
                new SoftCapAbsorption.Type("crops", new AbsorberCurve(1.0, 64)),
                new SoftCapAbsorption.Type("water", new AbsorberCurve(0.25, 128))));
    }

    @Test
    void reportTheCostOfOneCapacityCallAndOfRecomputingAllChunks() {
        SoftCapAbsorption absorption = table();
        Random random = new Random(5);
        int chunks = 2_500;
        int[][] counts = new int[chunks][4];
        for (int[] row : counts) {
            for (int t = 0; t < 4; t++) {
                row[t] = random.nextInt(400);
            }
        }
        double[] capacities = new double[chunks];
        double sink = 0;
        for (int round = 0; round < 200; round++) { // warm the JIT
            for (int c = 0; c < chunks; c++) {
                sink += absorption.capacity(counts[c]);
            }
        }

        long[] fullNanos = new long[200];
        for (int round = 0; round < fullNanos.length; round++) {
            long begin = System.nanoTime();
            for (int c = 0; c < chunks; c++) {
                capacities[c] = absorption.capacity(counts[c]);
            }
            fullNanos[round] = System.nanoTime() - begin;
        }
        Arrays.sort(fullNanos);
        double fullMs = fullNanos[fullNanos.length / 2] / 1e6;
        double perCallNs = fullNanos[fullNanos.length / 2] / (double) chunks;
        sink += Arrays.stream(capacities).sum();

        System.out.printf("PERF 013 capacity(): %.0f ns per call (4 types); recomputing %d chunks x 4 types once: %.3f ms "
                + "(= %.4f ms/tick if spread over a 20-tick interval; budget 0.05)%n", perCallNs, chunks, fullMs, fullMs / 20);
        assertTrue(Double.isFinite(sink), "keeps the loop from being optimised away");
    }
}
