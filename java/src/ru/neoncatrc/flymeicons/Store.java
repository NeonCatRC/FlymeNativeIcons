// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What we wrote into the cloud folders (so we never touch Flyme's own files) + app settings. */
final class Store {
    static final String PREF_RESTORE = "restore", PREF_AUTO_NEW = "auto_new";
    static final String PREF_PACK_NIGHT = "pack_night", PREF_PACK_COUNT = "pack_count";
    /** Slots: day fg, day bg, night fg, night bg. */
    static final int DAY_FG = 0, DAY_BG = 1, NIGHT_FG = 2, NIGHT_BG = 3;

    static final class Entry {
        String pkg;
        Options options = new Options();
        /** sha1 of the file we wrote per slot, null = slot unused. */
        String[] hash = new String[4];
        long versionCode;
        long time;
        /** Icons.GEN_REV the files were drawn with. */
        int rev = 1;

        boolean writes(int slot) {
            return hash[slot] != null;
        }
    }

    private static Store sInstance;

    private final File mFile;
    private final SharedPreferences mPrefs;
    private final Map<String, Entry> mEntries = new HashMap<>();
    /** Packages the user excluded from "convert all" / auto mode. */
    private final Set<String> mIgnored = new HashSet<>();
    private static final int BATCH_FLUSH = 16;
    private int mBatch, mDirty;

    static synchronized Store get(Context c) {
        if (sInstance == null) sInstance = new Store(c.getApplicationContext());
        return sInstance;
    }

    private Store(Context c) {
        mFile = new File(c.getFilesDir(), "registry.json");
        mPrefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        load();
    }

    SharedPreferences prefs() {
        return mPrefs;
    }

    boolean restoreOn() {
        return mPrefs.getBoolean(PREF_RESTORE, true);
    }

    boolean autoNewOn() {
        return mPrefs.getBoolean(PREF_AUTO_NEW, false);
    }

    synchronized Entry entry(String pkg) {
        return mEntries.get(pkg);
    }

    synchronized List<Entry> entries() {
        return new ArrayList<>(mEntries.values());
    }

    synchronized void put(Entry e) {
        mEntries.put(e.pkg, e);
        changed();
    }

    synchronized void remove(String pkg) {
        if (mEntries.remove(pkg) != null) changed();
    }

    synchronized boolean ignored(String pkg) {
        return mIgnored.contains(pkg);
    }

    synchronized void setIgnored(String pkg, boolean on) {
        if (on ? mIgnored.add(pkg) : mIgnored.remove(pkg)) changed();
    }

    /**
     * Many changes in a row ("Style all", "Remove all", restore): the registry is written every
     * BATCH_FLUSH changes and at endBatch() instead of after each one. Always pair in finally.
     */
    synchronized void beginBatch() {
        mBatch++;
    }

    synchronized void endBatch() {
        if (--mBatch == 0 && mDirty > 0) save();
    }

    private void changed() {
        if (mBatch > 0 && ++mDirty < BATCH_FLUSH) return;
        save();
    }

    private synchronized void load() {
        if (!mFile.exists()) return;
        try {
            byte[] data = new byte[(int) mFile.length()];
            try (FileInputStream in = new FileInputStream(mFile)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONObject apps = root.optJSONObject("apps");
            if (apps != null) {
                JSONArray names = apps.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    String pkg = names.getString(i);
                    JSONObject j = apps.getJSONObject(pkg);
                    Entry e = new Entry();
                    e.pkg = pkg;
                    e.options = Options.fromJson(j.optJSONObject("options"));
                    JSONArray h = j.optJSONArray("hash");
                    for (int k = 0; h != null && k < 4 && k < h.length(); k++)
                        e.hash[k] = h.isNull(k) ? null : h.getString(k);
                    e.versionCode = j.optLong("version");
                    e.time = j.optLong("time");
                    e.rev = j.optInt("rev", 1);
                    mEntries.put(pkg, e);
                }
            }
            JSONArray ign = root.optJSONArray("ignored");
            for (int i = 0; ign != null && i < ign.length(); i++) mIgnored.add(ign.getString(i));
        } catch (Exception e) {
            // start empty, but keep the broken file: the next save would overwrite it
            mEntries.clear();
            mIgnored.clear();
            File bad = new File(mFile.getPath() + ".bad");
            bad.delete();
            boolean kept = mFile.renameTo(bad);
            android.util.Log.e(Icons.TAG, "registry.json is unreadable" + (kept ? ", kept as " + bad : ""), e);
        }
    }

    private void save() {
        mDirty = 0;
        try {
            JSONObject apps = new JSONObject();
            for (Entry e : mEntries.values()) {
                JSONArray h = new JSONArray();
                for (String s : e.hash) h.put(s == null ? JSONObject.NULL : s);
                apps.put(e.pkg, new JSONObject()
                        .put("options", e.options.toJson())
                        .put("hash", h)
                        .put("version", e.versionCode)
                        .put("time", e.time)
                        .put("rev", e.rev));
            }
            JSONObject root = new JSONObject().put("apps", apps).put("ignored", new JSONArray(mIgnored));
            File tmp = new File(mFile.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            // rename replaces the old file in one step
            if (!tmp.renameTo(mFile)) {
                tmp.delete();
                throw new java.io.IOException("rename " + tmp);
            }
        } catch (Exception e) {
            android.util.Log.e(Icons.TAG, "registry.json not saved", e);
        }
    }
}
