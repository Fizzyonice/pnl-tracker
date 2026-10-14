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
        public Tx() {}
        public Tx(long t, String p, double a) { this.t = t; this.p = p; this.a = a; }
    }

    public List<Tx> txs = new ArrayList<>();
    public double balance = -1;          // -1 = unknown until "You have $..." shows up
    public boolean hudVisible = true;
    public float hudScale = 1.0f;
    public boolean rightSide = true;

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
        txs.add(new Tx(System.currentTimeMillis(), player, signed));
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

    public void reset() { txs.clear(); save(); }

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
                    d.prune();
                    return d;
                }
            }
        } catch (Exception e) {
            PnlTracker.LOG.warn("Couldn't read pnltracker.json, starting fresh", e);
        }
        return new PnlData();
    }

    public void save() {
        try { Files.writeString(FILE, GSON.toJson(this)); }
        catch (Exception e) { PnlTracker.LOG.warn("Couldn't save pnltracker.json", e); }
    }
}
