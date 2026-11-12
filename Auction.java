package com.fiizzyonice.pnltracker;

import java.util.*;

/**
 * One auction at a time. While it's running (and the Auction switch is on), money people pay you counts as their bid.
 * When the timer hits 0 it locks: later payments don't count, and the top bidder wins.
 */
public final class Auction {
    public static String itemId = "";
    public static long startAt = 0, endAt = 0;
    public static final LinkedHashMap<String, Double> bids = new LinkedHashMap<>();
    private static boolean finalized = true;
    private static final Map<String, Double> refundable = new HashMap<>();
    private static final long REFUND_WINDOW = 30 * 60_000L;

    public static boolean running() { long now = System.currentTimeMillis(); return startAt > 0 && now >= startAt && now < endAt; }

    /** Ended within the last `ms` milliseconds (used to keep "X won the auction" on screen for a bit). */
    public static boolean endedWithin(long ms) { long now = System.currentTimeMillis(); return startAt > 0 && now >= endAt && now - endAt < ms; }

    public static void start(String item, int seconds) {
        itemId = item;
        startAt = System.currentTimeMillis();
        endAt = startAt + seconds * 1000L;
        bids.clear();
        finalized = false;
    }

    /** End it now (the current leader wins). */
    public static void stop() { if (running()) endAt = System.currentTimeMillis(); }

    /** Throw it away with no winner. */
    public static void cancel() { startAt = 0; endAt = 0; bids.clear(); finalized = true; }

    public static long remainingMs() { return Math.max(0, endAt - System.currentTimeMillis()); }

    /** Counts a payment as a bid if an auction is running. A player's bids add up. */
    public static boolean bid(String player, double amount) {
        if (!running() || amount <= 0) return false;
        bids.merge(player, amount, Double::sum);
        return true;
    }

    /** Highest bidder (earliest wins a tie), or null. */
    public static Map.Entry<String, Double> leader() {
        Map.Entry<String, Double> best = null;
        for (Map.Entry<String, Double> e : bids.entrySet()) if (best == null || e.getValue() > best.getValue()) best = e;
        return best;
    }

    /** After the auction, paying a losing bidder back (up to what they bid) is a refund, not a gamble. */
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
        for (Map.Entry<String, Double> e : bids.entrySet()) if (win == null || !e.getKey().equals(win.getKey())) refundable.put(e.getKey(), e.getValue());
        PnlData.AucResult r = new PnlData.AucResult();
        r.t = endAt;
        r.item = itemId;
        r.winner = win == null ? "" : win.getKey();
        r.amount = win == null ? 0 : win.getValue();
        r.bidders = bids.size();
        d.aucHistory.add(r);
        while (d.aucHistory.size() > 50) d.aucHistory.remove(0);
        d.save();
    }

    public static String clock(long ms) {
        long s = (ms + 999) / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
