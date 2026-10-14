package com.fiizzyonice.pnltracker;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.*;

/** "24H PROFIT" panel: big profit, in/out, live chart, top winners/losers, recent payments. */
public class PnlHud {
    static final int PANEL   = 0xB8262E45; // translucent navy
    static final int CHARTBG = 0x50101626; // darker box behind the chart
    static final int EDGE    = 0x30FFFFFF;
    static final int PINK    = 0xFFE88AC0;
    static final int LABEL   = 0xFFA3ABC4;
    static final int WHITE   = 0xFFF2F4FA;
    static final int GREEN   = 0xFF5BE38C;
    static final int GREEN_F = 0x2A5BE38C; // faint fill under the line
    static final int RED     = 0xFFF0587A;
    static final int RED_F   = 0x2AF0587A;
    static final int WIN_H   = 0xFFF26B5B; // "TOP WINNERS" header
    static final int DARK    = 0xFF0C2416;

    static final int M = 6, P = 8, W = 176;

    private static float[] seriesCache = new float[0];

    public static void render(GuiGraphicsExtractor g, DeltaTracker tick) {
        PnlData d = PnlTracker.DATA;
        if (d == null || !d.hudVisible) return;

        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        float s = Math.max(0.5f, Math.min(2f, d.hudScale));
        int sw = (int) (mc.getWindow().getGuiScaledWidth() / s);
        int sh = (int) (mc.getWindow().getGuiScaledHeight() / s);

        List<PnlData.Tx> win = d.window();
        double in = 0, out = 0;
        Map<String, Double> nets = new HashMap<>();
        for (PnlData.Tx tx : win) {
            if (tx.a >= 0) in += tx.a; else out -= tx.a;
            nets.merge(tx.p, tx.a, Double::sum);
        }
        double profit = in - out;

        // Winners = players who took the most from you. Losers = players who paid you the most.
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(nets.entrySet());
        sorted.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
        List<Map.Entry<String, Double>> winners = new ArrayList<>(), losers = new ArrayList<>();
        for (Map.Entry<String, Double> e : sorted) if (e.getValue() < 0 && winners.size() < 5) winners.add(e);
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Map.Entry<String, Double> e = sorted.get(i);
            if (e.getValue() > 0 && losers.size() < 5) losers.add(e);
        }
        int listRows = Math.max(1, Math.max(winners.size(), losers.size()));
        int recentRows = Math.min(3, win.size());

        int chartH = 58;
        int h = P + 10 + 22 + 12 + chartH + 8 + 11 + listRows * 10 + (recentRows > 0 ? 8 + recentRows * 10 : 0) + P - 2;

        g.pose().pushMatrix();
        g.pose().scale(s, s);

        int x = d.rightSide ? sw - W - M : M;
        int y = M;
        panel(g, x, y, W, h, PANEL);

        // ---- header ----
        int cy = y + P;
        spaced(g, f, "24H PROFIT", x + P, cy, PINK, 0.8f);
        if (d.balance >= 0) {
            String bal = "Bal " + PnlData.format(d.balance);
            text(g, f, bal, x + W - P - w(f, bal, 0.7f), cy + 1, LABEL, 0.7f, false);
        }
        cy += 11;

        // ---- big profit ----
        String big = PnlData.signed(profit);
        float bs = 2.2f;
        while (f.width(big) * bs > W - 2 * P && bs > 1.2f) bs -= 0.05f;
        text(g, f, big, x + P, cy, profit >= 0 ? GREEN : RED, bs, true);
        cy += 21;

        // ---- in / out ----
        float ls = 0.75f;
        int ix = x + P;
        text(g, f, "In ", ix, cy, LABEL, ls, false);           ix += w(f, "In ", ls);
        String inS = PnlData.format(in);
        text(g, f, inS, ix, cy, WHITE, ls, false);              ix += w(f, inS, ls) + 6;
        text(g, f, "Out ", ix, cy, LABEL, ls, false);          ix += w(f, "Out ", ls);
        text(g, f, PnlData.format(out), ix, cy, WHITE, ls, false);
        cy += 12;

        // ---- chart ----
        chart(g, f, win, x + P, cy, W - 2 * P, chartH, profit);
        cy += chartH + 8;

        // ---- top winners / losers ----
        int colW = (W - 2 * P - 6) / 2;
        int lx = x + P, rx = x + P + colW + 6;
        spaced(g, f, "TOP WINNERS", lx, cy, WIN_H, 0.62f);
        spaced(g, f, "TOP LOSERS", rx, cy, GREEN, 0.62f);
        cy += 11;
        for (int i = 0; i < listRows; i++) {
            if (i < winners.size()) row(g, f, lx, cy, colW, winners.get(i).getKey(), PnlData.format(-winners.get(i).getValue()), RED);
            else if (i == 0) text(g, f, "-", lx, cy, LABEL, 0.75f, false);
            if (i < losers.size()) row(g, f, rx, cy, colW, losers.get(i).getKey(), PnlData.format(losers.get(i).getValue()), GREEN);
            else if (i == 0) text(g, f, "-", rx, cy, LABEL, 0.75f, false);
            cy += 10;
        }

        // ---- recent payments ----
        if (recentRows > 0) {
            cy += 4;
            g.fill(x + P, cy - 2, x + W - P, cy - 1, 0x22FFFFFF);
            cy += 2;
            for (int i = 0; i < recentRows; i++) {
                PnlData.Tx tx = win.get(win.size() - 1 - i);
                boolean got = tx.a >= 0;
                String arrow = got ? "← " : "→ ";
                text(g, f, arrow + trim(f, tx.p, 110), x + P, cy, LABEL, 0.8f, false);
                String amt = PnlData.signed(tx.a);
                text(g, f, amt, x + W - P - w(f, amt, 0.8f), cy, got ? GREEN : RED, 0.8f, true);
                cy += 10;
            }
        }

        balanceCard(g, f, d, mc.getUser().getName(), sw, sh);

        g.pose().popMatrix();
    }

    /** Bottom-right card: face, name, full live balance. */
    private static void balanceCard(GuiGraphicsExtractor g, Font f, PnlData d, String name, int sw, int sh) {
        int cw = 196, ch = 44, face = 32;
        int x = sw - cw - M, y = sh - ch - M;
        panel(g, x, y, cw, ch, PANEL);

        int fx = x + 6, fy = y + (ch - face) / 2;
        int[] px = PlayerFace.get(name);
        if (px != null) {
            int cell = face / 8;
            for (int r = 0; r < 8; r++)
                for (int c = 0; c < 8; c++)
                    g.fill(fx + c * cell, fy + r * cell, fx + (c + 1) * cell, fy + (r + 1) * cell, px[r * 8 + c]);
        } else {
            g.fill(fx, fy, fx + face, fy + face, 0x60FFFFFF);
        }

        int tx = fx + face + 7, tw = x + cw - 6 - tx;
        spaced(g, f, name.toUpperCase(), tx, y + 6, PINK, 0.72f);
        String bal = d.balance >= 0 ? "$" + String.format("%,.0f", d.balance) : "/bal to load";
        float bs = 1.9f;
        while (f.width(bal) * bs > tw && bs > 0.8f) bs -= 0.05f;
        text(g, f, bal, tx, y + 15, d.balance >= 0 ? GREEN : LABEL, bs, true);
        spaced(g, f, "BALANCE", tx, y + ch - 9, LABEL, 0.55f);
    }

    /** Running 24h profit as a line chart, with high/low labels, a value pill and a LIVE tag. */
    private static void chart(GuiGraphicsExtractor g, Font f, List<PnlData.Tx> win, int cx, int cy, int cw, int ch, double profit) {
        panel(g, cx, cy, cw, ch, CHARTBG);

        long now = System.currentTimeMillis(), start = now - PnlData.DAY;
        if (seriesCache.length != cw) seriesCache = new float[cw];
        double cum = 0, min = 0, max = 0;
        int k = 0;
        for (int i = 0; i < cw; i++) {
            long t = start + (long) ((i + 1) * (double) PnlData.DAY / cw);
            while (k < win.size() && win.get(k).t <= t) cum += win.get(k++).a;
            seriesCache[i] = (float) cum;
            min = Math.min(min, cum);
            max = Math.max(max, cum);
        }
        if (max - min < 1) max = min + 1;

        int top = cy + 10, bot = cy + ch - 10;
        int lineC = profit >= 0 ? GREEN : RED, fillC = profit >= 0 ? GREEN_F : RED_F;
        int zeroY = yOf(0, min, max, top, bot);
        int prevY = yOf(seriesCache[0], min, max, top, bot);
        for (int i = 0; i < cw; i++) {
            int yy = yOf(seriesCache[i], min, max, top, bot);
            int px = cx + i;
            g.fill(px, Math.min(yy, zeroY), px + 1, Math.max(yy, zeroY), fillC);
            g.fill(px, Math.min(prevY, yy), px + 1, Math.max(prevY, yy) + 1, lineC);
            prevY = yy;
        }

        // high / low labels
        text(g, f, PnlData.signed(max), cx + 3, cy + 2, LABEL, 0.55f, false);
        text(g, f, PnlData.signed(min), cx + 3, cy + ch - 7, LABEL, 0.55f, false);

        // current value pill at the right end of the line
        String v = PnlData.signed(profit);
        float ps = 0.62f;
        int tw = w(f, v, ps), pw = tw + 6, ph = 9;
        int px = cx + cw - pw - 2;
        int py = Math.max(cy + 1, Math.min(cy + ch - ph - 9, prevY - ph / 2));
        panel(g, px, py, pw, ph, lineC);
        text(g, f, v, px + 3, py + 2, DARK, ps, false);

        // LIVE tag
        int lw = w(f, "LIVE", 0.5f);
        g.fill(cx + cw - lw - 8, cy + ch - 6, cx + cw - lw - 6, cy + ch - 4, RED);
        text(g, f, "LIVE", cx + cw - lw - 3, cy + ch - 7, LABEL, 0.5f, false);
    }

    private static int yOf(double v, double min, double max, int top, int bot) {
        return (int) Math.round(bot - (v - min) / (max - min) * (bot - top));
    }

    private static void row(GuiGraphicsExtractor g, Font f, int x, int y, int colW, String name, String amt, int color) {
        float sc = 0.72f;
        int aw = w(f, amt, sc);
        int maxName = (int) ((colW - aw - 4) / sc);
        text(g, f, trim(f, name, maxName), x, y, WHITE, sc, false);
        text(g, f, amt, x + colW - aw, y, color, sc, true);
    }

    /** Rounded rectangle (radius 3) with a faint top highlight. */
    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 3, y, x + w - 3, y + h, c);
        g.fill(x, y + 3, x + 3, y + h - 3, c);
        g.fill(x + w - 3, y + 3, x + w, y + h - 3, c);
        g.fill(x + 1, y + 1, x + 3, y + 3, c);
        g.fill(x + w - 3, y + 1, x + w - 1, y + 3, c);
        g.fill(x + 1, y + h - 3, x + 3, y + h - 1, c);
        g.fill(x + w - 3, y + h - 3, x + w - 1, y + h - 1, c);
        if (c == PANEL) g.fill(x + 3, y, x + w - 3, y + 1, EDGE);
    }

    private static int w(Font f, String s, float scale) { return (int) Math.ceil(f.width(s) * scale); }

    private static void text(GuiGraphicsExtractor g, Font f, String s, float x, float y, int color, float scale, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(f, s, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

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
        if (maxW <= 0) return "";
        if (f.width(s) <= maxW) return s;
        return f.plainSubstrByWidth(s, Math.max(0, maxW - f.width(".."))) + "..";
    }
}
