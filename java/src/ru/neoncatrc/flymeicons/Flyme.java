// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the Flyme 12.6 icon engine (android.content.res.flymetheme.FlymeThemeHelper) looks at.
 *
 * Icons are adaptive layer pairs <pkg>_fg.png / <pkg>_bg.png, 432x432 (108dp @ xxxhdpi):
 *  - system theme zips /system/customizecenter/theme/icons[_night];
 *  - the "cloud" folders /sdcard/Customize/.FlymeIcon/{OriginIcon,NightIcon}, read with plain
 *    java.io.File by whoever draws the icon (the launcher has all-files access).
 * Night files are used when current_launcher_icon_color == 2, or == 0 and the system is dark.
 * A night fg without a night bg gets the theme's night background.png (dark gradient).
 */
final class Flyme {
    static final String THEME_DIR = "/system/customizecenter/theme/";
    static final String ACTION_CHANGE_ICON = "com.meizu.flyme.launcher.CHANGEICON";
    static final String LAUNCHER = "com.meizu.flyme.launcher";
    static final int SIZE = 432;

    /** current_launcher_icon_color: 0 follow system, 1 light, 2 dark. */
    static final int COLOR_SYSTEM = 0, COLOR_LIGHT = 1, COLOR_DARK = 2;

    private static final Map<String, Set<String>> sZipIndex = new HashMap<>();
    /** The theme zips stay open: /system does not change while we run. */
    private static final Map<String, ZipFile> sZips = new HashMap<>();
    /** Only the few shared images (backgrounds, masks), about 0.75 MB each. */
    private static final Map<String, Bitmap> sShared = new HashMap<>();
    /** Entries the zip does not have. */
    private static final Set<String> sAbsent = new HashSet<>();

    private Flyme() {}

    static File cloudRoot() {
        return new File(Environment.getExternalStorageDirectory(), "Customize/.FlymeIcon");
    }

    static File cloudDir(boolean night) {
        return new File(cloudRoot(), night ? "NightIcon" : "OriginIcon");
    }

    static File cloudFile(String pkg, boolean night, boolean fg) {
        return new File(cloudDir(night), pkg + (fg ? "_fg.png" : "_bg.png"));
    }

    static boolean hasStorageAccess() {
        return Environment.isExternalStorageManager();
    }

    static int iconColor(ContentResolver cr) {
        return Settings.System.getInt(cr, "current_launcher_icon_color", COLOR_SYSTEM);
    }

    /** Flyme icons switch (persist.sys.use.flyme.icon in Settings.System, default on). */
    static boolean flymeIconsOn(ContentResolver cr) {
        return Settings.System.getInt(cr, "persist.sys.use.flyme.icon", 1) == 1;
    }

    static String cornerSize(ContentResolver cr) {
        String s = Settings.System.getString(cr, "current_launcher_icon_corner_size");
        return s == null ? "corner_size_1" : s;
    }

    /** Launcher "Content Size": scale of the foreground layer, 1 = untouched. */
    static float contentSize(ContentResolver cr) {
        return Settings.System.getFloat(cr, "current_launcher_icon_content_size", 1f);
    }

    /** True when the launcher currently draws night icon files. */
    static boolean nightIcons(Context c) {
        int color = iconColor(c.getContentResolver());
        if (color == COLOR_DARK) return true;
        if (color == COLOR_LIGHT) return false;
        int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Packages that have an icon in the system theme zip. */
    static synchronized Set<String> systemPackages(boolean night) {
        String zip = THEME_DIR + (night ? "icons_night" : "icons");
        Set<String> set = sZipIndex.get(zip);
        if (set != null) return set;
        set = new HashSet<>();
        try {
            Enumeration<? extends ZipEntry> e = zip(night ? "icons_night" : "icons").entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.indexOf('/') < 0 && n.endsWith("_fg.png")) set.add(n.substring(0, n.length() - 7));
            }
            // the calendar has its own folder with dated layers
            set.add("com.android.calendar");
        } catch (Exception ignored) {
        }
        set = Collections.unmodifiableSet(set);
        sZipIndex.put(zip, set);
        return set;
    }

    private static ZipFile zip(String name) throws java.io.IOException {
        ZipFile z = sZips.get(name);
        if (z == null) {
            z = new ZipFile(THEME_DIR + name);
            sZips.put(name, z);
        }
        return z;
    }

    /** An image from a theme zip; shared = kept in memory (the per-app layers are not). */
    private static synchronized Bitmap zipBitmap(String zip, String entry, boolean shared) {
        String key = zip + "!" + entry;
        Bitmap b = sShared.get(key);
        if (b != null || sAbsent.contains(key)) return b;
        try {
            ZipFile z = zip(zip);
            ZipEntry ze = z.getEntry(entry);
            if (ze == null) {
                sAbsent.add(key);
                return null;
            }
            try (InputStream in = z.getInputStream(ze)) {
                b = BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            // not cached: a read error may pass, a missing zip costs one failed open per call
            return null;
        }
        if (b != null && shared) sShared.put(key, b);
        return b;
    }

    /** The theme's own icon background (white gradient by day, #333 -> #161616 at night). */
    static Bitmap background(boolean night) {
        return zipBitmap(night ? "icons_night" : "icons", "background.png", true);
    }

    static Bitmap systemLayer(String pkg, boolean night, boolean fg) {
        return zipBitmap(night ? "icons_night" : "icons", pkg + (fg ? "_fg.png" : "_bg.png"), false);
    }

    /** Launcher icon mask for the current corner size (FlymeThemeResource.getMaskIcon). */
    static Bitmap mask(Context c) {
        String corner = cornerSize(c.getContentResolver());
        String name = "mask1.png";
        switch (corner) {
            case "corner_size_2": name = "mask2.png"; break;
            case "corner_size_3": name = "mask3.png"; break;
            case "corner_size_4": name = "mask4.png"; break;
            case "corner_size_5": name = "mask5.png"; break;
        }
        return zipBitmap("masks", name, true);
    }

    /** Ask the Flyme launcher to reload icons (it bumps ConfigurationExt.iconChanged for everyone). */
    static void notifyLauncher(Context c, ArrayList<String> pkgs) {
        Intent i = new Intent(ACTION_CHANGE_ICON);
        i.setPackage(LAUNCHER);
        i.putStringArrayListExtra("iconPackage", pkgs);
        i.putExtra("itemType", 0);
        i.putExtra("changeType", 0);
        c.sendBroadcast(i);
    }
}
