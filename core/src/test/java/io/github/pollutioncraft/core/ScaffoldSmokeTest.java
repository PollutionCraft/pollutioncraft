package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScaffoldSmokeTest {
    @Test
    void coreIsWiredIntoTheUnitTestLayer() {
        assertEquals("pollutioncraft-core", CoreInfo.NAME);
    }
}
