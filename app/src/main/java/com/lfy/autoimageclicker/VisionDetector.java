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
     * Strict WeChat red-packet fallback detector tuned for the supplied target layout.
     * A candidate must simultaneously contain:
     * 1) a wide orange card, 2) a red envelope on the left, 3) a yellow coin inside it,
     * 4) a white greeting region in the upper-right, and 5) a white "WeChat red packet"
     * label region below. This is intentionally stricter than simple color matching so that
     * colorful emoji/stickers are rejected.
     */
    public static Match detectRedPacket(Bitmap source) {
        Frame f = Frame.from(source, 480);
        if (f == null) return null;
        int[] orange = integral(f, 0);
        int[] red = integral(f, 1);
        int[] white = integral(f, 2);
        int[] yellow = integral(f, 6);

        double best = 0;
        int bestCardX = 0, bestCardY = 0, bestCardW = 0, bestCardH = 0;

        // Search by the distinctive left envelope icon, then validate the whole card geometry.
        double[] iconWidthRatios = {0.045, 0.055, 0.065, 0.075, 0.085, 0.095, 0.105, 0.115};
        double[] iconHeightRatios = {1.15, 1.24, 1.33, 1.42};
        double[] cardWidthMultipliers = {7.0, 7.6, 8.2, 8.8};
        double[] cardHeightMultipliers = {1.95, 2.10, 2.25, 2.40};
        int step = 3;
        int yMin = (int) (f.h * 0.04);
        int yMax = (int) (f.h * 0.94);

        for (double wr : iconWidthRatios) {
            int iw = Math.max(12, (int) (f.w * wr));
            for (double hr : iconHeightRatios) {
                int ih = Math.max(14, (int) (iw * hr));
                if (iw >= f.w || ih >= f.h) continue;

                for (int y = yMin; y + ih < yMax; y += step) {
                    for (int x = 0; x + iw < f.w; x += step) {
                        // The envelope itself must be strongly red.
                        double redFrac = frac(red, f.w, x, y, iw, ih);
                        if (redFrac < 0.53) continue;

                        // Its yellow coin must sit near the upper-middle of the envelope.
                        int coinW = Math.max(4, (int) (iw * 0.52));
                        int coinH = Math.max(4, (int) (ih * 0.36));
                        int coinX = x + (iw - coinW) / 2;
                        int coinY = y + (int) (ih * 0.24);
                        double yellowFrac = frac(yellow, f.w, coinX, coinY, coinW, coinH);
                        if (yellowFrac < 0.020) continue;

                        for (double cwm : cardWidthMultipliers) {
                            int cw = (int) (iw * cwm);
                            for (double chm : cardHeightMultipliers) {
                                int ch = (int) (ih * chm);

                                // In the supplied target the icon begins about 5% into the card.
                                int cx = x - (int) (iw * 0.38);
                                int cy = y - (int) (ih * 0.18);
                                if (cx < 0 || cy < 0 || cx + cw > f.w || cy + ch > f.h) continue;

                                double aspect = cw / (double) ch;
                                if (aspect < 2.35 || aspect > 3.55) continue;

                                // Whole-card orange occupancy must be high. Emoji/stickers usually
                                // fail here because they do not form a large rectangular card.
                                double wholeOrange = frac(orange, f.w, cx, cy, cw, ch);
                                if (wholeOrange < 0.49) continue;

                                // Validate the upper orange body in three separated bands, not just
                                // one local patch. This makes random orange stickers much less likely.
                                int upperH = (int) (ch * 0.68);
                                double leftUpperOrange = frac(orange, f.w,
                                        cx, cy, Math.max(1, (int) (cw * 0.24)), upperH);
                                double midUpperOrange = frac(orange, f.w,
                                        cx + (int) (cw * 0.24), cy,
                                        Math.max(1, (int) (cw * 0.38)), upperH);
                                double rightUpperOrange = frac(orange, f.w,
                                        cx + (int) (cw * 0.62), cy,
                                        Math.max(1, (int) (cw * 0.38)), upperH);
                                if (leftUpperOrange < 0.36 || midUpperOrange < 0.46 || rightUpperOrange < 0.46) continue;

                                // The red icon must be located in the left part of this very card.
                                int iconZoneX = cx + (int) (cw * 0.025);
                                int iconZoneY = cy + (int) (ch * 0.08);
                                int iconZoneW = Math.max(1, (int) (cw * 0.20));
                                int iconZoneH = Math.max(1, (int) (ch * 0.58));
                                double leftRed = frac(red, f.w, iconZoneX, iconZoneY, iconZoneW, iconZoneH);
                                double leftYellow = frac(yellow, f.w, iconZoneX, iconZoneY, iconZoneW, iconZoneH);
                                if (leftRed < 0.20 || leftYellow < 0.006) continue;

                                // Greeting: upper-right region must contain visible white text while
                                // still being predominantly orange.
                                int gx = cx + (int) (cw * 0.22);
                                int gy = cy + (int) (ch * 0.10);
                                int gw = Math.max(1, (int) (cw * 0.72));
                                int gh = Math.max(1, (int) (ch * 0.43));
                                double greetOrange = frac(orange, f.w, gx, gy, gw, gh);
                                double greetWhite = frac(white, f.w, gx, gy, gw, gh);
                                if (greetOrange < 0.44 || greetWhite < 0.020) continue;

                                // Label: lower-left strip must separately contain white text. Requiring
                                // two distinct white-text regions is a strong discriminator vs emoji.
                                int lx = cx + (int) (cw * 0.018);
                                int ly = cy + (int) (ch * 0.71);
                                int lw = Math.max(1, (int) (cw * 0.42));
                                int lh = Math.max(1, (int) (ch * 0.24));
                                double labelOrange = frac(orange, f.w, lx, ly, lw, lh);
                                double labelWhite = frac(white, f.w, lx, ly, lw, lh);
                                if (labelOrange < 0.25 || labelWhite < 0.015) continue;

                                // Right-lower region should remain mostly orange and not contain a
                                // second large red icon, which filters several common red/orange emoji.
                                int rlx = cx + (int) (cw * 0.48);
                                int rly = cy + (int) (ch * 0.68);
                                int rlw = Math.max(1, (int) (cw * 0.48));
                                int rlh = Math.max(1, (int) (ch * 0.27));
                                double lowerRightOrange = frac(orange, f.w, rlx, rly, rlw, rlh);
                                double lowerRightRed = frac(red, f.w, rlx, rly, rlw, rlh);
                                if (lowerRightOrange < 0.30 || lowerRightRed > 0.16) continue;

                                double geometryScore = 1.0 - Math.min(1.0, Math.abs(aspect - 2.84) / 1.15);
                                double redScore = Math.min(1.0, redFrac / 0.76);
                                double yellowScore = Math.min(1.0, yellowFrac / 0.070);
                                double wholeOrangeScore = Math.min(1.0, wholeOrange / 0.70);
                                double greetScore = Math.min(1.0, greetWhite / 0.075);
                                double labelScore = Math.min(1.0, labelWhite / 0.060);
                                double score = geometryScore * 0.16
                                        + redScore * 0.22
                                        + yellowScore * 0.18
                                        + wholeOrangeScore * 0.20
                                        + greetScore * 0.14
                                        + labelScore * 0.10;

                                if (score > best) {
                                    best = score;
                                    bestCardX = cx;
                                    bestCardY = cy;
                                    bestCardW = cw;
                                    bestCardH = ch;
                                }
                            }
                        }
                    }
                }
            }
        }

        float scale = f.scale;
        f.recycle();
        if (best < 0.72) return null;
        float inv = 1f / scale;
        // Click the center-right portion of the actual card, away from the small icon edge.
        return new Match((bestCardX + bestCardW * 0.56f) * inv,
                (bestCardY + bestCardH * 0.48f) * inv, best);
    }

    /**
     * WeChat open-button fallback detector. It only searches the central popup area and
     * requires a beige circular body, dark central glyph and warm red/orange surroundings.
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
                    double centerPenalty = Math.min(0.12, Math.abs(ccx - screenCx) / f.w * 0.35
                            + Math.abs(ccy - screenCy) / f.h * 0.10);
                    double score = bodyScore * 0.34 + glyphScore * 0.28
                            + circleScore * 0.26 + warmScore * 0.12 - centerPenalty;

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
        int sum = ii[y2 * stride + x2] - ii[y * stride + x2]
                - ii[y2 * stride + x] + ii[y * stride + x];
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
            case 4: return r < 145 && g < 145 && b < 145 && Math.max(r, Math.max(g, b)) < 155;
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
