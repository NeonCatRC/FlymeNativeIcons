// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Developer aids started through DevActivity; results go to Android/data/ru.neoncatrc.flymeicons/files. */
final class Debug {
    private Debug() {}

    /**
     * Now/day/night previews of every app without a Flyme icon into files/dump, plus index.tsv
     * (no shared-storage access needed).
     */
    static void dump(Context c) {
        File dir = new File(c.getExternalFilesDir(null), "dump");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        dir.mkdirs();
        List<Icons.App> apps = Icons.scan(c);
        StringBuilder sb = new StringBuilder();
        for (Icons.App a : apps) {
            if (a.state != Icons.ST_NONE && a.state != Icons.ST_OURS) continue;
            try {
                IconSource src = IconSource.load(c, a.ai);
                if (src == null) throw new IllegalStateException("icon not readable");
                Generator.Analysis an = Generator.analyze(src);
                Generator.Result r = Generator.make(an, new Options());
                Drawable now = Icons.current(c, a);
                save(AppActivity.toBitmap(now, 192), new File(dir, a.pkg + "_0now.png"));
                Bitmap day = r.dayFg != null ? Icons.render(c, r.dayFg, r.dayBg, false, 192)
                        : Icons.render(c, an.fgLayer, an.bgKind == Generator.BG_NONE ? null : an.bgLayer, false, 192);
                save(day, new File(dir, a.pkg + "_1day.png"));
                if (r.nightFg != null) save(Icons.render(c, r.nightFg, r.nightBg, true, 192), new File(dir, a.pkg + "_2night.png"));
                sb.append(a.pkg).append('\t').append(a.label).append('\t')
                        .append(an.legacy ? "legacy" : "adaptive").append(an.tile ? "+tile" : "")
                        .append(an.hasMono ? "+mono" : "").append("\tbg=").append(an.bgKind)
                        .append(' ').append(ColorKit.hex(an.bgColor)).append("\tbrand=").append(ColorKit.hex(r.brand))
                        .append("\tglyph=").append(r.glyph).append("\tday=").append(r.day).append('\n');
            } catch (Throwable t) {
                sb.append(a.pkg).append("\tERROR ").append(t).append('\n');
            }
        }
        try (FileWriter w = new FileWriter(new File(dir, "index.tsv"))) {
            w.write(sb.toString());
        } catch (Exception ignored) {
        }
        android.util.Log.i(Icons.TAG, "dump ok: " + dir);
    }

    /** Build the icon pack into files/pack.apk without installing it; the result goes to the log. */
    static void packDump(Context c, boolean night) {
        String msg;
        try {
            PackBuilder.Result r = PackBuilder.build(c, night, (d, t) -> {});
            File out = new File(c.getExternalFilesDir(null), "pack.apk");
            try (InputStream in = new FileInputStream(r.apk); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            msg = "pack dump ok: " + r.icons + (r.skipped > 0 ? ", did not fit: " + r.skipped : "");
        } catch (Throwable e) {
            android.util.Log.w(Icons.TAG, "pack dump", e);
            msg = "pack dump failed: " + e;
        }
        android.util.Log.i(Icons.TAG, msg);
    }

    private static void save(Bitmap b, File f) throws Exception {
        if (b == null) return;
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
