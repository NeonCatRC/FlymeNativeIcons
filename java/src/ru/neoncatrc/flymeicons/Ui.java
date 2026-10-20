// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntConsumer;

import flyme.support.v7.app.a$a;

/** Flyme 12 dialogs (flyme.support AlertDialog, R8-renamed: see the stub) and small helpers. */
final class Ui {
    static final String SOURCE_URL = "https://github.com/NeonCatRC/FlymeNativeIcons";

    private Ui() {}

    static void confirm(Context c, CharSequence title, CharSequence message, CharSequence positive, Runnable yes) {
        a$a b = new a$a(c);
        b.r(title);
        if (message != null) {
            TextView tv = (TextView) LayoutInflater.from(b.e()).inflate(R.layout.fi_dialog_message, null, false);
            tv.setText(message);
            b.s(tv);
        }
        b.o(positive, (d, w) -> yes.run());
        b.k(c.getString(R.string.fi_cancel), null);
        b.c().show();
    }

    static void about(final Activity a, final String version) {
        final boolean night = Flyme.nightIcons(a);
        final int size = a.getResources().getDimensionPixelSize(R.dimen.mz_list_icon_large_width) * 2;
        // the icon as the launcher draws it in the current mode (Flyme files + mask): drawn off
        // the UI thread, the mask comes from a system zip
        new Thread(() -> {
            final android.graphics.Bitmap icon = Icons.render(a, Icons.selfLayerBitmap(a, night), null, night, size);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                a$a b = new a$a(a);
                View v = LayoutInflater.from(b.e()).inflate(R.layout.fi_about, null, false);
                ((TextView) v.findViewById(R.id.fi_about_version)).setText(a.getString(R.string.fi_about_version, version));
                ((android.widget.ImageView) v.findViewById(R.id.fi_about_icon)).setImageBitmap(icon);
                TextView link = v.findViewById(R.id.fi_about_link);
                link.setTextColor(accent(b.e()));
                link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                link.setOnClickListener(x -> openUrl(a, SOURCE_URL));
                b.s(v);
                b.o(a.getString(R.string.fi_done), null);
                b.c().show();
            });
        }, "fi-about").start();
    }

    /** The theme's accent colour; the Flyme blue when the theme has none. */
    private static int accent(Context c) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (!c.getTheme().resolveAttribute(R.attr.colorPrimary, tv, true)) return 0xff206cff;
        return tv.resourceId != 0 ? c.getColor(tv.resourceId) : tv.data;
    }

    static void openUrl(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            toast(c, url);
        }
    }

    static void choose(Context c, CharSequence title, CharSequence[] items, int checked, IntConsumer pick) {
        a$a b = new a$a(c);
        b.r(title);
        b.q(items, checked, (d, w) -> {
            d.dismiss();
            pick.accept(w);
        });
        b.k(c.getString(R.string.fi_cancel), null);
        b.c().show();
    }

    /**
     * The nested-scroll decor makes the content taller than the screen by the title's scroll
     * range, so a bottom bar inside it would start off-screen: pin it to the window instead.
     */
    static void pinBottomBar(Activity a, final View bar) {
        ViewGroup parent = (ViewGroup) bar.getParent();
        if (parent != null) parent.removeView(bar);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, a.getResources().getDimensionPixelSize(
                R.dimen.mz_action_bar_default_height_appcompat_split), Gravity.BOTTOM);
        ((ViewGroup) a.getWindow().getDecorView()).addView(bar, lp);
        bar.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom;
                v.setLayoutParams(lp);
            }
            return insets;
        });
    }

    static void toast(Context c, int res) {
        Toast.makeText(c, res, Toast.LENGTH_SHORT).show();
    }

    static void toast(Context c, CharSequence s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    static void requestStorage(Activity a) {
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + a.getPackageName()));
        try {
            a.startActivity(i);
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** Flyme "Application startup management" (auto-start / background), else app details. */
    static void openBackgroundSettings(Activity a) {
        try {
            a.startActivity(new Intent("com.meizu.safe.security.background_manager_settings")
                    .putExtra("packageName", a.getPackageName()));
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        }
    }
}
