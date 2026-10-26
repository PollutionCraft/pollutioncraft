package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldRecordTest {
    @Test
    void activeTicksAdvanceOnlyWhileAPlayerIsOnline() {
        WorldRecord world = new WorldRecord();

        world.onServerTick(0);
        world.onServerTick(0);
        assertEquals(0, world.activeTicks());
        world.onServerTick(1);
        world.onServerTick(5);
        assertEquals(2, world.activeTicks());
        world.onServerTick(0);
        assertEquals(2, world.activeTicks());
    }

    @Test
    void gateIsOpenWithAtLeastOnePlayer() {
        assertFalse(SimulationGate.isOpen(0));
        assertTrue(SimulationGate.isOpen(1));
        assertTrue(SimulationGate.isOpen(20));
    }

    @Test
    void grossEmittedOnlyGrowsAndRejectsInvalidAmounts() {
        WorldRecord world = new WorldRecord();
        world.addGrossEmitted(2.5);
        world.addGrossEmitted(0.0);
        assertEquals(2.5, world.grossEmitted(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> world.addGrossEmitted(-1));
        assertThrows(IllegalArgumentException.class, () -> world.addGrossEmitted(Double.NaN));
        assertEquals(2.5, world.grossEmitted(), 0.0);
    }

    @Test
    void restoredValuesAreValidated() {
        WorldRecord world = new WorldRecord(100L, 7.5);
        assertEquals(100L, world.activeTicks());
        assertEquals(7.5, world.grossEmitted(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> new WorldRecord(-1L, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldRecord(0L, Double.NaN));
    }

    @Test
    void pressureReadsZeroUntilTask016() {
        assertEquals(0.0, new WorldRecord(10L, 10.0).pressure(), 0.0);
    }
}
