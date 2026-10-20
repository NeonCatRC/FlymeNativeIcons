// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

/**
 * The app's own launcher icon, read straight from its resources (bypassing the Flyme theme
 * hook in ApplicationPackageManager), rendered as 432 px layers.
 */
final class IconSource {
    static final int S = Flyme.SIZE;

    boolean adaptive;
    Bitmap bg, fg, mono;
    /** Non-adaptive icon, fitted into S x S. */
    Bitmap legacy;

    static IconSource load(Context c, ActivityInfo ai) {
        Drawable d = null;
        try {
            PackageManager pm = c.getPackageManager();
            Resources res = pm.getResourcesForApplication(ai.applicationInfo);
            int id = ai.getIconResource();
            if (id == 0) id = ai.applicationInfo.icon;
            if (id != 0) d = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Throwable ignored) {
        }
        // null: the app's resources are not reachable (e.g. Flyme hides other apps from a
        // background process) - never draw a replacement from the default icon then
        return d == null ? null : from(d);
    }

    static IconSource from(Drawable d) {
        IconSource s = new IconSource();
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            s.adaptive = true;
            s.bg = render(a.getBackground());
            s.fg = render(a.getForeground());
            s.mono = render(a.getMonochrome());
        } else {
            s.legacy = renderFit(d);
        }
        return s;
    }

    static Bitmap render(Drawable d) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, S, S);
        d.draw(c);
        return b;
    }

    static Bitmap renderFit(Drawable d) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (w <= 0 || h <= 0) {
            d.setBounds(0, 0, S, S);
        } else {
            float k = Math.min(S / (float) w, S / (float) h);
            int dw = Math.round(w * k), dh = Math.round(h * k);
            d.setBounds((S - dw) / 2, (S - dh) / 2, (S + dw) / 2, (S + dh) / 2);
        }
        d.draw(c);
        return b;
    }
}
