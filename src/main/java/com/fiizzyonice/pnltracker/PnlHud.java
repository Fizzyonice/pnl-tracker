package com.fiizzyonice.pnltracker;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Casino-overlay style HUD: rounded navy panels, pink spaced headers, big green/red numbers. */
public class PnlHud {
    static final int PANEL  = 0xB4303A57; // translucent navy
    static final int EDGE   = 0x30FFFFFF; // faint top highlight
    static final int HEADER = 0xFFE88AC0; // pink header
    static final int LABEL  = 0xFFA9B2CC; // gray-blue labels
    static final int WHITE  = 0xFFF2F4FA;
    static final int GREEN  = 0xFF5BE38C;
    static final int RED    = 0xFFF0587A;
    static final int PINK   = 0xFFF08CC4;

    static final int M = 6;    // screen margin
    static final int P = 8;    // panel padding

    public static void render(GuiGraphicsExtractor g, DeltaTracker tick) {
        PnlData d = PnlTracker.DATA;
        if (d == null || !d.hudVisible) return;

        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        float s = Math.max(0.5f, Math.min(2f, d.hudScale));
        int sw = (int) (mc.getWindow().getGuiScaledWidth() / s);
        int sh = (int) (mc.getWindow().getGuiScaledHeight() / s);

        g.pose().pushMatrix();
        g.pose().scale(s, s);

        int leftBottom = profitPanel(g, f, d, M, M);
        recentPanel(g, f, d, M, leftBottom + 5);
        playerNetsPanel(g, f, d, sw - 170 - M, M);
        balanceCard(g, f, d, mc, sw - 180 - M, sh - 46 - M);

        g.pose().popMatrix();
    }

    // ---------- top-left: PROFIT / LOSS (styled like "BET LIMITS") ----------
    private static int profitPanel(GuiGraphicsExtractor g, Font f, PnlData d, int x, int y) {
        int w = 140, rowH = 18;
        int h = P + 9 + 6 + 4 * rowH + P - 4;
        panel(g, x, y, w, h);
        spaced(g, f, "PROFIT / LOSS", x + P, y + P, HEADER, 0.8f);
        int cy = y + P + 15;
        double net = d.net();
        bigRow(g, f, x, w, cy, "NET", PnlData.signed(net), net >= 0 ? GREEN : RED); cy += rowH;
        bigRow(g, f, x, w, cy, "RECEIVED", PnlData.format(d.received), GREEN);      cy += rowH;
        bigRow(g, f, x, w, cy, "SENT", PnlData.format(d.sent), RED);                cy += rowH;
        bigRow(g, f, x, w, cy, "PAYMENTS", String.valueOf(d.payments), PINK);
        return y + h;
    }

    // ---------- left, under P/L: RECENT ----------
    private static void recentPanel(GuiGraphicsExtractor g, Font f, PnlData d, int x, int y) {
        if (d.recent.isEmpty()) return;
        int w = 140, n = Math.min(d.recent.size(), 5);
        int h = P + 9 + 5 + n * 10 + P - 3;
        panel(g, x, y, w, h);
        spaced(g, f, "RECENT", x + P, y + P, HEADER, 0.8f);
        int cy = y + P + 14;
        for (int i = 0; i < n; i++) {
            String line = d.recent.get(i);
            scaled(g, f, trim(f, line, (int) ((w - 2 * P) / 0.85f)), x + P, cy, line.startsWith("+") ? GREEN : RED, 0.85f, false);
            cy += 10;
        }
    }

    // ---------- top-right: PLAYER NETS ----------
    private static void playerNetsPanel(GuiGraphicsExtractor g, Font f, PnlData d, int x, int y) {
        int w = 170;
        // Player's side, like the casino overlay: + = player is up (you paid them more), - = they're down
        List<Map.Entry<String, Double>> nets = new ArrayList<>(d.playerNets.entrySet());
        nets.sort((a, b) -> Double.compare(a.getValue(), b.getValue())); // biggest player wins first
        List<Map.Entry<String, Double>> list = new ArrayList<>();
        if (nets.size() <= 10) list.addAll(nets);
        else { list.addAll(nets.subList(0, 5)); list.addAll(nets.subList(nets.size() - 5, nets.size())); }

        int rows = Math.max(list.size(), 1);
        int h = P + 9 + 4 + 7 + 6 + rows * 11 + P - 3;
        panel(g, x, y, w, h);
        spaced(g, f, "PLAYER NETS", x + P, y + P, HEADER, 0.8f);
        if (d.balance >= 0) {
            String bal = "Bal " + PnlData.format(d.balance);
            int bw = (int) (f.width(bal) * 0.7f);
            scaled(g, f, bal, x + w - P - bw, y + P + 1, LABEL, 0.7f, false);
        }
        scaled(g, f, "+ means the player is up, - means they're down", x + P, y + P + 12, LABEL, 0.6f, false);

        int cy = y + P + 25;
        if (list.isEmpty()) {
            scaled(g, f, "No payments yet", x + P + 2, cy, LABEL, 0.8f, false);
            return;
        }
        for (Map.Entry<String, Double> e : list) {
            double playerUp = -e.getValue();
            scaled(g, f, trim(f, e.getKey(), 100), x + P + 2, cy, WHITE, 0.85f, true);
            String val = PnlData.signed(playerUp);
            int vw = (int) (f.width(val) * 0.85f);
            scaled(g, f, val, x + w - P - vw, cy, playerUp >= 0 ? GREEN : RED, 0.85f, true);
            cy += 11;
        }
    }

    // ---------- bottom-right: BALANCE card ----------
    private static void balanceCard(GuiGraphicsExtractor g, Font f, PnlData d, Minecraft mc, int x, int y) {
        int w = 180, h = 46;
        panel(g, x, y, w, h);
        String name = mc.getUser().getName().toUpperCase();
        spaced(g, f, name, x + P, y + 6, HEADER, 0.8f);

        String bal = d.balance >= 0 ? "$" + String.format("%,.0f", d.balance) : "/bal to load";
        float bs = 1.8f;
        while (f.width(bal) * bs > w - 2 * P && bs > 0.9f) bs -= 0.05f;
        scaled(g, f, bal, x + P, y + 16, d.balance >= 0 ? GREEN : LABEL, bs, true);
        spaced(g, f, "BALANCE", x + P, y + h - 10, LABEL, 0.6f);
    }

    // ---------- helpers ----------
    private static void bigRow(GuiGraphicsExtractor g, Font f, int x, int w, int y, String label, String value, int color) {
        spaced(g, f, label, x + P + 2, y + 4, LABEL, 0.65f);
        float vs = 1.5f;
        while (f.width(value) * vs > w - 60 && vs > 1f) vs -= 0.05f;
        int vw = (int) (f.width(value) * vs);
        scaled(g, f, value, x + w - P - vw, y, color, vs, true);
    }

    /** Rounded rectangle (radius 3) plus a faint top highlight. */
    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x + 3, y, x + w - 3, y + h, PANEL);
        g.fill(x, y + 3, x + 3, y + h - 3, PANEL);
        g.fill(x + w - 3, y + 3, x + w, y + h - 3, PANEL);
        g.fill(x + 1, y + 1, x + 3, y + 3, PANEL);
        g.fill(x + w - 3, y + 1, x + w - 1, y + 3, PANEL);
        g.fill(x + 1, y + h - 3, x + 3, y + h - 1, PANEL);
        g.fill(x + w - 3, y + h - 3, x + w - 1, y + h - 1, PANEL);
        g.fill(x + 3, y, x + w - 3, y + 1, EDGE);
    }

    private static void scaled(GuiGraphicsExtractor g, Font f, String s, float x, float y, int color, float scale, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(f, s, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    /** Letter-spaced caps text, like the overlay's headers. */
    private static void spaced(GuiGraphicsExtractor g, Font f, String s, float x, float y, int color, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        int cx = 0;
        for (int i = 0; i < s.length(); i++) {
            String c = String.valueOf(s.charAt(i));
            g.text(f, c, cx, 0, color, false);
            cx += f.width(c) + 1;
        }
        g.pose().popMatrix();
    }

    private static String trim(Font f, String s, int maxW) {
        if (f.width(s) <= maxW) return s;
        return f.plainSubstrByWidth(s, maxW - f.width("..")) + "..";
    }
}
