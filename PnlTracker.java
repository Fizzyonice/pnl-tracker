package com.fiizzyonice.pnltracker;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PnlTracker implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("pnltracker");
    public static PnlData DATA;

    private static void say(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal(msg).withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    @Override
    public void onInitializeClient() {
        DATA = PnlData.load();

        // Server/system messages only — regular player chat can't trigger a fake payment.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) DATA.handle(message.getString());
            else DATA.handleBalanceOnly(message.getString()); // "/bal" also shows above the hotbar
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("pnltracker", "hud"), PnlHud::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(ClientCommands.literal("pnl")
                .executes(ctx -> {
                    double net = 0;
                    for (PnlData.Tx tx : DATA.window()) net += tx.a;
                    ctx.getSource().sendFeedback(Component.literal("24h PnL: ")
                        .append(Component.literal(PnlData.signed(net))
                            .withStyle(net >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
                        .append(Component.literal("  (/pnl reset, toggle, size 0.5-2, side left|right, rotate 0-60)").withStyle(ChatFormatting.GRAY)));
                    return 1;
                })
                .then(ClientCommands.literal("reset").executes(ctx -> { DATA.reset(); say(ctx, "PnL tracker reset."); return 1; }))
                .then(ClientCommands.literal("toggle").executes(ctx -> {
                    DATA.hudVisible = !DATA.hudVisible; DATA.save();
                    say(ctx, "PnL HUD " + (DATA.hudVisible ? "shown" : "hidden")); return 1;
                }))
                .then(ClientCommands.literal("size")
                    .then(ClientCommands.argument("scale", FloatArgumentType.floatArg(0.5f, 2.0f)).executes(ctx -> {
                        DATA.hudScale = FloatArgumentType.getFloat(ctx, "scale"); DATA.save();
                        say(ctx, "PnL HUD size set to " + DATA.hudScale); return 1;
                    })))
                .then(ClientCommands.literal("rotate")
                    .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(0, 60)).executes(ctx -> {
                        DATA.rotateSeconds = IntegerArgumentType.getInteger(ctx, "seconds"); DATA.save();
                        say(ctx, DATA.rotateSeconds == 0 ? "PnL HUD will stay on the profit page." : "PnL HUD switches pages every " + DATA.rotateSeconds + "s.");
                        return 1;
                    })))
                .then(ClientCommands.literal("side")
                    .then(ClientCommands.literal("left").executes(ctx -> { DATA.rightSide = false; DATA.save(); say(ctx, "PnL HUD moved left."); return 1; }))
                    .then(ClientCommands.literal("right").executes(ctx -> { DATA.rightSide = true; DATA.save(); say(ctx, "PnL HUD moved right."); return 1; })))
            ));
    }
}
