package io.github.pollutioncraft.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerConfigTest {
    private ServerConfig config;

    @BeforeEach
    void setUp() {
        config = new ServerConfig(message -> {});
    }

    /** The named accessor of a key; the convention is enforced by ConfigRegistryTest. */
    private double accessor(ConfigKey key) throws ReflectiveOperationException {
        Method method = ServerConfig.class.getMethod(key.name());
        return ((Number) method.invoke(config)).doubleValue();
    }

    private void assertValueMatchesAccessorForEveryKey() throws ReflectiveOperationException {
        for (ConfigKey key : ConfigRegistry.KEYS) {
            assertEquals(accessor(key), config.value(key), key.name());
        }
    }

    @Test
    void valueEqualsTheNamedAccessorForEveryKeyWithDefaultsAndAfterReload() throws ReflectiveOperationException {
        assertValueMatchesAccessorForEveryKey();

        Map<String, Object> raw = new HashMap<>();
        for (ConfigKey key : ConfigRegistry.KEYS) {
            // A different valid value per key: the middle of the range, whole for integers, below the 4x rule.
            double mid = key.name().equals("spreadFraction") ? 0.1 : key.min() + Math.min(7, (key.max() - key.min()) / 2);
            raw.put(key.name(), key.type() == ConfigKey.Type.INTEGER ? (Object) (int) mid : (Object) mid);
        }
        config.reload(raw);

        assertValueMatchesAccessorForEveryKey();
        assertTrue(ConfigRegistry.KEYS.stream().anyMatch(key -> config.value(key) != key.defaultValue()));
    }

    @Test
    void overrideWinsAndClearRestoresTheFileValue() {
        config.reload(Map.of("spreadFraction", 0.05));
        assertTrue(config.overrides().set("spreadFraction", 0.1).isEmpty());
        assertEquals(0.1, config.value(ConfigRegistry.SPREAD_FRACTION));

        config.overrides().clear("spreadFraction");

        assertEquals(0.05, config.value(ConfigRegistry.SPREAD_FRACTION));
    }

    @Test
    void aKeyOutsideTheRegistryIsRefused() {
        ConfigKey stranger = new ConfigKey("notInRegistry", ConfigKey.Type.DECIMAL, 1, 0, 2, "x");

        assertThrows(IllegalArgumentException.class, () -> config.value(stranger));
    }
}
