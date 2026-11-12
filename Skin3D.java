package com.fiizzyonice.pnltracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny 3D renderer for a Minecraft skin (head, body and arms, waist up), facing the front with a slight turn
 * so it looks 3D. Renders once into a pixel buffer, then turns it into horizontal color runs for drawing.
 */
public class Skin3D {
    /** Horizontal run of one color: y, x1, x2 (exclusive), ARGB. */
    public static final class Run { public final int y, x1, x2, col; Run(int y, int x1, int x2, int col) { this.y = y; this.x1 = x1; this.x2 = x2; this.col = col; } }

    public static final class Image { public final int w, h; public final List<Run> runs; Image(int w, int h, List<Run> runs) { this.w = w; this.h = h; this.runs = runs; } }

    private static final class Quad { double[] xs = new double[4], ys = new double[4]; double depth; int col; }

    private static final double YAW = Math.toRadians(-16), PITCH = Math.toRadians(8), CAM = 70;

    public static Image render(SkinCache.Skin s, int W, int H) {
        boolean modern = s.h == 64;
        boolean slim = modern && (s.at(55, 25) >>> 24) == 0;
        double aw = slim ? 3 : 4;
        List<Quad> quads = new ArrayList<>();

        // head + hat
        box(quads, s, -4, 24, -4, 8, 8, 8, 0, 0, 0, false);
        box(quads, s, -4, 24, -4, 8, 8, 8, 32, 0, 0.5, true);
        // body + jacket
        box(quads, s, -4, 12, -2, 8, 12, 4, 16, 16, 0, false);
        if (modern) box(quads, s, -4, 12, -2, 8, 12, 4, 16, 32, 0.25, true);
        // player's right arm (your left)
        box(quads, s, -4 - aw, 12, -2, aw, 12, 4, 40, 16, 0, false);
        if (modern) box(quads, s, -4 - aw, 12, -2, aw, 12, 4, 40, 32, 0.25, true);
        // player's left arm (your right)
        if (modern) {
            box(quads, s, 4, 12, -2, aw, 12, 4, 32, 48, 0, false);
            box(quads, s, 4, 12, -2, aw, 12, 4, 48, 48, 0.25, true);
        } else box(quads, s, 4, 12, -2, aw, 12, 4, 40, 16, 0, false);

        // fit to the image
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (Quad q : quads) for (int i = 0; i < 4; i++) { minX = Math.min(minX, q.xs[i]); maxX = Math.max(maxX, q.xs[i]); minY = Math.min(minY, q.ys[i]); maxY = Math.max(maxY, q.ys[i]); }
        double sc = Math.min((W - 2) / (maxX - minX), (H - 2) / (maxY - minY));
        double offX = (W - (maxX - minX) * sc) / 2 - minX * sc, offY = H - 1 - maxY * sc;
        quads.sort((a, b) -> Double.compare(b.depth, a.depth)); // far first

        int[] buf = new int[W * H];
        for (Quad q : quads) {
            double[] px = new double[4], py = new double[4];
            for (int i = 0; i < 4; i++) { px[i] = q.xs[i] * sc + offX; py[i] = q.ys[i] * sc + offY; }
            fillQuad(buf, W, H, px, py, q.col);
        }
        // runs
        List<Run> runs = new ArrayList<>();
        for (int y = 0; y < H; y++) {
            int x = 0;
            while (x < W) {
                int c = buf[y * W + x];
                if (c == 0) { x++; continue; }
                int x2 = x + 1;
                while (x2 < W && buf[y * W + x2] == c) x2++;
                runs.add(new Run(y, x, x2, c));
                x = x2;
            }
        }
        return new Image(W, H, runs);
    }

    /** A box made of one quad per skin pixel on its front, top and two sides. */
    private static void box(List<Quad> out, SkinCache.Skin s, double x0, double y0, double z0, double w, double h, double d,
                            int u, int v, double inflate, boolean overlay) {
        int iw = (int) w, ih = (int) h, id = (int) d;
        double X0 = x0 - inflate, Y0 = y0 - inflate, Z0 = z0 - inflate;
        double sx = (w + 2 * inflate) / w, sy = (h + 2 * inflate) / h, sz = (d + 2 * inflate) / d;
        double X1 = X0 + w * sx, Y1 = Y0 + h * sy, Z1 = Z0 + d * sz;
        // front (+z): column i = your left -> right, row j = top -> bottom
        for (int j = 0; j < ih; j++) for (int i = 0; i < iw; i++)
            face(out, s.at(u + id + i, v + id + j), overlay, 1.0,
                    X0 + i * sx, Y1 - j * sy, Z1, X0 + (i + 1) * sx, Y1 - j * sy, Z1, X0 + (i + 1) * sx, Y1 - (j + 1) * sy, Z1, X0 + i * sx, Y1 - (j + 1) * sy, Z1);
        // top: rows back -> front
        for (int j = 0; j < id; j++) for (int i = 0; i < iw; i++)
            face(out, s.at(u + id + i, v + j), overlay, 1.12,
                    X0 + i * sx, Y1, Z0 + j * sz, X0 + (i + 1) * sx, Y1, Z0 + j * sz, X0 + (i + 1) * sx, Y1, Z0 + (j + 1) * sz, X0 + i * sx, Y1, Z0 + (j + 1) * sz);
        // player's right side (-x): columns back -> front
        for (int j = 0; j < ih; j++) for (int i = 0; i < id; i++)
            face(out, s.at(u + i, v + id + j), overlay, 0.72,
                    X0, Y1 - j * sy, Z0 + i * sz, X0, Y1 - j * sy, Z0 + (i + 1) * sz, X0, Y1 - (j + 1) * sy, Z0 + (i + 1) * sz, X0, Y1 - (j + 1) * sy, Z0 + i * sz);
        // player's left side (+x): columns front -> back
        for (int j = 0; j < ih; j++) for (int i = 0; i < id; i++)
            face(out, s.at(u + id + iw + i, v + id + j), overlay, 0.62,
                    X1, Y1 - j * sy, Z1 - i * sz, X1, Y1 - j * sy, Z1 - (i + 1) * sz, X1, Y1 - (j + 1) * sy, Z1 - (i + 1) * sz, X1, Y1 - (j + 1) * sy, Z1 - i * sz);
    }

    private static void face(List<Quad> out, int argb, boolean overlay, double light, double... p) {
        int a = argb >>> 24;
        if (overlay && a < 32) return;
        if (!overlay) a = 255;
        int r = clamp((int) (((argb >> 16) & 255) * light)), g = clamp((int) (((argb >> 8) & 255) * light)), b = clamp((int) ((argb & 255) * light));
        Quad q = new Quad();
        double depth = 0;
        double cy = Math.cos(YAW), syw = Math.sin(YAW), cp = Math.cos(PITCH), sp = Math.sin(PITCH);
        double nx = 0, nz = 0;
        for (int i = 0; i < 4; i++) {
            double x = p[i * 3], y = p[i * 3 + 1] - 22, z = p[i * 3 + 2];
            double x1 = x * cy + z * syw, z1 = -x * syw + z * cy;     // turn
            double y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;       // tilt (camera a little above)
            double f = CAM / (CAM - z2);                                // perspective
            q.xs[i] = x1 * f;
            q.ys[i] = -y2 * f;
            depth += -z2;
        }
        // skip faces pointing away from the camera
        double ax = q.xs[1] - q.xs[0], ay = q.ys[1] - q.ys[0], bx = q.xs[2] - q.xs[0], by = q.ys[2] - q.ys[0];
        if (ax * by - ay * bx <= 0) return;
        q.depth = depth / 4;
        q.col = (255 << 24) | (r << 16) | (g << 8) | b;
        out.add(q);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** Scanline fill of a convex quad. */
    private static void fillQuad(int[] buf, int W, int H, double[] px, double[] py, int col) {
        double minY = Math.min(Math.min(py[0], py[1]), Math.min(py[2], py[3])), maxY = Math.max(Math.max(py[0], py[1]), Math.max(py[2], py[3]));
        for (int y = Math.max(0, (int) Math.floor(minY)); y <= Math.min(H - 1, (int) Math.ceil(maxY)); y++) {
            double cyl = y + 0.5, lo = 1e9, hi = -1e9;
            for (int i = 0; i < 4; i++) {
                double x1 = px[i], y1 = py[i], x2 = px[(i + 1) % 4], y2 = py[(i + 1) % 4];
                if ((cyl >= Math.min(y1, y2)) && (cyl < Math.max(y1, y2))) {
                    double x = x1 + (cyl - y1) * (x2 - x1) / (y2 - y1);
                    lo = Math.min(lo, x); hi = Math.max(hi, x);
                }
            }
            if (lo > hi) continue;
            for (int x = Math.max(0, (int) Math.round(lo)); x < Math.min(W, (int) Math.round(hi + 0.5)); x++) buf[y * W + x] = col;
        }
    }
}
