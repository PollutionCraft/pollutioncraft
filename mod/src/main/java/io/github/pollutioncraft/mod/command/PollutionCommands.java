package io.github.pollutioncraft.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.command.PollutionCommandHandlers.ChunkTarget;
import io.github.pollutioncraft.mod.config.ConfigKey;
import io.github.pollutioncraft.mod.config.ConfigRegistry;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Registers {@code /pollution} (debug commands, task 009). All subcommands need permission level 2. */
@EventBusSubscriber(modid = PollutionCraftMod.MOD_ID)
public final class PollutionCommands {
    static final int PERMISSION_LEVEL = 2;
    private static final String CHUNK_X = "chunkX";
    private static final String CHUNK_Z = "chunkZ";

    private PollutionCommands() {}

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pollution")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(chunkCommand("get", PollutionCommandHandlers::get))
                .then(chunkCommand("rates", PollutionCommandHandlers::rates))
                .then(Commands.literal("pressure").executes(c -> PollutionCommandHandlers.pressure(c.getSource())))
                .then(setCommand())
                .then(clearCommand())
                .then(stepCommand())
                .then(tuneCommand())
                .then(OverlayCommand.node()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> chunkCommand(
            String name, BiFunction<CommandSourceStack, ChunkTarget, Integer> handler) {
        return Commands.literal(name)
                .executes(c -> handler.apply(c.getSource(), ChunkTarget.of(c.getSource())))
                .then(chunkArguments(z -> z.executes(c -> handler.apply(c.getSource(), chunkOf(c)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setCommand() {
        return Commands.literal("set").then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                .executes(c -> PollutionCommandHandlers.set(c.getSource(), amount(c), ChunkTarget.of(c.getSource()), 0))
                .then(chunkArguments(z -> z
                        .executes(c -> PollutionCommandHandlers.set(c.getSource(), amount(c), chunkOf(c), 0))
                        .then(radiusArgument().executes(c ->
                                PollutionCommandHandlers.set(c.getSource(), amount(c), chunkOf(c), radius(c)))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> clearCommand() {
        return Commands.literal("clear")
                .executes(c -> PollutionCommandHandlers.clear(c.getSource(), ChunkTarget.of(c.getSource()), 0))
                .then(chunkArguments(z -> z
                        .executes(c -> PollutionCommandHandlers.clear(c.getSource(), chunkOf(c), 0))
                        .then(radiusArgument().executes(c ->
                                PollutionCommandHandlers.clear(c.getSource(), chunkOf(c), radius(c))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> stepCommand() {
        return Commands.literal("step")
                .executes(c -> PollutionCommandHandlers.step(c.getSource(), CommandLimits.MIN_STEPS))
                .then(Commands.argument("n", IntegerArgumentType.integer())
                        .executes(c -> PollutionCommandHandlers.step(c.getSource(), IntegerArgumentType.getInteger(c, "n"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tuneCommand() {
        return Commands.literal("tune")
                .executes(c -> PollutionCommandHandlers.tuneList(c.getSource()))
                .then(Commands.literal("reset")
                        .executes(c -> PollutionCommandHandlers.tuneResetAll(c.getSource()))
                        .then(keyArgument().executes(c ->
                                PollutionCommandHandlers.tuneReset(c.getSource(), StringArgumentType.getString(c, "key")))))
                .then(keyArgument().then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(c -> PollutionCommandHandlers.tuneSet(
                                c.getSource(), StringArgumentType.getString(c, "key"), DoubleArgumentType.getDouble(c, "value")))));
    }

    /** Suggestions come from the config registry, so a new config key is tunable with no change here. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> keyArgument() {
        return Commands.argument("key", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        ConfigRegistry.KEYS.stream().map(ConfigKey::name), builder));
    }

    /** {@code <chunkX> <chunkZ>}; {@code onZ} adds what may follow or run after both coordinates. */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> chunkArguments(
            Consumer<RequiredArgumentBuilder<CommandSourceStack, Integer>> onZ) {
        RequiredArgumentBuilder<CommandSourceStack, Integer> z = Commands.argument(CHUNK_Z, IntegerArgumentType.integer());
        onZ.accept(z);
        return Commands.argument(CHUNK_X, IntegerArgumentType.integer()).then(z);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> radiusArgument() {
        return Commands.argument("radius", IntegerArgumentType.integer());
    }

    private static ChunkTarget chunkOf(CommandContext<CommandSourceStack> c) {
        return ChunkTarget.explicit(IntegerArgumentType.getInteger(c, CHUNK_X), IntegerArgumentType.getInteger(c, CHUNK_Z));
    }

    private static double amount(CommandContext<CommandSourceStack> c) {
        return DoubleArgumentType.getDouble(c, "amount");
    }

    private static int radius(CommandContext<CommandSourceStack> c) {
        return IntegerArgumentType.getInteger(c, "radius");
    }
}
