package com.fiizzyonice.pnltracker;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Draws the panels in the same look as the casino overlay: navy panels, pink headers, green/red values. */
public class PnlHud {
    static final int PANEL  = 0xC8323C5A; // translucent navy
    static final int BORDER = 0x40FFFFFF;
    static final int HEADER = 0xFFF07AB8; // pink
    static final int LABEL  = 0xFFB4BCD4; // soft gray-blue
    static final int WHITE  = 0xFFFFFFFF;
    static final int GREEN  = 0xFF4ADE80;
    static final int RED    = 0xFFF0506E;
    static final int PINK   = 0xFFF07AB8;

    static final int W = 150;
    static final int PAD = 7;
    static final int MARGIN = 6;

    public static void render(GuiGraphicsExtractor g, DeltaTracker tick) {
        PnlData d = PnlTracker.DATA;
        if (d == null || !d.hudVisible) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int x = mc.getWindow().getGuiScaledWidth() - W - MARGIN;
        int y = MARGIN;

        // ---------- PROFIT / LOSS ----------
        int h = PAD + 9 + 4 + 18 + 6 + 3 * 12 + PAD - 2;
        panel(g, x, y, h);
        int cy = y + PAD;
        g.text(font, "PROFIT / LOSS", x + PAD, cy, HEADER, false);
        if (d.balance >= 0) {
            String bal = "Bal " + PnlData.format(d.balance);
            g.text(font, bal, x + W - PAD - font.width(bal), cy, LABEL, false);
        }
        cy += 13;

        double net = d.net();
        g.text(font, "NET", x + PAD, cy + 5, LABEL, false);
        big(g, font, PnlData.signed(net), x + W - PAD, cy, net >= 0 ? GREEN : RED);
        cy += 24;

        row(g, font, x, cy, "RECEIVED", "+" + PnlData.format(d.received), GREEN); cy += 12;
        row(g, font, x, cy, "SENT", "-" + PnlData.format(d.sent), RED);             cy += 12;
        row(g, font, x, cy, "PAYMENTS", String.valueOf(d.payments), PINK);
        y += h + 5;

        // ---------- PLAYER NETS ----------
        List<Map.Entry<String, Double>> nets = new ArrayList<>(d.playerNets.entrySet());
        nets.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (!nets.isEmpty()) {
            List<Map.Entry<String, Double>> list = new ArrayList<>();
            if (nets.size() <= 8) list.addAll(nets);
            else { list.addAll(nets.subList(0, 4)); list.addAll(nets.subList(nets.size() - 4, nets.size())); }

            h = PAD + 9 + 3 + 8 + 4 + list.size() * 11 + PAD - 3;
            panel(g, x, y, h);
            cy = y + PAD;
            g.text(font, "PLAYER NETS", x + PAD, cy, HEADER, false);
            cy += 11;
            small(g, font, "+ = they paid you, - = you paid them", x + PAD, cy, LABEL);
            cy += 10;
            for (Map.Entry<String, Double> e : list) {
                double v = e.getValue();
                g.text(font, trim(font, e.getKey(), 70), x + PAD, cy, WHITE, false);
                String val = PnlData.signed(v);
                g.text(font, val, x + W - PAD - font.width(val), cy, v >= 0 ? GREEN : RED, false);
                cy += 11;
            }
            y += h + 5;
        }

        // ---------- RECENT ----------
        if (!d.recent.isEmpty()) {
            int n = Math.min(d.recent.size(), 5);
            h = PAD + 9 + 3 + n * 10 + PAD - 3;
            panel(g, x, y, h);
            cy = y + PAD;
            g.text(font, "RECENT", x + PAD, cy, HEADER, false);
            cy += 12;
            for (int i = 0; i < n; i++) {
                String line = d.recent.get(i);
                small(g, font, trim(font, line, (W - 2 * PAD) * 100 / 85), x + PAD, cy, line.startsWith("+") ? GREEN : RED);
                cy += 10;
            }
        }
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int h) {
        g.fill(x, y, x + W, y + h, PANEL);
        g.fill(x + 1, y, x + W - 1, y + 1, BORDER);
    }

    private static void row(GuiGraphicsExtractor g, Font font, int x, int y, String label, String value, int color) {
        small(g, font, label, x + PAD, y + 1, LABEL);
        g.text(font, value, x + W - PAD - font.width(value), y, color, false);
    }

    private static void big(GuiGraphicsExtractor g, Font font, String s, int rightX, int y, int color) {
        float scale = 2f;
        while (font.width(s) * scale > W - 40 && scale > 1.2f) scale -= 0.1f;
        g.pose().pushMatrix();
        g.pose().translate(rightX - font.width(s) * scale, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, true);
        g.pose().popMatrix();
    }

    private static void small(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.85f, 0.85f);
        g.text(font, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    private static String trim(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, maxW - font.width("..")) + "..";
    }
}
