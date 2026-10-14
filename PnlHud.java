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
    static final int BET_ROWS = 12, BET_ROW_H = 14, FOOT = 12;
    static final int LOST_BG = 0x5534C66E, WON_BG = 0x55E8435E, PAID_BG = 0x40A0A8C0;

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
        List<PnlData.Bet> bets = PnlData.bets(win);

        int chartH = 62;
        int boxH = 6 + 10 + listRows * 11 + 3;
        int profitH = 12 + 22 + 13 + chartH + 7 + boxH + (recentRows > 0 ? 7 + recentRows * 12 : 0);
        int betsH = 14 + Math.max(1, Math.min(BET_ROWS, bets.size())) * BET_ROW_H;
        boolean rotating = d.rotateSeconds > 0;
        int bodyH = rotating ? Math.max(profitH, betsH) : profitH;
        int h = P + bodyH + (rotating ? FOOT : 0) + P - 2;

        long now = System.currentTimeMillis();
        long period = Math.max(1, d.rotateSeconds) * 1000L;
        int page = rotating ? (int) ((now / period) % 2) : 0;

        g.pose().pushMatrix();
        g.pose().scale(s, s);

        int x = d.rightSide ? sw - W - M : M;
        int y = M;
        panel(g, x, y, W, h, PANEL);
        outline(g, x, y, W, h, OUTLINE);

        if (page == 0) drawProfit(g, f, d, x, y + P, win, in, out, profit, winners, losers, listRows, recentRows, chartH, boxH);
        else drawBets(g, f, d, x, y + P, bets);

        if (rotating) footer(g, x, y + h - P - 4, page, (now % period) / (float) period);

        if (d.rbVisible) {
            int rbx = d.rightSide ? x - RB_W - 6 : x + W + 6;
            drawRakeback(g, f, d, rbx, y);
        }

        balanceCard(g, f, d, mc.getUser().getName(), sw, sh);
        g.pose().popMatrix();
    }

    private static void drawProfit(GuiGraphicsExtractor g, Font f, PnlData d, int x, int cy, List<PnlData.Tx> win,
                                   double in, double out, double profit,
                                   List<Map.Entry<String, Double>> winners, List<Map.Entry<String, Double>> losers,
                                   int listRows, int recentRows, int chartH, int boxH) {
        header(g, f, d, x, cy, "24H PROFIT");
        cy += 12;

        String big = PnlData.signed(profit);
        float bs = 2.3f;
        while (w(f, big, true, bs) > W - 2 * P && bs > 1.2f) bs -= 0.05f;
        txt(g, f, big, x + P, cy, profit >= 0 ? GREEN : RED, bs, true, true);
        cy += 22;

        float ls = 0.85f;
        int ix = x + P;
        txt(g, f, "In ", ix, cy, LABEL, ls, false, false);       ix += w(f, "In ", false, ls);
        String inS = PnlData.format(in);
        txt(g, f, inS, ix, cy, WHITE, ls, true, false);           ix += w(f, inS, true, ls) + 8;
        txt(g, f, "Out ", ix, cy, LABEL, ls, false, false);      ix += w(f, "Out ", false, ls);
        txt(g, f, PnlData.format(out), ix, cy, WHITE, ls, true, false);
        cy += 13;

        chart(g, f, win, x + P, cy, W - 2 * P, chartH, profit);
        cy += chartH + 7;

        int colW = (W - 2 * P - 6) / 2;
        int lx = x + P, rx = x + P + colW + 6;
        panel(g, lx, cy, colW, boxH, CARD);
        panel(g, rx, cy, colW, boxH, CARD);
        spaced(g, f, "TOP WINNERS", lx + 5, cy + 5, GREEN, 0.68f);
        spaced(g, f, "TOP LOSERS", rx + 5, cy + 5, WIN_H, 0.68f);
        int ry = cy + 16;
        for (int i = 0; i < listRows; i++) {
            if (i < winners.size()) row(g, f, lx + 5, ry, colW - 10, winners.get(i).getKey(), PnlData.format(-winners.get(i).getValue()), GREEN);
            else if (i == 0) txt(g, f, "-", lx + 5, ry, LABEL, 0.8f, true, false);
            if (i < losers.size()) row(g, f, rx + 5, ry, colW - 10, losers.get(i).getKey(), PnlData.format(losers.get(i).getValue()), RED);
            else if (i == 0) txt(g, f, "-", rx + 5, ry, LABEL, 0.8f, true, false);
            ry += 11;
        }
        cy += boxH;

        if (recentRows > 0) {
            cy += 7;
            for (int i = 0; i < recentRows; i++) {
                PnlData.Tx tx = win.get(win.size() - 1 - i);
                boolean got = tx.a >= 0;
                text(g, f, Component.literal(got ? "\u2190" : "\u2192"), x + P, cy, LABEL, 0.85f, false);
                String amt = PnlData.signed(tx.a);
                int aw = w(f, amt, true, 0.85f);
                txt(g, f, trim(f, tx.p, true, 0.85f, W - 2 * P - 14 - aw - 6), x + P + 11, cy, WHITE, 0.85f, true, false);
                txt(g, f, amt, x + W - P - aw, cy, got ? GREEN : RED, 0.85f, true, false);
                cy += 12;
            }
        }
    }

    /** RECENT BETS page: badge, player, amount, time ago. */
    private static void drawBets(GuiGraphicsExtractor g, Font f, PnlData d, int x, int cy, List<PnlData.Bet> bets) {
        header(g, f, d, x, cy, "RECENT BETS");
        cy += 14;
        if (bets.isEmpty()) {
            txt(g, f, "No bets yet", x + P, cy + 2, LABEL, 0.85f, false, false);
            return;
        }
        int n = Math.min(BET_ROWS, bets.size());
        for (int i = 0; i < n; i++) {
            PnlData.Bet b = bets.get(i);
            int rx = x + P, rw = W - 2 * P, rh = BET_ROW_H - 2;
            panel(g, rx, cy, rw, rh, CARD);

            String label = b.kind == 0 ? "LOST" : b.kind == 1 ? "WON" : b.kind == 3 ? "RAKE" : "PAID";
            int badgeC = b.kind == 0 ? GREEN : b.kind == 3 ? PINK : RED;
            int badgeBg = b.kind == 0 ? LOST_BG : b.kind == 1 ? WON_BG : b.kind == 3 ? 0x50F29BCB : PAID_BG;
            float bsz = 0.55f;
            int bw = 22;
            pill(g, rx + 3, cy + 2, bw, rh - 4, badgeBg);
            int lw = w(f, label, true, bsz);
            txt(g, f, label, rx + 3 + (bw - lw) / 2f, cy + 3.5f, badgeC, bsz, true, false);

            String ago = PnlData.ago(b.t);
            float ts = 0.7f;
            int tw = w(f, ago, false, ts);
            txt(g, f, ago, rx + rw - 4 - tw, cy + 3, LABEL, ts, false, false);

            String amt = PnlData.format(b.amt);
            float as = 0.82f;
            int aw = w(f, amt, true, as);
            int ax = rx + rw - 4 - 16 - aw;
            txt(g, f, amt, ax, cy + 2, b.kind == 0 ? GREEN : b.kind == 3 ? PINK : RED, as, true, false);

            int nx = rx + 3 + bw + 5;
            txt(g, f, trim(f, b.p, true, 0.82f, ax - nx - 4), nx, cy + 2, WHITE, 0.82f, true, false);
            cy += BET_ROW_H;
        }
    }

    static final int RB_W = 150, RB_ROW = 17;
    static final int RB_AMT = 0xFFE4E8F2; // bright so the numbers read on stream

    /** RAKEBACK panel: top 6 players by losses toward $1B, with a pink-to-green bar. */
    private static void drawRakeback(GuiGraphicsExtractor g, Font f, PnlData d, int x, int y) {
        List<Map.Entry<String, Double>> top = d.rakebackTop(6);
        int rows = Math.max(1, top.size());
        int h = 7 + 11 + rows * RB_ROW + 4;
        panel(g, x, y, RB_W, h, PANEL);
        outline(g, x, y, RB_W, h, OUTLINE);

        int px = 7;
        int cy = y + 7;
        spaced(g, f, "RAKEBACK", x + px, cy, PINK, 0.78f);
        String sub = Math.round(PnlData.RB_PCT * 100) + "% over " + PnlData.shortFmt(PnlData.RB_LIMIT) + " lost";
        txt(g, f, sub, x + RB_W - px - w(f, sub, false, 0.66f), cy + 1, LABEL, 0.66f, false, false);
        cy += 11;

        if (top.isEmpty()) {
            panel(g, x + px, cy, RB_W - 2 * px, RB_ROW - 2, CARD);
            txt(g, f, "No losses yet", x + px + 5, cy + 4, LABEL, 0.8f, false, false);
            return;
        }
        for (Map.Entry<String, Double> e : top) {
            int rx = x + px, rw = RB_W - 2 * px, rh = RB_ROW - 2;
            panel(g, rx, cy, rw, rh, CARD);
            double v = e.getValue();
            boolean ready = v >= PnlData.RB_LIMIT;

            String amt = ready ? "OWE " + PnlData.shortFmt(PnlData.owed(v))
                               : PnlData.shortFmt(v) + " / " + PnlData.shortFmt(PnlData.RB_LIMIT);
            float ts = 0.8f;
            int aw = w(f, amt, true, ts);
            txt(g, f, amt, rx + rw - 5 - aw, cy + 2, ready ? GREEN : RB_AMT, ts, true, false);
            txt(g, f, trim(f, e.getKey(), true, ts, rw - 10 - aw - 6), rx + 5, cy + 2, WHITE, ts, true, false);

            // thin progress bar under the text
            int bx = rx + 5, bw = rw - 10, by = cy + 11;
            float frac = (float) Math.min(1.0, v / PnlData.RB_LIMIT);
            g.pose().pushMatrix();
            g.pose().scale(0.5f, 0.5f);
            g.fill(bx * 2, by * 2, (bx + bw) * 2, by * 2 + 3, 0x26FFFFFF);
            int fillEnd = Math.round(bw * 2 * frac);
            for (int i = 0; i < fillEnd; i += 2) {
                float t = i / (float) (bw * 2);
                g.fill(bx * 2 + i, by * 2, bx * 2 + Math.min(i + 2, fillEnd), by * 2 + 3, lerp(PINK, GREEN, t));
            }
            g.pose().popMatrix();
            cy += RB_ROW;
        }
    }

    private static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = Math.round(ar + (br - ar) * t), gg = Math.round(ag + (bg - ag) * t), bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }

    private static void header(GuiGraphicsExtractor g, Font f, PnlData d, int x, int cy, String title) {
        spaced(g, f, title, x + P, cy, PINK, 0.85f);
        if (d.balance >= 0) {
            String bal = "Bal " + PnlData.format(d.balance);
            txt(g, f, bal, x + W - P - w(f, bal, false, 0.8f), cy, LABEL, 0.8f, false, false);
        }
    }

    /** Page dots + a bar that fills up until the next page switch. */
    private static void footer(GuiGraphicsExtractor g, int x, int y, int page, float progress) {
        for (int i = 0; i < 2; i++) {
            int dx = x + P + i * 6;
            g.fill(dx, y, dx + 3, y + 3, i == page ? PINK : 0x60FFFFFF);
        }
        int bx = x + P + 16, bw = W - 2 * P - 16;
        g.pose().pushMatrix();
        g.pose().scale(0.5f, 0.5f);
        g.fill(bx * 2, y * 2 + 2, (bx + bw) * 2, y * 2 + 4, 0x30FFFFFF);
        g.fill(bx * 2, y * 2 + 2, bx * 2 + Math.round(bw * 2 * progress), y * 2 + 4, PINK);
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

    /** Smooth capsule ("sideways oval"), drawn at quarter-pixel resolution so the ends look round. */
    private static void pill(GuiGraphicsExtractor g, float x, float y, float w, float h, int col) {
        final int Q = 4;
        int X = Math.round(x * Q), Y = Math.round(y * Q), Wd = Math.round(w * Q), H = Math.round(h * Q);
        float r = H / 2f;
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        for (int row = 0; row < H; row++) {
            float dy = r - (row + 0.5f);
            int inset = (int) Math.ceil(r - Math.sqrt(Math.max(0f, r * r - dy * dy)));
            g.fill(X + inset, Y + row, X + Wd - inset, Y + row + 1, col);
        }
        g.pose().popMatrix();
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
