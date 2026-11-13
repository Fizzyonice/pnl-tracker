package com.fiizzyonice.pnltracker;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
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
    public static KeyMapping DASHBOARD_KEY;
    private static boolean openDashboard = false;
    private static long lastTick = 0, lastPlaySave = 0;

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

        // Dashboard key (J by default, change it in Options > Controls)
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pnltracker", "pnltracker"));
        DASHBOARD_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.pnltracker.dashboard", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (DASHBOARD_KEY.consumeClick()) openDashboard = true;
            if (openDashboard && mc.screen == null) { openDashboard = false; mc.setScreen(new DashboardScreen()); }
            else if (openDashboard && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) openDashboard = false;

            Auction.tick(DATA);
            DATA.flushIfDue();

            // playtime: count time while you're in a world
            long now = System.currentTimeMillis();
            if (mc.player != null && lastTick > 0) DATA.addPlaytime(Math.min(1000, now - lastTick));
            lastTick = now;
            if (now - lastPlaySave > 60_000) { lastPlaySave = now; if (mc.player != null) DATA.save(); }
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
                .then(ClientCommands.literal("reset").executes(ctx -> {
                        say(ctx, "This deletes ALL your history (profit, chart, bets, winners/losers, player nets). Type /pnl reset confirm to do it, or /pnl reset profit for just the profit.");
                        return 1;
                    })
                    .then(ClientCommands.literal("confirm").executes(ctx -> {
                        DATA.reset();
                        say(ctx, "PnL tracker reset. Changed your mind? Type /pnl undo.");
                        return 1;
                    }))
                    .then(ClientCommands.literal("profit").executes(ctx -> {
                        DATA.resetProfit();
                        say(ctx, "24H profit and chart reset. Recent bets, winners/losers and rakeback kept.");
                        return 1;
                    })))
                .then(ClientCommands.literal("dashboard").executes(ctx -> { openDashboard = true; return 1; }))
                .then(ClientCommands.literal("name")
                    .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                        DATA.displayName = StringArgumentType.getString(ctx, "name").trim();
                        DATA.save();
                        say(ctx, "Dashboard name set to " + DATA.displayName + ".");
                        return 1;
                    })))
                .then(ClientCommands.literal("undo").executes(ctx -> {
                    int n = DATA.undo();
                    say(ctx, n < 0 ? "No backup to undo." : "Brought back " + n + " payments from before your last reset.");
                    return 1;
                }))
                .then(ClientCommands.literal("restore").executes(ctx -> {
                    int n = DATA.restore();
                    say(ctx, n < 0 ? "No pnltracker-restore.json found in your config folder." : "Restored " + n + " payments. Balance and rakeback kept.");
                    return 1;
                }))
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
                .then(ClientCommands.literal("rakeback")
                    .then(ClientCommands.literal("reset")
                        .then(ClientCommands.argument("player", StringArgumentType.word()).executes(ctx -> {
                            String p = StringArgumentType.getString(ctx, "player");
                            say(ctx, DATA.rakebackReset(p) ? "Rakeback reset for " + p + "." : p + " isn't on the rakeback list.");
                            return 1;
                        })))
                    .then(ClientCommands.literal("clear").executes(ctx -> { say(ctx, "This clears rakeback for EVERYONE. Type /pnl rakeback clear confirm to do it."); return 1; })
                        .then(ClientCommands.literal("confirm").executes(ctx -> { DATA.clearRakeback(); say(ctx, "Rakeback cleared for everyone. Changed your mind? Type /pnl undo."); return 1; })))
                    .then(ClientCommands.literal("toggle").executes(ctx -> {
                        DATA.rbVisible = !DATA.rbVisible; DATA.save();
                        say(ctx, "Rakeback panel " + (DATA.rbVisible ? "shown" : "hidden")); return 1;
                    })))
                .then(ClientCommands.literal("side")
                    .then(ClientCommands.literal("left").executes(ctx -> { DATA.rightSide = false; DATA.save(); say(ctx, "PnL HUD moved left."); return 1; }))
                    .then(ClientCommands.literal("right").executes(ctx -> { DATA.rightSide = true; DATA.save(); say(ctx, "PnL HUD moved right."); return 1; })))
            ));
    }
}
