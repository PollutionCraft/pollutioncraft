package io.github.pollutioncraft.core;

/** Per-world global record (architecture 3.2): {@code activeTicks} and {@code grossEmitted}. */
public final class WorldRecord {
    public static final int DATA_VERSION = 1;

    private long activeTicks;
    private double grossEmitted;

    public WorldRecord() {}

    /** Restores persisted values; rejects values no valid record can hold. */
    public WorldRecord(long activeTicks, double grossEmitted) {
        if (activeTicks < 0) {
            throw new IllegalArgumentException("activeTicks must be >= 0, was " + activeTicks);
        }
        requireValidAmount(grossEmitted);
        this.activeTicks = activeTicks;
        this.grossEmitted = grossEmitted;
    }

    /** Call once per server tick; counts only ticks the {@link SimulationGate} lets through. */
    public void onServerTick(int onlinePlayers) {
        if (SimulationGate.isOpen(onlinePlayers)) {
            activeTicks++;
        }
    }

    public void addGrossEmitted(double amount) {
        requireValidAmount(amount);
        grossEmitted += amount;
    }

    public long activeTicks() {
        return activeTicks;
    }

    public double grossEmitted() {
        return grossEmitted;
    }

    /** Stored pressure slot; the maths arrive with task 016, so this reads 0 until then. */
    public double pressure() {
        return 0.0;
    }

    private static void requireValidAmount(double amount) {
        if (!(amount >= 0) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("amount must be finite and >= 0, was " + amount);
        }
    }
}
