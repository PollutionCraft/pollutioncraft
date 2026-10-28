package io.github.pollutioncraft.mod.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MappingValidatorTest {
    private static final String FILE = "pollutioncraft:minecraft/furnace";

    private final List<String> log = new ArrayList<>();

    private static Map<String, Object> furnace() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("block", "minecraft:furnace");
        raw.put("rate", 1.0);
        raw.put("active_when", new HashMap<>(Map.of("property", "lit", "value", "true")));
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> activeWhen(Map<String, Object> raw) {
        return (Map<String, Object>) raw.get("active_when");
    }

    private Optional<MappingEntry> validate(Map<String, Object> raw) {
        return MappingValidator.validate(FILE, raw, log::add);
    }

    @Test
    void aValidBlockEntryIsAccepted() {
        MappingEntry entry = validate(furnace()).orElseThrow();

        assertEquals(MappingEntry.TargetKind.BLOCK, entry.kind());
        assertEquals("minecraft:furnace", entry.target());
        assertEquals(1.0, entry.rate());
        assertEquals(new MappingEntry.ActiveWhen("lit", "true"), entry.activeWhen());
        assertFalse(entry.isAlwaysActive());
        assertTrue(log.isEmpty(), log::toString);
    }

    @Test
    void aValidTagEntryIsAccepted() {
        Map<String, Object> raw = furnace();
        raw.remove("block");
        raw.put("tag", "minecraft:campfires");
        raw.put("rate", 0.0);

        MappingEntry entry = validate(raw).orElseThrow();

        assertEquals(MappingEntry.TargetKind.TAG, entry.kind());
        assertEquals("minecraft:campfires", entry.target());
        assertEquals(0.0, entry.rate());
        assertTrue(log.isEmpty(), log::toString);
    }

    @Test
    void anEntryWithoutActiveWhenIsAlwaysActive() {
        Map<String, Object> raw = furnace();
        raw.remove("active_when");
        raw.put("block", "minecraft:torch");
        raw.put("rate", 0.01);

        MappingEntry entry = validate(raw).orElseThrow();

        assertTrue(entry.isAlwaysActive());
        assertEquals(null, entry.activeWhen());
        assertEquals("minecraft:torch", entry.target());
        assertEquals(0.01, entry.rate());
        assertTrue(log.isEmpty(), log::toString);
    }

    @Test
    void anAlwaysActiveTagEntryIsAcceptedToo() {
        Map<String, Object> raw = furnace();
        raw.remove("active_when");
        raw.remove("block");
        raw.put("tag", "minecraft:candles");

        assertTrue(validate(raw).orElseThrow().isAlwaysActive());
    }

    static Stream<Arguments> invalidEntries() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("no target", (java.util.function.Consumer<Map<String, Object>>) r -> r.remove("block")));
        cases.add(Arguments.of("both targets", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("tag", "minecraft:x")));
        cases.add(Arguments.of("blank block id", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("block", " ")));
        cases.add(Arguments.of("block is not a string", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("block", 5)));
        cases.add(Arguments.of("missing rate", (java.util.function.Consumer<Map<String, Object>>) r -> r.remove("rate")));
        cases.add(Arguments.of("rate is a string", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("rate", "1.0")));
        cases.add(Arguments.of("negative rate", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("rate", -0.5)));
        cases.add(Arguments.of("NaN rate", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("rate", Double.NaN)));
        cases.add(Arguments.of("infinite rate", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("rate", Double.POSITIVE_INFINITY)));
        cases.add(Arguments.of("active_when is null", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("active_when", null)));
        cases.add(Arguments.of("active_when is an empty object", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("active_when", new HashMap<>())));
        cases.add(Arguments.of("property without value", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("active_when", new HashMap<>(Map.of("property", "lit")))));
        cases.add(Arguments.of("value without property", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("active_when", new HashMap<>(Map.of("value", "true")))));
        cases.add(Arguments.of("empty property", (java.util.function.Consumer<Map<String, Object>>) r -> activeWhen(r).put("property", "")));
        cases.add(Arguments.of("empty value", (java.util.function.Consumer<Map<String, Object>>) r -> activeWhen(r).put("value", "")));
        cases.add(Arguments.of("blank value", (java.util.function.Consumer<Map<String, Object>>) r -> activeWhen(r).put("value", "  ")));
        cases.add(Arguments.of("property is a number", (java.util.function.Consumer<Map<String, Object>>) r -> activeWhen(r).put("property", 3)));
        cases.add(Arguments.of("active_when is not an object", (java.util.function.Consumer<Map<String, Object>>) r -> r.put("active_when", "lit=true")));
        cases.add(Arguments.of("missing property", (java.util.function.Consumer<Map<String, Object>>) r -> activeWhen(r).remove("property")));
        cases.add(Arguments.of("missing value", (java.util.function.Consumer<Map<String, Object>>) r -> activeWhen(r).remove("value")));
        cases.add(Arguments.of("value is a boolean, not a string", (java.util.function.Consumer<Map<String, Object>>) r -> activeWhen(r).put("value", true)));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEntries")
    void anInvalidEntryIsSkippedWithExactlyOneLogLineNamingTheFile(String name, java.util.function.Consumer<Map<String, Object>> breakIt) {
        Map<String, Object> raw = furnace();
        breakIt.accept(raw);

        assertTrue(validate(raw).isEmpty());

        assertEquals(1, log.size(), log::toString);
        assertTrue(log.get(0).contains(FILE), log.get(0));
    }

    private static Map<String, Object> cart() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("entity", "minecraft:furnace_minecart");
        raw.put("rate", 0.45);
        return raw;
    }

    @Test
    void aValidEntityEntryIsAccepted() {
        MappingEntry entry = validate(cart()).orElseThrow();

        assertEquals(MappingEntry.TargetKind.ENTITY, entry.kind());
        assertEquals("minecraft:furnace_minecart", entry.target());
        assertEquals(0.45, entry.rate());
        assertTrue(entry.isAlwaysActive(), "an entity entry carries no active_when; its activity is built in");
        assertTrue(log.isEmpty(), log::toString);
    }

    @Test
    void anEntityEntryTogetherWithABlockOrTagIsRefused() {
        Map<String, Object> withBlock = cart();
        withBlock.put("block", "minecraft:furnace");
        Map<String, Object> withTag = cart();
        withTag.put("tag", "minecraft:campfires");

        assertTrue(validate(withBlock).isEmpty());
        assertTrue(validate(withTag).isEmpty());
        assertEquals(2, log.size());
        assertTrue(log.get(0).contains(FILE), log.get(0));
    }

    @Test
    void anEntityEntryWithActiveWhenIsRefused() {
        Map<String, Object> raw = cart();
        raw.put("active_when", new HashMap<>(Map.of("property", "lit", "value", "true")));

        assertTrue(validate(raw).isEmpty());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("active_when"), log.get(0));
    }

    @Test
    void anEntityEntryNeedsANonBlankIdAndAGoodRate() {
        Map<String, Object> blank = cart();
        blank.put("entity", " ");
        Map<String, Object> negative = cart();
        negative.put("rate", -1.0);
        Map<String, Object> missing = cart();
        missing.remove("rate");

        assertTrue(validate(blank).isEmpty());
        assertTrue(validate(negative).isEmpty());
        assertTrue(validate(missing).isEmpty());
        assertEquals(3, log.size());
    }
}
