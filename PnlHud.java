package com.fiizzyonice.pnltracker;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

/** "24H PROFIT" panel + bottom-right balance card, drawn with a smooth bundled font (Poppins). */
public class PnlHud {
    static final int PANEL   = 0xE00F1522; // dark navy, mostly solid so it reads well on stream
    static final int OUTLINE = 0x905C7FB8; // soft blue outline
    static final int CARD    = 0x70202A3D; // inner boxes
    static final int CHARTBG = 0x60080C16;
    static final int PINK    = 0xFFF29BCB;
    static final int LABEL   = 0xFF9AA4BF;
    static final int WHITE   = 0xFFFFFFFF;
    static final int GREEN   = 0xFF4CE38A;
    static final int GREEN_F = 0x224CE38A;
    static final int RED     = 0xFFFF5C7A;
    static final int RED_F   = 0x22FF5C7A;
    static final int WIN_H   = 0xFFFF6B6B;
    static final int DARK    = 0xFF06210F;
    static final int GRID    = 0x30FFFFFF;

    static final int M = 6, P = 9, W = 196;

    // ---------- smooth font ----------
    private static Style BOLD, MED;
    private static boolean fontsTried;

    private static void initFonts() {
        if (fontsTried) return;
        fontsTried = true;
        BOLD = fontStyle("bold");
        MED = fontStyle("medium");
    }

    /** Builds a Style that uses assets/pnltracker/font/<path>.json. Works whatever the exact 26.1 signature is. */
    private static Style fontStyle(String path) {
        try {
            Identifier id = Identifier.fromNamespaceAndPath("pnltracker", path);
            for (Method m : Style.class.getMethods()) {
                if (!m.getName().equals("withFont") || m.getParameterCount() != 1) continue;
                Class<?> pt = m.getParameterTypes()[0];
                Object arg = pt.isInstance(id) ? id : fontArg(pt, id);
                if (arg != null) return (Style) m.invoke(Style.EMPTY, arg);
            }
            PnlTracker.LOG.warn("PnL Tracker: no Style.withFont found, using the normal font");
        } catch (Throwable t) {
            PnlTracker.LOG.warn("PnL Tracker: smooth font unavailable, using the normal font", t);
        }
        return null;
    }

    private static Object fontArg(Class<?> type, Identifier id) throws Exception {
        List<Class<?>> candidates = new ArrayList<>();
        candidates.add(type);
        candidates.addAll(Arrays.asList(type.getDeclaredClasses()));
        if (type.isSealed()) candidates.addAll(Arrays.asList(type.getPermittedSubclasses()));
        for (Class<?> c : candidates) {
            if (!type.isAssignableFrom(c)) continue;
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (k.getParameterCount() == 1 && k.getParameterTypes()[0].isInstance(id)) {
                    k.setAccessible(true);
                    return k.newInstance(id);
                }
            }
        }
        return null;
    }

    private static Component c(String s, boolean bold) {
        MutableComponent m = Component.literal(s);
        Style st = bold ? BOLD : MED;
        if (st != null) m.setStyle(st);
        return m;
    }

    // ---------- render ----------
    public static void render(GuiGraphicsExtractor g, DeltaTracker tick) {
        PnlData d = PnlTracker.DATA;
        if (d == null || !d.hudVisible) return;
        initFonts();

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

        int chartH = 62;
        int boxH = 6 + 10 + listRows * 11 + 3;
        int h = P + 11 + 22 + 13 + chartH + 7 + boxH + (recentRows > 0 ? 7 + recentRows * 12 : 0) + P - 2;

        g.pose().pushMatrix();
        g.pose().scale(s, s);

        int x = d.rightSide ? sw - W - M : M;
        int y = M;
        panel(g, x, y, W, h, PANEL);
        outline(g, x, y, W, h, OUTLINE);

        // header
        int cy = y + P;
        spaced(g, f, "24H PROFIT", x + P, cy, PINK, 0.85f);
        if (d.balance >= 0) {
            String bal = "Bal " + PnlData.format(d.balance);
            txt(g, f, bal, x + W - P - w(f, bal, false, 0.8f), cy, LABEL, 0.8f, false, false);
        }
        cy += 12;

        // big profit
        String big = PnlData.signed(profit);
        float bs = 2.3f;
        while (w(f, big, true, bs) > W - 2 * P && bs > 1.2f) bs -= 0.05f;
        txt(g, f, big, x + P, cy, profit >= 0 ? GREEN : RED, bs, true, true);
        cy += 22;

        // in / out
        float ls = 0.85f;
        int ix = x + P;
        txt(g, f, "In ", ix, cy, LABEL, ls, false, false);       ix += w(f, "In ", false, ls);
        String inS = PnlData.format(in);
        txt(g, f, inS, ix, cy, WHITE, ls, true, false);           ix += w(f, inS, true, ls) + 8;
        txt(g, f, "Out ", ix, cy, LABEL, ls, false, false);      ix += w(f, "Out ", false, ls);
        txt(g, f, PnlData.format(out), ix, cy, WHITE, ls, true, false);
        cy += 13;

        // chart
        chart(g, f, win, x + P, cy, W - 2 * P, chartH, profit);
        cy += chartH + 7;

        // top winners / losers boxes
        int colW = (W - 2 * P - 6) / 2;
        int lx = x + P, rx = x + P + colW + 6;
        panel(g, lx, cy, colW, boxH, CARD);
        panel(g, rx, cy, colW, boxH, CARD);
        spaced(g, f, "TOP WINNERS", lx + 5, cy + 5, WIN_H, 0.68f);
        spaced(g, f, "TOP LOSERS", rx + 5, cy + 5, GREEN, 0.68f);
        int ry = cy + 16;
        for (int i = 0; i < listRows; i++) {
            if (i < winners.size()) row(g, f, lx + 5, ry, colW - 10, winners.get(i).getKey(), PnlData.format(-winners.get(i).getValue()), RED);
            else if (i == 0) txt(g, f, "-", lx + 5, ry, LABEL, 0.8f, true, false);
            if (i < losers.size()) row(g, f, rx + 5, ry, colW - 10, losers.get(i).getKey(), PnlData.format(losers.get(i).getValue()), GREEN);
            else if (i == 0) txt(g, f, "-", rx + 5, ry, LABEL, 0.8f, true, false);
            ry += 11;
        }
        cy += boxH;

        // recent payments
        if (recentRows > 0) {
            cy += 7;
            for (int i = 0; i < recentRows; i++) {
                PnlData.Tx tx = win.get(win.size() - 1 - i);
                boolean got = tx.a >= 0;
                text(g, f, Component.literal(got ? "←" : "→"), x + P, cy, LABEL, 0.85f, false);
                String amt = PnlData.signed(tx.a);
                int aw = w(f, amt, true, 0.85f);
                txt(g, f, trim(f, tx.p, true, 0.85f, W - 2 * P - 14 - aw - 6), x + P + 11, cy, WHITE, 0.85f, true, false);
                txt(g, f, amt, x + W - P - aw, cy, got ? GREEN : RED, 0.85f, true, false);
                cy += 12;
            }
        }

        balanceCard(g, f, d, mc.getUser().getName(), sw, sh);
        g.pose().popMatrix();
    }

    /** Bottom-right card: name and full live balance. */
    private static void balanceCard(GuiGraphicsExtractor g, Font f, PnlData d, String name, int sw, int sh) {
        int cw = 200, ch = 46;
        int x = sw - cw - M, y = sh - ch - M;
        panel(g, x, y, cw, ch, PANEL);
        outline(g, x, y, cw, ch, OUTLINE);
        int tx = x + 10, tw = cw - 20;
        spaced(g, f, name.toUpperCase(), tx, y + 6, PINK, 0.75f);
        String bal = d.balance >= 0 ? "$" + String.format("%,.0f", d.balance) : "/bal to load";
        float bs = 2.0f;
        while (w(f, bal, true, bs) > tw && bs > 0.8f) bs -= 0.05f;
        txt(g, f, bal, tx, y + 16, d.balance >= 0 ? GREEN : LABEL, bs, true, true);
        spaced(g, f, "BALANCE", tx, y + ch - 10, LABEL, 0.6f);
    }

    /** Running 24h profit, drawn at double resolution for a thin, smooth line. */
    private static void chart(GuiGraphicsExtractor g, Font f, List<PnlData.Tx> win, int cx, int cy, int cw, int ch, double profit) {
        panel(g, cx, cy, cw, ch, CHARTBG);

        int n = cw * 2;
        double[] vals = new double[n];
        long now = System.currentTimeMillis(), start = now - PnlData.DAY;
        double cum = 0, min = 0, max = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            long t = start + (long) ((i + 1) * (double) PnlData.DAY / n);
            while (k < win.size() && win.get(k).t <= t) cum += win.get(k++).a;
            vals[i] = cum;
            min = Math.min(min, cum);
            max = Math.max(max, cum);
        }
        if (max - min < 1) max = min + 1;

        int top = (cy + 11) * 2, bot = (cy + ch - 11) * 2;
        int lineC = profit >= 0 ? GREEN : RED, fillC = profit >= 0 ? GREEN_F : RED_F;

        g.pose().pushMatrix();
        g.pose().scale(0.5f, 0.5f);
        int zeroY = yOf(0, min, max, top, bot);
        for (int gx = cx * 2 + 2; gx < (cx + cw) * 2 - 2; gx += 6) g.fill(gx, zeroY, gx + 3, zeroY + 1, GRID); // dashed zero line
        int prevY = yOf(vals[0], min, max, top, bot);
        for (int i = 0; i < n; i++) {
            int yy = yOf(vals[i], min, max, top, bot);
            int px = cx * 2 + i;
            g.fill(px, Math.min(yy, zeroY), px + 1, Math.max(yy, zeroY), fillC);
            g.fill(px, Math.min(prevY, yy), px + 1, Math.max(prevY, yy) + 2, lineC);
            prevY = yy;
        }
        int ex = (cx + cw) * 2 - 1;
        g.fill(ex - 3, prevY - 2, ex + 2, prevY + 3, lineC); // end dot
        g.pose().popMatrix();

        txt(g, f, PnlData.signed(max), cx + 4, cy + 3, LABEL, 0.62f, false, false);
        txt(g, f, PnlData.signed(min), cx + 4, cy + ch - 8, LABEL, 0.62f, false, false);

        String v = PnlData.signed(profit);
        float ps = 0.72f;
        int tw = w(f, v, true, ps), pw = tw + 8, ph = 10;
        int px = cx + cw - pw - 5;
        int py = Math.max(cy + 2, Math.min(cy + ch - ph - 10, prevY / 2 - ph / 2));
        panel(g, px, py, pw, ph, lineC);
        txt(g, f, v, px + 4, py + 2, DARK, ps, true, false);

        int lw = w(f, "LIVE", true, 0.6f);
        g.fill(cx + cw - lw - 9, cy + ch - 7, cx + cw - lw - 6, cy + ch - 4, RED);
        txt(g, f, "LIVE", cx + cw - lw - 4, cy + ch - 8, LABEL, 0.6f, true, false);
    }

    private static int yOf(double v, double min, double max, int top, int bot) {
        return (int) Math.round(bot - (v - min) / (max - min) * (bot - top));
    }

    private static void row(GuiGraphicsExtractor g, Font f, int x, int y, int colW, String name, String amt, int color) {
        float sc = 0.78f;
        int aw = w(f, amt, true, sc);
        txt(g, f, trim(f, name, true, sc, colW - aw - 4), x, y, WHITE, sc, true, false);
        txt(g, f, amt, x + colW - aw, y, color, sc, true, false);
    }

    // ---------- drawing helpers ----------
    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int col) {
        g.fill(x + 3, y, x + w - 3, y + h, col);
        g.fill(x, y + 3, x + 3, y + h - 3, col);
        g.fill(x + w - 3, y + 3, x + w, y + h - 3, col);
        g.fill(x + 1, y + 1, x + 3, y + 3, col);
        g.fill(x + w - 3, y + 1, x + w - 1, y + 3, col);
        g.fill(x + 1, y + h - 3, x + 3, y + h - 1, col);
        g.fill(x + w - 3, y + h - 3, x + w - 1, y + h - 1, col);
    }

    /** 1px rounded outline, drawn at half-pixel resolution so it looks thin. */
    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int col) {
        g.pose().pushMatrix();
        g.pose().scale(0.5f, 0.5f);
        int X = x * 2, Y = y * 2, Wd = w * 2, H = h * 2;
        g.fill(X + 6, Y, X + Wd - 6, Y + 1, col);
        g.fill(X + 6, Y + H - 1, X + Wd - 6, Y + H, col);
        g.fill(X, Y + 6, X + 1, Y + H - 6, col);
        g.fill(X + Wd - 1, Y + 6, X + Wd, Y + H - 6, col);
        int[][] corner = {{1,4},{2,2},{4,1}}; // simple rounded corner steps
        for (int[] cpt : corner) {
            int a = cpt[0], b = cpt[1];
            g.fill(X + a, Y + b, X + a + 2, Y + b + 2, col);
            g.fill(X + Wd - a - 2, Y + b, X + Wd - a, Y + b + 2, col);
            g.fill(X + a, Y + H - b - 2, X + a + 2, Y + H - b, col);
            g.fill(X + Wd - a - 2, Y + H - b - 2, X + Wd - a, Y + H - b, col);
        }
        g.pose().popMatrix();
    }

    private static int w(Font f, String s, boolean bold, float scale) {
        return (int) Math.ceil(f.width(c(s, bold)) * scale);
    }

    private static void txt(GuiGraphicsExtractor g, Font f, String s, float x, float y, int color, float scale, boolean bold, boolean shadow) {
        text(g, f, c(s, bold), x, y, color, scale, shadow);
    }

    private static void text(GuiGraphicsExtractor g, Font f, Component comp, float x, float y, int color, float scale, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(f, comp, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    /** Letter-spaced bold caps for headers. */
    private static void spaced(GuiGraphicsExtractor g, Font f, String s, float x, float y, int color, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        int cx = 0;
        for (int i = 0; i < s.length(); i++) {
            Component ch = c(String.valueOf(s.charAt(i)), true);
            g.text(f, ch, cx, 0, color, false);
            cx += f.width(ch) + 1;
        }
        g.pose().popMatrix();
    }

    private static String trim(Font f, String s, boolean bold, float scale, int maxW) {
        if (maxW <= 0) return "";
        if (w(f, s, bold, scale) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && w(f, t + "..", bold, scale) > maxW) t = t.substring(0, t.length() - 1);
        return t + "..";
    }
}
