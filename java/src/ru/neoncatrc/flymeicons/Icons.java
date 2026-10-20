// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** App list, icon status and writing/removing our files in the Flyme cloud folders. */
final class Icons {
    static final int ST_NONE = 0, ST_OURS = 1, ST_SYSTEM = 2, ST_CLOUD = 3;
    /** Give Flyme time to clean up and pull its own cloud icon after an install/update. */
    static final long SETTLE_MS = 2 * 60 * 1000L;
    static final long AUTO_NEW_DELAY_MS = 10 * 60 * 1000L;
    /** Bump when the generator output changes: older icons are redrawn by restore(). */
    static final int GEN_REV = 5;
    static final String TAG = "FlymeIcons";

    static final class App {
        String pkg;
        CharSequence label;
        ActivityInfo ai;
        long versionCode, firstInstall, lastUpdate;
        int state;
        /** Flyme has a day icon but no night one. */
        boolean flymeNoNight;
    }

    private Icons() {}

    // ------------------------------------------------------------------ scanning

    static List<App> scan(Context c) {
        return scan(c, true);
    }

    /** Every launchable app but our own; without labels the list is unsorted (background use). */
    static List<App> scan(Context c, boolean labels) {
        PackageManager pm = c.getPackageManager();
        Store store = Store.get(c);
        List<App> list = new ArrayList<>();
        for (ResolveInfo ri : launchable(c).values()) {
            if (isSelf(c, ri.activityInfo.packageName)) continue;
            App a = app(pm, ri, labels);
            refreshState(c, store, a);
            list.add(a);
        }
        if (labels) {
            final Collator col = Collator.getInstance(Locale.getDefault());
            Collections.sort(list, (x, y) -> col.compare(x.label.toString(), y.label.toString()));
        }
        return list;
    }

    /** The first launcher activity of every package, the one the launcher shows. */
    private static Map<String, ResolveInfo> launchable(Context c) {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, ResolveInfo> map = new HashMap<>();
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(main, PackageManager.MATCH_ALL)) {
            if (ri.activityInfo != null && !map.containsKey(ri.activityInfo.packageName))
                map.put(ri.activityInfo.packageName, ri);
        }
        return map;
    }

    private static App app(PackageManager pm, ResolveInfo ri, boolean label) {
        App a = new App();
        a.pkg = ri.activityInfo.packageName;
        a.ai = ri.activityInfo;
        a.label = label ? ri.loadLabel(pm) : a.pkg;
        try {
            PackageInfo pi = pm.getPackageInfo(a.pkg, 0);
            a.versionCode = pi.getLongVersionCode();
            a.firstInstall = pi.firstInstallTime;
            a.lastUpdate = pi.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return a;
    }

    static void refreshState(Context c, Store store, App a) {
        Store.Entry e = store.entry(a.pkg);
        boolean sysDay = Flyme.systemPackages(false).contains(a.pkg);
        boolean sysNight = Flyme.systemPackages(true).contains(a.pkg);
        boolean cloudDay = Flyme.cloudFile(a.pkg, false, true).exists();
        boolean cloudNight = Flyme.cloudFile(a.pkg, true, true).exists();
        if (e != null && ownsAny(e)) {
            a.state = ST_OURS;
        } else if (cloudDay || cloudNight) {
            a.state = ST_CLOUD;
        } else if (sysDay || sysNight) {
            a.state = ST_SYSTEM;
        } else {
            a.state = ST_NONE;
        }
        a.flymeNoNight = (a.state == ST_CLOUD || a.state == ST_SYSTEM) && !sysNight && !cloudNight;
    }

    static App find(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, PackageManager.MATCH_ALL);
        if (ris.isEmpty() || ris.get(0).activityInfo == null) return null;
        App a = app(pm, ris.get(0), true);
        refreshState(c, Store.get(c), a);
        return a;
    }

    /** The icon as the launcher draws it right now (Flyme theme path of PackageManager). */
    static Drawable current(Context c, App a) {
        PackageManager pm = c.getPackageManager();
        Drawable d = null;
        try {
            int id = a.ai.getIconResource();
            d = pm.getDrawable(a.pkg, id, a.ai.applicationInfo);
        } catch (Throwable ignored) {
        }
        return d != null ? d : a.ai.loadIcon(pm);
    }

    // ------------------------------------------------------------------ files

    static File slotFile(String pkg, int slot) {
        boolean night = slot >= Store.NIGHT_FG;
        boolean fg = slot == Store.DAY_FG || slot == Store.NIGHT_FG;
        return Flyme.cloudFile(pkg, night, fg);
    }

    static boolean ownsAny(Store.Entry e) {
        for (int s = 0; s < 4; s++) if (owns(e, s)) return true;
        return false;
    }

    static boolean owns(Store.Entry e, int slot) {
        if (e == null || e.hash[slot] == null) return false;
        File f = slotFile(e.pkg, slot);
        return f.exists() && e.hash[slot].equals(sha1(f));
    }

    /** A file in the slot that we did not write (Flyme's own cloud icon). */
    static boolean foreign(Store.Entry e, String pkg, int slot) {
        File f = slotFile(pkg, slot);
        if (!f.exists()) return false;
        return e == null || e.hash[slot] == null || !e.hash[slot].equals(sha1(f));
    }

    static Generator.Result generate(Context c, ActivityInfo ai, Options o) {
        IconSource src = IconSource.load(c, ai);
        return src == null ? null : Generator.make(Generator.analyze(src), o);
    }

    /**
     * Generate and write the icon. Without force, Flyme's own files are never replaced.
     * Returns the stored entry, or null when Flyme owns the slots we need.
     */
    static synchronized Store.Entry apply(Context c, App app, Options o, boolean force) throws IOException {
        Store store = Store.get(c);
        Store.Entry old = store.entry(app.pkg);
        Generator.Result r = generate(c, app.ai, o);
        if (r == null) throw new IOException("icon of " + app.pkg + " is not readable");
        Bitmap[] slots = {r.dayFg, r.dayBg, r.nightFg, r.nightBg};
        if (slots[Store.DAY_FG] == null && slots[Store.NIGHT_FG] == null) {
            // no night variant and the day one left as it is: nothing of ours should stay
            remove(c, app.pkg);
            return null;
        }
        if (!force) {
            for (int s = 0; s < 4; s++)
                if (slots[s] != null && foreign(old, app.pkg, s)) return null;
        }
        mkdirs();
        Store.Entry e = new Store.Entry();
        e.pkg = app.pkg;
        e.options = o.copy();
        e.versionCode = app.versionCode;
        e.time = System.currentTimeMillis();
        e.rev = GEN_REV;
        // backgrounds first: the launcher looks the fg up first and falls back to background.png
        int[] order = {Store.DAY_BG, Store.NIGHT_BG, Store.DAY_FG, Store.NIGHT_FG};
        try {
            for (int s : order) {
                File f = slotFile(app.pkg, s);
                if (slots[s] != null) {
                    e.hash[s] = writePng(slots[s], f);
                } else if (old != null && owns(old, s) && !f.delete()) {
                    e.hash[s] = old.hash[s];
                }
            }
        } catch (Throwable t) {
            // keep every file of ours on record, or it would pass for Flyme's own from now on;
            // rev 0 makes restore() draw the icon again
            for (int s = 0; s < 4; s++)
                if (e.hash[s] == null && old != null && owns(old, s)) e.hash[s] = old.hash[s];
            e.rev = 0;
            if (ownsAny(e)) store.put(e);
            else store.remove(app.pkg);
            throw t;
        }
        store.put(e);
        return e;
    }

    static synchronized void remove(Context c, String pkg) {
        Store store = Store.get(c);
        Store.Entry e = store.entry(pkg);
        if (e != null) {
            for (int s = 0; s < 4; s++) if (owns(e, s)) slotFile(pkg, s).delete();
        }
        store.remove(pkg);
    }

    /**
     * Put our files back where Flyme deleted them (it wipes a package's cloud icons on every
     * install/update) and regenerate after app updates. Returns the packages that changed.
     */
    static ArrayList<String> restore(Context c, boolean doRestore, boolean autoNew) {
        return restore(c, doRestore, autoNew, null);
    }

    /** visible: the app list the caller has just scanned, null to look the apps up here. */
    static ArrayList<String> restore(Context c, boolean doRestore, boolean autoNew, List<App> visible) {
        ArrayList<String> changed = new ArrayList<>();
        if (!Flyme.hasStorageAccess()) return changed;
        Store store = Store.get(c);
        long now = System.currentTimeMillis();
        ensureSelf(c);
        Map<String, App> apps = new HashMap<>();
        if (visible != null) {
            for (App a : visible) apps.put(a.pkg, a);
        } else if (doRestore) {
            PackageManager pm = c.getPackageManager();
            Map<String, ResolveInfo> ris = launchable(c);
            for (Store.Entry e : store.entries()) {
                ResolveInfo ri = ris.get(e.pkg);
                if (ri != null) apps.put(e.pkg, app(pm, ri, false));
            }
        }
        int hidden = 0;
        store.beginBatch();
        try {
            if (doRestore) hidden = restoreEntries(c, store, apps, now, changed);
            if (autoNew) {
                for (App app : visible != null ? visible : scan(c, false)) {
                    if (app.state != ST_NONE || store.ignored(app.pkg)) continue;
                    if (now - app.firstInstall < AUTO_NEW_DELAY_MS || now - app.lastUpdate < SETTLE_MS) continue;
                    try {
                        if (apply(c, app, new Options(), false) != null) changed.add(app.pkg);
                    } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                        android.util.Log.w(TAG, "auto " + app.pkg, ex);
                    }
                }
            }
        } finally {
            store.endBatch();
        }
        if (!changed.isEmpty()) Flyme.notifyLauncher(c, changed);
        android.util.Log.i(TAG, "restore: entries=" + store.entries().size() + " changed=" + changed
                + " hidden=" + hidden + " restore=" + doRestore + " autoNew=" + autoNew);
        return changed;
    }

    /** Returns how many entries belong to apps that are not visible from here. */
    private static int restoreEntries(Context c, Store store, Map<String, App> apps, long now,
                                      ArrayList<String> changed) {
        int hidden = 0;
        for (Store.Entry e : store.entries()) {
            if (isSelf(c, e.pkg)) continue;
            App app = apps.get(e.pkg);
            if (app == null) {
                // not visible from here: uninstalled, or hidden from a background process by
                // Flyme's app-list permission. Never delete on that; forget() runs in foreground.
                hidden++;
                continue;
            }
            if (now - app.lastUpdate < SETTLE_MS) continue;
            boolean missing = false, foreignFile = false;
            for (int s = 0; s < 4; s++) {
                if (e.hash[s] == null) continue;
                File f = slotFile(e.pkg, s);
                if (!f.exists()) missing = true;
                else if (!e.hash[s].equals(sha1(f))) foreignFile = true;
            }
            if (foreignFile) {
                // Flyme brought its own icon for this app: it wins, our other layers go with us
                remove(c, e.pkg);
                changed.add(e.pkg);
                continue;
            }
            if (missing || app.versionCode != e.versionCode || e.rev < GEN_REV) {
                try {
                    if (apply(c, app, e.options, false) != null) changed.add(e.pkg);
                } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                    android.util.Log.w(TAG, "restore " + e.pkg, ex);
                }
            }
        }
        return hidden;
    }

    /** Foreground only: drop entries of apps that are really gone. */
    static void forgetUninstalled(Context c, List<App> visible) {
        if (visible.size() < 5) return; // the list itself is not trustworthy
        PackageManager pm = c.getPackageManager();
        Store store = Store.get(c);
        store.beginBatch();
        try {
            for (Store.Entry e : store.entries()) {
                if (isSelf(c, e.pkg)) continue;
                try {
                    pm.getApplicationInfo(e.pkg, 0);
                } catch (PackageManager.NameNotFoundException ex) {
                    android.util.Log.i(TAG, "forget uninstalled " + e.pkg);
                    remove(c, e.pkg);
                }
            }
        } finally {
            store.endBatch();
        }
    }

    /**
     * Our own icon as Flyme files too: the launcher's "icon color" switch only affects Flyme's
     * icon files, an adaptive icon with values-night follows the system theme only.
     * Returns true when files were (re)written.
     */
    static boolean ensureSelf(Context c) {
        boolean app = ensureOwnIcon(c, c.getPackageName());
        // the icon pack shows up in the launcher too (it needs a launcher activity for its preview)
        boolean pack = PackBuilder.installed(c) && ensureOwnIcon(c, PackBuilder.PACK_PKG);
        return app || pack;
    }

    private static synchronized boolean ensureOwnIcon(Context c, String pkg) {
        if (!Flyme.hasStorageAccess()) return false;
        Store store = Store.get(c);
        Store.Entry e = store.entry(pkg);
        // both icons are drawn from this app's resources: its version tells when they change
        long version = 0;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        boolean owned = e != null && owns(e, Store.DAY_FG) && owns(e, Store.NIGHT_FG);
        if (owned && e.versionCode == version) return false;
        if (foreign(e, pkg, Store.DAY_FG) && foreign(e, pkg, Store.NIGHT_FG)
                && (e == null || e.hash[Store.DAY_FG] == null)) return false;
        try {
            int res = c.getPackageName().equals(pkg) ? R.mipmap.fi_ic_launcher : R.drawable.fi_ic_pack_fg;
            byte[] day = png(selfLayer(c, false, res)), night = png(selfLayer(c, true, res));
            if (owned && sha1(day).equals(e.hash[Store.DAY_FG]) && sha1(night).equals(e.hash[Store.NIGHT_FG])) {
                // same pictures in a new version: just remember the version
                e.versionCode = version;
                store.put(e);
                return false;
            }
            mkdirs();
            Store.Entry n = new Store.Entry();
            n.pkg = pkg;
            n.versionCode = version;
            n.time = System.currentTimeMillis();
            n.options.day = Options.DAY_ORIGINAL;
            // backgrounds are left to the theme: white gradient by day, dark gradient at night
            n.hash[Store.DAY_FG] = writeBytes(day, slotFile(pkg, Store.DAY_FG));
            n.hash[Store.NIGHT_FG] = writeBytes(night, slotFile(pkg, Store.NIGHT_FG));
            store.put(n);
            ArrayList<String> pkgs = new ArrayList<>();
            pkgs.add(pkg);
            Flyme.notifyLauncher(c, pkgs);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    static Bitmap selfLayerBitmap(Context c, boolean night) {
        return selfLayer(c, night, R.mipmap.fi_ic_launcher);
    }

    static Bitmap packLayerBitmap(Context c, boolean night) {
        return selfLayer(c, night, R.drawable.fi_ic_pack_fg);
    }

    /** Foreground of one of our icons drawn in the day or night configuration. */
    private static Bitmap selfLayer(Context c, boolean night, int res) {
        android.content.res.Configuration cfg = new android.content.res.Configuration(c.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | (night ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO);
        Context cc = c.createConfigurationContext(cfg);
        Drawable d = cc.getResources().getDrawableForDensity(res,
                android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
        Drawable fg = d instanceof android.graphics.drawable.AdaptiveIconDrawable
                ? ((android.graphics.drawable.AdaptiveIconDrawable) d).getForeground() : d;
        return IconSource.render(fg);
    }

    /** Our own packages: this app and its icon pack. */
    static boolean isSelf(Context c, String pkg) {
        return c.getPackageName().equals(pkg) || PackBuilder.PACK_PKG.equals(pkg);
    }

    static void mkdirs() throws IOException {
        for (boolean night : new boolean[]{false, true}) {
            File d = Flyme.cloudDir(night);
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("mkdir " + d);
        }
    }

    static String writePng(Bitmap b, File f) throws IOException {
        return writeBytes(png(b), f);
    }

    static byte[] png(Bitmap b) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("png");
        return out.toByteArray();
    }

    static String writeBytes(byte[] data, File f) throws IOException {
        File tmp = new File(f.getParentFile(), "." + f.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            // rename replaces the old file in one step (on the FUSE shared storage too)
            if (!tmp.renameTo(f)) throw new IOException("rename " + f);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        return sha1(f);
    }

    /**
     * path -> {inode:ctime:size, sha1}: the app list checks our files on every visit. Not the
     * mtime: a file renamed over another one on the shared storage keeps the old file's mtime.
     */
    private static final Map<String, String[]> sHashes = new HashMap<>();

    static String sha1(File f) {
        String stamp;
        try {
            android.system.StructStat st = android.system.Os.stat(f.getPath());
            stamp = st.st_ino + ":" + st.st_ctim.tv_sec + "." + st.st_ctim.tv_nsec + ":" + st.st_size;
        } catch (android.system.ErrnoException e) {
            return "";
        }
        synchronized (sHashes) {
            String[] h = sHashes.get(f.getPath());
            if (h != null && h[0].equals(stamp)) return h[1];
        }
        String h = sha1Uncached(f);
        if (!h.isEmpty()) {
            synchronized (sHashes) {
                sHashes.put(f.getPath(), new String[]{stamp, h});
            }
        }
        return h;
    }

    private static String sha1Uncached(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    static String sha1(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    // ------------------------------------------------------------------ preview

    /**
     * Render layers like the launcher: bg (or the theme background) + fg, the visible 72dp
     * middle of the 108dp layers, cut with the launcher mask.
     */
    static Bitmap render(Context c, Bitmap fg, Bitmap bg, boolean night, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Rect src = new Rect(Generator.VIS0, Generator.VIS0, Generator.VIS1, Generator.VIS1);
        Rect dst = new Rect(0, 0, size, size);
        Bitmap back = bg != null ? bg : Flyme.background(night);
        if (back != null) cv.drawBitmap(back, src, dst, p);
        if (fg != null) {
            // the launcher's "Content Size" scales the foreground layer
            float cs = Flyme.contentSize(c.getContentResolver());
            Rect fd = dst;
            if (cs > 0f && cs < 1f) {
                int inset = Math.round(size * (1f - cs) / 2f);
                fd = new Rect(inset, inset, size - inset, size - inset);
            }
            cv.drawBitmap(fg, src, fd, p);
        }
        Bitmap mask = Flyme.mask(c);
        Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        if (mask != null) {
            cv.drawBitmap(mask, null, dst, mp);
        } else {
            Bitmap m = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            new Canvas(m).drawRoundRect(0, 0, size, size, size * 0.23f, size * 0.23f, new Paint(Paint.ANTI_ALIAS_FLAG));
            cv.drawBitmap(m, 0, 0, mp);
        }
        return out;
    }
}
