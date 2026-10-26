package io.github.pollutioncraft.mod.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The on-disk shape of the server config, generated from {@link ConfigRegistry}. The spec only checks that
 * a value is a number; bounds and cross-field rules are {@link ConfigValues#load}'s job, so the registry
 * stays the single source and every rejection is logged the same way.
 */
public final class ServerConfigSpec {
    private final ModConfigSpec spec;
    private final Map<String, ModConfigSpec.ConfigValue<?>> entries = new LinkedHashMap<>();

    public ServerConfigSpec() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        for (ConfigKey key : ConfigRegistry.KEYS) {
            Object defaultValue = key.type() == ConfigKey.Type.INTEGER ? (Object) (int) key.defaultValue() : key.defaultValue();
            builder.comment(key.comment(), "Range: " + key.min() + " to " + key.max() + ", default " + defaultValue);
            entries.put(key.name(), builder.define(key.name(), defaultValue, value -> value instanceof Number));
        }
        spec = builder.build();
    }

    public ModConfigSpec spec() {
        return spec;
    }

    /** Current raw values; only valid once the config has loaded. */
    public Map<String, Object> read() {
        Map<String, Object> raw = new LinkedHashMap<>();
        entries.forEach((name, value) -> raw.put(name, value.get()));
        return raw;
    }
}
