package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Budget guard (architecture 5): 0.5 ms/tick on average. One full step at 2,500 chunks runs once per
 * 20-tick interval, so the whole step must cost under 10 ms. The threshold is generous so the test
 * stays stable on CI; the measured numbers are printed for the record.
 */
class DiffusionPerformanceTest {
    private static PollutionField fieldWith2500Chunks() {
        Random rnd = new Random(11);
        PollutionField field = new PollutionField();
        while (field.size() < 2500) {
            field.getOrCreate(ChunkKey.pack(rnd.nextInt(60) - 30, rnd.nextInt(60) - 30)).absorbable = 5 + rnd.nextDouble() * 100;
        }
        return field;
    }

    @Test
    void fullStepAt2500ChunksFitsTheTickBudget() {
        DiffusionConfig config = new DiffusionConfig(0.02, 15.0, 0.0, 0.001);
        long[] nanos = new long[60];
        int stored = 0;
        for (int i = 0; i < 20 + nanos.length; i++) {
            PollutionField field = fieldWith2500Chunks(); // fresh each run: every stored chunk spreads
            stored = field.size();
            long t = System.nanoTime();
            DiffusionStep.run(field, Map.of(), key -> 0.0, config);
            long elapsed = System.nanoTime() - t;
            if (i >= 20) { // first 20 runs warm up the JIT
                nanos[i - 20] = elapsed;
            }
        }
        Arrays.sort(nanos);
        double medianMs = nanos[nanos.length / 2] / 1e6;
        double p95Ms = nanos[(int) (nanos.length * 0.95)] / 1e6;
        System.out.printf("PERF diffusion step @%d chunks: median %.3f ms, p95 %.3f ms, per-tick avg (/20) %.3f ms%n",
                stored, medianMs, p95Ms, medianMs / 20);

        assertTrue(medianMs < 10.0, "median full step " + medianMs + " ms exceeds 10 ms (0.5 ms x 20 ticks)");
    }
}
