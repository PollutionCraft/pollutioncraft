package io.github.pollutioncraft.core;

import java.util.function.DoubleConsumer;
import java.util.function.LongToDoubleFunction;
import java.util.function.Supplier;

/**
 * Runs the diffusion interval time-sliced (architecture 3.3). Call {@link #tick} once per <em>active</em>
 * tick (the caller applies the {@link SimulationGate}). The first tick of an interval snapshots the field and
 * drains the {@link EmissionBuffer} into it; the work is spread evenly over the interval's ticks; the deltas
 * are applied on its last tick. {@link #stepNow} runs whole intervals synchronously through the same
 * {@link DiffusionJob}. The settings are read from the supplier once when an interval starts, so a change
 * applies from the next interval and never alters one in flight.
 *
 * <p>The drained emissions are held until the interval ends. On commit they are reported to the caller's
 * {@code onCommit} (exactly once per committed interval, after the field was written; {@code 0} when nothing
 * was emitted). If the interval is dropped instead ({@link #abandon}, or an exception while it runs) they go
 * back into the buffer and join the next interval, and nothing is reported.
 */
public final class IntervalScheduler {
    /** The settings of one interval: its length in ticks and the diffusion parameters. */
    public record Settings(int intervalTicks, DiffusionConfig config) {
        public Settings {
            if (intervalTicks < 1) {
                throw new IllegalArgumentException("intervalTicks must be >= 1, was " + intervalTicks);
            }
        }
    }

    private final Supplier<Settings> settings;
    private final EmissionBuffer emissions;
    private DiffusionJob job;
    private DrainedEmissions drained = DrainedEmissions.NONE;
    private int intervalTicks;
    private int ticksIntoInterval;

    public IntervalScheduler(Supplier<Settings> settings, EmissionBuffer emissions) {
        this.settings = settings;
        this.emissions = emissions;
    }

    /**
     * @param onCommit receives the interval's emission total (PU) when this tick commits it
     * @return {@code true} when this tick applied the interval's deltas to the field
     */
    public boolean tick(PollutionField field, LongToDoubleFunction capacity, DoubleConsumer onCommit) {
        if (job == null) {
            Settings current = settings.get();
            intervalTicks = current.intervalTicks();
            startJob(field, capacity, current.config());
            ticksIntoInterval = 0;
        }
        ticksIntoInterval++;
        int ticksLeft = intervalTicks - ticksIntoInterval + 1;
        if (ticksLeft == 1) {
            finishNow(onCommit);
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
     * Runs {@code n} whole intervals now. An in-flight interval is finished first so no work is lost. The
     * first new interval drains the buffer. Each committed interval is reported to {@code onCommit}. An
     * interval that throws leaves the field unchanged, gives its emissions back to the buffer and propagates.
     */
    public void stepNow(PollutionField field, LongToDoubleFunction capacity, int n, DoubleConsumer onCommit) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, was " + n);
        }
        if (job != null) {
            finishNow(onCommit);
        }
        DiffusionConfig config = n == 0 ? null : settings.get().config();
        for (int i = 0; i < n; i++) {
            DrainedEmissions interval = i == 0 ? emissions.drain() : DrainedEmissions.NONE;
            try {
                DiffusionStep.run(field, interval, capacity, config);
            } catch (RuntimeException e) {
                emissions.requeue(interval);
                throw e;
            }
            onCommit.accept(interval.total());
        }
    }

    /**
     * Drops the in-flight interval without applying it (for example after an error). Its drained emissions
     * are put back into the buffer for the next interval.
     */
    public void abandon() {
        if (job != null) {
            emissions.requeue(drained);
        }
        job = null;
        drained = DrainedEmissions.NONE;
    }

    private void startJob(PollutionField field, LongToDoubleFunction capacity, DiffusionConfig config) {
        DrainedEmissions interval = emissions.drain();
        try {
            job = DiffusionJob.start(field, interval, capacity, config);
        } catch (RuntimeException e) {
            emissions.requeue(interval);
            throw e;
        }
        drained = interval;
    }

    private void finishNow(DoubleConsumer onCommit) {
        DiffusionJob finishing = job;
        try {
            finishing.advance(Math.max(1, finishing.chunkCount()));
            finishing.apply();
        } catch (RuntimeException e) {
            abandon();
            throw e;
        }
        double total = drained.total();
        job = null;
        drained = DrainedEmissions.NONE;
        onCommit.accept(total);
    }
}
