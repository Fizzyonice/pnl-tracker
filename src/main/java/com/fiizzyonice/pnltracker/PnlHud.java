package com.fiizzyonice.pnltracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Draws the panels in the same look as the casino overlay: navy panels, pink headers, green/red values. */
public class PnlHud {
    // ARGB colors
    static final int PANEL   = 0xC8323C5A; // translucent navy
    static final int BORDER  = 0x40FFFFFF;
    static final int HEADER  = 0xFFF07AB8; // pink
    static final int LABEL   = 0xFFB4BCD4; // soft gray-blue
    static final int WHITE   = 0xFFFFFFFF;
    static final int GREEN   = 0xFF4ADE80;
    static final int RED     = 0xFFF0506E;
    static final int PINK    = 0xFFF07AB8;

    static final int W = 150;   // panel width
    static final int PAD = 7;
    static final int MARGIN = 6;

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        PnlData d = PnlTracker.DATA;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!d.hudVisible || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;

        TextRenderer tr = mc.textRenderer;
        int x = ctx.getScaledWindowWidth() - W - MARGIN;
        int y = MARGIN;

        // ---------- PROFIT / LOSS ----------
        int h = PAD + 9 + 4 + 18 + 6 + 3 * 12 + PAD - 2;
        panel(ctx, x, y, h);
        int cy = y + PAD;
        ctx.drawText(tr, "PROFIT / LOSS", x + PAD, cy, HEADER, false);
        if (d.balance >= 0) {
            String bal = "Bal " + PnlData.format(d.balance);
            ctx.drawText(tr, bal, x + W - PAD - tr.getWidth(bal), cy, LABEL, false);
        }
        cy += 13;

        double net = d.net();
        String netStr = PnlData.signed(net);
        ctx.drawText(tr, "NET", x + PAD, cy + 5, LABEL, false);
        big(ctx, tr, netStr, x + W - PAD, cy, net >= 0 ? GREEN : RED);
        cy += 24;

        row(ctx, tr, x, cy, "RECEIVED", "+" + PnlData.format(d.received), GREEN); cy += 12;
        row(ctx, tr, x, cy, "SENT", "-" + PnlData.format(d.sent), RED);             cy += 12;
        row(ctx, tr, x, cy, "PAYMENTS", String.valueOf(d.payments), PINK);
        y += h + 5;

        // ---------- PLAYER NETS ----------
        List<Map.Entry<String, Double>> nets = new ArrayList<>(d.playerNets.entrySet());
        nets.sort((a, b) -> Double.compare(b.getValue(), a.getValue())); // biggest winners first, losers at bottom
        if (!nets.isEmpty()) {
            int shown = Math.min(nets.size(), 8);
            // keep the most extreme ones if there are more than 8
            List<Map.Entry<String, Double>> list = nets.size() <= 8 ? nets
                    : new ArrayList<>(nets.subList(0, 4)) {{ addAll(nets.subList(nets.size() - 4, nets.size())); }};
            h = PAD + 9 + 3 + 8 + 4 + shown * 11 + PAD - 3;
            panel(ctx, x, y, h);
            cy = y + PAD;
            ctx.drawText(tr, "PLAYER NETS", x + PAD, cy, HEADER, false);
            cy += 11;
            small(ctx, tr, "+ = they paid you, - = you paid them", x + PAD, cy, LABEL);
            cy += 10;
            for (Map.Entry<String, Double> e : list) {
                double v = e.getValue();
                ctx.drawText(tr, trim(tr, e.getKey(), 70), x + PAD, cy, WHITE, false);
                String val = PnlData.signed(v);
                ctx.drawText(tr, val, x + W - PAD - tr.getWidth(val), cy, v >= 0 ? GREEN : RED, false);
                cy += 11;
            }
            y += h + 5;
        }

        // ---------- RECENT ----------
        if (!d.recent.isEmpty()) {
            int n = Math.min(d.recent.size(), 5);
            h = PAD + 9 + 3 + n * 10 + PAD - 3;
            panel(ctx, x, y, h);
            cy = y + PAD;
            ctx.drawText(tr, "RECENT", x + PAD, cy, HEADER, false);
            cy += 12;
            for (int i = 0; i < n; i++) {
                String line = d.recent.get(i);
                small(ctx, tr, trim(tr, line, (W - 2 * PAD) * 100 / 85), x + PAD, cy, line.startsWith("+") ? GREEN : RED);
                cy += 10;
            }
        }
    }

    private static void panel(DrawContext ctx, int x, int y, int h) {
        ctx.fill(x, y, x + W, y + h, PANEL);
        // thin top highlight + soft corners
        ctx.fill(x + 1, y, x + W - 1, y + 1, BORDER);
        ctx.fill(x, y, x + 1, y + 1, 0);
    }

    private static void row(DrawContext ctx, TextRenderer tr, int x, int y, String label, String value, int color) {
        small(ctx, tr, label, x + PAD, y + 1, LABEL);
        ctx.drawText(tr, value, x + W - PAD - tr.getWidth(value), y, color, false);
    }

    /** Text at 2x size, right-aligned to rightX. */
    private static void big(DrawContext ctx, TextRenderer tr, String s, int rightX, int y, int color) {
        float scale = 2f;
        while (tr.getWidth(s) * scale > W - 40 && scale > 1.2f) scale -= 0.1f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(rightX - tr.getWidth(s) * scale, y);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawText(tr, s, 0, 0, color, true);
        ctx.getMatrices().popMatrix();
    }

    /** Text at 0.85x size for labels. */
    private static void small(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(0.85f, 0.85f);
        ctx.drawText(tr, s, 0, 0, color, false);
        ctx.getMatrices().popMatrix();
    }

    private static String trim(TextRenderer tr, String s, int maxW) {
        if (tr.getWidth(s) <= maxW) return s;
        return tr.trimToWidth(s, maxW - tr.getWidth("..")) + "..";
    }
}
