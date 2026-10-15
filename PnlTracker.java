package com.fiizzyonice.pnltracker;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
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
    private static long hideBalUntil = 0, lastQuietBal = 0;

    private static void say(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal(msg).withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    @Override
    public void onInitializeClient() {
        DATA = PnlData.load();

        // Server/system messages only — regular player chat can't trigger a fake payment.
        // Hide the reply to our own quiet /bal (but still read the exact balance from it)
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (System.currentTimeMillis() < hideBalUntil && DATA.isBalanceMessage(message.getString())) {
                DATA.handleBalanceOnly(message.getString());
                if (!overlay) hideBalUntil = 0; // got the chat copy; stop hiding
                return false;
            }
            return true;
        });

        // Run the quiet /bal a moment after a rounded amount
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            if (!DATA.autoBal || DATA.syncDue == 0 || now < DATA.syncDue) return;
            if (now - lastQuietBal < 4000) { DATA.syncDue = lastQuietBal + 4000; return; }
            if (mc.player == null || mc.getConnection() == null) return;
            DATA.syncDue = 0;
            lastQuietBal = now;
            hideBalUntil = now + 5000;
            mc.getConnection().sendCommand("bal");
        });

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
                .then(ClientCommands.literal("reset").executes(ctx -> { DATA.reset(); say(ctx, "PnL tracker reset."); return 1; })
                    .then(ClientCommands.literal("profit").executes(ctx -> {
                        DATA.resetProfit();
                        say(ctx, "24H profit and chart reset. Recent bets, winners/losers and rakeback kept.");
                        return 1;
                    })))
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
                    .then(ClientCommands.literal("clear").executes(ctx -> { DATA.rakeback.clear(); DATA.save(); say(ctx, "Rakeback cleared for everyone."); return 1; }))
                    .then(ClientCommands.literal("toggle").executes(ctx -> {
                        DATA.rbVisible = !DATA.rbVisible; DATA.save();
                        say(ctx, "Rakeback panel " + (DATA.rbVisible ? "shown" : "hidden")); return 1;
                    })))
                .then(ClientCommands.literal("autobal")
                    .then(ClientCommands.literal("on").executes(ctx -> { DATA.autoBal = true; DATA.save(); say(ctx, "Auto /bal on: balance fixes itself after rounded amounts."); return 1; }))
                    .then(ClientCommands.literal("off").executes(ctx -> { DATA.autoBal = false; DATA.save(); say(ctx, "Auto /bal off."); return 1; })))
                .then(ClientCommands.literal("side")
                    .then(ClientCommands.literal("left").executes(ctx -> { DATA.rightSide = false; DATA.save(); say(ctx, "PnL HUD moved left."); return 1; }))
                    .then(ClientCommands.literal("right").executes(ctx -> { DATA.rightSide = true; DATA.save(); say(ctx, "PnL HUD moved right."); return 1; })))
            ));
    }
}
