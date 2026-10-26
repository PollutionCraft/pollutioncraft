package io.github.pollutioncraft.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ConfigRegistryTest {
    /** Public no-argument int/double methods of {@link ServerConfig}: the accessors the simulation calls. */
    private static Set<String> accessorNames() {
        Set<String> names = new TreeSet<>();
        for (Method method : ServerConfig.class.getDeclaredMethods()) {
            boolean accessor = Modifier.isPublic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && (method.getReturnType() == double.class || method.getReturnType() == int.class);
            if (accessor) {
                names.add(method.getName());
            }
        }
        return names;
    }

    @Test
    void everyAccessorIsARegisteredKeyAndEveryKeyHasAnAccessor() {
        Set<String> keys = new TreeSet<>();
        ConfigRegistry.KEYS.forEach(key -> keys.add(key.name()));

        assertEquals(keys, accessorNames());
    }

    @Test
    void keyNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        ConfigRegistry.KEYS.forEach(key -> assertTrue(seen.add(key.name()), "duplicate " + key.name()));
    }

    @Test
    void everyKeyIsBoundedHasADefaultInsideTheBoundsAndAComment() {
        for (ConfigKey key : ConfigRegistry.KEYS) {
            assertTrue(Double.isFinite(key.min()) && Double.isFinite(key.max()), key.name() + " bounds must be finite");
            assertTrue(key.min() <= key.defaultValue() && key.defaultValue() <= key.max(), key.name() + " default out of bounds");
            assertFalse(key.comment().isBlank(), key.name() + " needs a comment");
        }
    }

    @Test
    void tickKeysStateTheirSecondsEquivalent() {
        assertTrue(ConfigRegistry.SPREAD_INTERVAL_TICKS.comment().contains("seconds"));
    }

    @Test
    void specificationsStayInLockstepWithTheRegistry() {
        assertEquals(20.0, ConfigRegistry.SPREAD_INTERVAL_TICKS.defaultValue());
        assertEquals(0.02, ConfigRegistry.SPREAD_FRACTION.defaultValue());
        assertEquals(1.0, ConfigRegistry.EMISSION_MULTIPLIER.defaultValue());
    }
}
