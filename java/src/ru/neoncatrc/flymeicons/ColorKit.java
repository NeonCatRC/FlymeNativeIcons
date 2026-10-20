// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.graphics.Color;

/** Small colour helpers (sRGB, WCAG luminance, HSL). */
final class ColorKit {
    private ColorKit() {}

    static double lin(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static double lum(int c) {
        return 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c));
    }

    static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** HSV-style saturation 0..1. */
    static float sat(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max == 0 ? 0f : (max - min) / (float) max;
    }

    /** Perceived lightness 0..1 (simple, for thresholds). */
    static float light(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    static float dist(int a, int b) {
        int dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        return (float) Math.sqrt(dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11) * 1.6f;
    }

    static int mix(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static float[] toHsl(int c) {
        float r = Color.red(c) / 255f, g = Color.green(c) / 255f, b = Color.blue(c) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float h, s, l = (max + min) / 2f, d = max - min;
        if (d == 0) {
            h = s = 0;
        } else {
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60f;
        }
        return new float[]{h, s, l};
    }

    static int fromHsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float hp = (h % 360f) / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        float m = l - c / 2;
        return Color.rgb(clamp255((r + m) * 255), clamp255((g + m) * 255), clamp255((b + m) * 255));
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Move lightness away from bg until the contrast reaches min (keeps hue and saturation). */
    static int ensureContrast(int color, int bg, double min) {
        if (contrast(color, bg) >= min) return color;
        float[] hsl = toHsl(color);
        boolean up = lum(bg) < 0.18;
        float l = hsl[2];
        for (int i = 0; i < 60; i++) {
            l += up ? 0.015f : -0.015f;
            if (l <= 0 || l >= 1) break;
            int c = fromHsl(hsl[0], hsl[1], l);
            if (contrast(c, bg) >= min) return c;
        }
        return up ? Color.rgb(238, 238, 238) : Color.rgb(28, 28, 30);
    }

    static String hex(int c) {
        return String.format("#%06X", c & 0xffffff);
    }
}
