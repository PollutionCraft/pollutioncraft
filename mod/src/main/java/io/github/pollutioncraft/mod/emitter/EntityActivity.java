package io.github.pollutioncraft.mod.emitter;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The built-in activity tests of the entity emitters (task 032): which entity types the mod can tell are working,
 * and how. A closed list in code with the rates in data (docs/emitter-mappings.md). Public vanilla API only.
 *
 * <p>The furnace minecart keeps its fuel in a private counter and its "has fuel" flag behind a protected getter.
 * The public equivalent is {@link AbstractMinecart#getDefaultDisplayBlockState()}, a furnace state whose {@code lit}
 * is that flag. {@code getDisplayBlockState()} is deliberately not used: a custom display block set with
 * {@code /data} would replace it.
 */
public final class EntityActivity {
    private EntityActivity() {}

    /** Whether the engine keeps entities of this type; also the list a mapping may name. */
    public static boolean isSupported(EntityType<?> type) {
        return type == EntityType.FURNACE_MINECART;
    }

    /** Whether this (supported) entity is working right now. */
    public static boolean isActive(Entity entity) {
        return entity instanceof AbstractMinecart cart
                && entity.getType() == EntityType.FURNACE_MINECART
                && cart.getDefaultDisplayBlockState().getValue(BlockStateProperties.LIT);
    }
}
