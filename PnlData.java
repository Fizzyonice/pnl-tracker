package com.fiizzyonice.pnltracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stores every payment (last 24h) and parses DonutSMP payment messages. Saved to config/pnltracker.json. */
public class PnlData {
    public static final long DAY = 24L * 60 * 60 * 1000;

    /** One payment. a > 0 = they paid you, a < 0 = you paid them. */
    public static class Tx {
        public long t;
        public String p;
        public double a;
        public boolean rb;   // this payment was a rakeback payout
        public Tx() {}
        public Tx(long t, String p, double a) { this.t = t; this.p = p; this.a = a; }
    }

    public List<Tx> txs = new ArrayList<>();
    public double balance = -1;          // -1 = unknown until "You have $..." shows up
    public boolean hudVisible = true;
    public float hudScale = 1.0f;
    public boolean rightSide = true;
    // ---- dashboard data ----
    public static class BalPt { public long t; public double b; public BalPt() {} BalPt(long t, double b) { this.t = t; this.b = b; } }
    public static class Session { public long start, end; public Session() {} Session(long s) { start = s; } }
    public List<BalPt> balHist = new ArrayList<>();          // balance over time
    public Map<String, Long> playMs = new HashMap<>();        // playtime per day, "2026-11-11" -> ms
    public List<Session> sessions = new ArrayList<>();       // hosting sessions (end = 0 while running)

    /** Remembers the balance after every change, for the balance chart. */
    public void recordBalance() {
        if (balance < 0) return;
        if (balHist == null) balHist = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (!balHist.isEmpty() && now - balHist.get(balHist.size() - 1).t < 2000) balHist.remove(balHist.size() - 1);
        balHist.add(new BalPt(now, balance));
        while (balHist.size() > 5000) balHist.remove(0);
    }

    public static String dayKey(long t) {
        return java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
    }

    public void addPlaytime(long ms) {
        if (playMs == null) playMs = new HashMap<>();
        playMs.merge(dayKey(System.currentTimeMillis()), ms, Long::sum);
    }

    public long playtimeSince(long since) {
        long total = 0;
        if (playMs == null) return 0;
        for (Map.Entry<String, Long> e : playMs.entrySet()) {
            long dayStart = java.time.LocalDate.parse(e.getKey()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (dayStart + DAY > since) total += e.getValue();
        }
        return total;
    }

    public Session activeSession() {
        if (sessions == null) sessions = new ArrayList<>();
        for (Session s : sessions) if (s.end == 0) return s;
        return null;
    }

    public void startSession() { if (activeSession() == null) { sessions.add(new Session(System.currentTimeMillis())); save(); } }

    public void stopSession() { Session s = activeSession(); if (s != null) { s.end = System.currentTimeMillis(); save(); } }

    public long profitStart = 0;         // /pnl reset profit: chart + profit only count payments after this
    public int rotateSeconds = 7;        // 0 = stay on the profit page

    // Rakeback: each player's net losses toward $1B (not limited to 24h). 10% back when they hit it.
    public static final double RB_LIMIT = 1e9, RB_PCT = 0.10;
    public Map<String, Double> rakeback = new HashMap<>();
    public boolean rbSeeded = false;
    public boolean rbVisible = true;
    public boolean panelVisible = true;        // the switching Profit / Recent Bets / Player Nets panel
    public boolean balVisible = true;          // the balance card in the bottom-right
    public String displayName = "Fizzyonice";  // name shown on the dashboard

    // ---- auctions ----
    public static class AucResult { public long t; public String item = "", name = "", winner = ""; public double amount; public int bidders, qty = 1; }
    public int aucColor = 0xFFF0B03C;          // timer color
    public int aucSeconds = 30;                // timer length
    public boolean aucMode = true;             // payments during an auction count as bids (not gambles)
    public String aucItem = "minecraft:diamond";
    public String aucName = "";                // custom item name ("" = real name)
    public int aucQty = 1;                     // how many of the item
    public List<AucResult> aucHistory = new ArrayList<>();

    public transient long syncDue = 0;         // when to run the quiet /bal

    /** Rounded amounts like "4.3K" or "1.25B" aren't exact, so schedule a quiet /bal. */
    private void checkRounded(String amt) {
        if (amt != null && amt.matches(".*[KkMmBbTt].*")) syncDue = System.currentTimeMillis() + 1200;
    }

    public boolean isBalanceMessage(String raw) { return BALANCE.matcher(raw.trim()).find(); }

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pnltracker.json");
    private static final Path BACKUP = FabricLoader.getInstance().getConfigDir().resolve("pnltracker-backup.json");
    private static final Path RESTORE = FabricLoader.getInstance().getConfigDir().resolve("pnltracker-restore.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // DonutSMP formats. Anchored to the start of the line so players can't fake them in normal chat.
    private static final String AMT = "\\$\\s?([\\d.,]+\\s?[KkMmBbTt]?)";
    private static final Pattern RECEIVED = Pattern.compile("^([A-Za-z0-9_.]{2,17}) paid you " + AMT, Pattern.CASE_INSENSITIVE);
    private static final Pattern SENT     = Pattern.compile("^You paid ([A-Za-z0-9_.]{2,17}) " + AMT, Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE  = Pattern.compile("^You have " + AMT, Pattern.CASE_INSENSITIVE);
    // Balance-only money (doesn't count toward profit, bets or rakeback)
    private static final Pattern AH_SOLD   = Pattern.compile("^[A-Za-z0-9_.]{2,17} bought your .+ for " + AMT + "\\W*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AH_BOUGHT = Pattern.compile("^You bought .+ for " + AMT + "\\W*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOLD      = Pattern.compile("^\\+?\\s?" + AMT + "\\W*$"); // /sell: just "$ 3.2K"

    /** Auction house buys/sells and /sell: only moves the balance. */
    public boolean handleMoney(String raw) {
        String msg = raw.trim();
        Matcher m;
        if ((m = AH_SOLD.matcher(msg)).find())   { moveBalance(parseAmount(m.group(1)));  checkRounded(m.group(1)); return true; }
        if ((m = AH_BOUGHT.matcher(msg)).find()) { moveBalance(-parseAmount(m.group(1))); checkRounded(m.group(1)); return true; }
        if ((m = SOLD.matcher(msg)).find())      { moveBalance(parseAmount(m.group(1)));  checkRounded(m.group(1)); return true; }
        return false;
    }

    private void moveBalance(double signed) {
        if (signed == 0 || !balVisible) return;
        if (balance >= 0) balance += signed;
        recordBalance();
        long now = System.currentTimeMillis();
        lastDelta = (now - lastDeltaAt < 6000) ? lastDelta + signed : signed;
        lastDeltaAt = now;
        save();
    }

    public boolean handle(String raw) {
        if (handleMoney(raw)) return true;
        String msg = raw.trim();
        Matcher m;
        if ((m = RECEIVED.matcher(msg)).find()) { add(m.group(1), parseAmount(m.group(2))); checkRounded(m.group(2)); return true; }
        if ((m = SENT.matcher(msg)).find())     { add(m.group(1), -parseAmount(m.group(2))); checkRounded(m.group(2)); return true; }
        if ((m = BALANCE.matcher(msg)).find())  { if (balVisible) { balance = parseAmount(m.group(1)); recordBalance(); save(); } return true; }
        return false;
    }

    public void handleBalanceOnly(String raw) {
        if (handleMoney(raw)) return;
        Matcher m = BALANCE.matcher(raw.trim());
        if (m.find() && balVisible) { balance = parseAmount(m.group(1)); recordBalance(); save(); }
    }

    // last balance change, for the +$200M / -$200M under the balance (not saved)
    public transient double lastDelta = 0;
    public transient long lastDeltaAt = 0;

    private void add(String player, double signed) {
        // balance (only while the Balance switch is on)
        if (balVisible) {
            if (balance >= 0) balance += signed;
            recordBalance();
            long now = System.currentTimeMillis();
            lastDelta = (now - lastDeltaAt < 6000) ? lastDelta + signed : signed; // quick payments add together
            lastDeltaAt = now;
        }
        // auctions: bids while an auction runs, refunds to losing bidders after
        if (aucMode) {
            if (signed > 0 && Auction.bid(player, signed)) { save(); return; }
            if (signed < 0 && Auction.refund(player, -signed)) { save(); return; }
        }
        // rakeback (only while the Rakeback switch is on)
        boolean claim = false;
        if (rbVisible) {
            double cur = rakeback.getOrDefault(player, 0.0);
            if (signed > 0) cur += signed;                                   // they lost a bet to you
            else if (cur >= RB_LIMIT && isRakebackAmount(-signed, cur)) { cur = 0; claim = true; } // you paid their rakeback
            else cur += signed;                                              // you paid out a win, so they're less down
            if (cur <= 0) rakeback.remove(player); else rakeback.put(player, cur);
        }
        // profit / bets / winners / player nets (only while the Profit tracker switch is on)
        if (panelVisible) {
            Tx tx = new Tx(System.currentTimeMillis(), player, signed);
            tx.rb = claim;
            txs.add(tx);
            prune();
        }
        save();
    }

    /** Drop payments older than 24h. */
    /** Keeps every payment until you reset (only drops the oldest past 20,000 so the file stays small). */
    public void prune() {
        txs.removeIf(tx -> tx == null);
        while (txs.size() > 20000) txs.remove(0);
    }

    /** Payments in the last 24h, oldest first. */
    /** All saved payments, oldest first (nothing expires on its own). */
    // window() is called many times per frame by the dashboard, so keep the sorted copy until the payments change
    private transient List<Tx> winCache;
    private transient int winSize = -1;
    private transient Tx winFirst, winLast;
    private transient long winAt;

    public List<Tx> window() {
        long now = System.currentTimeMillis();
        Tx f = txs.isEmpty() ? null : txs.get(0), l = txs.isEmpty() ? null : txs.get(txs.size() - 1);
        if (winCache != null && winSize == txs.size() && winFirst == f && winLast == l && now - winAt < 2000) return winCache;
        List<Tx> out = new ArrayList<>();
        for (Tx tx : txs) if (tx != null) out.add(tx);
        out.sort(Comparator.comparingLong(tx -> tx.t));
        winCache = Collections.unmodifiableList(out);
        winSize = txs.size(); winFirst = f; winLast = l; winAt = now;
        return winCache;
    }

    /** Saves a copy of everything before a big reset, so /pnl undo can bring it back. */
    public void backup() {
        try { Files.writeString(BACKUP, GSON.toJson(this)); }
        catch (Exception e) { PnlTracker.LOG.warn("Couldn't write pnltracker-backup.json", e); }
    }

    public void reset() { backup(); txs.clear(); profitStart = 0; save(); }

    public void clearRakeback() { backup(); rakeback.clear(); save(); }

    /** Puts back history (and rakeback, if it was cleared) from the backup. Keeps payments made since. Returns payments added, or -1. */
    public int undo() {
        try {
            if (!Files.exists(BACKUP)) return -1;
            PnlData b = GSON.fromJson(Files.readString(BACKUP), PnlData.class);
            if (b == null) return -1;
            int added = mergeTxs(b.txs);
            if (b.profitStart < profitStart || profitStart == 0) profitStart = b.profitStart;
            if (rakeback.isEmpty() && b.rakeback != null) rakeback.putAll(b.rakeback);
            save();
            return added;
        } catch (Exception e) {
            PnlTracker.LOG.warn("Couldn't read pnltracker-backup.json", e);
            return -1;
        }
    }

    /** Adds history from config/pnltracker-restore.json. Keeps balance and rakeback. Returns payments added, or -1. */
    public int restore() {
        try {
            if (!Files.exists(RESTORE)) return -1;
            PnlData r = GSON.fromJson(Files.readString(RESTORE), PnlData.class);
            if (r == null) return -1;
            backup();
            int added = mergeTxs(r.txs);
            if (r.profitStart > 0) profitStart = r.profitStart;
            save();
            Files.move(RESTORE, RESTORE.resolveSibling("pnltracker-restore-used.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return added;
        } catch (Exception e) {
            PnlTracker.LOG.warn("Couldn't read pnltracker-restore.json", e);
            return -1;
        }
    }

    private int mergeTxs(List<Tx> extra) {
        if (extra == null) return 0;
        Set<String> have = new HashSet<>();
        for (Tx tx : txs) if (tx != null) have.add(tx.t + "|" + tx.p + "|" + tx.a);
        int added = 0;
        for (Tx tx : extra) {
            if (tx == null || tx.p == null) continue;
            if (have.add(tx.t + "|" + tx.p + "|" + tx.a)) { txs.add(tx); added++; }
        }
        txs.sort(Comparator.comparingLong(tx -> tx.t));
        prune();
        return added;
    }

    public void resetProfit() { profitStart = System.currentTimeMillis(); save(); }

    /** One row on the Recent Bets page. kind: 0 = LOST (you kept their bet), 1 = WON (you paid them out), 2 = PAID (no bet to match), 3 = RAKE (rakeback payout). */
    public static class Bet {
        public String p; public double amt; public long t; public int kind;
        Bet(String p, double amt, long t, int kind) { this.p = p; this.amt = amt; this.t = t; this.kind = kind; }
    }

    /** Every payment is its own row: they pay you = LOST, you pay them = WON, rakeback payout = RAKE. Newest first. */
    public static List<Bet> bets(List<Tx> win) {
        List<Bet> out = new ArrayList<>();
        for (Tx tx : win) {
            if (tx.a >= 0) out.add(new Bet(tx.p, tx.a, tx.t, 0));
            else out.add(new Bet(tx.p, -tx.a, tx.t, tx.rb ? 3 : 1));
        }
        Collections.reverse(out); // newest first (keeps the right order for payments in the same second)
        return out;
    }

    public static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return "now";
        if (s < 3600) return (s / 60) + "m";
        return (s / 3600) + "h";
    }

    /** Handles "1,250,000", "1.5M", "392.97B", "2.1 K" etc. */
    public static double parseAmount(String s) {
        s = s.replace(",", "").replace(" ", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return 0;
        double mult = 1;
        switch (Character.toUpperCase(s.charAt(s.length() - 1))) {
            case 'K' -> mult = 1e3;
            case 'M' -> mult = 1e6;
            case 'B' -> mult = 1e9;
            case 'T' -> mult = 1e12;
            default -> {}
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try { return Double.parseDouble(s) * mult; } catch (NumberFormatException e) { return 0; }
    }

    /** 392970000000 -> "$392.97B" */
    public static String format(double v) {
        double a = Math.abs(v);
        String out;
        if (a >= 1e12) out = String.format("%.2fT", a / 1e12);
        else if (a >= 1e9) out = String.format("%.2fB", a / 1e9);
        else if (a >= 1e6) out = String.format("%.2fM", a / 1e6);
        else if (a >= 1e3) out = String.format("%.2fK", a / 1e3);
        else out = String.format("%.0f", a);
        return "$" + out;
    }

    public static String signed(double v) { return (v >= 0 ? "+" : "-") + format(v); }

    public static PnlData load() {
        try {
            if (Files.exists(FILE)) {
                PnlData d = GSON.fromJson(Files.readString(FILE), PnlData.class);
                if (d != null) {
                    if (d.txs == null) d.txs = new ArrayList<>();
                    if (d.hudScale <= 0) d.hudScale = 1.0f;
                    if (d.rakeback == null) d.rakeback = new HashMap<>();
                    if (d.balHist == null) d.balHist = new ArrayList<>();
                    if (d.playMs == null) d.playMs = new HashMap<>();
                    if (d.sessions == null) d.sessions = new ArrayList<>();
                    if (d.aucHistory == null) d.aucHistory = new ArrayList<>();
                    if (d.aucItem == null) d.aucItem = "minecraft:diamond";
                    if (d.aucSeconds <= 0) d.aucSeconds = 30;
                    if (d.aucName == null) d.aucName = "";
                    if (d.aucQty <= 0) d.aucQty = 1;
                    d.prune();
                    if (!d.rbSeeded) { // start rakeback from today's payments
                        for (Tx tx : d.window()) d.rakeback.merge(tx.p, tx.a, Double::sum);
                        d.rakeback.values().removeIf(v -> v <= 0);
                        d.rbSeeded = true;
                    }
                    return d;
                }
            }
        } catch (Exception e) {
            PnlTracker.LOG.warn("Couldn't read pnltracker.json, starting fresh", e);
        }
        PnlData fresh = new PnlData();
        fresh.rbSeeded = true;
        return fresh;
    }

    /** What you owe a player: 10% of everything they've lost, once they reach $1B. */
    public static double owed(double lost) { return lost >= RB_LIMIT ? lost * RB_PCT : 0; }

    /** A payment counts as a rakeback payout if it matches what you owe within 5%. */
    static boolean isRakebackAmount(double paid, double lost) {
        double o = owed(lost);
        return o > 0 && Math.abs(paid - o) <= o * 0.05 + 1;
    }

    /** Top players by progress toward $1B, closest first. */
    public List<Map.Entry<String, Double>> rakebackTop(int max) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(rakeback.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }

    public boolean rakebackReset(String name) {
        String key = null;
        for (String k : rakeback.keySet()) if (k.equalsIgnoreCase(name)) key = k;
        if (key == null) return false;
        rakeback.remove(key);
        save();
        return true;
    }

    /** "$500.00M" -> "$500M", "$1.50B" -> "$1.5B" */
    public static String shortFmt(double v) {
        String s = format(v);
        return s.replaceAll("\\.00([KMBT])", "$1").replaceAll("(\\.\\d)0([KMBT])", "$1$2");
    }

    // Saving happens on a background thread so the game never freezes while the file is written.
    private static final java.util.concurrent.ThreadPoolExecutor SAVER = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 3, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "PnL save");
                t.setDaemon(false);
                return t;
            });
    static { SAVER.allowCoreThreadTimeOut(true); }
    private static final java.util.concurrent.atomic.AtomicReference<String> PENDING = new java.util.concurrent.atomic.AtomicReference<>();
    private transient long dirtyAt = 0;

    public void save() {
        dirtyAt = 0;
        String json;
        try { json = GSON.toJson(this); }
        catch (Exception e) { PnlTracker.LOG.warn("Couldn't save pnltracker.json", e); return; }
        if (PENDING.getAndSet(json) != null) return;   // a write is already queued; it will pick up this newest copy
        SAVER.execute(() -> {
            String j = PENDING.getAndSet(null);
            if (j == null) return;
            try {
                Path tmp = FILE.resolveSibling("pnltracker.json.tmp");
                Files.writeString(tmp, j);
                try { Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                catch (Exception e) { Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            } catch (Exception e) { PnlTracker.LOG.warn("Couldn't save pnltracker.json", e); }
        });
    }

    /** For things that change every keystroke: save a moment after you stop. */
    public void saveLater() { dirtyAt = System.currentTimeMillis(); }

    /** Called every tick: does the delayed save once you've stopped typing/dragging for a bit. */
    public void flushIfDue() { if (dirtyAt != 0 && System.currentTimeMillis() - dirtyAt > 700) save(); }
}
