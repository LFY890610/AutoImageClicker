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
     * Red-packet fallback detector.
     * Instead of accepting any orange rectangle, look for the distinctive combination:
     * a tall red envelope icon + yellow coin + orange card + nearby white text.
     */
    public static Match detectRedPacket(Bitmap source) {
        Frame f = Frame.from(source, 540);
        if (f == null) return null;
        int[] orange = integral(f, 0);
        int[] red = integral(f, 1);
        int[] white = integral(f, 2);
        int[] yellow = integral(f, 6);

        double best = 0;
        int bestX = 0, bestY = 0, bestW = 0, bestH = 0;
        double[] widthRatios = {0.055, 0.070, 0.085, 0.100, 0.115, 0.130, 0.150};
        double[] heightRatios = {1.18, 1.28, 1.38};
        int step = Math.max(2, f.w / 180);
        int xMin = Math.max(0, (int) (f.w * 0.01));
        int xMax = Math.min(f.w, (int) (f.w * 0.91));
        int yMin = Math.max(0, (int) (f.h * 0.05));
        int yMax = Math.min(f.h, (int) (f.h * 0.93));

        for (double wr : widthRatios) {
            int ww = Math.max(12, (int) (f.w * wr));
            for (double hr : heightRatios) {
                int hh = Math.max(14, (int) (ww * hr));
                if (ww >= f.w || hh >= f.h) continue;

                for (int y = yMin; y + hh < yMax; y += step) {
                    for (int x = xMin; x + ww < xMax; x += step) {
                        double redFrac = frac(red, f.w, x, y, ww, hh);
                        if (redFrac < 0.48) continue;

                        int coinW = Math.max(4, (int) (ww * 0.46));
                        int coinH = Math.max(4, (int) (hh * 0.38));
                        int coinX = x + (ww - coinW) / 2;
                        int coinY = y + (int) (hh * 0.23);
                        double yellowFrac = frac(yellow, f.w, coinX, coinY, coinW, coinH);
                        if (yellowFrac < 0.010) continue;

                        int rx = Math.max(0, x + (int) (ww * 0.82));
                        int ry = Math.max(0, y - (int) (hh * 0.10));
                        int rr = Math.min(f.w, x + (int) (ww * 4.35));
                        int rb = Math.min(f.h, y + (int) (hh * 1.08));
                        if (rr - rx < ww || rb - ry < hh / 2) continue;
                        double rightOrange = frac(orange, f.w, rx, ry, rr - rx, rb - ry);
                        double rightWhite = frac(white, f.w, rx, ry, rr - rx, rb - ry);
                        if (rightOrange < 0.16 || rightWhite < 0.008) continue;

                        int bx = Math.max(0, x - (int) (ww * 0.18));
                        int by = Math.min(f.h - 1, y + (int) (hh * 0.78));
                        int br = Math.min(f.w, x + (int) (ww * 4.25));
                        int bb = Math.min(f.h, y + (int) (hh * 1.72));
                        double lowerOrange = 0;
                        double lowerWhite = 0;
                        if (br > bx && bb > by) {
                            lowerOrange = frac(orange, f.w, bx, by, br - bx, bb - by);
                            lowerWhite = frac(white, f.w, bx, by, br - bx, bb - by);
                        }

                        double redScore = Math.min(1.0, redFrac / 0.72);
                        double yellowScore = Math.min(1.0, yellowFrac / 0.055);
                        double orangeScore = Math.min(1.0, Math.max(rightOrange, lowerOrange) / 0.58);
                        double whiteScore = Math.min(1.0, Math.max(rightWhite, lowerWhite) / 0.075);
                        double score = redScore * 0.43 + yellowScore * 0.25 + orangeScore * 0.20 + whiteScore * 0.12;

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
        if (best < 0.60) return null;
        float inv = 1f / scale;
        // Clicking the envelope icon itself is safely inside the red-packet bubble.
        return new Match((bestX + bestW * 0.50f) * inv, (bestY + bestH * 0.50f) * inv, best);
    }

    /**
     * Open-button fallback detector. Search only the central popup area and require a
     * circular beige body, dark center glyph and non-beige corners.
     */
    public static Match detectOpenButton(Bitmap source) {
        Frame f = Frame.from(source, 540);
        if (f == null) return null;
        int[] beige = integral(f, 3);
        int[] dark = integral(f, 4);
        int[] warm = integral(f, 5);

        double best = 0;
        int bestX = 0, bestY = 0, bestS = 0;
        double[] sizes = {0.14, 0.17, 0.20, 0.23, 0.26, 0.29, 0.32, 0.35, 0.38};
        int step = Math.max(2, f.w / 180);
        int xMin = (int) (f.w * 0.12);
        int xMax = (int) (f.w * 0.88);
        int yMin = (int) (f.h * 0.10);
        int yMax = (int) (f.h * 0.88);

        for (double sr : sizes) {
            int s = (int) (f.w * sr);
            if (s < 28 || s >= f.w) continue;
            for (int y = yMin; y + s < yMax; y += step) {
                for (int x = xMin; x + s < xMax; x += step) {
                    double wholeBeige = frac(beige, f.w, x, y, s, s);
                    if (wholeBeige < 0.50 || wholeBeige > 0.88) continue;

                    int m = Math.max(2, (int) (s * 0.20));
                    int inner = s - 2 * m;
                    if (inner <= 4) continue;
                    double innerBeige = frac(beige, f.w, x + m, y + m, inner, inner);
                    if (innerBeige < 0.66) continue;
                    double innerDark = frac(dark, f.w, x + m, y + m, inner, inner);
                    if (innerDark < 0.012 || innerDark > 0.26) continue;

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
                    if (sideBeige < 0.40) continue;

                    int margin = Math.max(3, (int) (s * 0.10));
                    int ex = Math.max(0, x - margin);
                    int ey = Math.max(0, y - margin);
                    int er = Math.min(f.w, x + s + margin);
                    int eb = Math.min(f.h, y + s + margin);
                    double expandedWarm = frac(warm, f.w, ex, ey, er - ex, eb - ey);

                    double bodyScore = Math.min(1.0, wholeBeige / 0.78);
                    double centerScore = Math.min(1.0, innerBeige / 0.90);
                    double glyphScore = Math.min(1.0, innerDark / 0.10);
                    double circleScore = Math.min(1.0, Math.max(0, sideBeige - cornerBeige) / 0.65);
                    double warmScore = Math.min(1.0, expandedWarm / 0.35);
                    double score = bodyScore * 0.31 + centerScore * 0.19 + glyphScore * 0.22 + circleScore * 0.22 + warmScore * 0.06;

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
        if (best < 0.62) return null;
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
            case 0: // orange/red-packet card background
                return r > 175 && g > 65 && g < 205 && b < 150 && r > g + 28 && g > b + 5;
            case 1: // red envelope icon
                return r > 175 && g < 135 && b < 140 && r > g + 48 && r > b + 45;
            case 2: // white text
                return r > 205 && g > 205 && b > 190 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 55;
            case 3: // beige/yellow open circle
                return r > 190 && g > 150 && g < 245 && b > 85 && b < 220 && r >= g - 3 && g > b + 8 && r > b + 25;
            case 4: // dark glyph
                return r < 145 && g < 145 && b < 145 && Math.max(r, Math.max(g, b)) < 155;
            case 5: // warm red/orange popup surrounding the circle
                return r > 155 && g < 195 && b < 165 && r > b + 28;
            case 6: // yellow coin on envelope icon
                return r > 205 && g > 155 && b < 105 && r > b + 110 && g > b + 70;
            default:
                return false;
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
