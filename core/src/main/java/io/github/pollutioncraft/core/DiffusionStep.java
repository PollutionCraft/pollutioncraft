package io.github.pollutioncraft.core;

import java.util.Map;
import java.util.function.LongToDoubleFunction;

/**
 * One whole interval of the pollution simulation (architecture 3.3), computed in a single pass. This is
 * {@link DiffusionJob} run to completion; the scheduler uses the same job in slices, so both give identical
 * results.
 */
public final class DiffusionStep {
    private DiffusionStep() {}

    /** See {@link DiffusionJob#start} for the parameter contract. */
    public static void run(
            PollutionField field,
            Map<Long, Double> emissions,
            LongToDoubleFunction absorptionCapacity,
            DiffusionConfig config) {
        DiffusionJob job = DiffusionJob.start(field, emissions, absorptionCapacity, config);
        job.advance(Math.max(1, job.chunkCount()));
        job.apply();
    }
}
