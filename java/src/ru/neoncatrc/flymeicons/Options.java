// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import org.json.JSONException;
import org.json.JSONObject;

/** Per-app generation settings. */
final class Options {
    /** Where the glyph comes from. */
    static final int GLYPH_AUTO = 0, GLYPH_MONO = 1, GLYPH_FG = 2, GLYPH_WHOLE = 3;
    /** Day variant: AUTO = untouched for adaptive icons, ORIGINAL for legacy ones. */
    static final int DAY_AUTO = -1, DAY_KEEP = 0, DAY_ORIGINAL = 1, DAY_LIGHT = 2;

    int glyph = GLYPH_AUTO;
    int day = DAY_AUTO;
    boolean night = true;
    /** 0 = brand colour picked from the icon, otherwise opaque RGB. */
    int color = 0;
    /** Glyph size, percent of the automatic size. */
    int scale = 100;
    /** Glyph shift, percent of the visible icon side (+ = right / down). */
    int dx = 0, dy = 0;
    /** Night only: darkening of the foreground, percent (Flyme's cloud night icons have ~15). */
    int dim = DEFAULT_DIM;

    static final int DEFAULT_DIM = 15;
    /** Ranges of the settings screen; stored values are clamped to them. */
    static final int SCALE_MIN = 60, SCALE_MAX = 140, SHIFT_MAX = 20, DIM_MAX = 40;

    Options copy() {
        Options o = new Options();
        o.glyph = glyph;
        o.day = day;
        o.night = night;
        o.color = color;
        o.scale = scale;
        o.dx = dx;
        o.dy = dy;
        o.dim = dim;
        return o;
    }

    JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("glyph", glyph).put("day", day).put("night", night).put("color", color).put("scale", scale)
                    .put("dx", dx).put("dy", dy).put("dim", dim);
        } catch (JSONException ignored) {
        }
        return j;
    }

    static Options fromJson(JSONObject j) {
        Options o = new Options();
        if (j == null) return o;
        o.glyph = clamp(j.optInt("glyph", o.glyph), GLYPH_AUTO, GLYPH_WHOLE);
        o.day = clamp(j.optInt("day", o.day), DAY_AUTO, DAY_LIGHT);
        o.night = j.optBoolean("night", o.night);
        o.color = j.optInt("color", o.color);
        if (o.color != 0) o.color |= 0xff000000;
        o.scale = clamp(j.optInt("scale", o.scale), SCALE_MIN, SCALE_MAX);
        o.dx = clamp(j.optInt("dx", o.dx), -SHIFT_MAX, SHIFT_MAX);
        o.dy = clamp(j.optInt("dy", o.dy), -SHIFT_MAX, SHIFT_MAX);
        o.dim = clamp(j.optInt("dim", o.dim), 0, DIM_MAX);
        return o;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public boolean equals(Object x) {
        if (!(x instanceof Options)) return false;
        Options o = (Options) x;
        return glyph == o.glyph && day == o.day && night == o.night && color == o.color && scale == o.scale
                && dx == o.dx && dy == o.dy && dim == o.dim;
    }

    @Override
    public int hashCode() {
        return ((((glyph * 31 + day) * 31 + (night ? 1 : 0)) * 31 + color * 7 + scale) * 31 + dx * 7 + dy) * 31 + dim;
    }
}
