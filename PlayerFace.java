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

/** Gets the player's 8x8 face (with hat layer) once, caches it to config, and hands out the pixels. */
public class PlayerFace {
    private static volatile int[] pixels;   // 64 ARGB values, row by row
    private static volatile String loadingFor;

    public static int[] get(String name) {
        if (name == null || name.isEmpty()) return null;
        if (!name.equals(loadingFor)) {
            loadingFor = name;
            pixels = null;
            Thread t = new Thread(() -> load(name), "pnltracker-face");
            t.setDaemon(true);
            t.start();
        }
        return pixels;
    }

    private static void load(String name) {
        Path cache = FabricLoader.getInstance().getConfigDir().resolve("pnltracker-face-" + name.toLowerCase() + ".png");
        try {
            if (Files.exists(cache)) { pixels = decode(Files.readAllBytes(cache)); if (pixels != null) return; }
        } catch (Exception ignored) {}

        String[] urls = {
            "https://minotar.net/helm/" + name + "/8.png",
            "https://mc-heads.net/avatar/" + name + "/8"
        };
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        for (String url : urls) {
            try {
                HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) continue;
                int[] px = decode(res.body());
                if (px != null) {
                    pixels = px;
                    try { Files.write(cache, res.body()); } catch (Exception ignored) {}
                    return;
                }
            } catch (Exception e) {
                PnlTracker.LOG.warn("Couldn't fetch face from " + url, e);
            }
        }
    }

    /** Scales any face image down to 8x8 by sampling, returns ARGB pixels. */
    private static int[] decode(byte[] png) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) return null;
        int[] out = new int[64];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int sx = (int) ((x + 0.5) * img.getWidth() / 8.0);
                int sy = (int) ((y + 0.5) * img.getHeight() / 8.0);
                out[y * 8 + x] = img.getRGB(sx, sy) | 0xFF000000;
            }
        return out;
    }
}
