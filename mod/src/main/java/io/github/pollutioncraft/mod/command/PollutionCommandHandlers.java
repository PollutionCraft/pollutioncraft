package io.github.pollutioncraft.mod.command;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.WorldPollution;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Thin world access for {@code /pollution}: resolves the chunk and dimension, refuses paused records, and
 * hands text to {@link PollutionText}. Every field write follows the interval write rule: {@code abandon()}
 * (or {@code stepNow}) first, then write, then {@code setDirty()}, all in the same tick.
 */
final class PollutionCommandHandlers {
    private static final int SUCCESS = 1;
    private static final int FAILURE = 0;

    private PollutionCommandHandlers() {}

    /** The chunk to act on: the explicit coordinates, or the executor's own chunk. */
    record ChunkTarget(int x, int z) {
        static ChunkTarget explicit(int x, int z) {
            return new ChunkTarget(x, z);
        }

        static ChunkTarget of(CommandSourceStack source) {
            BlockPos pos = BlockPos.containing(source.getPosition());
            return new ChunkTarget(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    static int get(CommandSourceStack source, ChunkTarget target) {
        ServerLevel level = source.getLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        if (dimension.isPreserved()) {
            return failure(source, PollutionText.pausedDimension(dimensionId(level)));
        }
        ChunkPollution chunk = dimension.field().get(ChunkKey.pack(target.x(), target.z()));
        return read(source, withWorldNotice(source, PollutionText.get(target.x(), target.z(), chunk)));
    }

    static int rates(CommandSourceStack source, ChunkTarget target) {
        ServerLevel level = source.getLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        if (dimension.isPreserved()) {
            return failure(source, PollutionText.pausedDimension(dimensionId(level)));
        }
        ChunkPollution chunk = dimension.field().get(ChunkKey.pack(target.x(), target.z()));
        List<String> lines = new ArrayList<>(PollutionText.rates(target.x(), target.z(), chunk));
        lines.add("rates in force:");
        lines.addAll(RatesText.effectiveRates(PollutionCraftMod.config()).stream().map(line -> "  " + line).toList());
        return read(source, withWorldNotice(source, lines));
    }

    /** A paused world record freezes the whole simulation, so the readable field values get its notice first. */
    private static List<String> withWorldNotice(CommandSourceStack source, List<String> lines) {
        if (!PollutionManager.world(source.getServer()).isPreserved()) {
            return lines;
        }
        List<String> noticed = new ArrayList<>();
        noticed.add(PollutionText.pausedWorld());
        noticed.addAll(lines);
        return noticed;
    }

    /** The status command: world record values plus every paused record of the server. */
    static int pressure(CommandSourceStack source) {
        List<String> lines = new ArrayList<>();
        WorldPollution world = PollutionManager.world(source.getServer());
        if (world.isPreserved()) {
            lines.add(PollutionText.pausedWorld());
        } else {
            lines.addAll(PollutionText.pressure(world.record()));
        }
        for (ServerLevel level : source.getServer().getAllLevels()) {
            if (PollutionManager.dimension(level).isPreserved()) {
                lines.add(PollutionText.pausedDimension(dimensionId(level)));
            }
        }
        return read(source, lines);
    }

    static int set(CommandSourceStack source, double amount, ChunkTarget target, int radius) {
        Optional<String> problem = CommandLimits.problemWithAmount(amount);
        if (problem.isEmpty()) {
            problem = CommandLimits.problemWithRadius(radius);
        }
        if (problem.isPresent()) {
            return failure(source, problem.get());
        }
        ServerLevel level = source.getLevel();
        Optional<String> paused = pausedReason(level);
        if (paused.isPresent()) {
            return failure(source, paused.get());
        }
        DimensionPollution dimension = PollutionManager.dimension(level);
        String dropped = dropInFlightInterval(dimension);
        int chunks = forEachInSquare(target, radius, key -> dimension.field().getOrCreate(key).absorbable = amount);
        dimension.setDirty();
        return gatedMutation(source, "Set absorbable to " + PollutionText.number(amount) + " PU in " + chunks
                + " chunk(s) around " + target.x() + ", " + target.z() + dropped);
    }

    static int clear(CommandSourceStack source, ChunkTarget target, int radius) {
        Optional<String> problem = CommandLimits.problemWithRadius(radius);
        if (problem.isPresent()) {
            return failure(source, problem.get());
        }
        ServerLevel level = source.getLevel();
        Optional<String> paused = pausedReason(level);
        if (paused.isPresent()) {
            return failure(source, paused.get());
        }
        DimensionPollution dimension = PollutionManager.dimension(level);
        String dropped = dropInFlightInterval(dimension);
        int chunks = forEachInSquare(target, radius, key -> dimension.field().remove(key));
        dimension.setDirty();
        return gatedMutation(source, "Cleared both pools in " + chunks + " chunk(s) around " + target.x() + ", "
                + target.z() + "; pending emissions are not discarded" + dropped);
    }

    static int step(CommandSourceStack source, int n) {
        Optional<String> problem = CommandLimits.problemWithSteps(n);
        if (problem.isPresent()) {
            return failure(source, problem.get());
        }
        ServerLevel level = source.getLevel();
        return switch (PollutionManager.stepNow(level, n)) {
            case RAN -> gatedMutation(source, "Ran " + n + " interval(s) in " + dimensionId(level));
            case PAUSED_WORLD_RECORD -> failure(source, PollutionText.pausedWorld());
            case PAUSED_DIMENSION_RECORD -> failure(source, PollutionText.pausedDimension(dimensionId(level)));
        };
    }

    static int tuneSet(CommandSourceStack source, String key, double value) {
        return tuneReply(source, new TuneCommand(PollutionCraftMod.config()).set(key, value));
    }

    static int tuneReset(CommandSourceStack source, String key) {
        return tuneReply(source, new TuneCommand(PollutionCraftMod.config()).reset(key));
    }

    static int tuneResetAll(CommandSourceStack source) {
        return tuneReply(source, new TuneCommand(PollutionCraftMod.config()).resetAll());
    }

    static int tuneList(CommandSourceStack source) {
        return read(source, new TuneCommand(PollutionCraftMod.config()).list());
    }

    /** Why a write must be refused, naming the unreadable record; empty when the dimension is writable. */
    private static Optional<String> pausedReason(ServerLevel level) {
        if (PollutionManager.world(level.getServer()).isPreserved()) {
            return Optional.of(PollutionText.pausedWorld());
        }
        if (PollutionManager.dimension(level).isPreserved()) {
            return Optional.of(PollutionText.pausedDimension(dimensionId(level)));
        }
        return Optional.empty();
    }

    /** Applies the interval write rule; returns a reply suffix when an interval in flight was dropped. */
    private static String dropInFlightInterval(DimensionPollution dimension) {
        boolean inFlight = dimension.scheduler().inFlight();
        dimension.scheduler().abandon();
        return inFlight ? "; the interval in flight was dropped" : "";
    }

    private static int forEachInSquare(ChunkTarget center, int radius, LongConsumer action) {
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                action.accept(ChunkKey.pack(center.x() + dx, center.z() + dz));
                count++;
            }
        }
        return count;
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static int tuneReply(CommandSourceStack source, TuneCommand.Reply reply) {
        return reply.ok() ? mutation(source, reply.message()) : failure(source, reply.message());
    }

    private static int read(CommandSourceStack source, List<String> lines) {
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return SUCCESS;
    }

    private static int mutation(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), true);
        return SUCCESS;
    }

    /** Developer actions bypass the player-online gate, and the reply says so. */
    private static int gatedMutation(CommandSourceStack source, String message) {
        return mutation(source, PollutionText.withGateNote(message));
    }

    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return FAILURE;
    }
}
