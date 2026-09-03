package com.lfy.autoimageclicker;

import android.graphics.Bitmap;
import android.graphics.Rect;

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

    public static Match detectRedPacket(Bitmap source) {
        Frame f = Frame.from(source, 360);
        if (f == null) return null;
        int[] orange = integral(f, 0);
        int[] red = integral(f, 1);
        int[] white = integral(f, 2);

        double best = 0;
        int bestX = 0, bestY = 0, bestW = 0, bestH = 0;
        double[] widths = {0.30, 0.38, 0.46, 0.54, 0.62, 0.70, 0.78};
        double[] aspects = {0.31, 0.36, 0.41};
        int step = Math.max(3, f.w / 90);
        int yMin = (int) (f.h * 0.06);
        int yMax = (int) (f.h * 0.92);

        for (double wr : widths) {
            int ww = (int) (f.w * wr);
            for (double ar : aspects) {
                int hh = (int) (ww * ar);
                if (ww < 40 || hh < 20 || ww >= f.w || hh >= f.h) continue;
                for (int y = yMin; y + hh < yMax; y += step) {
                    for (int x = 0; x + ww < f.w; x += step) {
                        double of = frac(orange, f.w, x, y, ww, hh);
                        if (of < 0.34) continue;
                        int lw = Math.max(1, (int) (ww * 0.38));
                        double rf = frac(red, f.w, x, y, lw, hh);
                        if (rf < 0.018) continue;
                        double wf = frac(white, f.w, x, y, ww, hh);
                        if (wf < 0.008) continue;

                        double redScore = Math.min(1.0, rf * 8.0);
                        double whiteScore = Math.min(1.0, wf * 8.0);
                        double score = of * 0.66 + redScore * 0.22 + whiteScore * 0.12;
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

        f.recycle();
        if (best < 0.48) return null;
        float sx = 1f / f.scale;
        return new Match((bestX + bestW * 0.52f) * sx, (bestY + bestH * 0.50f) * sx, best);
    }

    public static Match detectOpenButton(Bitmap source) {
        Frame f = Frame.from(source, 360);
        if (f == null) return null;
        int[] beige = integral(f, 3);
        int[] dark = integral(f, 4);
        int[] warm = integral(f, 5);

        double best = 0;
        int bestX = 0, bestY = 0, bestS = 0;
        double[] sizes = {0.12, 0.15, 0.18, 0.21, 0.24, 0.27, 0.30, 0.33};
        int step = Math.max(3, f.w / 100);
        int xMin = (int) (f.w * 0.10);
        int xMax = (int) (f.w * 0.90);
        int yMin = (int) (f.h * 0.08);
        int yMax = (int) (f.h * 0.92);

        for (double sr : sizes) {
            int s = (int) (f.w * sr);
            if (s < 30 || s >= f.w) continue;
            for (int y = yMin; y + s < yMax; y += step) {
                for (int x = xMin; x + s < xMax; x += step) {
                    double wholeBeige = frac(beige, f.w, x, y, s, s);
                    if (wholeBeige < 0.42 || wholeBeige > 0.88) continue;

                    int m = (int) (s * 0.20);
                    int cs = s - 2 * m;
                    if (cs <= 0) continue;
                    double centerBeige = frac(beige, f.w, x + m, y + m, cs, cs);
                    if (centerBeige < 0.55) continue;
                    double centerDark = frac(dark, f.w, x + m, y + m, cs, cs);
                    if (centerDark < 0.012 || centerDark > 0.28) continue;
                    if (centerBeige - wholeBeige < 0.035) continue;

                    int margin = Math.max(3, (int) (s * 0.12));
                    int ex = Math.max(0, x - margin);
                    int ey = Math.max(0, y - margin);
                    int er = Math.min(f.w, x + s + margin);
                    int eb = Math.min(f.h, y + s + margin);
                    double expandedWarm = frac(warm, f.w, ex, ey, er - ex, eb - ey);
                    double warmScore = Math.min(1.0, expandedWarm * 2.2);
                    double darkScore = Math.min(1.0, centerDark * 7.0);
                    double circleScore = Math.min(1.0, (centerBeige - wholeBeige) * 5.0);
                    double score = wholeBeige * 0.50 + centerBeige * 0.20 + darkScore * 0.12 + circleScore * 0.10 + warmScore * 0.08;

                    if (score > best) {
                        best = score;
                        bestX = x;
                        bestY = y;
                        bestS = s;
                    }
                }
            }
        }

        f.recycle();
        if (best < 0.57) return null;
        float sx = 1f / f.scale;
        return new Match((bestX + bestS / 2f) * sx, (bestY + bestS / 2f) * sx, best);
    }

    private static int[] integral(Frame f, int kind) {
        int stride = f.w + 1;
        int[] out = new int[(f.w + 1) * (f.h + 1)];
        for (int y = 1; y <= f.h; y++) {
            int rowSum = 0;
            int rowBase = (y - 1) * f.w;
            for (int x = 1; x <= f.w; x++) {
                int c = f.pixels[rowBase + x - 1];
                if (matchesColor(c, kind)) rowSum++;
                out[y * stride + x] = out[(y - 1) * stride + x] + rowSum;
            }
        }
        return out;
    }

    private static double frac(int[] ii, int w, int x, int y, int rw, int rh) {
        if (rw <= 0 || rh <= 0) return 0;
        int stride = w + 1;
        int x2 = x + rw;
        int y2 = y + rh;
        int sum = ii[y2 * stride + x2] - ii[y * stride + x2] - ii[y2 * stride + x] + ii[y * stride + x];
        return sum / (double) (rw * rh);
    }

    private static boolean matchesColor(int c, int kind) {
        int r = (c >> 16) & 255;
        int g = (c >> 8) & 255;
        int b = c & 255;
        switch (kind) {
            case 0: // orange/red packet background
                return r > 175 && g > 65 && g < 195 && b < 140 && r > g + 35 && g > b + 10;
            case 1: // red packet icon
                return r > 170 && g < 125 && b < 125 && r > g + 55 && r > b + 55;
            case 2: // white text
                return r > 205 && g > 205 && b > 195 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 45;
            case 3: // beige/yellow open circle
                return r > 195 && g > 160 && g < 240 && b > 90 && b < 215 && r >= g && g > b + 12 && r > b + 30;
            case 4: // dark character in circle
                return r < 140 && g < 140 && b < 140;
            case 5: // warm red/orange popup surrounding the circle
                return r > 160 && g < 190 && b < 150 && r > b + 35;
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
            Bitmap small = scale == 1f ? source.copy(Bitmap.Config.ARGB_8888, false) : Bitmap.createScaledBitmap(source, w, h, true);
            int[] p = new int[w * h];
            small.getPixels(p, 0, w, 0, 0, w, h);
            return new Frame(small, p, w, h, scale);
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}
