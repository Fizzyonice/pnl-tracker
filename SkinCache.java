package com.fiizzyonice.pnltracker;

import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads Java skins from minotar.net (a public skin site, not the Minecraft server),
 * keeps them in config/pnltracker-skins for 3 days, and hands out the pixels.
 * Bedrock players (names starting with ".") have no Java skin, so they keep their letter.
 */
public class SkinCache {
    public static final class Skin {
        public final int w, h;
        public final int[] px; // ARGB, row by row
        public Skin(int w, int h, int[] px) { this.w = w; this.h = h; this.px = px; }
        public int at(int x, int y) { return (x < 0 || y < 0 || x >= w || y >= h) ? 0 : px[y * w + x]; }
    }

    private static final Map<String, Skin> SKINS = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pnltracker-skins");
        t.setDaemon(true);
        return t;
    });
    private static HttpClient http;

    /** For previews/tests: put a skin in directly. */
    public static void preload(String name, Skin s) { SKINS.put(name.toLowerCase(), s); }

    /** The skin if it's ready; otherwise starts loading it and returns null. */
    public static Skin get(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{2,16}")) return null;
        String key = name.toLowerCase();
        Skin s = SKINS.get(key);
        if (s != null) return s;
        Long failedAt = FAILED.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 10 * 60_000L) return null;
        if (LOADING.putIfAbsent(key, true) == null) POOL.submit(() -> load(name, key));
        return null;
    }

    private static void load(String name, String key) {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("pnltracker-skins");
            Path file = dir.resolve(key + ".png");
            byte[] bytes = null;
            if (Files.exists(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < 3L * 24 * 3600_000L)
                bytes = Files.readAllBytes(file);
            if (bytes == null) {
                if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create("https://minotar.net/skin/" + name))
                        .timeout(Duration.ofSeconds(10)).header("User-Agent", "pnltracker-mod").build(), HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode());
                bytes = res.body();
                try { Files.createDirectories(dir); Files.write(file, bytes); } catch (Exception ignored) {}
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null || img.getWidth() < 64 || img.getHeight() < 32) throw new RuntimeException("not a skin");
            int w = 64, h = img.getHeight() >= 64 ? 64 : 32;
            int sx = img.getWidth() / 64; // HD skins: sample every Nth pixel
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) px[y * w + x] = img.getRGB(x * sx, y * sx);
            SKINS.put(key, new Skin(w, h, px));
        } catch (Throwable t) {
            FAILED.put(key, System.currentTimeMillis());
        } finally {
            LOADING.remove(key);
        }
    }

    /** Face (8x8) with the hat layer on top. */
    public static int[] face(Skin s) {
        int[] out = new int[64];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int base = s.at(8 + x, 8 + y) | 0xFF000000;
                int hat = s.at(40 + x, 8 + y);
                out[y * 8 + x] = (hat >>> 24) > 32 ? hat | 0xFF000000 : base;
            }
        return out;
    }

    /** Front view from the head down to the waist: 16 wide x 20 tall (0 = empty). */
    public static int[] body(Skin s) {
        int[] g = new int[16 * 20];
        boolean modern = s.h == 64;
        boolean slim = modern && (s.at(55, 25) >>> 24) == 0;
        int aw = slim ? 3 : 4;
        // head + hat
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) put(g, 4 + x, y, s.at(8 + x, 8 + y), s.at(40 + x, 8 + y));
        for (int y = 0; y < 12; y++) {
            // torso + jacket
            for (int x = 0; x < 8; x++) put(g, 4 + x, 8 + y, s.at(20 + x, 20 + y), modern ? s.at(20 + x, 36 + y) : 0);
            // player's right arm (your left)
            for (int x = 0; x < aw; x++) put(g, 4 - aw + x, 8 + y, s.at(44 + x, 20 + y), modern ? s.at(44 + x, 36 + y) : 0);
            // player's left arm (your right)
            for (int x = 0; x < aw; x++) {
                int base = modern ? s.at(36 + x, 52 + y) : s.at(44 + (aw - 1 - x), 20 + y);
                int over = modern ? s.at(52 + x, 52 + y) : 0;
                put(g, 12 + x, 8 + y, base, over);
            }
        }
        return g;
    }

    private static void put(int[] g, int x, int y, int base, int over) {
        if (x < 0 || x >= 16 || y < 0 || y >= 20) return;
        int c = (over >>> 24) > 32 ? over : base;
        if ((c >>> 24) == 0) return;
        g[y * 16 + x] = c | 0xFF000000;
    }
}
