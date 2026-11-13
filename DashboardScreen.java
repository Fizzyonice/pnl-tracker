package com.fiizzyonice.pnltracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Full-screen dashboard: Overview, Win/Loss, Rakeback and Sessions.
 * Everything is drawn on a fixed 900x560 canvas that is scaled to fit the window.
 */
public class DashboardScreen extends Screen {
    // ---------- colors ----------
    static final int BG       = 0xF20A0B14;
    static final int EDGE     = 0xFF262838;
    static final int CARD     = 0xF0101220;
    static final int CARD_HI  = 0xFF171A2C;
    static final int ROW_LINE = 0xFF1C1F30;
    static final int TEXT     = 0xFFF1F2F8;
    static final int MUTED    = 0xFF8B8FA8;
    static final int DIM      = 0xFF5D6178;
    static final int PURPLE   = 0xFF9B7BFF;
    static final int PURPLE_D = 0xFF6C4CF0;
    static final int GOLD     = 0xFFD9A93B;
    static final int BLUE     = 0xFF5B7BE0;
    static final int GREEN    = 0xFF3FE08A;
    static final int RED      = 0xFFFF5A72;
    static final int AMBER    = 0xFFF0B03C;

    static final int VW = 900, VH = 560;

    // ---------- state (kept while the game is open) ----------
    private static int tab = 0;              // 0 overview, 1 win/loss, 2 rakeback, 3 sessions, 4 profit panel
    private static int range = 1;            // 0 24h, 1 7d, 2 30d, 3 all
    private static int feedFilter = 0;       // 0 all, 1 in, 2 out
    private static String search = "";
    private static String selectedPlayer = null;   // Win/Loss: open player page
    private static int selectedSession = -1; // index into sessions, -1 = current/latest

    private boolean searchFocused = false;
    private boolean aucFocused = false;
    private static String aucSearch = "";
    private float scale = 1f;
    private int ox = 0, oy = 0;
    private final Map<String, Float> scroll = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<int[]> scrollAreas = new ArrayList<>();
    private final List<String> scrollKeys = new ArrayList<>();
    private float mouseVX, mouseVY;

    private record Hit(float x, float y, float w, float h, Runnable action) {}

    public DashboardScreen() { super(Component.literal("Dashboard")); }

    @Override public boolean isPauseScreen() { return false; }

    // =====================================================================
    // Rendering
    // =====================================================================
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        PnlHud.initFonts();
        PnlData d = PnlTracker.DATA;
        if (d == null) return;

        scale = Math.min((this.width - 16) / (float) VW, (this.height - 16) / (float) VH);
        ox = Math.round((this.width - VW * scale) / 2f);
        oy = Math.round((this.height - VH * scale) / 2f);
        mouseVX = (mouseX - ox) / scale;
        mouseVY = (mouseY - oy) / scale;
        long nowNs = System.nanoTime();
        frameDt = lastFrameNs == 0 ? 0.016f : Math.min(0.1f, (nowNs - lastFrameNs) / 1e9f);
        lastFrameNs = nowNs;
        hits.clear();
        scrollAreas.clear();
        scrollKeys.clear();

        g.pose().pushMatrix();
        g.pose().translate(ox, oy);
        g.pose().scale(scale, scale);

        rounded(g, 0, 0, VW, VH, 8, BG);
        border(g, 0, 0, VW, VH, EDGE);
        header(g);

        switch (tab) {
            case 0 -> overview(g, d);
            case 1 -> winLoss(g, d);
            case 2 -> rakeback(g, d);
            case 3 -> sessions(g, d);
            case 4 -> auctions(g, d);
            default -> panels(g, d);
        }

        txt(g, "Display only. Data is read from your chat; nothing is sent to the server.", 20, VH - 13, DIM, 0.62f, false);
        g.pose().popMatrix();
        if (entityArea != null) drawEntity(g, entityArea);
        entityArea = null;

    }

    // ---------- header ----------
    private void header(GuiGraphicsExtractor g) {
        fill(g, 0, 34, VW, 35, EDGE);
        PnlHud.circle(g, 22, 17, 6.5f, PURPLE_D);
        PnlHud.circle(g, 22, 17, 3.2f, BG);
        spaced(g, "DASHBOARD", 34, 13, TEXT, 0.8f);
        txt(g, "v1.0", 112, 13.5f, DIM, 0.7f, false);
        pillBox(g, 140, 9, 66, 16, 0x00000000, EDGE);
        boolean liveData = PnlTracker.DATA != null && PnlTracker.DATA.panelVisible;
        PnlHud.circle(g, 150, 17, 2.4f, liveData ? GREEN : AMBER);
        spaced(g, liveData ? "DATA LIVE" : "PAUSED", 157, 13.5f, MUTED, 0.6f);

        String[] tabs = {"OVERVIEW", "WIN / LOSS", "RAKEBACK", "SESSIONS", "AUCTIONS", "PROFIT PANEL"};
        float tx = 226;
        for (int i = 0; i < tabs.length; i++) {
            float w = spacedW(tabs[i], 0.66f) + 18;
            boolean on = tab == i;
            boolean hover = in(tx, 9, w, 17);
            if (on) roundedF(g, tx, 9, w, 17, 3, PURPLE_D);
            else if (hover) roundedF(g, tx, 9, w, 17, 3, CARD_HI);
            spaced(g, tabs[i], tx + 9, 14, on ? TEXT : MUTED, 0.66f);
            final int idx = i;
            hits.add(new Hit(tx, 9, w, 17, () -> { tab = idx; searchFocused = false; aucFocused = false; field = null; selectedPlayer = null; }));
            tx += w + 4;
        }

        String[] ranges = {"24H", "7D", "30D", "ALL"};
        float rx = VW - 20 - 4 * 34;
        pillBox(g, rx - 2, 8, 4 * 34 + 4, 19, 0x00000000, EDGE);
        for (int i = 0; i < 4; i++) {
            boolean on = range == i;
            if (on) roundedF(g, rx + i * 34, 10, 32, 15, 2, PURPLE_D);
            float w = spacedW(ranges[i], 0.66f);
            spaced(g, ranges[i], rx + i * 34 + (32 - w) / 2f, 14, on ? TEXT : MUTED, 0.66f);
            final int idx = i;
            hits.add(new Hit(rx + i * 34, 10, 32, 15, () -> range = idx));
        }
    }

    // =====================================================================
    // OVERVIEW
    // =====================================================================
    private void overview(GuiGraphicsExtractor g, PnlData d) {
        String name = d.displayName == null || d.displayName.isBlank() ? Minecraft.getInstance().getUser().getName() : d.displayName;
        long now = System.currentTimeMillis();
        long since = rangeStart(now);
        List<PnlData.Tx> all = d.window();

        // welcome strip
        fill(g, 20, 44, VW - 20, 118, 0x40141024);
        drawBody(g, Minecraft.getInstance().getUser().getName(), 24, 40, 92, 78);
        float nx = 124 + w("Welcome, ", true, 2.0f);
        glowBehind(g, nx - 26, 46, w(name, true, 2.0f) + 52, 50, PURPLE);
        txt(g, "Welcome, ", 124, 64, TEXT, 2.0f, true);
        glowText(g, name, nx, 64, 0xFFB49BFF, 2.0f, 0x18);
        txt(g, "Have a great time on DonutSMP!", 125, 90, MUTED, 0.85f, false);
        PnlData.Session cur = d.activeSession();
        if (cur != null) {
            String live = "SESSION LIVE  " + dur(now - cur.start);
            float lw = spacedW(live, 0.62f) + 20;
            pillBox(g, VW - 30 - lw, 70, lw, 16, 0x332BD47A, 0xFF2BD47A);
            spaced(g, live, VW - 20 - lw, 75, GREEN, 0.62f);
        }

        // cards
        float cw = (VW - 40 - 20) / 3f, cy = 126, ch = 92;
        // balance
        double bal = d.balance;
        double balThen = balanceAt(d, now - 7 * PnlData.DAY);
        card(g, 20, cy, cw, ch, GOLD, "BALANCE", bal >= 0 ? fmtShort(bal) : "/bal", TEXT,
                bal >= 0 && balThen >= 0 ? arrow(bal - balThen) + fmtShort(Math.abs(bal - balThen)) + " this week" : "type /bal to load",
                bal - balThen >= 0 ? MUTED : MUTED, balanceSeries(d, now - 7 * PnlData.DAY, now, 40), GOLD);
        // playtime
        long totalPlay = d.playtimeSince(0), weekPlay = d.playtimeSince(now - 7 * PnlData.DAY);
        card(g, 20 + cw + 10, cy, cw, ch, BLUE, "PLAYTIME", hours(totalPlay), TEXT,
                "▲ +" + hours(weekPlay) + " this week", MUTED, playSeries(d, now), BLUE);
        // profit for range
        double rp = 0;
        for (PnlData.Tx tx : all) if (tx.t >= since) rp += tx.a;
        int pc = rp >= 0 ? GREEN : RED;
        card(g, 20 + 2 * (cw + 10), cy, cw, ch, pc, rangeName() + " PROFIT", PnlData.signed(rp).replace(".00", ""), pc,
                range == 3 ? "all time" : "since " + date(since), MUTED, profitSeries(all, since, now, 40), pc);

        // payment feed
        float fy = 228, fh = VH - 20 - fy;
        rounded(g, 20, fy, 300, fh, 5, CARD);
        border(g, 20, fy, 300, fh, EDGE);
        spaced(g, "PAYMENT FEED", 34, fy + 16, TEXT, 0.72f);
        String[] f = {"ALL", "IN", "OUT"};
        float bx = 320 - 14 - 3 * 30;
        pillBox(g, bx - 2, fy + 10, 3 * 30 + 4, 19, 0x00000000, EDGE);
        for (int i = 0; i < 3; i++) {
            boolean on = feedFilter == i;
            if (on) roundedF(g, bx + i * 30, fy + 12, 28, 15, 2, PURPLE_D);
            float w = spacedW(f[i], 0.62f);
            spaced(g, f[i], bx + i * 30 + (28 - w) / 2f, fy + 16.5f, on ? TEXT : MUTED, 0.62f);
            final int idx = i;
            hits.add(new Hit(bx + i * 30, fy + 12, 28, 15, () -> { feedFilter = idx; resetScroll("feed"); }));
        }
        List<PnlData.Tx> feed = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            PnlData.Tx tx = all.get(i);
            if (tx.t < since) continue;
            if (feedFilter == 1 && tx.a < 0) continue;
            if (feedFilter == 2 && tx.a >= 0) continue;
            feed.add(tx);
        }
        float ly = fy + 38, rowH = 30;
        int visible = (int) ((fy + fh - 8 - ly) / rowH);
        int first = scrollIndex("feed", feed.size(), visible, 20, ly, 300, visible * rowH);
        if (feed.isEmpty()) txt(g, "No payments in this range yet", 34, ly + 10, DIM, 0.8f, false);
        beginRows(g, rowH);
        for (int i = first; i < Math.min(feed.size(), first + visible + 1); i++) {
            PnlData.Tx tx = feed.get(i);
            float ry = ly + (i - first) * rowH;
            fill(g, 30, ry + rowH - 1, 310, ry + rowH, ROW_LINE);
            txt(g, time(tx.t), 30, ry + 11, DIM, 0.62f, false);
            avatar(g, tx.p, 82, ry + 15, 9);
            txt(g, trim(tx.p, true, 0.8f, 120), 96, ry + 6, TEXT, 0.8f, true);
            txt(g, tx.rb ? "rakeback paid" : tx.a >= 0 ? "paid you" : "you paid", 96, ry + 17, DIM, 0.62f, false);
            String amt = PnlData.signed(tx.a);
            txt(g, amt, 306 - w(amt, true, 0.82f), ry + 10, tx.a >= 0 ? GREEN : RED, 0.82f, true);
        }
        endRows(g);
        scrollBar(g, 314, ly, visible * rowH, "feed", feed.size(), visible);

        // big chart
        float gx = 330, gw = VW - 20 - gx;
        rounded(g, gx, fy, gw, fh, 5, CARD);
        border(g, gx, fy, gw, fh, EDGE);
        spaced(g, "PROFIT / LOSS", gx + 14, fy + 16, TEXT, 0.72f);
        String days = range == 3 ? "all of your data" : rangeLong() + " of data";
        txt(g, days, gx + 14 + spacedW("PROFIT / LOSS", 0.72f) + 10, fy + 16.5f, DIM, 0.62f, false);
        String big = PnlData.signed(rp).replace(".00", "");
        txt(g, big, gx + gw - 14 - w(big, true, 1.5f), fy + 11, pc, 1.5f, true);
        bigChart(g, all, since, now, gx + 14, fy + 40, gw - 28, fh - 56, pc);
    }

    private void card(GuiGraphicsExtractor g, float x, float y, float w, float h, int accent, String label, String value, int valueCol,
                      String sub, int subCol, double[] series, int lineCol) {
        rounded(g, x, y, w, h, 5, CARD);
        border(g, x, y, w, h, EDGE);
        fill(g, x + 1, y, x + w - 1, y + 1.5f, accent);
        glow(g, x + w * 0.35f, y + 2, w * 0.4f, h - 4, accent);
        spaced(g, label, x + 12, y + 12, MUTED, 0.6f);
        txt(g, value, x + 12, y + 25, valueCol, 1.55f, true);
        float sx = x + 12;
        if (sub.startsWith("\u25B2") || sub.startsWith("\u25BC")) {
            boolean up = sub.startsWith("\u25B2");
            int ac = up ? GREEN : RED;
            for (float i = 0; i < 4; i += 0.25f) {
                float half = (up ? i : 4 - i) * 0.6f;
                fill(g, sx + 2.4f - half, y + 46 + i, sx + 2.4f + half, y + 46.25f + i, ac);
            }
            sx += 7;
            sub = sub.substring(2);
        }
        txt(g, sub, sx, y + 45, subCol, 0.64f, false);
        sparkline(g, series, x + 12, y + 57, w - 24, h - 66, lineCol);
    }

    // =====================================================================
    // WIN / LOSS
    // =====================================================================
    private void winLoss(GuiGraphicsExtractor g, PnlData d) {
        if (selectedPlayer != null) { playerPage(g, d, selectedPlayer); return; }
        List<PnlData.Tx> all = d.window();
        Map<String, double[]> stats = playerStats(all, 0, Long.MAX_VALUE); // [net (player side), wagered, bets]
        List<Map.Entry<String, double[]>> list = new ArrayList<>(stats.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        int upCount = 0, downCount = 0;
        double upSum = 0, downSum = 0;
        for (Map.Entry<String, double[]> e : list) {
            if (e.getValue()[0] > 0) { upCount++; upSum += e.getValue()[0]; }
            else if (e.getValue()[0] < 0) { downCount++; downSum += e.getValue()[0]; }
        }

        txt(g, "Win / Loss", 20, 46, TEXT, 1.4f, true);
        txt(g, "Everyone who has gambled with you. Top = won the most off you, bottom = lost the most. Click a player to see their chart.", 20, 66, MUTED, 0.68f, false);

        // stat chips
        chip(g, 20, 82, "PLAYERS", String.valueOf(list.size()), TEXT);
        chip(g, 140, 82, "UP ON YOU", upCount + "  ·  " + PnlData.signed(upSum).replace(".00", ""), GREEN);
        chip(g, 300, 82, "DOWN ON YOU", downCount + "  ·  " + PnlData.signed(downSum).replace(".00", ""), RED);

        // search box
        float sx = VW - 20 - 230, sy = 84, sw = 230, sh = 24;
        rounded(g, sx, sy, sw, sh, 4, searchFocused ? CARD_HI : CARD);
        border(g, sx, sy, sw, sh, searchFocused ? PURPLE_D : EDGE);
        searchIcon(g, sx + 12, sy + 12);
        boolean caret = searchFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        if (search.isEmpty() && !searchFocused) txt(g, "Search players...", sx + 24, sy + 8, DIM, 0.75f, false);
        else txt(g, search + (caret ? "|" : ""), sx + 24, sy + 8, TEXT, 0.75f, false);
        hits.add(new Hit(sx, sy, sw, sh, () -> searchFocused = true));

        // filter
        List<Map.Entry<String, double[]>> shown = new ArrayList<>();
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < list.size(); i++) rank.put(list.get(i).getKey(), i + 1);
        String q = search.toLowerCase(Locale.ROOT).trim();
        for (Map.Entry<String, double[]> e : list) if (q.isEmpty() || e.getKey().toLowerCase(Locale.ROOT).contains(q)) shown.add(e);

        // table
        float ty = 120, th = VH - 20 - ty;
        rounded(g, 20, ty, VW - 40, th, 5, CARD);
        border(g, 20, ty, VW - 40, th, EDGE);
        spaced(g, "#", 36, ty + 12, DIM, 0.6f);
        spaced(g, "PLAYER", 74, ty + 12, DIM, 0.6f);
        spaced(g, "BETS", 420, ty + 12, DIM, 0.6f);
        spaced(g, "WAGERED", 520, ty + 12, DIM, 0.6f);
        String netH = "NET (THEIR SIDE)";
        spaced(g, netH, VW - 40 - spacedW(netH, 0.6f), ty + 12, DIM, 0.6f);
        fill(g, 30, ty + 26, VW - 30, ty + 27, ROW_LINE);

        float ly = ty + 30, rowH = 26;
        int visible = (int) ((ty + th - 8 - ly) / rowH);
        int first = scrollIndex("wl", shown.size(), visible, 20, ly, VW - 40, visible * rowH);
        if (shown.isEmpty()) txt(g, list.isEmpty() ? "No bets yet" : "No player matches \"" + search + "\"", 74, ly + 10, DIM, 0.8f, false);
        beginRows(g, rowH);
        for (int i = first; i < Math.min(shown.size(), first + visible + 1); i++) {
            Map.Entry<String, double[]> e = shown.get(i);
            float ry = ly + (i - first) * rowH;
            if (in(20, ry, VW - 40, rowH)) fill(g, 22, ry, VW - 22, ry + rowH, 0x10FFFFFF);
            double net = e.getValue()[0];
            int col = net > 0 ? GREEN : net < 0 ? RED : MUTED;
            txt(g, String.valueOf(rank.get(e.getKey())), 36, ry + 8, DIM, 0.75f, false);
            avatar(g, e.getKey(), 62, ry + 13, 8);
            txt(g, e.getKey(), 76, ry + 7.5f, TEXT, 0.82f, true);
            txt(g, String.valueOf((int) e.getValue()[2]), 420, ry + 8, MUTED, 0.75f, false);
            txt(g, PnlData.format(e.getValue()[1]), 520, ry + 8, MUTED, 0.75f, false);
            String amt = PnlData.signed(net);
            txt(g, amt, VW - 40 - w(amt, true, 0.88f), ry + 7, col, 0.88f, true);
            fill(g, 30, ry + rowH - 1, VW - 30, ry + rowH, ROW_LINE);
            final String who = e.getKey();
            hits.add(new Hit(20, ry, VW - 40, rowH, () -> { selectedPlayer = who; resetScroll("pp"); }));
        }
        endRows(g);
        scrollBar(g, VW - 27, ly, visible * rowH, "wl", shown.size(), visible);
    }

    // =====================================================================
    // PLAYER PAGE (Win/Loss -> click a name)
    // =====================================================================
    private void playerPage(GuiGraphicsExtractor g, PnlData d, String name) {
        List<PnlData.Tx> txs = new ArrayList<>();
        for (PnlData.Tx tx : d.window()) if (tx.p.equals(name) && !tx.rb) txs.add(tx);

        // back
        boolean hb = in(20, 42, 70, 18);
        roundedF(g, 20, 42, 70, 18, 4, hb ? CARD_HI : CARD);
        border(g, 20, 42, 70, 18, EDGE);
        for (int i = 0; i < 4; i++) { g.fill(31 + i, 51 - i, 32 + i, 52 + i, TEXT); }   // little "<" arrow
        txt(g, "Back", 40, 46.5f, TEXT, 0.72f, true);
        hits.add(new Hit(20, 42, 70, 18, () -> selectedPlayer = null));

        // header
        double net = 0, wagered = 0, bigWin = 0, bigLoss = 0;
        int bets = 0;
        for (PnlData.Tx tx : txs) {
            net -= tx.a;
            if (tx.a > 0) { wagered += tx.a; bets++; bigLoss = Math.max(bigLoss, tx.a); }
            else bigWin = Math.max(bigWin, -tx.a);
        }
        avatar(g, name, 118, 52, 13);
        txt(g, name, 138, 41, TEXT, 1.35f, true);
        txt(g, net > 0 ? "is up on you" : net < 0 ? "is down on you" : "is even with you", 139, 57, MUTED, 0.68f, false);
        String ns = PnlData.signed(net).replace(".00", "");
        int nc = net > 0 ? GREEN : net < 0 ? RED : MUTED;
        txt(g, ns, VW - 20 - w(ns, true, 1.6f), 42, nc, 1.6f, true);
        spaced(g, "THEIR NET (ALL TIME)", VW - 20 - spacedW("THEIR NET (ALL TIME)", 0.56f), 62, DIM, 0.56f);

        float cx = chip(g, 20, 76, "BETS", String.valueOf(bets), TEXT);
        cx = chip(g, cx, 76, "WAGERED", PnlData.format(wagered), TEXT);
        cx = chip(g, cx, 76, "BIGGEST WIN", bigWin > 0 ? PnlData.format(bigWin) : "-", bigWin > 0 ? GREEN : MUTED);
        cx = chip(g, cx, 76, "BIGGEST LOSS", bigLoss > 0 ? PnlData.format(bigLoss) : "-", bigLoss > 0 ? RED : MUTED);
        if (!txs.isEmpty()) {
            cx = chip(g, cx, 76, "FIRST BET", dateLong(txs.get(0).t), MUTED);
            chip(g, cx, 76, "LAST SEEN", dateLong(txs.get(txs.size() - 1).t), MUTED);
        }

        // chart card
        float gx = 20, gy = 112, gw = 600, gh = VH - 20 - gy;
        rounded(g, gx, gy, gw, gh, 5, CARD);
        border(g, gx, gy, gw, gh, EDGE);
        spaced(g, "UP / DOWN ON YOU", gx + 14, gy + 14, TEXT, 0.7f);
        txt(g, "all time  \u00B7  hover the line", gx + 14 + spacedW("UP / DOWN ON YOU", 0.7f) + 10, gy + 14.5f, DIM, 0.6f, false);
        playerChart(g, txs, gx + 14, gy + 36, gw - 28, gh - 52);

        // payments with them
        float px = 630, pw = VW - 20 - px;
        rounded(g, px, gy, pw, gh, 5, CARD);
        border(g, px, gy, pw, gh, EDGE);
        spaced(g, "PAYMENTS", px + 14, gy + 14, TEXT, 0.7f);
        float ly = gy + 32, rowH = 26;
        int visible = (int) ((gy + gh - 8 - ly) / rowH);
        int first = scrollIndex("pp", txs.size(), visible, px, ly, pw, visible * rowH);
        if (txs.isEmpty()) txt(g, "No payments with " + name, px + 14, ly + 4, DIM, 0.72f, false);
        beginRows(g, rowH);
        for (int i = first; i < Math.min(txs.size(), first + visible + 1); i++) {
            PnlData.Tx tx = txs.get(txs.size() - 1 - i);
            float ry = ly + (i - first) * rowH;
            txt(g, tx.a >= 0 ? "they bet / paid you" : "you paid them", px + 14, ry + 3, TEXT, 0.68f, true);
            txt(g, dateLong(tx.t) + "  \u00B7  " + time(tx.t), px + 14, ry + 13, DIM, 0.58f, false);
            String a = PnlData.signed(-tx.a);
            txt(g, a, px + pw - 14 - w(a, true, 0.78f), ry + 7, tx.a > 0 ? RED : GREEN, 0.78f, true);
            fill(g, px + 10, ry + rowH - 1, px + pw - 10, ry + rowH, ROW_LINE);
        }
        endRows(g);
        scrollBar(g, px + pw - 6, ly, visible * rowH, "pp", txs.size(), visible);
    }

    /** Their running net (their side) after every payment: green above zero, red below, with a hover readout. */
    private void playerChart(GuiGraphicsExtractor g, List<PnlData.Tx> txs, float x, float y, float w, float h) {
        int n = txs.size();
        double[] v = new double[n + 1];
        for (int i = 0; i < n; i++) v[i + 1] = v[i] - txs.get(i).a;
        double min = 0, max = 0;
        for (double a : v) { min = Math.min(min, a); max = Math.max(max, a); }
        if (max - min < 1) { max += 1; min -= 1; }
        double pad = (max - min) * 0.1;
        max += pad; min -= pad;
        for (int i = 0; i <= 4; i++) {
            float gy = y + h * i / 4f;
            for (float gx = x; gx < x + w; gx += 6) fill(g, gx, gy, gx + 2, gy + 0.6f, 0x12FFFFFF);
        }
        if (n == 0) {
            String m = "No payments yet";
            txt(g, m, x + (w - w(m, false, 0.8f)) / 2f, y + h / 2f - 4, DIM, 0.8f, false);
            return;
        }
        final int Q = 4;
        int X0 = Math.round(x * Q), W = Math.round(w * Q), Y0 = Math.round(y * Q), H = Math.round(h * Q);
        int zeroY = Y0 + (int) Math.round(max / (max - min) * H);
        int segs = v.length - 1;
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        for (int gx = X0; gx < X0 + W; gx += 16) g.fill(gx, zeroY, gx + 8, zeroY + 2, 0x55FFFFFF);
        // line in half-pixel steps, the soft fill under it in whole-pixel steps (the line covers the edge)
        final int STEP = 2;
        int prev = -1, k2 = 0;
        for (int px = 0; px <= W; px += STEP, k2++) {
            double pos = Math.min(W, px) / (double) W * segs;
            int i = Math.min(segs - 1, (int) pos);
            double f = pos - i;
            double val = v[i] + (v[i + 1] - v[i]) * f;
            int yy = Y0 + (int) Math.round((max - val) / (max - min) * H);
            boolean up = val >= 0;
            int sw = Math.min(STEP, W + 1 - px);
            if (k2 % 2 == 0) g.fill(X0 + px, Math.min(yy, zeroY), X0 + Math.min(px + 2 * STEP, W + 1), Math.max(yy, zeroY), up ? 0x223FE08A : 0x22FF5A72);
            int top = prev < 0 ? yy : Math.min(prev, yy), bot = prev < 0 ? yy : Math.max(prev, yy);
            g.fill(X0 + px, top - 3, X0 + px + sw, bot + 4, up ? GREEN : RED);
            prev = yy;
        }
        g.pose().popMatrix();
        txt(g, PnlData.signed(max - pad).replace(".00", ""), x + 2, y + 2, DIM, 0.58f, false);
        txt(g, PnlData.signed(min + pad).replace(".00", ""), x + 2, y + h - 8, DIM, 0.58f, false);
        double last = v[n];
        float ly = (float) (y + (max - last) / (max - min) * h);
        PnlHud.circle(g, x + w, ly, 2.8f, last >= 0 ? GREEN : RED);
        PnlHud.circle(g, x + w, ly, 1.3f, TEXT);

        // hover: snap to the nearest payment
        if (mouseVX >= x && mouseVX <= x + w && mouseVY >= y - 4 && mouseVY <= y + h + 4) {
            int k = Math.max(0, Math.min(segs, Math.round((mouseVX - x) / w * segs)));
            float px = x + w * k / (float) segs;
            float py = (float) (y + (max - v[k]) / (max - min) * h);
            fill(g, px - 0.25f, y, px + 0.25f, y + h, 0x55FFFFFF);
            int c = v[k] >= 0 ? GREEN : RED;
            PnlHud.circle(g, px, py, 3.4f, c);
            PnlHud.circle(g, px, py, 1.6f, TEXT);
            String l1 = PnlData.signed(v[k]).replace(".00", "") + (v[k] > 0 ? "  up on you" : v[k] < 0 ? "  down on you" : "  even");
            String l2, l3;
            if (k == 0) { l2 = "Before their first payment"; l3 = ""; }
            else {
                PnlData.Tx tx = txs.get(k - 1);
                l2 = dateLong(tx.t) + "  \u00B7  " + time(tx.t);
                l3 = tx.a >= 0 ? "They paid you " + PnlData.format(tx.a) : "You paid them " + PnlData.format(-tx.a);
            }
            float tw = Math.max(w(l1, true, 0.8f), Math.max(w(l2, false, 0.62f), w(l3, false, 0.62f))) + 16;
            float th = l3.isEmpty() ? 30 : 40;
            float tx0 = px + 10 + tw > x + w ? px - 10 - tw : px + 10;
            float ty0 = Math.max(y, Math.min(y + h - th, py - th / 2f));
            rounded(g, tx0, ty0, tw, th, 4, 0xF2070912);
            border(g, tx0, ty0, tw, th, c);
            txt(g, l1, tx0 + 8, ty0 + 6, c, 0.8f, true);
            txt(g, l2, tx0 + 8, ty0 + 18, MUTED, 0.62f, false);
            if (!l3.isEmpty()) txt(g, l3, tx0 + 8, ty0 + 28, TEXT, 0.62f, false);
        }
    }

    private float chip(GuiGraphicsExtractor g, float x, float y, String label, String value, int col) {
        float w = Math.max(spacedW(label, 0.56f), w(value, true, 0.8f)) + 20;
        rounded(g, x, y, w, 28, 4, CARD);
        border(g, x, y, w, 28, EDGE);
        spaced(g, label, x + 10, y + 5, DIM, 0.56f);
        txt(g, value, x + 10, y + 14, col, 0.8f, true);
        return x + w + 8;
    }

    // =====================================================================
    // RAKEBACK
    // =====================================================================
    private void rakeback(GuiGraphicsExtractor g, PnlData d) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(d.rakeback.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int ready = 0;
        double owed = 0;
        for (Map.Entry<String, Double> e : list) if (e.getValue() >= PnlData.RB_LIMIT) { ready++; owed += PnlData.owed(e.getValue()); }

        txt(g, "Rakeback", 20, 46, TEXT, 1.4f, true);
        txt(g, Math.round(PnlData.RB_PCT * 100) + "% back once a player has lost " + PnlData.shortFmt(PnlData.RB_LIMIT) + ". Everyone, closest to " + PnlData.shortFmt(PnlData.RB_LIMIT) + " first.",
                20, 66, MUTED, 0.68f, false);
        float cx = chip(g, 20, 82, "PLAYERS", String.valueOf(list.size()), TEXT);
        cx = chip(g, cx, 82, "READY", String.valueOf(ready), GREEN);
        chip(g, cx, 82, "YOU OWE", PnlData.shortFmt(owed), ready > 0 ? GREEN : MUTED);

        float ty = 120, th = VH - 20 - ty;
        rounded(g, 20, ty, VW - 40, th, 5, CARD);
        border(g, 20, ty, VW - 40, th, EDGE);
        float ly = ty + 10, rowH = 34;
        int visible = (int) ((ty + th - 8 - ly) / rowH);
        int first = scrollIndex("rb", list.size(), visible, 20, ly, VW - 40, visible * rowH);
        if (list.isEmpty()) txt(g, "No losses yet", 36, ly + 10, DIM, 0.8f, false);
        beginRows(g, rowH);
        for (int i = first; i < Math.min(list.size(), first + visible + 1); i++) {
            Map.Entry<String, Double> e = list.get(i);
            float ry = ly + (i - first) * rowH;
            float rx = 30, rw = VW - 60, rh = rowH - 5;
            rounded(g, rx, ry, rw, rh, 4, CARD_HI);
            double v = e.getValue();
            boolean isReady = v >= PnlData.RB_LIMIT;
            txt(g, String.valueOf(i + 1), rx + 10, ry + 7, DIM, 0.72f, false);
            txt(g, e.getKey(), rx + 34, ry + 6, TEXT, 0.85f, true);
            String amt = isReady ? "OWE " + PnlData.shortFmt(PnlData.owed(v)) + "   ·   " + PnlData.shortFmt(v) + " lost"
                                 : PnlData.shortFmt(v) + " / " + PnlData.shortFmt(PnlData.RB_LIMIT);
            txt(g, amt, rx + rw - 10 - w(amt, true, 0.82f), ry + 6, isReady ? GREEN : TEXT, 0.82f, true);
            float bx = rx + 34, bw = rw - 44, by = ry + 20;
            float frac = (float) Math.min(1.0, v / PnlData.RB_LIMIT);
            roundedF(g, bx, by, bw, 3, 1.5f, 0x22FFFFFF);
            gradientBar(g, bx, by, bw * frac, 3, bw);
        }
        endRows(g);
        scrollBar(g, VW - 27, ly, visible * rowH, "rb", list.size(), visible);
    }

    // =====================================================================
    // SESSIONS
    // =====================================================================
    private void sessions(GuiGraphicsExtractor g, PnlData d) {
        long now = System.currentTimeMillis();
        List<PnlData.Session> ss = d.sessions;
        PnlData.Session active = d.activeSession();
        int sel = selectedSession;
        if (sel < 0 || sel >= ss.size()) sel = ss.isEmpty() ? -1 : ss.size() - 1;
        PnlData.Session s = sel >= 0 ? ss.get(sel) : null;
        long end = s == null ? now : (s.end == 0 ? now : s.end);

        txt(g, "Sessions", 20, 46, TEXT, 1.4f, true);
        txt(g, "Start a session when you host. It tracks your profit and every bet until you stop it.", 20, 66, MUTED, 0.68f, false);

        // left: session card
        float lx = 20, lw = 430, cy = 84;
        rounded(g, lx, cy, lw, 132, 5, CARD);
        border(g, lx, cy, lw, 132, EDGE);
        if (s == null) {
            txt(g, "No sessions yet", lx + 16, cy + 16, MUTED, 0.9f, true);
            txt(g, "Press Start session when you begin hosting.", lx + 16, cy + 32, DIM, 0.7f, false);
        } else {
            boolean live = s.end == 0;
            spaced(g, live ? "CURRENT SESSION" : "SESSION", lx + 16, cy + 14, live ? GREEN : MUTED, 0.62f);
            String when = dateLong(s.start) + "  ·  " + time(s.start) + (live ? "" : " – " + time(s.end));
            txt(g, when, lx + 16, cy + 26, MUTED, 0.68f, false);
            txt(g, dur(end - s.start), lx + 16, cy + 42, TEXT, 2.0f, true);
            double sp = 0;
            for (PnlData.Tx tx : d.window()) if (tx.t >= s.start && tx.t <= end) sp += tx.a;
            String sps = PnlData.signed(sp).replace(".00", "");
            spaced(g, "PROFIT", lx + lw - 16 - spacedW("PROFIT", 0.62f), cy + 14, MUTED, 0.62f);
            txt(g, sps, lx + lw - 16 - w(sps, true, 2.0f), cy + 42, sp >= 0 ? GREEN : RED, 2.0f, true);
        }
        // start / stop button
        boolean running = active != null;
        String btn = running ? "STOP SESSION" : "START SESSION";
        float bw = spacedW(btn, 0.72f) + 34, bx = lx + 16, by = cy + 92;
        boolean hover = in(bx, by, bw, 26);
        int bc = running ? (hover ? 0xFFFF6E83 : RED) : (hover ? 0xFF8F6CFF : PURPLE_D);
        roundedF(g, bx, by, bw, 26, 4, bc);
        if (running) roundedF(g, bx + 12, by + 9, 8, 8, 1, TEXT);
        else triangle(g, bx + 13, by + 8.5f, 9, TEXT);
        spaced(g, btn, bx + 26, by + 9.5f, TEXT, 0.72f);
        hits.add(new Hit(bx, by, bw, 26, () -> {
            if (PnlTracker.DATA.activeSession() != null) PnlTracker.DATA.stopSession();
            else { PnlTracker.DATA.startSession(); selectedSession = -1; }
        }));
        if (s != null && sel != ss.size() - 1) {
            String back = "Back to latest";
            float bkx = bx + bw + 10;
            txt(g, back, bkx, by + 9, PURPLE, 0.7f, true);
            hits.add(new Hit(bkx, by + 4, w(back, true, 0.7f), 18, () -> selectedSession = -1));
        }

        // left bottom: players in this session
        float py = 226, ph = VH - 20 - py;
        rounded(g, lx, py, lw, ph, 5, CARD);
        border(g, lx, py, lw, ph, EDGE);
        spaced(g, "PLAYERS THIS SESSION", lx + 16, py + 14, TEXT, 0.66f);
        String side = "THEIR SIDE";
        spaced(g, side, lx + lw - 16 - spacedW(side, 0.6f), py + 14.5f, DIM, 0.6f);
        List<Map.Entry<String, double[]>> players = new ArrayList<>();
        if (s != null) players.addAll(playerStats(d.window(), s.start, end).entrySet());
        players.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        float ly = py + 32, rowH = 24;
        int visible = (int) ((py + ph - 8 - ly) / rowH);
        int first = scrollIndex("sp", players.size(), visible, lx, ly, lw, visible * rowH);
        if (players.isEmpty()) txt(g, "No bets in this session yet", lx + 16, ly + 6, DIM, 0.78f, false);
        beginRows(g, rowH);
        for (int i = first; i < Math.min(players.size(), first + visible + 1); i++) {
            Map.Entry<String, double[]> e = players.get(i);
            float ry = ly + (i - first) * rowH;
            double net = e.getValue()[0];
            avatar(g, e.getKey(), lx + 24, ry + 11, 8);
            txt(g, e.getKey(), lx + 38, ry + 4, TEXT, 0.8f, true);
            int nb = (int) e.getValue()[2];
            txt(g, nb == 0 ? "paid out" : nb + (nb == 1 ? " bet" : " bets"), lx + 38, ry + 14, DIM, 0.6f, false);
            String amt = PnlData.signed(net);
            txt(g, amt, lx + lw - 16 - w(amt, true, 0.84f), ry + 7, net > 0 ? GREEN : net < 0 ? RED : MUTED, 0.84f, true);
            fill(g, lx + 12, ry + rowH - 1, lx + lw - 12, ry + rowH, ROW_LINE);
        }
        endRows(g);
        scrollBar(g, lx + lw - 6, ly, visible * rowH, "sp", players.size(), visible);

        // right: history
        float rx = 460, rw = VW - 20 - rx, ry0 = 84, rh = VH - 20 - ry0;
        rounded(g, rx, ry0, rw, rh, 5, CARD);
        border(g, rx, ry0, rw, rh, EDGE);
        spaced(g, "SESSION HISTORY", rx + 16, ry0 + 14, TEXT, 0.66f);
        txt(g, "click one to see its players", rx + 16 + spacedW("SESSION HISTORY", 0.66f) + 8, ry0 + 14.5f, DIM, 0.6f, false);
        float hy = ry0 + 32, hRow = 34;
        int hv = (int) ((ry0 + rh - 8 - hy) / hRow);
        int hf = scrollIndex("sh", ss.size(), hv, rx, hy, rw, hv * hRow);
        if (ss.isEmpty()) txt(g, "Your sessions will show up here", rx + 16, hy + 6, DIM, 0.78f, false);
        beginRows(g, hRow);
        for (int k = hf; k < Math.min(ss.size(), hf + hv + 1); k++) {
            int idx = ss.size() - 1 - k; // newest first
            PnlData.Session x = ss.get(idx);
            long xe = x.end == 0 ? now : x.end;
            double p = 0;
            for (PnlData.Tx tx : d.window()) if (tx.t >= x.start && tx.t <= xe) p += tx.a;
            float yy = hy + (k - hf) * hRow;
            boolean isSel = idx == sel;
            rounded(g, rx + 10, yy, rw - 20, hRow - 5, 4, isSel ? CARD_HI : 0x00000000);
            if (isSel) fill(g, rx + 10, yy + 3, rx + 12, yy + hRow - 8, PURPLE);
            txt(g, dateLong(x.start), rx + 20, yy + 5, TEXT, 0.78f, true);
            String t2 = time(x.start) + (x.end == 0 ? "  ·  live" : " – " + time(x.end)) + "  ·  " + dur(xe - x.start);
            txt(g, t2, rx + 20, yy + 16, x.end == 0 ? GREEN : DIM, 0.62f, false);
            String ps = PnlData.signed(p).replace(".00", "");
            txt(g, ps, rx + rw - 20 - w(ps, true, 0.9f), yy + 8, p >= 0 ? GREEN : RED, 0.9f, true);
            final int pick = idx;
            hits.add(new Hit(rx + 10, yy, rw - 20, hRow - 5, () -> { selectedSession = pick; resetScroll("sp"); }));
        }
        endRows(g);
        scrollBar(g, rx + rw - 6, hy, hv * hRow, "sh", ss.size(), hv);
    }

    // =====================================================================
    // AUCTIONS
    // =====================================================================
    static final int[] AUC_COLORS = {0xFFF0B03C, 0xFF9B7BFF, 0xFF3FE08A, 0xFFFF5A72, 0xFF5B9BFF, 0xFFF29BCB, 0xFF3FD8E0, 0xFFF1F2F8};

    private void auctions(GuiGraphicsExtractor g, PnlData d) {
        txt(g, "Auctions", 20, 46, TEXT, 1.4f, true);
        txt(g, "Pick an item, set the timer and start. Whoever pays you the most before it hits 0 wins.", 20, 66, MUTED, 0.68f, false);
        boolean live = Auction.running();
        boolean recent = Auction.endedWithin(20 * 60_000L);
        int col = d.aucColor;

        // ----- timer card -----
        float lx = 20, lw = 410, cy0 = 84, ch = 222;
        rounded(g, lx, cy0, lw, ch, 6, CARD);
        border(g, lx, cy0, lw, ch, EDGE);
        String label = "AUCTIONS";
        spaced(g, label, lx + (lw - spacedW(label, 0.9f)) / 2f, cy0 + 10, col, 0.9f);
        float ccx = lx + lw / 2f, ccy = cy0 + 104;
        long total = Math.max(1, Auction.endAt - Auction.startAt);
        float prog = live ? Auction.remainingMs() / (float) total : 1f;
        PnlHud.circle(g, ccx, ccy, 64, 0xFF0C0E19);
        PnlHud.ring(g, ccx, ccy, 76, 66, prog, live ? col : (recent ? (col & 0xFFFFFF) | 0x66000000 : col), 0x22FFFFFF);
        String showId = live || recent ? Auction.itemId : d.aucItem;
        Items.Entry it = Items.get(showId);
        int showQty = live || recent ? Auction.qty : d.aucQty;
        if (it != null) {
            g.pose().pushMatrix();
            g.pose().translate(ccx - 24, ccy - 40);
            g.pose().scale(3.0f, 3.0f);
            Items.draw(g, it.stack, 0, 0);
            g.pose().popMatrix();
            if (showQty > 1) {
                String q = "x" + showQty;
                float qw = w(q, true, 0.75f) + 10;
                roundedF(g, ccx + 14, ccy - 2, qw, 13, 6.5f, (col & 0xFFFFFF) | 0xEE000000);
                txt(g, q, ccx + 19, ccy + 1, 0xFF0A0B14, 0.75f, true);
            }
        }
        String clock = live ? Auction.clock(Auction.remainingMs()) : recent ? "SOLD" : Auction.clock(d.aucSeconds * 1000L);
        txt(g, clock, ccx - w(clock, true, 1.4f) / 2f, ccy + 16, TEXT, 1.4f, true);
        String nm = it == null ? "No item picked" : (live || recent ? Auction.label(Auction.itemId, Auction.name, Auction.qty) : Auction.label(d.aucItem, d.aucName, d.aucQty));
        nm = trim(nm, false, 0.66f, 110);
        txt(g, nm, ccx - w(nm, false, 0.66f) / 2f, ccy + 34, MUTED, 0.66f, false);
        Map.Entry<String, Double> lead = Auction.leader();
        String status;
        int sc;
        if (live) { status = lead == null ? (d.aucMode ? "Waiting for bids..." : "Auction switch is off: payments aren't counted as bids") : lead.getKey() + " is winning  \u00B7  " + PnlData.format(lead.getValue()); sc = lead == null ? MUTED : GREEN; }
        else if (recent) { status = lead == null ? "Ended with no bids" : lead.getKey() + " won the auction!  \u00B7  " + PnlData.format(lead.getValue()); sc = lead == null ? MUTED : GREEN; }
        else { status = "Ready when you are"; sc = MUTED; }
        txt(g, status, ccx - w(status, true, 0.8f) / 2f, cy0 + ch - 18, sc, 0.8f, true);

        // ----- start / end -----
        float y = cy0 + ch + 8;
        String btn = live ? "END NOW" : "START AUCTION";
        float bw = spacedW(btn, 0.72f) + 34;
        boolean hover = in(lx, y, bw, 24);
        int bcol = live ? (hover ? 0xFFFF6E83 : RED) : (hover ? 0xFF8F6CFF : PURPLE_D);
        roundedF(g, lx, y, bw, 24, 4, bcol);
        if (live) roundedF(g, lx + 12, y + 8, 8, 8, 1, TEXT); else triangle(g, lx + 13, y + 7.5f, 9, TEXT);
        spaced(g, btn, lx + 26, y + 8.5f, TEXT, 0.72f);
        hits.add(new Hit(lx, y, bw, 24, () -> {
            PnlData dd = PnlTracker.DATA;
            if (Auction.running()) Auction.stop();
            else if (Items.get(dd.aucItem) != null) { field = null; Auction.start(dd.aucItem, dd.aucName, dd.aucQty, dd.aucSeconds); }
        }));
        if (live || recent) {
            String cl = live ? "Cancel (no winner)" : "Clear";
            float cx = lx + bw + 12;
            txt(g, cl, cx, y + 8, MUTED, 0.7f, true);
            hits.add(new Hit(cx, y + 2, w(cl, true, 0.7f), 20, Auction::cancel));
        }
        String rule = "Biggest single payment wins (bids don't stack)";
        txt(g, rule, lx + lw - w(rule, false, 0.56f), y + 9, DIM, 0.56f, false);

        // ----- name / quantity / seconds -----
        y += 32;
        if (!"name".equals(field)) nameBuf = d.aucName;
        if (!"qty".equals(field)) qtyBuf = String.valueOf(d.aucQty);
        if (!"sec".equals(field)) secBuf = String.valueOf(d.aucSeconds);
        String realName = it == null ? "Item name" : it.name;
        input(g, "name", lx, y, 210, "NAME", nameBuf, realName);
        input(g, "qty", lx + 218, y, 90, "QUANTITY", qtyBuf, "1");
        input(g, "sec", lx + 316, y, 94, "SECONDS", secBuf, "30");

        // ----- timer color: quick colors + picker -----
        y += 40;
        spaced(g, "TIMER COLOR", lx, y, DIM, 0.58f);
        float sx = lx;
        float sy = y + 10;
        for (int i = 0; i < AUC_COLORS.length; i++) {
            int c = AUC_COLORS[i];
            float bx2 = sx + (i % 4) * 22, by2 = sy + (i / 4) * 20;
            boolean on = d.aucColor == c;
            if (on) PnlHud.circle(g, bx2 + 8, by2 + 8, 8.6f, TEXT);
            PnlHud.circle(g, bx2 + 8, by2 + 8, 7, c);
            hits.add(new Hit(bx2, by2, 16, 16, () -> { PnlTracker.DATA.aucColor = c; PnlTracker.DATA.save(); syncPicker(c); }));
        }
        colorPicker(g, d, lx + 100, sy, 230, 36);
        // preview swatch
        roundedF(g, lx + 340, sy, 70, 36, 5, d.aucColor);
        String hex = String.format("#%06X", d.aucColor & 0xFFFFFF);
        txt(g, hex, lx + 375 - w(hex, true, 0.62f) / 2f, sy + 14, (luma(d.aucColor) > 150) ? 0xFF0A0B14 : TEXT, 0.62f, true);

        // ----- auction switch -----
        y = sy + 44;
        float rw = lw, rh = 34;
        rounded(g, lx, y, rw, rh, 5, CARD);
        border(g, lx, y, rw, rh, d.aucMode ? 0xFF3B2C7A : EDGE);
        txt(g, "Auction switch", lx + 12, y + 6, TEXT, 0.8f, true);
        txt(g, "On: while an auction runs, money paid to you counts as bids, not bets.", lx + 12, y + 20, MUTED, 0.58f, false);
        float swx = lx + rw - 12 - 34, swy = y + (rh - 16) / 2f;
        roundedF(g, swx, swy, 34, 16, 8, d.aucMode ? PURPLE_D : 0xFF2A2D40);
        PnlHud.circle(g, d.aucMode ? swx + 26 : swx + 8, swy + 8, 5.5f, TEXT);
        hits.add(new Hit(lx, y, rw, rh, () -> { PnlTracker.DATA.aucMode = !PnlTracker.DATA.aucMode; PnlTracker.DATA.save(); }));

        // ----- bids / last results -----
        y += rh + 6;
        float bh = VH - 20 - y;
        rounded(g, lx, y, lw, bh, 5, CARD);
        border(g, lx, y, lw, bh, EDGE);
        int rows = Math.max(1, (int) ((bh - 20) / 11));
        if (live || (recent && !Auction.bids.isEmpty())) {
            spaced(g, "BIDS", lx + 12, y + 7, TEXT, 0.6f);
            txt(g, "biggest single payment", lx + 12 + spacedW("BIDS", 0.6f) + 8, y + 7.5f, DIM, 0.56f, false);
            List<Map.Entry<String, Double>> bl = new ArrayList<>(Auction.bids.entrySet());
            bl.sort((a2, b2) -> Double.compare(b2.getValue(), a2.getValue()));
            float ry = y + 19;
            if (bl.isEmpty()) txt(g, "No bids yet", lx + 12, ry, DIM, 0.66f, false);
            for (int i = 0; i < Math.min(rows, bl.size()); i++) {
                avatar(g, bl.get(i).getKey(), lx + 18, ry + 4.5f, 5);
                txt(g, bl.get(i).getKey(), lx + 28, ry, i == 0 ? TEXT : MUTED, 0.68f, true);
                String a2 = PnlData.format(bl.get(i).getValue());
                txt(g, a2, lx + lw - 12 - w(a2, true, 0.68f), ry, i == 0 ? GREEN : MUTED, 0.68f, true);
                ry += 11;
            }
        } else {
            spaced(g, "LAST AUCTIONS", lx + 12, y + 7, TEXT, 0.6f);
            float ry = y + 19;
            if (d.aucHistory.isEmpty()) txt(g, "Your finished auctions will show up here", lx + 12, ry, DIM, 0.66f, false);
            for (int i = d.aucHistory.size() - 1, n = 0; i >= 0 && n < rows; i--, n++) {
                PnlData.AucResult r = d.aucHistory.get(i);
                String left = Auction.label(r.item, r.name, r.qty) + "  \u2192  " + (r.winner.isEmpty() ? "no bids" : r.winner);
                txt(g, trim(left, true, 0.66f, lw - 110), lx + 12, ry, TEXT, 0.66f, true);
                String a2 = r.winner.isEmpty() ? "-" : PnlData.format(r.amount);
                txt(g, a2, lx + lw - 12 - w(a2, true, 0.66f), ry, r.winner.isEmpty() ? DIM : GREEN, 0.66f, true);
                ry += 11;
            }
        }

        // ----- item picker -----
        float px = 440, pw = VW - 20 - px, py = 84, ph = VH - 20 - py;
        rounded(g, px, py, pw, ph, 6, CARD);
        border(g, px, py, pw, ph, EDGE);
        spaced(g, "PICK AN ITEM", px + 14, py + 13, TEXT, 0.66f);
        Items.Entry sel = Items.get(d.aucItem);
        if (sel != null) {
            String sn = sel.name;
            txt(g, sn, px + pw - 14 - w(sn, true, 0.74f), py + 12, col, 0.74f, true);
        }
        float sbx = px + 14, sby = py + 28, sbw = pw - 28, sbh = 22;
        rounded(g, sbx, sby, sbw, sbh, 4, aucFocused ? CARD_HI : 0xFF0C0E19);
        border(g, sbx, sby, sbw, sbh, aucFocused ? PURPLE_D : EDGE);
        searchIcon(g, sbx + 12, sby + 11);
        boolean caret = aucFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        if (aucSearch.isEmpty() && !aucFocused) txt(g, "Search every item and block...", sbx + 24, sby + 7, DIM, 0.72f, false);
        else txt(g, aucSearch + (caret ? "|" : ""), sbx + 24, sby + 7, TEXT, 0.72f, false);
        hits.add(new Hit(sbx, sby, sbw, sbh, () -> aucFocused = true));

        List<Items.Entry> found = Items.search(aucSearch);
        float gx0 = px + 14, gy0 = sby + sbh + 10, tile = 40, gap = 5;
        int cols = (int) ((pw - 28 + gap) / (tile + gap));
        int rowsVis = (int) ((py + ph - 26 - gy0 + gap) / (tile + gap));
        int rowsAll = (found.size() + cols - 1) / cols;
        int firstRow = scrollIndex("items", rowsAll, rowsVis, px, gy0, pw, rowsVis * (tile + gap));
        String hoverName = null;
        if (found.isEmpty()) txt(g, Items.all().isEmpty() ? "Items load when you're in a world" : "Nothing matches \"" + aucSearch + "\"", gx0, gy0 + 4, DIM, 0.75f, false);
        beginRows(g, tile + gap);
        for (int r = 0; r < rowsVis + 1; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (firstRow + r) * cols + c;
                if (idx >= found.size()) break;
                Items.Entry e = found.get(idx);
                float tx2 = gx0 + c * (tile + gap), ty2 = gy0 + r * (tile + gap);
                boolean isSel = e.id.equals(d.aucItem);
                boolean hov = in(tx2, ty2, tile, tile);
                if (hov) hoverName = e.name;
                rounded(g, tx2, ty2, tile, tile, 5, isSel ? CARD_HI : hov ? 0xFF161A2B : 0xFF0E1120);
                if (isSel) border(g, tx2, ty2, tile, tile, col);
                g.pose().pushMatrix();
                g.pose().translate(tx2 + (tile - 24) / 2f, ty2 + (tile - 24) / 2f);
                g.pose().scale(1.5f, 1.5f);
                Items.draw(g, e.stack, 0, 0);
                g.pose().popMatrix();
                final String id = e.id;
                hits.add(new Hit(tx2, ty2, tile, tile, () -> {
                    if (Auction.running()) return;
                    if (!id.equals(PnlTracker.DATA.aucItem)) PnlTracker.DATA.aucName = "";
                    PnlTracker.DATA.aucItem = id;
                    PnlTracker.DATA.save();
                }));
            }
        }
        endRows(g);
        scrollBar(g, px + pw - 7, gy0, rowsVis * (tile + gap), "items", rowsAll, rowsVis);
        String foot = hoverName != null ? hoverName : found.size() + " items";
        txt(g, foot, px + 14, py + ph - 16, hoverName != null ? TEXT : DIM, 0.7f, hoverName != null);
    }

    // ---------- small text boxes (name / quantity / seconds) ----------
    private static String field = null;   // which box you're typing in
    private static String nameBuf = "", qtyBuf = "1", secBuf = "30";

    private void input(GuiGraphicsExtractor g, String key, float x, float y, float w, String label, String value, String placeholder) {
        boolean on = key.equals(field);
        spaced(g, label, x, y, DIM, 0.56f);
        float by = y + 8, h = 22;
        rounded(g, x, by, w, h, 4, on ? CARD_HI : 0xFF0C0E19);
        border(g, x, by, w, h, on ? PURPLE_D : EDGE);
        boolean caret = on && (System.currentTimeMillis() / 500) % 2 == 0;
        String shown = trim(value, false, 0.76f, w - 16);
        if (value.isEmpty() && !on) txt(g, trim(placeholder, false, 0.76f, w - 16), x + 8, by + 7, DIM, 0.76f, false);
        else txt(g, shown + (caret ? "|" : ""), x + 8, by + 7, TEXT, 0.76f, false);
        hits.add(new Hit(x, by, w, h, () -> { if (!Auction.running()) field = key; }));
    }

    /** Saves what you typed into a box. */
    private static void commitField() {
        PnlData d = PnlTracker.DATA;
        if (d == null || field == null) return;
        switch (field) {
            case "name" -> d.aucName = nameBuf.trim();
            case "qty" -> { try { d.aucQty = Math.max(1, Math.min(1_000_000, Integer.parseInt(qtyBuf.trim()))); } catch (Exception ignored) {} }
            case "sec" -> { try { d.aucSeconds = Math.max(1, Math.min(3600, Integer.parseInt(secBuf.trim()))); } catch (Exception ignored) {} }
            default -> {}
        }
        d.saveLater();
    }

    // ---------- color picker ----------
    private static float pickH = 0.11f, pickS = 0.75f, pickV = 0.94f;
    private static boolean pickerSynced = false;
    private static int dragTarget = 0;          // 1 = color square, 2 = rainbow bar
    private float pickX, pickY, pickW, pickH2, hueX, hueW;

    private static void syncPicker(int argb) {
        float[] hsv = rgbToHsv(argb);
        pickH = hsv[0]; pickS = hsv[1]; pickV = hsv[2];
    }

    /** Color square (saturation / brightness) on the left, rainbow bar (hue) on the right. Click or drag. */
    private void colorPicker(GuiGraphicsExtractor g, PnlData d, float x, float y, float w, float h) {
        if (!pickerSynced) { syncPicker(d.aucColor); pickerSynced = true; }
        float sqW = w - 22;
        pickX = x; pickY = y; pickW = sqW; pickH2 = h; hueX = x + sqW + 6; hueW = 16;
        // Cheap to draw: color columns (white -> full color), then rows of black that get darker going down.
        // Black at alpha a over a color = that color at brightness (1 - a), which is exactly what the square shows.
        final int Q = 4;
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        int X = Math.round(x * Q), Y = Math.round(y * Q), Wd = Math.round(sqW * Q), H = Math.round(h * Q);
        final int colStep = 3, rowStep = 2;
        for (int cx = 0; cx < Wd; cx += colStep)
            g.fill(X + cx, Y, X + Math.min(cx + colStep, Wd), Y + H, hsvToRgb(pickH, (cx + colStep / 2f) / Wd, 1));
        for (int cy = 0; cy < H; cy += rowStep) {
            int a = Math.round(255 * Math.min(1f, (cy + rowStep / 2f) / H));
            if (a > 0) g.fill(X, Y + cy, X + Wd, Y + Math.min(cy + rowStep, H), a << 24);
        }
        int HX = Math.round(hueX * Q), HW = Math.round(hueW * Q);
        for (int cy = 0; cy < H; cy += rowStep)
            g.fill(HX, Y + cy, HX + HW, Y + Math.min(cy + rowStep, H), hsvToRgb((cy + rowStep / 2f) / H, 1, 1));
        g.pose().popMatrix();
        border(g, x, y, sqW, h, EDGE);
        border(g, hueX, y, hueW, h, EDGE);
        // markers
        float mx = x + pickS * sqW, my = y + (1 - pickV) * h;
        PnlHud.circle(g, mx, my, 3.4f, 0xFFFFFFFF);
        PnlHud.circle(g, mx, my, 2.2f, d.aucColor);
        float hy = y + pickH * h;
        fill(g, hueX - 1.5f, hy - 1, hueX + hueW + 1.5f, hy + 1, 0xFFFFFFFF);
        hits.add(new Hit(x, y, sqW, h, () -> { dragTarget = 1; applyPick(); }));
        hits.add(new Hit(hueX, y, hueW, h, () -> { dragTarget = 2; applyPick(); }));
    }

    private float lastVX, lastVY;

    private void applyPick() {
        PnlData d = PnlTracker.DATA;
        if (d == null || dragTarget == 0) return;
        if (dragTarget == 1) {
            pickS = clamp01((lastVX - pickX) / pickW);
            pickV = 1 - clamp01((lastVY - pickY) / pickH2);
        } else {
            pickH = clamp01((lastVY - pickY) / pickH2);
            if (pickH >= 1) pickH = 0.999f;
        }
        d.aucColor = hsvToRgb(pickH, pickS, pickV);
    }

    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }

    private static int hsvToRgb(float h, float s, float v) {
        float r, g, b;
        int i = (int) Math.floor(h * 6) % 6;
        float f = h * 6 - (float) Math.floor(h * 6), p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private static float[] rgbToHsv(int argb) {
        float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), dlt = max - min;
        float h = 0;
        if (dlt > 0) {
            if (max == r) h = ((g - b) / dlt) % 6;
            else if (max == g) h = (b - r) / dlt + 2;
            else h = (r - g) / dlt + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        return new float[]{h, max == 0 ? 0 : dlt / max, max};
    }

    private static int luma(int argb) { return (int) (0.299 * ((argb >> 16) & 255) + 0.587 * ((argb >> 8) & 255) + 0.114 * (argb & 255)); }

    // =====================================================================
    // PROFIT PANEL (on-screen panel switches)
    // =====================================================================
    private void panels(GuiGraphicsExtractor g, PnlData d) {
        txt(g, "Profit Panel", 20, 46, TEXT, 1.4f, true);
        txt(g, "Turn each one on or off. When it's off it disappears from your screen and stops tracking until you turn it back on.", 20, 66, MUTED, 0.68f, false);
        float y = 90;
        y = toggleRow(g, y, "Profit tracker", "Profit chart, Recent Bets and Player Nets. Off = payments aren't recorded at all.", d.panelVisible,
                () -> { PnlTracker.DATA.panelVisible = !PnlTracker.DATA.panelVisible; PnlTracker.DATA.save(); });
        y = toggleRow(g, y, "Rakeback", "Rakeback panel (top 6 closest to " + PnlData.shortFmt(PnlData.RB_LIMIT) + "). Off = rakeback stops counting.", d.rbVisible,
                () -> { PnlTracker.DATA.rbVisible = !PnlTracker.DATA.rbVisible; PnlTracker.DATA.save(); });
        toggleRow(g, y, "Balance", "Your balance in the bottom-right. Off = it stops updating; type /bal after turning it back on.", d.balVisible,
                () -> { PnlTracker.DATA.balVisible = !PnlTracker.DATA.balVisible; PnlTracker.DATA.save(); });
    }

    private float toggleRow(GuiGraphicsExtractor g, float y, String title, String desc, boolean on, Runnable flip) {
        float x = 20, w = VW - 40, h = 56;
        boolean hover = in(x, y, w, h);
        rounded(g, x, y, w, h, 6, hover ? CARD_HI : CARD);
        border(g, x, y, w, h, on ? 0xFF3B2C7A : EDGE);
        txt(g, title, x + 18, y + 14, TEXT, 1.0f, true);
        txt(g, desc, x + 18, y + 31, MUTED, 0.7f, false);
        String state = on ? "ON" : "OFF";
        spaced(g, state, x + w - 74 - spacedW(state, 0.62f), y + 25, on ? GREEN : DIM, 0.62f);
        // switch
        float sw = 40, sh = 20, sx = x + w - 18 - sw, sy = y + (h - sh) / 2f;
        roundedF(g, sx, sy, sw, sh, sh / 2f, on ? PURPLE_D : 0xFF2A2D40);
        PnlHud.circle(g, on ? sx + sw - sh / 2f : sx + sh / 2f, sy + sh / 2f, sh / 2f - 3, TEXT);
        hits.add(new Hit(x, y, w, h, flip));
        return y + h + 10;
    }

    // =====================================================================
    // Data helpers
    // =====================================================================
    /** name -> [net from the player's side, total they bet with you, number of bets] */
    private static Map<String, double[]> playerStats(List<PnlData.Tx> txs, long from, long to) {
        Map<String, double[]> m = new HashMap<>();
        for (PnlData.Tx tx : txs) {
            if (tx.t < from || tx.t > to || tx.rb) continue;
            double[] v = m.computeIfAbsent(tx.p, k -> new double[3]);
            v[0] -= tx.a;
            if (tx.a > 0) { v[1] += tx.a; v[2]++; }
        }
        return m;
    }

    private long rangeStart(long now) {
        return switch (range) {
            case 0 -> now - PnlData.DAY;
            case 1 -> now - 7 * PnlData.DAY;
            case 2 -> now - 30 * PnlData.DAY;
            default -> 0;
        };
    }

    private String rangeName() { return new String[]{"24H", "7D", "30D", "ALL"}[range]; }

    private String rangeLong() { return new String[]{"24 hours", "7 days", "30 days", "all time"}[range]; }

    private static double balanceAt(PnlData d, long t) {
        double v = -1;
        for (PnlData.BalPt p : d.balHist) { if (p.t <= t) v = p.b; else break; }
        if (v < 0 && !d.balHist.isEmpty()) v = d.balHist.get(0).b;
        return v < 0 ? d.balance : v;
    }

    private static double[] balanceSeries(PnlData d, long from, long to, int n) {
        double[] s = new double[n];
        for (int i = 0; i < n; i++) s[i] = balanceAt(d, from + (to - from) * i / (n - 1));
        return s;
    }

    private static double[] playSeries(PnlData d, long now) {
        double[] s = new double[7];
        for (int i = 0; i < 7; i++) {
            String key = PnlData.dayKey(now - (6 - i) * PnlData.DAY);
            s[i] = d.playMs.getOrDefault(key, 0L);
        }
        return s;
    }

    private static double[] profitSeries(List<PnlData.Tx> all, long from, long to, int n) {
        if (from <= 0 && !all.isEmpty()) from = all.get(0).t;
        if (from <= 0) from = to - PnlData.DAY;
        double[] s = new double[n];
        int k = 0;
        double cum = 0;
        while (k < all.size() && all.get(k).t < from) k++;
        for (int i = 0; i < n; i++) {
            long t = from + (to - from) * i / (n - 1);
            while (k < all.size() && all.get(k).t <= t) cum += all.get(k++).a;
            s[i] = cum;
        }
        return s;
    }

    // =====================================================================
    // Charts
    // =====================================================================
    /** Big P/L chart: one point per payment, smooth line, area fill to zero, dashed zero line. */
    private void bigChart(GuiGraphicsExtractor g, List<PnlData.Tx> all, long since, long now, float x, float y, float w, float h, int col) {
        List<Double> pts = new ArrayList<>();
        pts.add(0.0);
        double cum = 0;
        for (PnlData.Tx tx : all) if (tx.t >= since) { cum += tx.a; pts.add(cum); }
        if (pts.size() < 2) pts.add(0.0);
        // grid
        for (int i = 0; i <= 4; i++) {
            float gy = y + h * i / 4f;
            for (float gx = x; gx < x + w; gx += 6) fill(g, gx, gy, gx + 2, gy + 0.6f, 0x14FFFFFF);
        }
        if (pts.size() <= 2 && cum == 0) {
            String m = "Your profit chart fills in as payments come in";
            txt(g, m, x + (w - w(m, false, 0.8f)) / 2f, y + h / 2f - 4, DIM, 0.8f, false);
        }
        double[] arr = new double[pts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = pts.get(i);
        lineArea(g, arr, x, y, w, h, col, true, 1.6f);
        // max / min labels
        double max = 0, min = 0;
        for (double v : arr) { max = Math.max(max, v); min = Math.min(min, v); }
        txt(g, PnlData.signed(max).replace(".00", ""), x + 2, y + 2, DIM, 0.6f, false);
        txt(g, PnlData.signed(min).replace(".00", ""), x + 2, y + h - 8, DIM, 0.6f, false);
    }

    private void sparkline(GuiGraphicsExtractor g, double[] s, float x, float y, float w, float h, int col) {
        boolean flat = true;
        for (double v : s) if (v != s[0]) { flat = false; break; }
        if (flat) { fill(g, x, y + h / 2f, x + w, y + h / 2f + 0.8f, (col & 0x00FFFFFF) | 0x66000000); return; }
        lineArea(g, s, x, y, w, h, col, false, 1.1f);
    }

    /** Smooth line through the values (drawn at 4x resolution), with a soft fill under it. */
    private void lineArea(GuiGraphicsExtractor g, double[] v, float x, float y, float w, float h, int col, boolean zeroBased, float thick) {
        double min = zeroBased ? 0 : Double.MAX_VALUE, max = zeroBased ? 0 : -Double.MAX_VALUE;
        for (double d : v) { min = Math.min(min, d); max = Math.max(max, d); }
        if (max - min < 1e-9) { max += 1; min -= 1; }
        double pad = (max - min) * 0.08;
        max += pad; min -= pad;
        final int Q = 4;
        int X0 = Math.round(x * Q), W = Math.round(w * Q), Y0 = Math.round(y * Q), H = Math.round(h * Q);
        int zeroY = Y0 + (int) Math.round((max - (zeroBased ? 0 : min)) / (max - min) * H);
        if (!zeroBased) zeroY = Y0 + H;
        int segs = v.length - 1;
        int fillCol = (col & 0x00FFFFFF) | 0x22000000;
        int t = Math.max(2, Math.round(thick * Q));
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        if (zeroBased) for (int gx = X0; gx < X0 + W; gx += 16) g.fill(gx, zeroY, gx + 8, zeroY + 2, 0x40FFFFFF);
        // steps of 2 (half a pixel): looks the same, but half as much to draw every frame
        final int STEP = zeroBased ? 2 : 4;   // small card charts: one column per pixel is plenty
        int prev = -1, lastY = 0, k2 = 0;
        for (int px = 0; px <= W; px += STEP, k2++) {
            double pos = Math.min(W, px) / (double) W * segs;
            int i = Math.min(segs - 1, (int) pos);
            double f = pos - i;
            double a = v[i], b = v[i + 1];
            double val = a + (b - a) * (f * f * (3 - 2 * f)); // smooth step between points
            int yy = Y0 + (int) Math.round((max - val) / (max - min) * H);
            int sw = Math.min(STEP, W + 1 - px);
            if (k2 % 2 == 0) g.fill(X0 + px, Math.min(yy, zeroY), X0 + Math.min(px + 2 * STEP, W + 1), Math.max(yy, zeroY), fillCol);
            int top = prev < 0 ? yy : Math.min(prev, yy), bot = prev < 0 ? yy : Math.max(prev, yy);
            g.fill(X0 + px, top - t / 2, X0 + px + sw, bot + t / 2 + 1, col);
            prev = yy;
            lastY = yy;
        }
        g.pose().popMatrix();
        if (zeroBased) {
            PnlHud.circle(g, x + w, lastY / (float) Q, 2.6f, col);
            PnlHud.circle(g, x + w, lastY / (float) Q, 1.3f, 0xFFFFFFFF);
        }
    }

    private void gradientBar(GuiGraphicsExtractor g, float x, float y, float w, float h, float fullW) {
        if (w <= 0) return;
        final int Q = 4;
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        int X = Math.round(x * Q), Y = Math.round(y * Q), Wd = Math.round(w * Q), H = Math.round(h * Q);
        for (int i = 0; i < Wd; i += 2)
            g.fill(X + i, Y, X + Math.min(i + 2, Wd), Y + H, PnlHud.lerp(0xFFF29BCB, GREEN, i / (fullW * Q)));
        g.pose().popMatrix();
    }

    private void glow(GuiGraphicsExtractor g, float x, float y, float w, float h, int col) {
        for (int i = 0; i < 24; i++) {
            float k = i / 24f;
            fill(g, x + w * k / 2, y, x + w - w * k / 2, y + h * (1 - k * 0.85f), (2 << 24) | (col & 0xFFFFFF));
        }
    }

    // =====================================================================
    // Skins
    // =====================================================================
    private float[] entityArea;
    private static java.lang.reflect.Method entityMethod;
    private static boolean entityTried, entityFailed;
    private static String skin3DFor;
    private static Skin3D.Image skin3D;

    /** Your 3D skin, facing the front. Uses the game's own player model; if that isn't available, draws your skin in 3D itself. */
    private void drawBody(GuiGraphicsExtractor g, String name, float x, float y, float w, float h) {
        if (Minecraft.getInstance().player != null && entityAvailable()) { entityArea = new float[]{x, y, w, h}; return; }
        SkinCache.Skin skin = SkinCache.get(name);
        if (skin == null) { avatar(g, name, x + w / 2f, y + h / 2f, Math.min(w, h) * 0.32f); return; }
        if (skin3D == null || !name.equals(skin3DFor)) { skin3D = Skin3D.render(skin, 160, 160); skin3DFor = name; }
        float k = Math.min(w / skin3D.w, h / skin3D.h);
        float bx = x + (w - skin3D.w * k) / 2f, by = y + h - skin3D.h * k;
        final int Q = 4;
        g.pose().pushMatrix();
        g.pose().translate(bx, by);
        g.pose().scale(k, k);
        for (Skin3D.Run r : skin3D.runs) g.fill(r.x1, r.y, r.x2, r.y + 1, r.col);
        g.pose().popMatrix();
    }

    private static boolean entityAvailable() {
        if (entityFailed) return false;
        if (!entityTried) {
            entityTried = true;
            try {
                Class<?> inv = Class.forName("net.minecraft.client.gui.screens.inventory.InventoryScreen");
                for (java.lang.reflect.Method m : inv.getMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (!m.getName().contains("EntityInInventoryFollowsMouse")) continue;
                    if (m.getParameterCount() == 10 && m.getParameterTypes()[0] == GuiGraphicsExtractor.class) { entityMethod = m; break; }
                }
            } catch (Throwable ignored) {}
            if (entityMethod == null) entityFailed = true;
        }
        return entityMethod != null;
    }

    private static java.lang.reflect.Method scissorOn, scissorOff;
    private static boolean scissorTried;

    /** The game's real 3D player, looking straight ahead, cut off at the bottom of the welcome strip (so it stands behind the cards). */
    private void drawEntity(GuiGraphicsExtractor g, float[] a) {
        if (!scissorTried) {
            scissorTried = true;
            for (java.lang.reflect.Method m : GuiGraphicsExtractor.class.getMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!n.contains("scissor")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (n.contains("enable") && p.length == 4 && p[0] == int.class) scissorOn = m;
                if (n.contains("disable") && p.length == 0) scissorOff = m;
            }
        }
        float stripTop = 44, stripBottom = 118;
        int x1 = Math.round(ox + a[0] * scale), x2 = Math.round(ox + (a[0] + a[2]) * scale);
        int top = Math.round(oy + stripTop * scale), bottom = Math.round(oy + stripBottom * scale);
        try {
            if (scissorOn != null && scissorOff != null) {
                // big model (head to waist fills the strip); the legs hang below and get cut off
                int fullH = Math.round((bottom - top) * 1.62f);
                int y1 = top + 2, y2 = y1 + fullH;
                int size = Math.round(fullH / 2.05f);
                scissorOn.invoke(g, x1 - 20, top, x2 + 20, bottom);
                entityMethod.invoke(null, g, x1 - 20, y1, x2 + 20, y2, size, 0.0625f, (x1 + x2) / 2f, y1 + fullH * 0.15f, Minecraft.getInstance().player);
                scissorOff.invoke(g);
            } else {
                // can't cut it off on this version: draw the whole player small enough to fit inside the strip
                int size = Math.round((bottom - top) / 2.2f);
                entityMethod.invoke(null, g, x1, top, x2, bottom, size, 0.0625f, (x1 + x2) / 2f, top + (bottom - top) * 0.2f, Minecraft.getInstance().player);
            }
        } catch (Throwable t) {
            try { if (scissorOff != null) scissorOff.invoke(g); } catch (Throwable ignored) {}
            entityFailed = true;
            entityMethod = null;
        }
    }

    /** Soft colored light behind a word. */
    private void glowBehind(GuiGraphicsExtractor g, float x, float y, float w, float h, int col) {
        // 10 soft layers drawn at normal resolution (it's a blur, so it doesn't need the 4x detail)
        for (int i = 0; i < 10; i++) {
            float k = i / 10f;
            float ix = w * 0.5f * k, iy = h * 0.5f * k;
            roundedQ(g, x + ix, y + iy, w - 2 * ix, h - 2 * iy, (h - 2 * iy) / 2f, (5 << 24) | (col & 0xFFFFFF), 1);
        }
    }

    /** Text with a soft glow around it. */
    private void glowText(GuiGraphicsExtractor g, String s, float x, float y, int col, float sc, int glowAlpha) {
        int gc = (glowAlpha << 24) | (col & 0xFFFFFF);
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI * 2 * i / 8;
            for (float r : new float[]{0.9f, 1.9f})
                txt(g, s, x + (float) Math.cos(ang) * r, y + (float) Math.sin(ang) * r, gc, sc, true);
        }
        txt(g, s, x, y, col, sc, true);
    }

    // =====================================================================
    // Input
    // =====================================================================
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        float vx = (float) ((event.x() - ox) / scale), vy = (float) ((event.y() - oy) / scale);
        lastVX = vx; lastVY = vy;
        boolean wasFocused = searchFocused || aucFocused || field != null;
        searchFocused = false;
        aucFocused = false;
        if (field != null) { commitField(); field = null; }
        dragTarget = 0;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (vx >= h.x() && vx <= h.x() + h.w() && vy >= h.y() && vy <= h.y() + h.h()) {
                h.action().run();
                return true;
            }
        }
        return wasFocused || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragTarget != 0) {
            lastVX = (float) ((event.x() - ox) / scale);
            lastVY = (float) ((event.y() - oy) / scale);
            applyPick();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragTarget != 0) { dragTarget = 0; syncPicker(PnlTracker.DATA.aucColor); PnlTracker.DATA.save(); return true; }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        float vx = (float) ((mouseX - ox) / scale), vy = (float) ((mouseY - oy) / scale);
        for (int i = 0; i < scrollAreas.size(); i++) {
            int[] a = scrollAreas.get(i);
            if (vx >= a[0] && vx <= a[0] + a[2] && vy >= a[1] && vy <= a[1] + a[3]) {
                String key = scrollKeys.get(i);
                scroll.put(key, Math.max(0, scroll.getOrDefault(key, 0f) - (float) scrollY * 2));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!searchFocused && !aucFocused && field == null) return super.charTyped(event);
        int cp = event.codepoint();
        if (cp >= 32 && cp != 127) {
            String add = new String(Character.toChars(cp));
            if ("name".equals(field) && nameBuf.length() < 32) nameBuf += add;
            if ("qty".equals(field) && Character.isDigit(cp) && qtyBuf.length() < 7) qtyBuf = (qtyBuf.equals("0") ? "" : qtyBuf) + add;
            if ("sec".equals(field) && Character.isDigit(cp) && secBuf.length() < 4) secBuf = (secBuf.equals("0") ? "" : secBuf) + add;
            if (field != null) commitField();
            if (searchFocused && search.length() < 24) { search += add; resetScroll("wl"); }
            if (aucFocused && aucSearch.length() < 32) { aucSearch += add; resetScroll("items"); }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (field != null) {
            if (key == 259) {                                                          // Backspace
                if ("name".equals(field) && !nameBuf.isEmpty()) nameBuf = nameBuf.substring(0, nameBuf.length() - 1);
                if ("qty".equals(field) && !qtyBuf.isEmpty()) qtyBuf = qtyBuf.substring(0, qtyBuf.length() - 1);
                if ("sec".equals(field) && !secBuf.isEmpty()) secBuf = secBuf.substring(0, secBuf.length() - 1);
                commitField();
                return true;
            }
            if (key == 256 || key == 257 || key == 335 || key == 258) {             // Esc / Enter / Tab: done typing
                commitField();
                field = key == 258 ? ("name".equals(field) ? "qty" : "qty".equals(field) ? "sec" : null) : null;
                return true;
            }
            return true;
        }
        if (searchFocused || aucFocused) {
            if (key == 256) { searchFocused = false; aucFocused = false; return true; } // Esc: leave the search box
            if (key == 259) {                                                          // Backspace
                if (searchFocused && !search.isEmpty()) search = search.substring(0, search.length() - 1);
                if (aucFocused && !aucSearch.isEmpty()) aucSearch = aucSearch.substring(0, aucSearch.length() - 1);
                resetScroll("wl");
                resetScroll("items");
                return true;
            }
            if (key == 257 || key == 335) { searchFocused = false; aucFocused = false; return true; } // Enter
            return true; // swallow other keys while typing (so letters don't trigger keybinds)
        }
        if (key == 256 && tab == 1 && selectedPlayer != null) { selectedPlayer = null; return true; } // Esc on a player page: back to the list
        if (PnlTracker.DASHBOARD_KEY != null && PnlTracker.DASHBOARD_KEY.matches(event)) { onClose(); return true; }
        if (key == 70 && tab == 1) { searchFocused = true; return true; }             // F: jump to search on Win/Loss
        return super.keyPressed(event);
    }

    // =====================================================================
    // Scrolling
    // =====================================================================
    // Smooth scrolling: `scroll` is where the list is heading, `scrollAnim` is where it is right now (glides toward it).
    private final Map<String, Float> scrollAnim = new HashMap<>();
    private long lastFrameNs = 0;
    private float frameDt = 0.016f;
    private float rowsFrac;            // how far between two rows the list is (0..1), for the list being drawn
    private float[] rowsArea;
    private float rowsShift;
    private int rowsHitMark;
    private boolean rowsClipped;

    private void resetScroll(String key) { scroll.put(key, 0f); scrollAnim.put(key, 0f); }

    private int scrollIndex(String key, int count, int visible, float x, float y, float w, float h) {
        scrollAreas.add(new int[]{Math.round(x), Math.round(y), Math.round(w), Math.round(h)});
        scrollKeys.add(key);
        int maxFirst = Math.max(0, count - visible);
        float s = Math.min(scroll.getOrDefault(key, 0f), maxFirst);
        scroll.put(key, s);
        float a = scrollAnim.getOrDefault(key, s);
        a += (s - a) * (1f - (float) Math.exp(-frameDt * 18f));
        if (Math.abs(s - a) < 0.003f) a = s;
        a = Math.max(0, Math.min(a, maxFirst));
        scrollAnim.put(key, a);
        int first = (int) Math.floor(a);
        rowsFrac = a - first;
        rowsArea = new float[]{x, y, w, h};
        return first;
    }

    /** Call right before drawing a list's rows: slides them up by the in-between amount and cuts them off at the list's edges. */
    private void beginRows(GuiGraphicsExtractor g, float rowH) {
        rowsShift = rowsFrac * rowH;
        rowsHitMark = hits.size();
        rowsClipped = clipOn(g, rowsArea[0], rowsArea[1], rowsArea[2], rowsArea[3]);
        g.pose().pushMatrix();
        g.pose().translate(0, -rowsShift);
        mouseVY += rowsShift;
    }

    private void endRows(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
        mouseVY -= rowsShift;
        if (rowsClipped) clipOff(g);
        // move the click areas to where the rows really are, and only inside the list
        float top = rowsArea[1], bottom = rowsArea[1] + rowsArea[3];
        for (int i = hits.size() - 1; i >= rowsHitMark; i--) {
            Hit h = hits.get(i);
            float y1 = Math.max(top, h.y() - rowsShift), y2 = Math.min(bottom, h.y() + h.h() - rowsShift);
            if (y2 <= y1) hits.remove(i);
            else hits.set(i, new Hit(h.x(), y1, h.w(), y2 - y1, h.action()));
        }
    }

    private static boolean scissorLookup;

    private static void findScissor() {
        if (scissorLookup) return;
        scissorLookup = true;
        for (java.lang.reflect.Method m : GuiGraphicsExtractor.class.getMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            if (!n.contains("scissor")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (n.contains("enable") && p.length == 4 && p[0] == int.class) scissorOn = m;
            if (n.contains("disable") && p.length == 0) scissorOff = m;
        }
    }

    /** Only draw inside this box (canvas coordinates). Returns false if the game can't do that. */
    private boolean clipOn(GuiGraphicsExtractor g, float x, float y, float w, float h) {
        findScissor();
        if (scissorOn == null || scissorOff == null) return false;
        int x1 = (int) Math.floor(ox + x * scale), y1 = (int) Math.floor(oy + y * scale);
        int x2 = (int) Math.ceil(ox + (x + w) * scale), y2 = (int) Math.ceil(oy + (y + h) * scale);
        g.pose().pushMatrix();
        g.pose().identity();
        try { scissorOn.invoke(g, x1, y1, x2, y2); return true; }
        catch (Throwable t) { scissorOn = null; return false; }
        finally { g.pose().popMatrix(); }
    }

    private void clipOff(GuiGraphicsExtractor g) {
        try { scissorOff.invoke(g); } catch (Throwable ignored) {}
    }

    private void scrollBar(GuiGraphicsExtractor g, float x, float y, float h, String key, int count, int visible) {
        if (count <= visible || visible <= 0) return;
        float s = scrollAnim.getOrDefault(key, scroll.getOrDefault(key, 0f));
        float bh = Math.max(14, h * visible / count);
        float by = y + (h - bh) * s / Math.max(1, count - visible);
        roundedF(g, x, y, 2.5f, h, 1.2f, 0x18FFFFFF);
        roundedF(g, x, by, 2.5f, bh, 1.2f, 0x66FFFFFF);
    }

    // =====================================================================
    // Small drawing helpers
    // =====================================================================
    private boolean in(float x, float y, float w, float h) {
        return mouseVX >= x && mouseVX <= x + w && mouseVY >= y && mouseVY <= y + h;
    }

    /** fill() with float coordinates (drawn at 4x resolution so edges land between pixels). */
    private static void fill(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int col) {
        final int Q = 4;
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        g.fill(Math.round(x1 * Q), Math.round(y1 * Q), Math.round(x2 * Q), Math.round(y2 * Q), col);
        g.pose().popMatrix();
    }

    /** Smooth rounded rectangle (radius r), drawn at 4x resolution. */
    private static void roundedF(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int col) { roundedQ(g, x, y, w, h, r, col, 4); }

    private static void roundedQ(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int col, int Q) {
        if ((col >>> 24) == 0) return;
        int X = Math.round(x * Q), Y = Math.round(y * Q), Wd = Math.round(w * Q), H = Math.round(h * Q);
        float R = Math.min(r * Q, Math.min(Wd, H) / 2f);
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        int rr = (int) Math.ceil(R);
        g.fill(X, Y + rr, X + Wd, Y + H - rr, col);
        // corner rows; rows with the same inset are drawn as one piece (same picture, far fewer pieces)
        int row = 0;
        while (row < rr) {
            int inset = cornerInset(R, row);
            int end = row + 1;
            while (end < rr && cornerInset(R, end) == inset) end++;
            g.fill(X + inset, Y + row, X + Wd - inset, Y + end, col);
            g.fill(X + inset, Y + H - end, X + Wd - inset, Y + H - row, col);
            row = end;
        }
        g.pose().popMatrix();
    }

    private static int cornerInset(float R, int row) {
        float dy = R - (row + 0.5f);
        return (int) Math.ceil(R - Math.sqrt(Math.max(0f, R * R - dy * dy)));
    }

    private static void rounded(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int col) { roundedF(g, x, y, w, h, r, col); }

    /** Thin 1px-at-4x border with rounded corners. */
    private static void border(GuiGraphicsExtractor g, float x, float y, float w, float h, int col) {
        final int Q = 4;
        int X = Math.round(x * Q), Y = Math.round(y * Q), Wd = Math.round(w * Q), H = Math.round(h * Q), t = 2, r = 10;
        g.pose().pushMatrix();
        g.pose().scale(1f / Q, 1f / Q);
        g.fill(X + r, Y, X + Wd - r, Y + t, col);
        g.fill(X + r, Y + H - t, X + Wd - r, Y + H, col);
        g.fill(X, Y + r, X + t, Y + H - r, col);
        g.fill(X + Wd - t, Y + r, X + Wd, Y + H - r, col);
        for (int i = 0; i < r; i++) {
            double ang = (Math.PI / 2) * (i + 0.5) / r;
            int dx = (int) Math.round(r - r * Math.sin(ang)), dy = (int) Math.round(r - r * Math.cos(ang));
            g.fill(X + dx, Y + dy, X + dx + t, Y + dy + t, col);
            g.fill(X + Wd - dx - t, Y + dy, X + Wd - dx, Y + dy + t, col);
            g.fill(X + dx, Y + H - dy - t, X + dx + t, Y + H - dy, col);
            g.fill(X + Wd - dx - t, Y + H - dy - t, X + Wd - dx, Y + H - dy, col);
        }
        g.pose().popMatrix();
    }

    private static void pillBox(GuiGraphicsExtractor g, float x, float y, float w, float h, int fillCol, int edge) {
        roundedF(g, x, y, w, h, 3, edge);
        roundedF(g, x + 0.5f, y + 0.5f, w - 1, h - 1, 2.6f, BG);
        if ((fillCol >>> 24) != 0) roundedF(g, x + 0.5f, y + 0.5f, w - 1, h - 1, 2.6f, fillCol);
    }

    /** Small circle with the player's first letter (used for the feed and lists). */
    private void avatar(GuiGraphicsExtractor g, String name, float cx, float cy, float r) {
        SkinCache.Skin skin = SkinCache.get(name);
        if (skin != null) {
            int[] face = SkinCache.face(skin);
            float size = r * 1.8f, cell = size / 8f, x0 = cx - size / 2f, y0 = cy - size / 2f;
            roundedF(g, x0 - 1, y0 - 1, size + 2, size + 2, 2.5f, 0x55000000);
            for (int yy = 0; yy < 8; yy++)
                for (int xx = 0; xx < 8; xx++)
                    fill(g, x0 + xx * cell, y0 + yy * cell, x0 + (xx + 1) * cell + 0.05f, y0 + (yy + 1) * cell + 0.05f, face[yy * 8 + xx]);
            return;
        }
        int h = name.hashCode();
        int col = 0xFF000000 | PnlHud.lerp(0xFF3A3F66, 0xFF6C4CF0, ((h >>> 3) & 255) / 255f) & 0xFFFFFF;
        PnlHud.circle(g, cx, cy, r, col);
        String ch = name.isEmpty() ? "?" : name.replace(".", "").replace("_", "").isEmpty() ? "?" : String.valueOf(Character.toUpperCase(name.replace(".", "").replace("_", "").charAt(0)));
        float s = r / 8f * 0.85f;
        txt(g, ch, cx - w(ch, true, s) / 2f, cy - 4.2f * s, TEXT, s, true);
    }

    private void searchIcon(GuiGraphicsExtractor g, float cx, float cy) {
        PnlHud.circle(g, cx - 1, cy - 1, 3.6f, MUTED);
        PnlHud.circle(g, cx - 1, cy - 1, 2.6f, CARD);
        for (int i = 0; i < 4; i++) fill(g, cx + 1.4f + i * 0.7f, cy + 1.4f + i * 0.7f, cx + 2.6f + i * 0.7f, cy + 2.6f + i * 0.7f, MUTED);
    }

    /** Right-pointing play triangle. */
    private void triangle(GuiGraphicsExtractor g, float x, float y, float s, int col) {
        for (float yy = 0; yy < s; yy += 0.25f) {
            float half = s / 2f, w = (half - Math.abs(yy + 0.125f - half)) * 1.7f;
            fill(g, x, y + yy, x + w, y + yy + 0.25f, col);
        }
    }

    // ---------- text ----------
    private void txt(GuiGraphicsExtractor g, String s, float x, float y, int col, float sc, boolean bold) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(sc, sc);
        g.text(this.font, PnlHud.c(s, bold), 0, 0, col, false);
        g.pose().popMatrix();
    }

    private float w(String s, boolean bold, float sc) { return this.font.width(PnlHud.c(s, bold)) * sc; }

    private void spaced(GuiGraphicsExtractor g, String s, float x, float y, int col, float sc) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(sc, sc);
        int cx = 0;
        for (int i = 0; i < s.length(); i++) {
            Component ch = PnlHud.c(String.valueOf(s.charAt(i)), true);
            g.text(this.font, ch, cx, 0, col, false);
            cx += this.font.width(ch) + 1;
        }
        g.pose().popMatrix();
    }

    private float spacedW(String s, float sc) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += this.font.width(PnlHud.c(String.valueOf(s.charAt(i)), true)) + 1;
        return w * sc;
    }

    private String trim(String s, boolean bold, float sc, float maxW) {
        if (w(s, bold, sc) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && w(t + "..", bold, sc) > maxW) t = t.substring(0, t.length() - 1);
        return t + "..";
    }

    // ---------- formatting ----------
    private static String fmtShort(double v) { return PnlData.format(v).replace(".00", ""); }

    private static String arrow(double d) { return d >= 0 ? "▲ +" : "▼ -"; }

    private static String hours(long ms) {
        long h = ms / 3_600_000L;
        if (h >= 1) return h + "h";
        return (ms / 60_000L) + "m";
    }

    private static String dur(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter DATE_LONG = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    private static String time(long t) { return TIME.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())).toLowerCase(Locale.US); }

    private static String date(long t) { return DATE.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())); }

    private static String dateLong(long t) { return DATE_LONG.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())); }
}
