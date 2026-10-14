package com.fiizzyonice.pnltracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
                Identifier.of("pnltracker", "hud"), PnlHud::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(ClientCommandManager.literal("pnl")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("PnL: ")
                        .append(Text.literal(PnlData.signed(DATA.net()))
                            .formatted(DATA.net() >= 0 ? Formatting.GREEN : Formatting.RED))
                        .append(Text.literal("  (/pnl reset, /pnl toggle)").formatted(Formatting.GRAY)));
                    return 1;
                })
                .then(ClientCommandManager.literal("reset").executes(ctx -> {
                    DATA.reset();
                    ctx.getSource().sendFeedback(Text.literal("PnL tracker reset.").formatted(Formatting.LIGHT_PURPLE));
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    DATA.hudVisible = !DATA.hudVisible;
                    DATA.save();
                    ctx.getSource().sendFeedback(Text.literal("PnL HUD " + (DATA.hudVisible ? "shown" : "hidden"))
                        .formatted(Formatting.LIGHT_PURPLE));
                    return 1;
                }))
            ));
    }
}
