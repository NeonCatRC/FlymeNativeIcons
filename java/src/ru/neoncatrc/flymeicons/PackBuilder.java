// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ADW icon pack for launchers / Flyme's icon pack mode: every launchable app as the Flyme
 * engine would draw it (our files, Flyme's own, or generated on the fly), in one variant.
 * The template (assets/pack_template.apk) has placeholder drawables fi_NNN; we replace their
 * PNGs, write assets/appfilter.xml and sign the result.
 */
final class PackBuilder {
    static final String PACK_PKG = "ru.neoncatrc.flymeicons.pack";
    static final int ICON_PX = 256;
    private static final Pattern SLOT = Pattern.compile("res/drawable[^/]*/fi_(\\d{3})\\.png");

    interface Progress {
        void step(int done, int total);
    }

    static final class Result {
        File apk;
        int icons;
        /** Apps left out: more of them than slots in the template. */
        int skipped;
    }

    private PackBuilder() {}

    static Result build(Context c, boolean night, Progress progress) throws Exception {
        // the template is small (1x1 placeholders): its entries stay in memory
        Map<Integer, String> slotPaths = new LinkedHashMap<>();
        String iconPath = null;
        Map<String, byte[]> template = new LinkedHashMap<>();
        try (InputStream in = c.getAssets().open("pack_template.apk"); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || name.startsWith("META-INF/")) continue;
                template.put(name, readAll(z));
                Matcher m = SLOT.matcher(name);
                if (m.matches()) slotPaths.put(Integer.parseInt(m.group(1)), name);
                else if (name.matches("res/drawable[^/]*/ic\\.png")) iconPath = name;
            }
        }

        // one icon per package, mapped from every launcher activity of it
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> ris = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL);
        Map<String, List<ComponentName>> components = new LinkedHashMap<>();
        Map<String, ActivityInfo> first = new HashMap<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            // the launcher draws these itself (live date / clock hands); a static icon would be worse
            if (pkg.equals(PACK_PKG) || pkg.equals("com.android.calendar") || pkg.equals("com.android.alarmclock")) continue;
            List<ComponentName> list = components.get(pkg);
            if (list == null) components.put(pkg, list = new ArrayList<>());
            list.add(new ComponentName(pkg, ri.activityInfo.name));
            if (!first.containsKey(pkg)) first.put(pkg, ri.activityInfo);
        }

        File out = new File(c.getCacheDir(), "flymeicons-pack.apk");
        Result r = new Result();
        r.apk = out;
        StringBuilder filter = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
        // icons go to the file one by one as they are drawn
        try (ApkWriter w = new ApkWriter(out)) {
            // manifest and resources first, like aapt does
            for (String name : new String[]{"AndroidManifest.xml", "resources.arsc"}) {
                byte[] data = template.remove(name);
                if (data != null) w.add(name, data);
            }
            int slot = 0, done = 0, total = components.size();
            for (Map.Entry<String, List<ComponentName>> en : components.entrySet()) {
                if (slot >= slotPaths.size()) {
                    r.skipped = total - done;
                    android.util.Log.w(Icons.TAG, "pack: " + r.skipped + " apps do not fit into " + slot + " slots");
                    break;
                }
                progress.step(++done, total);
                Bitmap icon = icon(c, en.getKey(), first.get(en.getKey()), night);
                if (icon == null) continue;
                String path = slotPaths.get(slot);
                w.add(path, png(icon));
                template.remove(path);
                String drawable = String.format(Locale.ROOT, "fi_%03d", slot);
                for (ComponentName cn : en.getValue()) {
                    filter.append("    <item component=\"").append(xml(cn.toString())).append("\" drawable=\"")
                            .append(drawable).append("\"/>\n");
                }
                slot++;
            }
            r.icons = slot;
            filter.append("</resources>\n");
            if (iconPath != null) {
                w.add(iconPath, png(Icons.render(c, Icons.packLayerBitmap(c, night), null, night, ICON_PX)));
                template.remove(iconPath);
            }
            // the rest of the template, unused slots with their placeholders
            for (Map.Entry<String, byte[]> en : template.entrySet()) w.add(en.getKey(), en.getValue());
            w.add("assets/appfilter.xml", filter.toString().getBytes(StandardCharsets.UTF_8));
            w.finish();
        }
        return r;
    }

    /** The icon the Flyme engine would show for this variant. */
    static Bitmap icon(Context c, String pkg, ActivityInfo ai, boolean night) {
        Bitmap[] l = flymeLayers(pkg, night);
        if (l == null && night) l = flymeLayers(pkg, false);
        if (l != null) return Icons.render(c, l[0], l[1], l[2] != null, ICON_PX);
        // no Flyme file: what this app would generate with its saved (or default) options
        IconSource src = IconSource.load(c, ai);
        if (src == null) return null;
        Generator.Analysis a = Generator.analyze(src);
        Store.Entry e = Store.get(c).entry(pkg);
        Generator.Result r = Generator.make(a, e != null ? e.options : new Options());
        if (night && r.nightFg != null) return Icons.render(c, r.nightFg, r.nightBg, true, ICON_PX);
        if (r.dayFg != null) return Icons.render(c, r.dayFg, r.dayBg, false, ICON_PX);
        return Icons.render(c, a.fgLayer, a.bgKind == Generator.BG_NONE ? null : a.bgLayer, false, ICON_PX);
    }

    /** {fg, bg or null (theme background), non-null marker when it is a night file} */
    private static Bitmap[] flymeLayers(String pkg, boolean night) {
        File fg = Flyme.cloudFile(pkg, night, true);
        if (fg.exists()) {
            Bitmap f = BitmapFactory.decodeFile(fg.getPath());
            File bg = Flyme.cloudFile(pkg, night, false);
            Bitmap b = bg.exists() ? BitmapFactory.decodeFile(bg.getPath()) : null;
            if (f != null) return new Bitmap[]{f, b, night ? f : null};
        }
        if (Flyme.systemPackages(night).contains(pkg)) {
            Bitmap f = Flyme.systemLayer(pkg, night, true);
            Bitmap b = Flyme.systemLayer(pkg, night, false);
            if (f != null) return new Bitmap[]{f, b, night ? f : null};
        }
        return null;
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACK_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static byte[] png(Bitmap b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
