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
     * WeChat red-packet fallback detector tuned for the supplied target:
     * orange card + tall red envelope + yellow coin + white greeting/label.
     */
    public static Match detectRedPacket(Bitmap source) {
        Frame f = Frame.from(source, 480);
        if (f == null) return null;
        int[] orange = integral(f, 0);
        int[] red = integral(f, 1);
        int[] white = integral(f, 2);
        int[] yellow = integral(f, 6);

        double best = 0;
        int bestX = 0, bestY = 0, bestW = 0, bestH = 0;
        double[] widthRatios = {0.045, 0.060, 0.075, 0.090, 0.105, 0.120};
        double[] heightRatios = {1.15, 1.25, 1.35};
        int step = 3;
        int xMin = 0;
        int xMax = (int) (f.w * 0.94);
        int yMin = (int) (f.h * 0.04);
        int yMax = (int) (f.h * 0.94);

        for (double wr : widthRatios) {
            int ww = Math.max(12, (int) (f.w * wr));
            for (double hr : heightRatios) {
                int hh = Math.max(14, (int) (ww * hr));
                if (ww >= f.w || hh >= f.h) continue;

                for (int y = yMin; y + hh < yMax; y += step) {
                    for (int x = xMin; x + ww < xMax; x += step) {
                        double redFrac = frac(red, f.w, x, y, ww, hh);
                        if (redFrac < 0.48) continue;

                        int coinW = Math.max(4, (int) (ww * 0.48));
                        int coinH = Math.max(4, (int) (hh * 0.42));
                        int coinX = x + (ww - coinW) / 2;
                        int coinY = y + (int) (hh * 0.20);
                        double yellowFrac = frac(yellow, f.w, coinX, coinY, coinW, coinH);
                        if (yellowFrac < 0.012) continue;

                        // Greeting text lies to the right of the envelope.
                        int tx = Math.max(0, x + (int) (ww * 1.05));
                        int ty = Math.max(0, y - (int) (hh * 0.10));
                        int tr = Math.min(f.w, x + (int) (ww * 6.60));
                        int tb = Math.min(f.h, y + (int) (hh * 1.10));
                        if (tr <= tx || tb <= ty) continue;
                        double textOrange = frac(orange, f.w, tx, ty, tr - tx, tb - ty);
                        double textWhite = frac(white, f.w, tx, ty, tr - tx, tb - ty);
                        if (textOrange < 0.20 || textWhite < 0.010) continue;

                        // “微信红包” label is below the greeting line in the supplied target.
                        int lx = Math.max(0, x - (int) (ww * 0.20));
                        int ly = Math.max(0, y + (int) (hh * 1.05));
                        int lr = Math.min(f.w, x + (int) (ww * 4.50));
                        int lb = Math.min(f.h, y + (int) (hh * 1.90));
                        double labelOrange = 0;
                        double labelWhite = 0;
                        if (lr > lx && lb > ly) {
                            labelOrange = frac(orange, f.w, lx, ly, lr - lx, lb - ly);
                            labelWhite = frac(white, f.w, lx, ly, lr - lx, lb - ly);
                        }

                        double redScore = Math.min(1.0, redFrac / 0.74);
                        double yellowScore = Math.min(1.0, yellowFrac / 0.060);
                        double orangeScore = Math.min(1.0, Math.max(textOrange, labelOrange) / 0.62);
                        double whiteScore = Math.min(1.0, Math.max(textWhite, labelWhite) / 0.080);
                        double score = redScore * 0.42 + yellowScore * 0.27 + orangeScore * 0.18 + whiteScore * 0.13;

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
        }

        float scale = f.scale;
        f.recycle();
        if (best < 0.64) return null;
        float inv = 1f / scale;
        // Click well inside the orange card, to the right of the envelope icon.
        return new Match((bestX + bestW * 3.25f) * inv, (bestY + bestH * 0.82f) * inv, best);
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
                    double centerPenalty = Math.min(0.12, Math.abs(ccx - screenCx) / f.w * 0.35 + Math.abs(ccy - screenCy) / f.h * 0.10);
                    double score = bodyScore * 0.34 + glyphScore * 0.28 + circleScore * 0.26 + warmScore * 0.12 - centerPenalty;

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
        return new Match((bestX + bestS * 0.50f) * inv, (bestY + bestS * 0.50f) * inv, best);
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
        int sum = ii[y2 * stride + x2] - ii[y * stride + x2] - ii[y2 * stride + x] + ii[y * stride + x];
        return sum / (double) ((x2 - x) * (y2 - y));
    }

    private static boolean matchesColor(int c, int kind) {
        int r = (c >> 16) & 255;
        int g = (c >> 8) & 255;
        int b = c & 255;
        switch (kind) {
            case 0: return r > 175 && g > 65 && g < 210 && b < 155 && r > g + 25 && g > b + 3;
            case 1: return r > 175 && g < 140 && b < 145 && r > g + 45 && r > b + 42;
            case 2: return r > 205 && g > 205 && b > 190 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 55;
            case 3: return r > 190 && g > 150 && g < 245 && b > 85 && b < 220 && r >= g - 3 && g > b + 8 && r > b + 25;
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
            Bitmap small = scale == 1f ? source.copy(Bitmap.Config.ARGB_8888, false) : Bitmap.createScaledBitmap(source, w, h, false);
            int[] p = new int[w * h];
            small.getPixels(p, 0, w, 0, 0, w, h);
            return new Frame(small, p, w, h, scale);
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}
