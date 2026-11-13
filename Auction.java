package com.fiizzyonice.pnltracker;

import java.util.*;

/**
 * One auction at a time. While it's running (and the Auction switch is on), money people pay you counts as bids.
 * Bids do NOT stack: each player's bid is their single biggest payment, and the biggest single payment wins.
 * When the timer hits 0 it locks: later payments don't count.
 */
public final class Auction {
    public static String itemId = "";
    public static String name = "";   // custom name ("" = the item's real name)
    public static int qty = 1;
    public static long startAt = 0, endAt = 0;
    /** Each player's biggest single payment. */
    public static final LinkedHashMap<String, Double> bids = new LinkedHashMap<>();
    /** When each player's biggest payment came in (earlier wins a tie). */
    private static final Map<String, Long> bidAt = new HashMap<>();
    /** Everything each player paid during the auction (for refunds). */
    private static final Map<String, Double> paid = new HashMap<>();
    private static boolean finalized = true;
    private static final Map<String, Double> refundable = new HashMap<>();
    private static final long REFUND_WINDOW = 30 * 60_000L;

    public static boolean running() { long now = System.currentTimeMillis(); return startAt > 0 && now >= startAt && now < endAt; }

    /** Ended within the last `ms` milliseconds (used to keep "X won the auction" on screen for a bit). */
    public static boolean endedWithin(long ms) { long now = System.currentTimeMillis(); return startAt > 0 && now >= endAt && now - endAt < ms; }

    public static void start(String item, String customName, int quantity, int seconds) {
        itemId = item;
        name = customName == null ? "" : customName.trim();
        qty = Math.max(1, quantity);
        startAt = System.currentTimeMillis();
        endAt = startAt + Math.max(1, seconds) * 1000L;
        bids.clear();
        bidAt.clear();
        paid.clear();
        finalized = false;
    }

    /** End it now (the current leader wins). */
    public static void stop() { if (running()) endAt = System.currentTimeMillis(); }

    /** Throw it away with no winner. */
    public static void cancel() { startAt = 0; endAt = 0; bids.clear(); bidAt.clear(); paid.clear(); finalized = true; }

    public static long remainingMs() { return Math.max(0, endAt - System.currentTimeMillis()); }

    /** Counts a payment as a bid if an auction is running. Only a player's biggest single payment is their bid. */
    public static boolean bid(String player, double amount) {
        if (!running() || amount <= 0) return false;
        paid.merge(player, amount, Double::sum);
        Double cur = bids.get(player);
        if (cur == null || amount > cur) {
            bids.put(player, amount);
            bidAt.put(player, System.nanoTime());
        }
        return true;
    }

    /** Highest single bid (if two are equal, whoever paid it first). */
    public static Map.Entry<String, Double> leader() {
        Map.Entry<String, Double> best = null;
        for (Map.Entry<String, Double> e : bids.entrySet()) {
            if (best == null || e.getValue() > best.getValue()
                    || (e.getValue().equals(best.getValue()) && bidAt.getOrDefault(e.getKey(), 0L) < bidAt.getOrDefault(best.getKey(), 0L)))
                best = e;
        }
        return best;
    }

    /** After the auction, paying someone back (up to what they paid beyond a winning bid) is a refund, not a gamble. */
    public static boolean refund(String player, double amount) {
        if (startAt == 0 || System.currentTimeMillis() - endAt > REFUND_WINDOW || running()) return false;
        Double left = refundable.get(player);
        if (left == null || amount > left + 1) return false;
        if (left - amount < 1) refundable.remove(player); else refundable.put(player, left - amount);
        return true;
    }

    /** Called every tick: when the timer runs out, lock in the winner and save it to history. */
    public static void tick(PnlData d) {
        if (finalized || startAt == 0 || System.currentTimeMillis() < endAt) return;
        finalized = true;
        Map.Entry<String, Double> win = leader();
        refundable.clear();
        for (Map.Entry<String, Double> e : paid.entrySet()) {
            double back = e.getValue() - (win != null && e.getKey().equals(win.getKey()) ? win.getValue() : 0);
            if (back >= 1) refundable.put(e.getKey(), back);
        }
        PnlData.AucResult r = new PnlData.AucResult();
        r.t = endAt;
        r.item = itemId;
        r.name = name;
        r.qty = qty;
        r.winner = win == null ? "" : win.getKey();
        r.amount = win == null ? 0 : win.getValue();
        r.bidders = bids.size();
        d.aucHistory.add(r);
        while (d.aucHistory.size() > 50) d.aucHistory.remove(0);
        d.save();
    }

    /** "64x Neth Block" / "Diamond" */
    public static String label(String item, String customName, int quantity) {
        Items.Entry e = Items.get(item);
        String n = customName != null && !customName.isBlank() ? customName.trim() : (e == null ? item : e.name);
        return quantity > 1 ? quantity + "x " + n : n;
    }

    public static String clock(long ms) {
        long s = (ms + 999) / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
