package io.github.pollutioncraft.mod.emitter;

import com.google.gson.JsonParser;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code data/<namespace>/pollution_emitters/**.json} on every datapack reload. The off-thread
 * prepare step only parses and validates ({@link MappingValidator}); the entries are resolved against the
 * registries when {@link TagsUpdatedEvent} fires, because block tags are bound only after the reload
 * listeners have finished. The resolved {@link EmitterRules} are installed as one reference swap.
 */
public final class EmitterRulesLoader extends SimplePreparableReloadListener<List<EmitterRules.NamedEntry>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmitterRulesLoader.class);
    private static final FileToIdConverter FILES = FileToIdConverter.json("pollution_emitters");

    private static volatile List<EmitterRules.NamedEntry> loaded = List.of();

    @Override
    protected List<EmitterRules.NamedEntry> prepare(ResourceManager resources, ProfilerFiller profiler) {
        List<EmitterRules.NamedEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> file : FILES.listMatchingResources(resources).entrySet()) {
            String id = FILES.fileToId(file.getKey()).toString();
            try (BufferedReader reader = file.getValue().openAsReader()) {
                if (JsonPlain.of(JsonParser.parseReader(reader)) instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) raw;
                    MappingValidator.validate(id, entry, LOGGER::warn).ifPresent(valid -> entries.add(new EmitterRules.NamedEntry(id, valid)));
                } else {
                    LOGGER.warn("Skipping emitter mapping {}: the file must contain one JSON object", id);
                }
            } catch (Exception e) {
                LOGGER.warn("Skipping emitter mapping {}: cannot be read as JSON ({})", id, e.getMessage());
            }
        }
        return entries;
    }

    @Override
    protected void apply(List<EmitterRules.NamedEntry> entries, ResourceManager resources, ProfilerFiller profiler) {
        loaded = List.copyOf(entries);
    }

    @EventBusSubscriber(modid = PollutionCraftMod.MOD_ID)
    static final class Hooks {
        private Hooks() {}

        @SubscribeEvent
        static void addListener(AddReloadListenerEvent event) {
            event.addListener(new EmitterRulesLoader());
        }

        @SubscribeEvent
        static void resolve(TagsUpdatedEvent event) {
            if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
                return;
            }
            EmitterRules rules = EmitterRules.resolve(loaded, net.minecraft.core.registries.BuiltInRegistries.BLOCK, LOGGER::warn);
            EmitterEngine.installRules(rules);
            LOGGER.info("Emitter mappings active: {} file(s), {} block(s)", loaded.size(), rules.blockCount());
        }
    }
}
