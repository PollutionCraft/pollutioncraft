package io.github.pollutioncraft.mod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.pollutioncraft.mod.overlay.OverlayRecipients;
import io.github.pollutioncraft.mod.overlay.OverlaySender;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /pollution overlay on|off}: switches the F3 pollution section on for the executing player (task 030).
 * Per player, in memory, default off. {@code on} also sends one payload at once, because an interval can be
 * up to an hour long; {@code off} sends nothing and the client falls back to {@code no data}.
 */
final class OverlayCommand {
    private OverlayCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("overlay")
                .requires(source -> source.hasPermission(OverlayRecipients.REQUIRED_LEVEL))
                .then(Commands.literal("on").executes(c -> switchTo(c.getSource(), true)))
                .then(Commands.literal("off").executes(c -> switchTo(c.getSource(), false)));
    }

    static int switchTo(CommandSourceStack source, boolean on) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can switch the pollution overlay on or off"));
            return 0;
        }
        OverlaySender sender = OverlaySender.global();
        if (on) {
            sender.subscribers().add(player.getUUID());
            source.sendSuccess(() -> Component.literal("Pollution overlay on: open F3 to see the Pollution section"), false);
            sender.sendNow(player);
        } else {
            sender.subscribers().remove(player.getUUID());
            source.sendSuccess(() -> Component.literal("Pollution overlay off"), false);
        }
        return 1;
    }
}
