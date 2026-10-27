package io.github.pollutioncraft.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OverlayDisplayTest {
    private static final List<String> NO_DATA = List.of("Pollution", "no data");
    private static final OverlayDisplay.Shown HERE = new OverlayDisplay.Shown("minecraft:overworld", 3, -4, 20, List.of("absorbable: 5"));

    private final OverlayDisplay display = new OverlayDisplay();

    @Test
    void withNoPayloadYetItShowsNoData() {
        assertEquals(NO_DATA, display.render("minecraft:overworld", 3, -4, 100));
    }

    @Test
    void aFreshPayloadForThisChunkShowsItsLinesUnderTheTitle() {
        display.receive(HERE, 100);

        assertEquals(List.of("Pollution", "absorbable: 5"), display.render("minecraft:overworld", 3, -4, 110));
    }

    @Test
    void anotherChunkOrDimensionShowsNoData() {
        display.receive(HERE, 100);

        assertEquals(NO_DATA, display.render("minecraft:overworld", 4, -4, 101));
        assertEquals(NO_DATA, display.render("minecraft:overworld", 3, -5, 101));
        assertEquals(NO_DATA, display.render("minecraft:the_nether", 3, -4, 101));
    }

    @Test
    void aPayloadOlderThanThreeIntervalsShowsNoData() {
        display.receive(HERE, 100);

        assertEquals(List.of("Pollution", "absorbable: 5"), display.render("minecraft:overworld", 3, -4, 160), "exactly 3 x 20 is still fresh");
        assertEquals(NO_DATA, display.render("minecraft:overworld", 3, -4, 161));
    }

    @Test
    void anUnsupportedVersionClearsTheOldNumbersAndIsLoggedOnce() {
        display.receive(HERE, 100);

        assertTrue(display.receiveUnsupported(2), "the first rejection is reported so it can be logged");
        assertFalse(display.receiveUnsupported(2), "and only the first");
        assertEquals(NO_DATA, display.render("minecraft:overworld", 3, -4, 101), "never the old numbers");
    }

    @Test
    void aNewGoodPayloadAfterAnUnsupportedOneShowsAgain() {
        display.receiveUnsupported(2);
        display.receive(HERE, 100);

        assertEquals(List.of("Pollution", "absorbable: 5"), display.render("minecraft:overworld", 3, -4, 101));
    }

    @Test
    void clearForgetsEverything() {
        display.receive(HERE, 100);
        display.clear();

        assertEquals(NO_DATA, display.render("minecraft:overworld", 3, -4, 101));
    }

    @Test
    void aClockThatWentBackwardsIsStale() {
        display.receive(HERE, 100);

        assertEquals(NO_DATA, display.render("minecraft:overworld", 3, -4, 50));
    }
}
