package io.github.pollutioncraft.mod.emitter;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The emitter mappings resolved against the real registries: block (or tag) to the states that count as
 * active and their rate. Immutable, so one reference swap replaces the whole rule set on a data reload.
 *
 * <p>{@link #resolve} is where the entries that only the registries can judge are skipped and logged:
 * unknown block or tag, a property the block does not have, a value the property cannot take. An unknown
 * tag counts as an empty tag.
 */
public final class EmitterRules {
    public static final EmitterRules EMPTY = new EmitterRules(new Reference2ObjectOpenHashMap<>());

    /** A validated entry and the id of the file it came from. */
    public record NamedEntry(String id, MappingEntry entry) {}

    /** {@code property} is null for an always-active rule, which matches every state of its block. */
    private record Rule(String id, Property<?> property, Comparable<?> value, double rate) {
        boolean matches(BlockState state) {
            return property == null || state.getValue(property).equals(value);
        }

        /** Whether some state can satisfy both rules. */
        boolean overlaps(Rule other) {
            return property == null || other.property == null || !property.equals(other.property) || value.equals(other.value);
        }
    }

    private static final Rule[] NO_RULES = new Rule[0];

    private final Reference2ObjectOpenHashMap<Block, Rule[]> byBlock;

    private EmitterRules(Reference2ObjectOpenHashMap<Block, Rule[]> byBlock) {
        this.byBlock = byBlock;
    }

    /**
     * Resolves {@code entries} (any order). If several entries can apply to the same block state, the one
     * with the lowest id wins and one warning names both.
     */
    public static EmitterRules resolve(List<NamedEntry> entries, Registry<Block> blocks, Consumer<String> log) {
        List<NamedEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(NamedEntry::id));
        Reference2ObjectOpenHashMap<Block, List<Rule>> building = new Reference2ObjectOpenHashMap<>();
        Set<String> warnedConflicts = new HashSet<>();
        for (NamedEntry named : sorted) {
            MappingEntry entry = named.entry();
            List<Block> targets = targetsOf(named, blocks, log);
            int lacking = 0;
            for (Block block : targets) {
                Rule rule = ruleFor(named, block, log);
                if (rule == null) {
                    lacking++;
                    continue;
                }
                List<Rule> rules = building.computeIfAbsent(block, key -> new ArrayList<>());
                for (Rule earlier : rules) {
                    if (earlier.overlaps(rule) && warnedConflicts.add(earlier.id() + "|" + rule.id())) {
                        log.accept("Emitter mappings " + earlier.id() + " and " + rule.id() + " can both apply to the same state of "
                                + "a block; " + earlier.id() + " wins (lowest id)");
                    }
                }
                rules.add(rule);
            }
            if (lacking > 0 && entry.kind() == MappingEntry.TargetKind.TAG) {
                log.accept("Emitter mapping " + named.id() + ": " + lacking + " block(s) of tag " + entry.target()
                        + " lack property '" + entry.activeWhen().property() + "' or its value '" + entry.activeWhen().value() + "'; those were skipped");
            }
        }
        Reference2ObjectOpenHashMap<Block, Rule[]> frozen = new Reference2ObjectOpenHashMap<>();
        building.forEach((block, rules) -> frozen.put(block, rules.toArray(NO_RULES)));
        return new EmitterRules(frozen);
    }

    /** The rule for one block, or null (after logging for a block target) if the block lacks the property or value. */
    private static Rule ruleFor(NamedEntry named, Block block, Consumer<String> log) {
        MappingEntry entry = named.entry();
        if (entry.isAlwaysActive()) {
            return new Rule(named.id(), null, null, entry.rate());
        }
        MappingEntry.ActiveWhen when = entry.activeWhen();
        Property<?> property = block.getStateDefinition().getProperty(when.property());
        if (property == null) {
            if (entry.kind() == MappingEntry.TargetKind.BLOCK) {
                log.accept("Skipping emitter mapping " + named.id() + ": block " + entry.target() + " has no property '" + when.property() + "'");
            }
            return null;
        }
        Comparable<?> value = property.getValue(when.value()).orElse(null);
        if (value == null) {
            if (entry.kind() == MappingEntry.TargetKind.BLOCK) {
                log.accept("Skipping emitter mapping " + named.id() + ": property '" + when.property() + "' of " + entry.target()
                        + " cannot be '" + when.value() + "'");
            }
            return null;
        }
        return new Rule(named.id(), property, value, entry.rate());
    }

    private static List<Block> targetsOf(NamedEntry named, Registry<Block> blocks, Consumer<String> log) {
        MappingEntry entry = named.entry();
        ResourceLocation id = ResourceLocation.tryParse(entry.target());
        if (entry.kind() == MappingEntry.TargetKind.BLOCK) {
            if (id == null || !blocks.containsKey(id)) {
                log.accept("Skipping emitter mapping " + named.id() + ": unknown block '" + entry.target() + "'");
                return List.of();
            }
            return List.of(blocks.get(id));
        }
        HolderSet.Named<Block> tag = id == null ? null : blocks.getTag(TagKey.create(Registries.BLOCK, id)).orElse(null);
        if (tag == null) {
            log.accept("Skipping emitter mapping " + named.id() + ": unknown block tag '" + entry.target() + "' (treated as empty)");
            return List.of();
        }
        return tag.stream().map(Holder::value).toList();
    }

    /** True if some mapping targets this block, whatever its state. The cheap test for hot paths. */
    public boolean mapsBlock(Block block) {
        return byBlock.containsKey(block);
    }

    /**
     * The rate (PU per interval) if the state is active under some mapping, else {@code -1}: either the
     * block is not mapped or its {@code active_when} does not hold. A rate of 0 is active but emits nothing.
     */
    public double activeRate(BlockState state) {
        Rule[] rules = byBlock.get(state.getBlock());
        if (rules == null) {
            return -1;
        }
        for (Rule rule : rules) {
            if (rule.matches(state)) {
                return rule.rate();
            }
        }
        return -1;
    }

    /** Number of distinct mapped blocks. */
    public int blockCount() {
        return byBlock.size();
    }
}
