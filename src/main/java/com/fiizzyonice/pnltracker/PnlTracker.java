package com.fiizzyonice.pnltracker;

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

    @Override
    public void onInitializeClient() {
        DATA = PnlData.load();

        // Server/system messages only — regular player chat can't trigger a fake payment.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) DATA.handle(message.getString());
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("pnltracker", "hud"), PnlHud::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(ClientCommands.literal("pnl")
                .executes(ctx -> {
                    double net = DATA.net();
                    ctx.getSource().sendFeedback(Component.literal("PnL: ")
                        .append(Component.literal(PnlData.signed(net))
                            .withStyle(net >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
                        .append(Component.literal("  (/pnl reset, /pnl toggle)").withStyle(ChatFormatting.GRAY)));
                    return 1;
                })
                .then(ClientCommands.literal("reset").executes(ctx -> {
                    DATA.reset();
                    ctx.getSource().sendFeedback(Component.literal("PnL tracker reset.").withStyle(ChatFormatting.LIGHT_PURPLE));
                    return 1;
                }))
                .then(ClientCommands.literal("toggle").executes(ctx -> {
                    DATA.hudVisible = !DATA.hudVisible;
                    DATA.save();
                    ctx.getSource().sendFeedback(Component.literal("PnL HUD " + (DATA.hudVisible ? "shown" : "hidden"))
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
                    return 1;
                }))
            ));
    }
}
