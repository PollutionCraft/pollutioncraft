package io.github.pollutioncraft.core;

import java.util.Map;
import java.util.function.LongToDoubleFunction;

/**
 * Runs the diffusion interval time-sliced (architecture 3.3). Call {@link #tick} once per <em>active</em>
 * tick (the caller applies the {@link SimulationGate}). The first tick of an interval snapshots the field;
 * the work is spread evenly over the interval's ticks; the deltas are applied on its last tick.
 * {@link #stepNow} runs whole intervals synchronously through the same {@link DiffusionJob}.
 */
public final class IntervalScheduler {
    private final int intervalTicks;
    private final DiffusionConfig config;
    private DiffusionJob job;
    private int ticksIntoInterval;

    public IntervalScheduler(int intervalTicks, DiffusionConfig config) {
        if (intervalTicks < 1) {
            throw new IllegalArgumentException("intervalTicks must be >= 1, was " + intervalTicks);
        }
        this.intervalTicks = intervalTicks;
        this.config = config;
    }

    /**
     * @param emissions pending emissions, drained into the snapshot only on the interval's first tick
     * @return {@code true} when this tick applied the interval's deltas to the field
     */
    public boolean tick(PollutionField field, Map<Long, Double> emissions, LongToDoubleFunction capacity) {
        if (job == null) {
            job = DiffusionJob.start(field, emissions, capacity, config);
            ticksIntoInterval = 0;
        }
        ticksIntoInterval++;
        int ticksLeft = intervalTicks - ticksIntoInterval + 1;
        if (ticksLeft == 1) {
            finishNow();
            return true;
        }
        int remaining = job.chunkCount() - job.computedChunks();
        job.advance(Math.max(1, Math.ceilDiv(remaining, ticksLeft)));
        return false;
    }

    /** True while an interval has been started but not yet applied. */
    public boolean inFlight() {
        return job != null;
    }

    /**
     * Runs {@code n} whole intervals now. An in-flight interval is finished first so no work is lost.
     * Exceptions propagate before the failing interval changes the field.
     */
    public void stepNow(PollutionField field, Map<Long, Double> emissions, LongToDoubleFunction capacity, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, was " + n);
        }
        if (job != null) {
            finishNow();
        }
        for (int i = 0; i < n; i++) {
            DiffusionStep.run(field, i == 0 ? emissions : Map.of(), capacity, config);
        }
    }

    /** Drops the in-flight interval without applying it (for example after an error). */
    public void abandon() {
        job = null;
    }

    private void finishNow() {
        DiffusionJob finishing = job;
        finishing.advance(Math.max(1, finishing.chunkCount()));
        finishing.apply();
        job = null;
    }

}
