// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Turns an app icon into Flyme 12 style layers.
 *
 * Flyme's own night icons keep the brand colour in the glyph and put it on the theme's dark
 * gradient (the bg layer is omitted, so the launcher uses night background.png). Day icons are
 * the brand icons themselves; legacy (non-adaptive) icons get rebuilt into proper layers.
 * Glyph size reference: the max side of Flyme's glyphs is ~0.51 of the 432 px layer (median).
 */
final class Generator {
    static final int S = Flyme.SIZE;
    /** Visible part of an adaptive layer: the middle 72dp of 108dp. */
    static final int VIS0 = 72, VIS1 = 360;
    /** Middle colour of the theme backgrounds. */
    static final int NIGHT_BG = 0xff242424, DAY_BG = 0xfffdfdfd;
    static final int NEUTRAL_NIGHT = 0xffeeeeee, NEUTRAL_DAY = 0xff1c1c1e;
    static final float MONO_SIZE = 0.44f, FREEFORM_SIZE = 0.56f, WHOLE_SIZE = 0.60f;

    static final int BG_NONE = 0, BG_LIGHT = 1, BG_COLOR = 2, BG_DARK = 3, BG_COMPLEX = 4;

    static final class Analysis {
        boolean legacy, tile, hasMono;
        int bgKind = BG_NONE;
        int bgColor;
        /** 0 = achromatic icon. */
        int brand;
        Bitmap bgLayer, fgLayer, mono;
    }

    static final class Result {
        Bitmap dayFg, dayBg, nightFg, nightBg;
        int glyph, day, brand;
    }

    private Generator() {}

    // ------------------------------------------------------------------ analysis

    static Analysis analyze(IconSource src) {
        Analysis a = new Analysis();
        if (src.adaptive) {
            a.bgLayer = src.bg;
            a.fgLayer = src.fg;
            a.mono = src.mono;
            classifyBg(a, a.bgLayer);
            if (a.fgLayer != null && coverage(a.fgLayer, VIS0, VIS0, VIS1, VIS1) > 0.85f) {
                // the foreground is itself a full tile (legacy art packed into an adaptive icon)
                Bitmap comp = composite(a.bgLayer, a.fgLayer);
                if (!extractTile(a, comp, new Rect(VIS0, VIS0, VIS1, VIS1), null)) {
                    a.bgLayer = comp;
                    a.fgLayer = null;
                    a.bgKind = BG_COMPLEX;
                }
            }
        } else {
            a.legacy = true;
            Bitmap l = src.legacy;
            Rect b = alphaBounds(l, 128);
            if (b == null) {
                a.bgKind = BG_NONE;
            } else {
                int w = b.width(), h = b.height();
                boolean square = Math.abs(w - h) < 0.12f * Math.max(w, h);
                float cov = coverage(l, b.left, b.top, b.right, b.bottom);
                RectF dst = new RectF(VIS0 - 6, VIS0 - 6, VIS1 + 6, VIS1 + 6);
                if (square && cov > 0.80f) {
                    if (!extractTile(a, l, b, dst)) {
                        a.bgLayer = transform(l, b, dst);
                        a.fgLayer = null;
                        a.bgKind = BG_COMPLEX;
                    }
                } else {
                    float side = FREEFORM_SIZE * S;
                    float k = side / Math.max(w, h);
                    float cx = S / 2f, cy = S / 2f;
                    a.fgLayer = transform(l, b, new RectF(cx - w * k / 2, cy - h * k / 2, cx + w * k / 2, cy + h * k / 2));
                    a.bgLayer = null;
                    a.bgKind = BG_NONE;
                }
            }
        }
        a.hasMono = a.mono != null && alphaBounds(a.mono, 16) != null;
        if (!a.hasMono) a.mono = null;
        a.brand = pickBrand(a);
        return a;
    }

    /** Split a full tile into a solid background and the glyph drawn over it. */
    private static boolean extractTile(Analysis a, Bitmap img, Rect b, RectF dst) {
        int w = b.width();
        int[] px = new int[S * S];
        img.getPixels(px, 0, S, 0, 0, S, S);
        // ring just inside the tile edge
        int in0 = Math.round(w * 0.05f), in1 = Math.round(w * 0.12f);
        int[] hist = new int[4096];
        int n = 0;
        for (int y = b.top; y < b.bottom; y += 2) {
            for (int x = b.left; x < b.right; x += 2) {
                int dx = Math.min(x - b.left, b.right - 1 - x), dy = Math.min(y - b.top, b.bottom - 1 - y);
                int d = Math.min(dx, dy);
                if (d < in0 || d > in1) continue;
                int c = px[y * S + x];
                if (Color.alpha(c) < 250) continue;
                hist[((Color.red(c) >> 4) << 8) | ((Color.green(c) >> 4) << 4) | (Color.blue(c) >> 4)]++;
                n++;
            }
        }
        if (n < 50) return false;
        int best = 0;
        for (int i = 1; i < hist.length; i++) if (hist[i] > hist[best]) best = i;
        int approx = Color.rgb(((best >> 8) & 15) * 16 + 8, ((best >> 4) & 15) * 16 + 8, (best & 15) * 16 + 8);
        long sr = 0, sg = 0, sb = 0;
        int near = 0, total = 0;
        for (int y = b.top; y < b.bottom; y += 2) {
            for (int x = b.left; x < b.right; x += 2) {
                int dx = Math.min(x - b.left, b.right - 1 - x), dy = Math.min(y - b.top, b.bottom - 1 - y);
                int d = Math.min(dx, dy);
                if (d < in0 || d > in1) continue;
                int c = px[y * S + x];
                if (Color.alpha(c) < 250) continue;
                total++;
                if (ColorKit.dist(c, approx) < 28) {
                    near++;
                    sr += Color.red(c);
                    sg += Color.green(c);
                    sb += Color.blue(c);
                }
            }
        }
        if (near < total * 0.6f) return false;
        int bg = Color.rgb((int) (sr / near), (int) (sg / near), (int) (sb / near));
        int[] out = new int[S * S];
        for (int y = b.top; y < b.bottom; y++) {
            for (int x = b.left; x < b.right; x++) {
                int c = px[y * S + x];
                int al = Color.alpha(c);
                if (al == 0) continue;
                float d = ColorKit.dist(c, bg);
                float k = smooth(16f, 44f, d);
                if (k <= 0) continue;
                out[y * S + x] = Color.argb(Math.round(al * k), Color.red(c), Color.green(c), Color.blue(c));
            }
        }
        Bitmap glyph = Bitmap.createBitmap(out, S, S, Bitmap.Config.ARGB_8888);
        a.tile = true;
        a.bgColor = bg;
        a.fgLayer = dst == null ? glyph : transform(glyph, b, dst);
        a.bgLayer = solid(bg);
        a.bgKind = kindOf(bg);
        return true;
    }

    private static void classifyBg(Analysis a, Bitmap bg) {
        if (bg == null) {
            a.bgKind = BG_NONE;
            return;
        }
        int[] px = new int[S * S];
        bg.getPixels(px, 0, S, 0, 0, S, S);
        int lo = VIS0 + 12, hi = VIS1 - 12;
        double r = 0, g = 0, bl = 0, alpha = 0;
        int n = 0;
        for (int y = lo; y < hi; y += 2)
            for (int x = lo; x < hi; x += 2) {
                int c = px[y * S + x];
                alpha += Color.alpha(c);
                r += Color.red(c);
                g += Color.green(c);
                bl += Color.blue(c);
                n++;
            }
        if (alpha / n < 128) {
            a.bgKind = BG_NONE;
            return;
        }
        int mean = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        double sd = 0, grad = 0;
        int gn = 0;
        for (int y = lo; y < hi; y += 2)
            for (int x = lo; x < hi; x += 2) {
                int c = px[y * S + x];
                sd += ColorKit.dist(c, mean);
                if (x + 4 < hi) {
                    grad += ColorKit.dist(c, px[y * S + x + 4]);
                    gn++;
                }
            }
        sd /= n;
        grad /= Math.max(1, gn);
        if (sd < 14 || grad < 5) {
            a.bgColor = sd < 14 ? mean : mostSaturated(px, lo, hi, mean);
            a.bgKind = kindOf(a.bgColor);
        } else {
            a.bgColor = mean;
            a.bgKind = BG_COMPLEX;
        }
    }

    private static int kindOf(int c) {
        float l = ColorKit.light(c), s = ColorKit.sat(c);
        if (l > 0.82f && s < 0.15f) return BG_LIGHT;
        if (l < 0.13f) return BG_DARK;
        return BG_COLOR;
    }

    private static int mostSaturated(int[] px, int lo, int hi, int fallback) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        float best = 0;
        for (int y = lo; y < hi; y += 4)
            for (int x = lo; x < hi; x += 4) best = Math.max(best, ColorKit.sat(px[y * S + x]));
        for (int y = lo; y < hi; y += 4)
            for (int x = lo; x < hi; x += 4) {
                int c = px[y * S + x];
                if (ColorKit.sat(c) >= best * 0.85f) {
                    r += Color.red(c);
                    g += Color.green(c);
                    b += Color.blue(c);
                    n++;
                }
            }
        return n == 0 ? fallback : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /** Dominant chromatic colour: the background if it is coloured, else the glyph's. */
    private static int pickBrand(Analysis a) {
        if (a.bgKind == BG_COLOR) return a.bgColor;
        Bitmap src = a.fgLayer != null ? a.fgLayer : a.bgLayer;
        if (src == null) return 0;
        int[] px = new int[S * S];
        src.getPixels(px, 0, S, 0, 0, S, S);
        float[] w = new float[24];
        double[][] sum = new double[24][3];
        double total = 0;
        for (int i = 0; i < px.length; i += 3) {
            int c = px[i];
            float al = Color.alpha(c) / 255f;
            if (al < 0.6f) continue;
            total += al;
            float s = ColorKit.sat(c), l = ColorKit.light(c);
            if (s < 0.28f || l < 0.12f || l > 0.97f) continue;
            float[] hsl = ColorKit.toHsl(c);
            int bin = Math.min(23, (int) (hsl[0] / 15f));
            float wt = al * s;
            w[bin] += wt;
            sum[bin][0] += Color.red(c) * wt;
            sum[bin][1] += Color.green(c) * wt;
            sum[bin][2] += Color.blue(c) * wt;
        }
        int best = 0;
        for (int i = 1; i < 24; i++) if (w[i] > w[best]) best = i;
        // merge the neighbouring hue bins
        float ww = 0;
        double r = 0, g = 0, b = 0;
        for (int k = -1; k <= 1; k++) {
            int i = (best + k + 24) % 24;
            ww += w[i];
            r += sum[i][0];
            g += sum[i][1];
            b += sum[i][2];
        }
        if (total == 0 || ww < total * 0.04) return 0;
        return Color.rgb((int) (r / ww), (int) (g / ww), (int) (b / ww));
    }

    // ------------------------------------------------------------------ synthesis

    static Result make(Analysis a, Options o) {
        Result r = new Result();
        int brand = o.color != 0 ? (o.color | 0xff000000) : a.brand;
        r.brand = brand;
        int glyph = o.glyph;
        if (glyph == Options.GLYPH_AUTO) {
            if (a.bgKind == BG_COMPLEX && a.hasMono) glyph = Options.GLYPH_MONO;
            else if (a.bgKind == BG_COMPLEX && a.fgLayer == null) glyph = Options.GLYPH_WHOLE;
            else glyph = Options.GLYPH_FG;
        }
        if (glyph == Options.GLYPH_FG && a.fgLayer == null) glyph = Options.GLYPH_WHOLE;
        r.glyph = glyph;
        float scale = o.scale / 100f;

        float sx = o.dx / 100f * (VIS1 - VIS0), sy = o.dy / 100f * (VIS1 - VIS0);
        if (o.night) {
            int color = brand != 0 ? ColorKit.ensureContrast(brand, NIGHT_BG, 3.0) : NEUTRAL_NIGHT;
            r.nightFg = dim(shift(glyphLayer(a, glyph, color, true, scale), sx, sy), o.dim / 100f);
        }

        int day = o.day == Options.DAY_AUTO ? autoDay(a) : o.day;
        r.day = day;
        if (day == Options.DAY_ORIGINAL) {
            r.dayBg = a.bgKind == BG_NONE ? null : a.bgLayer;
            r.dayFg = a.fgLayer != null ? shift(scaled(a.fgLayer, scale), sx, sy) : empty();
        } else if (day == Options.DAY_LIGHT) {
            r.dayFg = shift(glyphLayer(a, glyph, dayColor(brand), false, scale), sx, sy);
        }
        return r;
    }

    /**
     * Default day variant: the light Flyme style (white theme background, brand-coloured glyph).
     * Artwork icons without a usable glyph keep their own look.
     */
    static int autoDay(Analysis a) {
        boolean keep = a.bgKind == BG_COMPLEX && !a.hasMono;
        // a light-coloured glyph made for a dark plate (a yellow star) does not read on white
        if (a.bgKind == BG_DARK && a.fgLayer != null) keep = weakOnWhite(recolor(a, dayColor(a.brand), false));
        if (keep) return a.legacy ? Options.DAY_ORIGINAL : Options.DAY_KEEP;
        return Options.DAY_LIGHT;
    }

    static int dayColor(int brand) {
        return brand != 0 ? ColorKit.ensureContrast(brand, DAY_BG, 3.0) : NEUTRAL_DAY;
    }

    private static boolean weakOnWhite(Bitmap glyph) {
        int[] px = new int[S * S];
        glyph.getPixels(px, 0, S, 0, 0, S, S);
        double area = 0, sum = 0;
        for (int i = 0; i < px.length; i += 3) {
            float al = (px[i] >>> 24) / 255f;
            if (al < 0.5f) continue;
            area += al;
            sum += al * Math.min(4.0, ColorKit.contrast(px[i] | 0xff000000, DAY_BG));
        }
        return area > 0 && sum / area < 1.9;
    }

    private static Bitmap glyphLayer(Analysis a, int glyph, int color, boolean night, float scale) {
        switch (glyph) {
            case Options.GLYPH_MONO: {
                Bitmap m = a.mono != null ? a.mono : a.fgLayer;
                return tint(normalize(m, MONO_SIZE * scale), color);
            }
            case Options.GLYPH_WHOLE:
                return whole(a, scale);
            default:
                return scaled(recolor(a, color, night), scale);
        }
    }

    /**
     * Keep the glyph's own colours; white-ish parts that sat on a coloured background take the
     * brand colour, and neutral parts that would vanish on the new background are flipped.
     */
    private static Bitmap recolor(Analysis a, int color, boolean night) {
        int[] px = new int[S * S];
        a.fgLayer.getPixels(px, 0, S, 0, 0, S, S);
        int neutral = night ? NEUTRAL_NIGHT : NEUTRAL_DAY;
        boolean onColor = a.bgKind == BG_COLOR;
        boolean onLight = a.bgKind == BG_LIGHT || a.bgKind == BG_NONE;
        boolean onDark = a.bgKind == BG_DARK;

        if (onColor) {
            recolorOnColor(px, a.bgColor, color, night);
        } else if (onLight && night) {
            // white app background: small enclosed holes (the "4" in a circle) keep it
            fillHoles(px, a.bgLayer);
            // black parts were drawn for a white background: turn them light
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int al = c >>> 24;
                if (al == 0) continue;
                int rgb = c | 0xff000000;
                float s = ColorKit.sat(rgb), l = ColorKit.light(rgb);
                if (s < 0.2f) px[i] = (ColorKit.mix(rgb, neutral, 1f - smooth(0.22f, 0.45f, l)) & 0xffffff) | (al << 24);
            }
        } else if (onDark && !night) {
            // light style from a dark icon: a dark plate inside the foreground goes,
            // white parts would vanish on white and take the brand colour
            removePlate(px, a.bgColor);
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int al = c >>> 24;
                if (al == 0) continue;
                int rgb = c | 0xff000000;
                float s = ColorKit.sat(rgb), l = ColorKit.light(rgb);
                if (s < 0.2f) px[i] = (ColorKit.mix(rgb, color, smooth(0.6f, 0.85f, l)) & 0xffffff) | (al << 24);
            }
        }
        // dark background (or complex art): the glyph already works on a dark plate, keep it
        return Bitmap.createBitmap(px, S, S, Bitmap.Config.ARGB_8888);
    }

    /** Parts of the foreground painted in the plate colour belong to the background. */
    private static void removePlate(int[] px, int plate) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int al = c >>> 24;
            if (al == 0) continue;
            float keep = smooth(22f, 40f, ColorKit.dist(c | 0xff000000, plate));
            int na = Math.round(al * keep);
            px[i] = na == 0 ? 0 : (c & 0xffffff) | (na << 24);
        }
    }

    /**
     * Glyph that sat on a coloured plate. Parts of the foreground painted in the plate colour
     * are background and go; the glyph's main neutral tone (white, sometimes black) takes the
     * brand colour - unless brand-coloured details sit inside it (a white chip with coloured
     * text), then the chip stays as it is.
     */
    private static void recolorOnColor(int[] px, int plate, int color, boolean night) {
        removePlate(px, plate);
        // the glyph's tone is relative to the plate: white on a dark plate, black on a light one
        final float lp = ColorKit.light(plate);
        final float lightMin = Math.max(0.66f, lp + 0.2f), darkMax = Math.min(0.34f, lp - 0.3f);
        double light = 0, dark = 0, area = 0;
        int l0 = S, t0 = S, r0 = -1, b0 = -1;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int c = px[y * S + x];
                float al = (c >>> 24) / 255f;
                if (al < 0.5f) continue;
                int rgb = c | 0xff000000;
                area += al;
                float s = ColorKit.sat(rgb), l = ColorKit.light(rgb);
                if (s < 0.25f && l > lightMin) {
                    light += al;
                    if (x < l0) l0 = x;
                    if (x > r0) r0 = x;
                    if (y < t0) t0 = y;
                    if (y > b0) b0 = y;
                } else if (l < darkMax && s < 0.7f) {
                    dark += al;
                }
            }
        boolean primaryLight = light >= dark;
        double primary = Math.max(light, dark);
        boolean recolor = area > 0 && primary / area > 0.12;
        // by day the chip simply takes the brand colour again (the original look)
        if (recolor && night && primaryLight && r0 > l0) {
            // a white chip/label with the text cut out of it (or drawn in the plate colour):
            // recolouring would merge text and chip, keep it white
            boolean[] holes = enclosedHoles(px);
            double inside = 0;
            for (int y = t0; y <= b0; y++)
                for (int x = l0; x <= r0; x++)
                    if (holes[y * S + x]) inside++;
            double box = (double) (r0 - l0 + 1) * (b0 - t0 + 1);
            // (an outline with big enclosed areas, like a controller sketch, is not a chip)
            if ((light + inside) / box > 0.72 && light / box > 0.45 && inside > light * 0.03) recolor = false;
        }
        if (!recolor) return;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int al = c >>> 24;
            if (al == 0) continue;
            int rgb = c | 0xff000000;
            float s = ColorKit.sat(rgb), l = ColorKit.light(rgb);
            float w = primaryLight
                    ? smooth(lightMin - 0.08f, lightMin + 0.16f, l) * (1f - smooth(0.16f, 0.32f, s))
                    : (1f - smooth(darkMax - 0.08f, darkMax + 0.12f, l)) * (1f - smooth(0.55f, 0.75f, s));
            px[i] = (ColorKit.mix(rgb, color, w) & 0xffffff) | (al << 24);
        }
    }

    /** Transparent pixels not connected to the layer border. */
    private static boolean[] enclosedHoles(int[] px) {
        int n = S * S;
        boolean[] out = new boolean[n];
        boolean[] reach = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int k = 0; k < S; k++) {
            int[] edge = {k, (S - 1) * S + k, k * S, k * S + S - 1};
            for (int i : edge)
                if (!reach[i] && (px[i] >>> 24) < 100) {
                    reach[i] = true;
                    queue[tail++] = i;
                }
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % S, y = i / S;
            int[] nb = {x > 0 ? i - 1 : -1, x < S - 1 ? i + 1 : -1, y > 0 ? i - S : -1, y < S - 1 ? i + S : -1};
            for (int j : nb)
                if (j >= 0 && !reach[j] && (px[j] >>> 24) < 100) {
                    reach[j] = true;
                    queue[tail++] = j;
                }
        }
        for (int i = 0; i < n; i++) out[i] = !reach[i] && (px[i] >>> 24) < 100;
        return out;
    }

    /** Fill small transparent areas enclosed by the glyph with the original background. */
    private static void fillHoles(int[] px, Bitmap bgLayer) {
        int n = S * S;
        byte[] state = new byte[n]; // 0 unknown, 1 outside, 2 glyph
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) if ((px[i] >>> 24) >= 100) state[i] = 2;
        for (int x = 0; x < S; x++) {
            for (int y : new int[]{0, S - 1}) {
                int i = y * S + x;
                if (state[i] == 0) { state[i] = 1; queue[tail++] = i; }
                int j = x * S + y;
                if (state[j] == 0) { state[j] = 1; queue[tail++] = j; }
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % S, y = i / S;
            if (x > 0 && state[i - 1] == 0) { state[i - 1] = 1; queue[tail++] = i - 1; }
            if (x < S - 1 && state[i + 1] == 0) { state[i + 1] = 1; queue[tail++] = i + 1; }
            if (y > 0 && state[i - S] == 0) { state[i - S] = 1; queue[tail++] = i - S; }
            if (y < S - 1 && state[i + S] == 0) { state[i + S] = 1; queue[tail++] = i + S; }
        }
        // glyph area for the size limit
        int glyph = 0;
        for (int i = 0; i < n; i++) if (state[i] == 2) glyph++;
        int[] bg = null;
        byte[] seen = new byte[n];
        int[] comp = new int[n];
        for (int s0 = 0; s0 < n; s0++) {
            if (state[s0] != 0 || seen[s0] != 0) continue;
            // one hole component
            int cn = 0;
            head = 0;
            tail = 0;
            queue[tail++] = s0;
            seen[s0] = 1;
            while (head < tail) {
                int i = queue[head++];
                comp[cn++] = i;
                int x = i % S, y = i / S;
                int[] nb = {x > 0 ? i - 1 : -1, x < S - 1 ? i + 1 : -1, y > 0 ? i - S : -1, y < S - 1 ? i + S : -1};
                for (int j : nb)
                    if (j >= 0 && state[j] == 0 && seen[j] == 0) {
                        seen[j] = 1;
                        queue[tail++] = j;
                    }
            }
            // a big enclosed area (inside of a ring) is background, not a detail
            if (cn > glyph * 0.6) continue;
            if (bg == null) {
                bg = new int[n];
                if (bgLayer != null) bgLayer.getPixels(bg, 0, S, 0, 0, S, S);
                else java.util.Arrays.fill(bg, 0xffffffff);
            }
            for (int k = 0; k < cn; k++) {
                int i = comp[k];
                px[i] = over(bg[i] | 0xff000000, px[i]);
                // anti-aliased rim around the hole
                int x = i % S, y = i / S;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= S || yy >= S) continue;
                        int j = yy * S + xx;
                        if (state[j] == 2 && (px[j] >>> 24) < 255) px[j] = over(bg[j] | 0xff000000, px[j]);
                    }
            }
        }
    }

    /** src over an opaque dst. */
    private static int over(int dst, int src) {
        float a = (src >>> 24) / 255f;
        return ColorKit.mix(dst, src | 0xff000000, a);
    }

    /** The whole original icon as a rounded tile in the middle. */
    private static Bitmap whole(Analysis a, float scale) {
        Bitmap comp = composite(a.bgLayer, a.fgLayer);
        Bitmap out = empty();
        Canvas c = new Canvas(out);
        float side = WHOLE_SIZE * S * scale;
        RectF dst = new RectF((S - side) / 2, (S - side) / 2, (S + side) / 2, (S + side) / 2);
        Path clip = new Path();
        clip.addRoundRect(dst, side * 0.24f, side * 0.24f, Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(comp, new Rect(VIS0, VIS0, VIS1, VIS1), dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    // ------------------------------------------------------------------ bitmap helpers

    static Bitmap empty() {
        return Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
    }

    static Bitmap solid(int color) {
        Bitmap b = empty();
        b.eraseColor(color | 0xff000000);
        return b;
    }

    static Bitmap composite(Bitmap bg, Bitmap fg) {
        Bitmap out = empty();
        Canvas c = new Canvas(out);
        if (bg != null) c.drawBitmap(bg, 0, 0, null);
        if (fg != null) c.drawBitmap(fg, 0, 0, null);
        return out;
    }

    static Bitmap transform(Bitmap src, Rect from, RectF to) {
        Bitmap out = empty();
        Canvas c = new Canvas(out);
        Matrix m = new Matrix();
        m.setRectToRect(new RectF(from), to, Matrix.ScaleToFit.FILL);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    static Bitmap scaled(Bitmap src, float k) {
        if (Math.abs(k - 1f) < 0.005f) return src;
        float side = S * k;
        return transform(src, new Rect(0, 0, S, S),
                new RectF((S - side) / 2, (S - side) / 2, (S + side) / 2, (S + side) / 2));
    }

    /**
     * Scale the glyph about the layer centre so its longer side is size * S: a monochrome layer
     * drawn off-centre on purpose keeps its place.
     */
    static Bitmap normalize(Bitmap src, float size) {
        Rect b = alphaBounds(src, 16);
        if (b == null) return empty();
        float k = size * S / Math.max(b.width(), b.height());
        float c = S / 2f;
        RectF dst = new RectF(c + (b.left - c) * k, c + (b.top - c) * k, c + (b.right - c) * k, c + (b.bottom - c) * k);
        // keep it inside the visible part
        float ox = Math.max(0, VIS0 - dst.left) - Math.max(0, dst.right - VIS1);
        float oy = Math.max(0, VIS0 - dst.top) - Math.max(0, dst.bottom - VIS1);
        dst.offset(ox, oy);
        return transform(src, b, dst);
    }

    /** Move the glyph by (x, y) px. */
    static Bitmap shift(Bitmap src, float x, float y) {
        if (src == null || (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f)) return src;
        Bitmap out = empty();
        new Canvas(out).drawBitmap(src, x, y, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Darken the foreground (a black veil of the given strength), alpha untouched. */
    static Bitmap dim(Bitmap src, float k) {
        if (src == null || k <= 0f) return src;
        int[] px = new int[S * S];
        src.getPixels(px, 0, S, 0, 0, S, S);
        float m = 1f - Math.min(0.9f, k);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if ((c >>> 24) == 0) continue;
            px[i] = (c & 0xff000000) | (Math.round(Color.red(c) * m) << 16) | (Math.round(Color.green(c) * m) << 8)
                    | Math.round(Color.blue(c) * m);
        }
        return Bitmap.createBitmap(px, S, S, Bitmap.Config.ARGB_8888);
    }

    static Bitmap tint(Bitmap alphaSrc, int color) {
        Bitmap out = empty();
        Canvas c = new Canvas(out);
        c.drawBitmap(alphaSrc, 0, 0, null);
        Paint p = new Paint();
        p.setColor(color | 0xff000000);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawRect(0, 0, S, S, p);
        return out;
    }

    static Rect alphaBounds(Bitmap b, int threshold) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, bo = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((px[y * w + x] >>> 24) > threshold) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > bo) bo = y;
                }
        return r < 0 ? null : new Rect(l, t, r + 1, bo + 1);
    }

    static float coverage(Bitmap b, int l, int t, int r, int bo) {
        int n = 0, in = 0;
        for (int y = t; y < bo; y += 3)
            for (int x = l; x < r; x += 3) {
                n++;
                if ((b.getPixel(x, y) >>> 24) > 200) in++;
            }
        return n == 0 ? 0 : in / (float) n;
    }

    static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
