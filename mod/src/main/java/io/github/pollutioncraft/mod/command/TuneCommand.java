package io.github.pollutioncraft.mod.command;

import io.github.pollutioncraft.mod.config.ConfigRegistry;
import io.github.pollutioncraft.mod.config.ServerConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Logic of {@code /pollution tune}: in-memory overrides only, the config file is never touched. */
public final class TuneCommand {
    /** Outcome with the text to show; {@code ok} picks sendSuccess or sendFailure. */
    public record Reply(boolean ok, String message) {}

    private final ServerConfig config;

    public TuneCommand(ServerConfig config) {
        this.config = config;
    }

    public Reply set(String key, double value) {
        Optional<String> refusal = config.overrides().set(key, value);
        if (refusal.isPresent()) {
            return new Reply(false, refusal.get());
        }
        return new Reply(true, key + " = " + PollutionText.number(value)
                + " (overridden); applies from the next interval, not saved to the config file");
    }

    public Reply reset(String key) {
        if (ConfigRegistry.find(key).isEmpty()) {
            return new Reply(false, "Unknown config key '" + key + "'");
        }
        if (!config.overrides().clear(key)) {
            return new Reply(true, key + " has no override");
        }
        return new Reply(true, key + " override removed; applies from the next interval");
    }

    public Reply resetAll() {
        int count = config.overrides().list().size();
        config.overrides().clearAll();
        return new Reply(true, "Cleared " + count + " override(s); applies from the next interval");
    }

    /** Active overrides, sorted by key; one line when there are none. */
    public List<String> list() {
        Map<String, Double> active = config.overrides().list();
        if (active.isEmpty()) {
            return List.of("No runtime overrides active");
        }
        return active.keySet().stream()
                .map(name -> RatesText.line(config, ConfigRegistry.find(name).orElseThrow()))
                .toList();
    }
}
