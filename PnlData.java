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
    public long profitStart = 0;         // /pnl reset profit: chart + profit only count payments after this
    public int rotateSeconds = 7;        // 0 = stay on the profit page

    // Rakeback: each player's net losses toward $1B (not limited to 24h). 10% back when they hit it.
    public static final double RB_LIMIT = 1e9, RB_PCT = 0.10;
    public Map<String, Double> rakeback = new HashMap<>();
    public boolean rbSeeded = false;
    public boolean rbVisible = true;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pnltracker.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // DonutSMP formats. Anchored to the start of the line so players can't fake them in normal chat.
    private static final String AMT = "\\$\\s?([\\d.,]+\\s?[KkMmBbTt]?)";
    private static final Pattern RECEIVED = Pattern.compile("^([A-Za-z0-9_.]{2,17}) paid you " + AMT, Pattern.CASE_INSENSITIVE);
    private static final Pattern SENT     = Pattern.compile("^You paid ([A-Za-z0-9_.]{2,17}) " + AMT, Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE  = Pattern.compile("^You have " + AMT, Pattern.CASE_INSENSITIVE);

    public boolean handle(String raw) {
        String msg = raw.trim();
        Matcher m;
        if ((m = RECEIVED.matcher(msg)).find()) { add(m.group(1), parseAmount(m.group(2))); return true; }
        if ((m = SENT.matcher(msg)).find())     { add(m.group(1), -parseAmount(m.group(2))); return true; }
        if ((m = BALANCE.matcher(msg)).find())  { balance = parseAmount(m.group(1)); save(); return true; }
        return false;
    }

    public void handleBalanceOnly(String raw) {
        Matcher m = BALANCE.matcher(raw.trim());
        if (m.find()) { balance = parseAmount(m.group(1)); save(); }
    }

    private void add(String player, double signed) {
        if (balance >= 0) balance += signed;
        double cur = rakeback.getOrDefault(player, 0.0);
        boolean claim = false;
        if (signed > 0) cur += signed;                                   // they lost a bet to you
        else if (cur >= RB_LIMIT && isRakebackAmount(-signed, cur)) { cur = 0; claim = true; } // you paid their rakeback
        else cur += signed;                                              // you paid out a win, so they're less down
        if (cur <= 0) rakeback.remove(player); else rakeback.put(player, cur);
        Tx tx = new Tx(System.currentTimeMillis(), player, signed);
        tx.rb = claim;
        txs.add(tx);
        prune();
        save();
    }

    /** Drop payments older than 24h. */
    public void prune() {
        long cutoff = System.currentTimeMillis() - DAY;
        txs.removeIf(tx -> tx == null || tx.t < cutoff);
        while (txs.size() > 20000) txs.remove(0);
    }

    /** Payments in the last 24h, oldest first. */
    public List<Tx> window() {
        long cutoff = System.currentTimeMillis() - DAY;
        List<Tx> out = new ArrayList<>();
        for (Tx tx : txs) if (tx.t >= cutoff) out.add(tx);
        out.sort(Comparator.comparingLong(tx -> tx.t));
        return out;
    }

    public void reset() { txs.clear(); profitStart = 0; save(); }

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

    public void save() {
        try { Files.writeString(FILE, GSON.toJson(this)); }
        catch (Exception e) { PnlTracker.LOG.warn("Couldn't save pnltracker.json", e); }
    }
}
