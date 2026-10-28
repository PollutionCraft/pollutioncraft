package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;
import org.junit.jupiter.api.Test;

class SoftCapAbsorptionTest {
    // The placeholder curves of ADR 0004 B, used by 014; nothing in the production classes hard-codes them.
    private static final AbsorberCurve LEAVES = new AbsorberCurve(2.0, 64);
    private static final AbsorberCurve GRASS = new AbsorberCurve(0.5, 128);
    private static final AbsorberCurve CROPS = new AbsorberCurve(1.0, 64);
    private static final AbsorberCurve WATER = new AbsorberCurve(0.25, 128);

    private static SoftCapAbsorption table() {
        return new SoftCapAbsorption(List.of(
                new SoftCapAbsorption.Type("leaves", LEAVES),
                new SoftCapAbsorption.Type("grass", GRASS),
                new SoftCapAbsorption.Type("crops", CROPS),
                new SoftCapAbsorption.Type("water", WATER)));
    }

    @Test
    void theCapacityIsTheSumOfEachCurve() {
        double expected = LEAVES.absorb(100) + GRASS.absorb(30) + CROPS.absorb(0) + WATER.absorb(7);

        assertEquals(expected, table().capacity(new int[] {100, 30, 0, 7}), 1e-12);
    }

    @Test
    void theOrderOfTheTypesDoesNotChangeTheResultForTheSameCountsById() {
        SoftCapAbsorption reversed = new SoftCapAbsorption(List.of(
                new SoftCapAbsorption.Type("water", WATER),
                new SoftCapAbsorption.Type("crops", CROPS),
                new SoftCapAbsorption.Type("grass", GRASS),
                new SoftCapAbsorption.Type("leaves", LEAVES)));

        assertEquals(table().capacity(new int[] {100, 30, 9, 7}), reversed.capacity(new int[] {7, 9, 30, 100}), 1e-12);
    }

    @Test
    void theSameCountsGiveTheSameBitsEveryTime() {
        int[] counts = {321, 17, 4, 999};

        assertEquals(Double.doubleToLongBits(table().capacity(counts)), Double.doubleToLongBits(table().capacity(counts)));
    }

    @Test
    void aDuplicateIdIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new SoftCapAbsorption(List.of(
                new SoftCapAbsorption.Type("leaves", LEAVES), new SoftCapAbsorption.Type("leaves", GRASS))));
        assertTrue(e.getMessage().contains("leaves"), e.getMessage());
    }

    @Test
    void aBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SoftCapAbsorption.Type(" ", LEAVES));
    }

    @Test
    void aLengthMismatchOrANegativeCountThrows() {
        assertThrows(IllegalArgumentException.class, () -> table().capacity(new int[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> table().capacity(new int[] {1, 2, 3, 4, 5}));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> table().capacity(new int[] {1, -2, 3, 4}));
        assertTrue(e.getMessage().contains("-2"), e.getMessage());
    }

    @Test
    void indexOfFindsATypeAndReturnsMinusOneForAnUnknownOne() {
        assertEquals(0, table().indexOf("leaves"));
        assertEquals(3, table().indexOf("water"));
        assertEquals(-1, table().indexOf("lava"));
        assertEquals(4, table().size());
    }

    @Test
    void anEmptyTableGivesZero() {
        assertEquals(0.0, new SoftCapAbsorption(List.of()).capacity(new int[0]), 0.0);
    }

    @Test
    void theTableCopiesItsList() {
        List<SoftCapAbsorption.Type> mutable = new ArrayList<>(List.of(new SoftCapAbsorption.Type("leaves", LEAVES)));
        SoftCapAbsorption absorption = new SoftCapAbsorption(mutable);
        mutable.add(new SoftCapAbsorption.Type("grass", GRASS));

        assertEquals(1, absorption.size());
        assertThrows(IllegalArgumentException.class, () -> absorption.capacity(new int[] {1, 1}));
    }

    @Test
    void theResultIsFiniteAndNotNegativeEvenForHugeCounts() {
        double capacity = table().capacity(new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE});

        assertTrue(Double.isFinite(capacity) && capacity >= 0, "capacity " + capacity);
        assertEquals(2.0 + 0.5 + 1.0 + 0.25, capacity, 1e-12);
    }

    // ---- ADR 0004 B as a player sees it: spamming one type does not scale ------------------------------

    @Test
    void nineTimesMoreLeavesAddLessThanTheFirstBatchDid() {
        double firstBatch = LEAVES.absorb(64) - LEAVES.absorb(0);
        double nineMoreBatches = LEAVES.absorb(640) - LEAVES.absorb(64);

        assertTrue(nineMoreBatches < firstBatch, "9k more leaves added " + nineMoreBatches + ", the first k added " + firstBatch);
    }

    @Test
    void aDifferentTypeBeatsMoreOfTheSameOne() {
        // 10k leaves already; 90k more leaves versus k crops.
        double moreLeaves = LEAVES.absorb(640 + 5_760) - LEAVES.absorb(640);
        double someCrops = CROPS.absorb(64) - CROPS.absorb(0);

        assertTrue(moreLeaves < 1e-3, "90k more leaves added " + moreLeaves);
        assertTrue(someCrops > 100 * moreLeaves, "k crops added " + someCrops + ", 90k leaves " + moreLeaves);
    }

    // ---- the seam ----------------------------------------------------------------------------------------

    @Test
    void theAbsorptionModelIsAFunctionalSeamThatFitsWhereTheDiffusionTakesAFunction() {
        assertTrue(AbsorptionModel.class.isAnnotationPresent(FunctionalInterface.class));

        double capacity = table().capacity(new int[] {64, 0, 0, 0});
        AbsorptionModel model = chunkKey -> capacity;
        LongToDoubleFunction asFunction = model::capacity;

        assertEquals(capacity, asFunction.applyAsDouble(ChunkKey.pack(3, 4)), 0.0);
    }
}
