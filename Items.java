package com.fiizzyonice.pnltracker;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.*;

/** Every item and block in the game (for the auction search), plus drawing an item icon. */
public final class Items {
    public static final class Entry {
        public final String id, name, search;
        public final ItemStack stack;
        Entry(String id, String name, ItemStack stack) { this.id = id; this.name = name; this.stack = stack; this.search = (name + " " + id).toLowerCase(Locale.ROOT); }
    }

    private static List<Entry> all;
    private static final Map<String, Entry> byId = new HashMap<>();

    public static List<Entry> all() {
        if (all != null) return all;
        List<Entry> list = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                String id = String.valueOf(BuiltInRegistries.ITEM.getKey(item));
                if (id.equals("minecraft:air")) continue;
                ItemStack st = new ItemStack(item);
                if (st.isEmpty()) continue;
                String name = st.getHoverName().getString();
                Entry e = new Entry(id, name, st);
                list.add(e);
                byId.put(id, e);
            } catch (Throwable ignored) {}
        }
        list.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
        all = list;
        return all;
    }

    public static Entry get(String id) { all(); return byId.get(id); }

    /** Search by name or id, best matches first (name starts with the text). */
    private static String lastQ;
    private static List<Entry> lastResult;

    public static List<Entry> search(String q) {
        q = q.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return all();
        if (q.equals(lastQ) && lastResult != null && all != null) return lastResult;   // same text as last frame
        lastQ = q;
        lastResult = searchNow(q);
        return lastResult;
    }

    private static List<Entry> searchNow(String q) {
        List<Entry> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Entry e : all()) {
            if (e.name.toLowerCase(Locale.ROOT).startsWith(q)) starts.add(e);
            else if (e.search.contains(q)) contains.add(e);
        }
        starts.addAll(contains);
        return starts;
    }

    // ---------- drawing an item icon (16x16 at the current pose) ----------
    private static Method itemMethod;
    private static boolean tried;

    public static boolean draw(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (!tried) {
            tried = true;
            Method fallback = null;
            for (Method m : GuiGraphicsExtractor.class.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3 || p[0] != ItemStack.class || p[1] != int.class || p[2] != int.class) continue;
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (n.contains("fake")) { itemMethod = m; break; }
                if (n.contains("item") && fallback == null) fallback = m;
            }
            if (itemMethod == null) itemMethod = fallback;
        }
        if (itemMethod == null || stack == null) return false;
        try { itemMethod.invoke(g, stack, x, y); return true; }
        catch (Throwable t) { itemMethod = null; return false; }
    }
}
