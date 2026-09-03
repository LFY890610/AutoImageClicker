package com.lfy.autoimageclicker;

import android.graphics.Bitmap;

public final class VisionDetector {
    private VisionDetector() {}

    public static final class Match {
        public final float x;
        public final float y;
        public final double score;
        Match(float x, float y, double score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    /**
     * Fast strict WeChat red-packet detector.
     *
     * The old detector scanned many rectangle sizes across the whole screen. This version starts
     * from the rare yellow-coin feature, then verifies the red envelope and the two white-text
     * zones around it. That cuts candidate work dramatically while still rejecting emoji/stickers.
     */
    public static Match detectRedPacket(Bitmap source) {
        Frame f = Frame.from(source, 420);
        if (f == null) return null;

        int[] orange = integral(f, 0);
        int[] red = integral(f, 1);
        int[] white = integral(f, 2);
        int[] yellow = integral(f, 6);

        double best = 0;
        int bestX = 0, bestY = 0, bestW = 0, bestH = 0;

        // The supplied WeChat packet uses a small portrait red-envelope icon at the left of a
        // much wider orange card. Five icon scales are enough for common phone DPIs.
        double[] widthRatios = {0.055, 0.070, 0.085, 0.100, 0.115};
        int step = Math.max(4, f.w / 120);
        int xMin = 0;
        int xMax = (int) (f.w * 0.94);
        int yMin = (int) (f.h * 0.05);
        int yMax = (int) (f.h * 0.93);

        for (double wr : widthRatios) {
            int ww = Math.max(14, (int) (f.w * wr));
            int hh = Math.max(17, (int) (ww * 1.22));
            if (ww >= f.w || hh >= f.h) continue;

            int coinW = Math.max(6, (int) (ww * 0.52));
            int coinH = Math.max(6, (int) (hh * 0.42));
            int coinOffX = (ww - coinW) / 2;
            int coinOffY = (int) (hh * 0.27);

            for (int y = yMin; y + hh < yMax; y += step) {
                for (int x = xMin; x + ww < xMax; x += step) {
                    // Cheap rare-feature gate first. Most screen locations die here after one O(1)
                    // integral lookup, instead of running the full packet checks.
                    double yellowFrac = frac(yellow, f.w,
                            x + coinOffX, y + coinOffY, coinW, coinH);
                    if (yellowFrac < 0.055) continue;

                    double redFrac = frac(red, f.w, x, y, ww, hh);
                    if (redFrac < 0.44) continue;

                    // Full orange card around the icon. Stickers normally fail this because they
                    // do not contain a large, nearly uniform, wide orange message card.
                    int cardX = Math.max(0, x - (int) (ww * 0.25));
                    int cardY = Math.max(0, y - (int) (hh * 0.18));
                    int cardR = Math.min(f.w, x + (int) (ww * 6.35));
                    int cardB = Math.min(f.h, y + (int) (hh * 2.05));
                    if (cardR - cardX < ww * 4 || cardB - cardY < hh) continue;
                    double cardOrange = frac(orange, f.w,
                            cardX, cardY, cardR - cardX, cardB - cardY);
                    if (cardOrange < 0.43) continue;

                    // Top-right white greeting: “恭喜发财，大吉大利”. We do not OCR it here;
                    // accessibility handles exact text when available, while image fallback checks
                    // the fixed white-text density/placement.
                    int gx = Math.max(0, x + (int) (ww * 1.20));
                    int gy = Math.max(0, y - (int) (hh * 0.04));
                    int gr = Math.min(f.w, x + (int) (ww * 6.15));
                    int gb = Math.min(f.h, y + (int) (hh * 0.95));
                    if (gr <= gx || gb <= gy) continue;
                    double greetingWhite = frac(white, f.w, gx, gy, gr - gx, gb - gy);
                    double greetingOrange = frac(orange, f.w, gx, gy, gr - gx, gb - gy);
                    if (greetingWhite < 0.018 || greetingOrange < 0.40) continue;

                    // Separate lower “微信红包” line. Requiring a second white-text band at the
                    // correct vertical offset is the strongest anti-emoji/sticker discriminator.
                    int lx = Math.max(0, x - (int) (ww * 0.15));
                    int ly = Math.max(0, y + (int) (hh * 1.06));
                    int lr = Math.min(f.w, x + (int) (ww * 4.55));
                    int lb = Math.min(f.h, y + (int) (hh * 1.88));
                    if (lr <= lx || lb <= ly) continue;
                    double labelWhite = frac(white, f.w, lx, ly, lr - lx, lb - ly);
                    double labelOrange = frac(orange, f.w, lx, ly, lr - lx, lb - ly);
                    if (labelWhite < 0.011 || labelOrange < 0.33) continue;

                    double redScore = Math.min(1.0, redFrac / 0.70);
                    double coinScore = Math.min(1.0, yellowFrac / 0.30);
                    double cardScore = Math.min(1.0, cardOrange / 0.67);
                    double greetingScore = Math.min(1.0, greetingWhite / 0.075);
                    double labelScore = Math.min(1.0, labelWhite / 0.060);
                    double score = redScore * 0.25
                            + coinScore * 0.22
                            + cardScore * 0.22
                            + greetingScore * 0.17
                            + labelScore * 0.14;

                    if (score > best) {
                        best = score;
                        bestX = x;
                        bestY = y;
                        bestW = ww;
                        bestH = hh;
                    }
                }
            }
        }

        float scale = f.scale;
        f.recycle();
        if (best < 0.66) return null;

        float inv = 1f / scale;
        // Tap inside the orange card rather than on the small red icon edge.
        return new Match(
                (bestX + bestW * 3.10f) * inv,
                (bestY + bestH * 0.82f) * inv,
                best);
    }

    /**
     * WeChat open-button fallback detector. It only searches the central popup area and requires
     * a beige circular body, dark central glyph and warm red/orange surroundings.
     */
    public static Match detectOpenButton(Bitmap source) {
        Frame f = Frame.from(source, 480);
        if (f == null) return null;
        int[] beige = integral(f, 3);
        int[] dark = integral(f, 4);
        int[] warm = integral(f, 5);

        double best = 0;
        int bestX = 0, bestY = 0, bestS = 0;
        double[] sizes = {0.08, 0.10, 0.12, 0.14, 0.16, 0.18, 0.20, 0.23, 0.26};
        int step = 2;
        int xMin = (int) (f.w * 0.25);
        int xMax = (int) (f.w * 0.75);
        int yMin = (int) (f.h * 0.20);
        int yMax = (int) (f.h * 0.76);
        float screenCx = f.w * 0.5f;
        float screenCy = f.h * 0.50f;

        for (double sr : sizes) {
            int s = (int) (f.w * sr);
            if (s < 26 || s >= f.w) continue;
            for (int y = yMin; y + s < yMax; y += step) {
                for (int x = xMin; x + s < xMax; x += step) {
                    float ccx = x + s * 0.5f;
                    float ccy = y + s * 0.5f;
                    if (Math.abs(ccx - screenCx) > f.w * 0.19f) continue;

                    double wholeBeige = frac(beige, f.w, x, y, s, s);
                    if (wholeBeige < 0.48 || wholeBeige > 0.88) continue;

                    int m = Math.max(2, (int) (s * 0.20));
                    int inner = s - 2 * m;
                    if (inner <= 4) continue;
                    double innerBeige = frac(beige, f.w, x + m, y + m, inner, inner);
                    if (innerBeige < 0.64) continue;
                    double innerDark = frac(dark, f.w, x + m, y + m, inner, inner);
                    if (innerDark < 0.010 || innerDark > 0.28) continue;

                    int c = Math.max(3, (int) (s * 0.18));
                    double cornerBeige = (
                            frac(beige, f.w, x, y, c, c) +
                            frac(beige, f.w, x + s - c, y, c, c) +
                            frac(beige, f.w, x, y + s - c, c, c) +
                            frac(beige, f.w, x + s - c, y + s - c, c, c)
                    ) / 4.0;
                    if (cornerBeige > 0.38) continue;

                    int p = Math.max(3, (int) (s * 0.17));
                    int mid = Math.max(0, (s - p) / 2);
                    double sideBeige = (
                            frac(beige, f.w, x, y + mid, p, p) +
                            frac(beige, f.w, x + s - p, y + mid, p, p) +
                            frac(beige, f.w, x + mid, y, p, p) +
                            frac(beige, f.w, x + mid, y + s - p, p, p)
                    ) / 4.0;
                    if (sideBeige < 0.38) continue;

                    int margin = Math.max(3, (int) (s * 0.12));
                    int ex = Math.max(0, x - margin);
                    int ey = Math.max(0, y - margin);
                    int er = Math.min(f.w, x + s + margin);
                    int eb = Math.min(f.h, y + s + margin);
                    double expandedWarm = frac(warm, f.w, ex, ey, er - ex, eb - ey);
                    if (expandedWarm < 0.08) continue;

                    double bodyScore = Math.min(1.0, wholeBeige / 0.77);
                    double glyphScore = Math.min(1.0, innerDark / 0.10);
                    double circleScore = Math.min(1.0, Math.max(0, sideBeige - cornerBeige) / 0.62);
                    double warmScore = Math.min(1.0, expandedWarm / 0.34);
                    double centerPenalty = Math.min(0.12,
                            Math.abs(ccx - screenCx) / f.w * 0.35
                                    + Math.abs(ccy - screenCy) / f.h * 0.10);
                    double score = bodyScore * 0.34
                            + glyphScore * 0.28
                            + circleScore * 0.26
                            + warmScore * 0.12
                            - centerPenalty;

                    if (score > best) {
                        best = score;
                        bestX = x;
                        bestY = y;
                        bestS = s;
                    }
                }
            }
        }

        float scale = f.scale;
        f.recycle();
        if (best < 0.61) return null;
        float inv = 1f / scale;
        return new Match((bestX + bestS * 0.50f) * inv,
                (bestY + bestS * 0.50f) * inv, best);
    }

    private static int[] integral(Frame f, int kind) {
        int stride = f.w + 1;
        int[] out = new int[(f.w + 1) * (f.h + 1)];
        for (int y = 1; y <= f.h; y++) {
            int rowSum = 0;
            int rowBase = (y - 1) * f.w;
            for (int x = 1; x <= f.w; x++) {
                int color = f.pixels[rowBase + x - 1];
                if (matchesColor(color, kind)) rowSum++;
                out[y * stride + x] = out[(y - 1) * stride + x] + rowSum;
            }
        }
        return out;
    }

    private static double frac(int[] ii, int w, int x, int y, int rw, int rh) {
        if (rw <= 0 || rh <= 0) return 0;
        int maxH = ii.length / (w + 1) - 1;
        x = Math.max(0, Math.min(w, x));
        y = Math.max(0, Math.min(maxH, y));
        int x2 = Math.max(x, Math.min(w, x + rw));
        int y2 = Math.max(y, Math.min(maxH, y + rh));
        if (x2 <= x || y2 <= y) return 0;
        int stride = w + 1;
        int sum = ii[y2 * stride + x2]
                - ii[y * stride + x2]
                - ii[y2 * stride + x]
                + ii[y * stride + x];
        return sum / (double) ((x2 - x) * (y2 - y));
    }

    private static boolean matchesColor(int c, int kind) {
        int r = (c >> 16) & 255;
        int g = (c >> 8) & 255;
        int b = c & 255;
        switch (kind) {
            case 0: return r > 175 && g > 65 && g < 210 && b < 155 && r > g + 25 && g > b + 3;
            case 1: return r > 175 && g < 140 && b < 145 && r > g + 45 && r > b + 42;
            case 2: return r > 205 && g > 205 && b > 190
                    && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 55;
            case 3: return r > 190 && g > 150 && g < 245 && b > 85 && b < 220
                    && r >= g - 3 && g > b + 8 && r > b + 25;
            case 4: return r < 145 && g < 145 && b < 145
                    && Math.max(r, Math.max(g, b)) < 155;
            case 5: return r > 155 && g < 200 && b < 170 && r > b + 25;
            case 6: return r > 205 && g > 155 && b < 110 && r > b + 105 && g > b + 65;
            default: return false;
        }
    }

    private static final class Frame {
        final Bitmap bitmap;
        final int[] pixels;
        final int w;
        final int h;
        final float scale;

        private Frame(Bitmap bitmap, int[] pixels, int w, int h, float scale) {
            this.bitmap = bitmap;
            this.pixels = pixels;
            this.w = w;
            this.h = h;
            this.scale = scale;
        }

        static Frame from(Bitmap source, int maxWidth) {
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
            float scale = Math.min(1f, maxWidth / (float) source.getWidth());
            int w = Math.max(1, Math.round(source.getWidth() * scale));
            int h = Math.max(1, Math.round(source.getHeight() * scale));
            Bitmap small = scale == 1f
                    ? source.copy(Bitmap.Config.ARGB_8888, false)
                    : Bitmap.createScaledBitmap(source, w, h, false);
            int[] p = new int[w * h];
            small.getPixels(p, 0, w, 0, 0, w, h);
            return new Frame(small, p, w, h, scale);
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}
