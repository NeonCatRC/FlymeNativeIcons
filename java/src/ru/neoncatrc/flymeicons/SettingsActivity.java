// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import flyme.support.v7.app.ActionBar;
import flyme.support.v7.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private Rows mRows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ActionBar ab = R();
        if (ab != null) ab.C(true);
        setContentView(R.layout.fi_list);
        RecyclerView list = findViewById(R.id.fi_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        mRows = new Rows(this);
        list.setAdapter(mRows);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PackInstall.setListener(this::packChanged);
        build();
    }

    private void packChanged() {
        String status = PackInstall.status();
        int i = mRows.indexOf("pack");
        if (status != null && i >= 0 && !mRows.rows().get(i).enabled) {
            // build progress: only the summary of the row changes
            mRows.rows().get(i).summary = status;
            mRows.notifyItemChanged(i);
        } else {
            build();
        }
    }

    @Override
    protected void onPause() {
        PackInstall.setListener(null);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void build() {
        final Store store = Store.get(this);
        List<Rows.Row> rows = new ArrayList<>();
        rows.add(Rows.Row.category(getString(R.string.fi_cat_auto)));
        rows.add(Rows.Row.item("restore", getString(R.string.fi_set_restore), getString(R.string.fi_set_restore_summary))
                .toggle(store.restoreOn(), on -> store.prefs().edit().putBoolean(Store.PREF_RESTORE, on).apply()));
        rows.add(Rows.Row.item("auto", getString(R.string.fi_set_auto_new), getString(R.string.fi_set_auto_new_summary))
                .toggle(store.autoNewOn(), on -> store.prefs().edit().putBoolean(Store.PREF_AUTO_NEW, on).apply()));
        rows.add(Rows.Row.item("bg", getString(R.string.fi_set_background), getString(R.string.fi_set_background_summary))
                .arrow(() -> Ui.openBackgroundSettings(this)));

        rows.add(Rows.Row.category(getString(R.string.fi_cat_data)));
        rows.add(Rows.Row.item("folder", getString(R.string.fi_set_folder), Flyme.cloudRoot().getPath()));
        int n = 0;
        for (Store.Entry e : store.entries()) if (!Icons.isSelf(this, e.pkg)) n++;
        final int count = n;
        Rows.Row del = Rows.Row.item("remove_all", getString(R.string.fi_set_remove_all), null).arrow(() ->
                Ui.confirm(this, getString(R.string.fi_set_remove_all), getString(R.string.fi_remove_all_msg, count),
                        getString(R.string.fi_delete), this::removeAll));
        del.enabled = count > 0;
        rows.add(del);

        rows.add(Rows.Row.category(getString(R.string.fi_cat_pack)));
        final boolean packInstalled = PackBuilder.installed(this);
        final String packStatus = PackInstall.status();
        String packSummary = packStatus != null ? packStatus : packInstalled
                ? getString(R.string.fi_pack_summary_installed,
                getString(store.prefs().getBoolean(Store.PREF_PACK_NIGHT, false) ? R.string.fi_pack_dark : R.string.fi_pack_light),
                store.prefs().getInt(Store.PREF_PACK_COUNT, 0))
                : getString(R.string.fi_pack_summary_none);
        Rows.Row pack = Rows.Row.item("pack", getString(R.string.fi_pack_build), packSummary).arrow(this::buildPack);
        pack.enabled = packStatus == null;
        rows.add(pack);
        if (packInstalled) {
            rows.add(Rows.Row.item("pack_remove", getString(R.string.fi_pack_remove), null).arrow(() -> {
                try {
                    PackInstall.uninstall(this);
                } catch (Exception e) {
                    Ui.toast(this, getString(R.string.fi_pack_remove_failed, String.valueOf(e.getMessage())));
                }
            }));
        }
        rows.add(Rows.Row.note(getString(R.string.fi_pack_note)));

        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
        }
        final String ver = version;
        rows.add(Rows.Row.category(""));
        // the version is long ("Release[date time]") and would be cut in the row: the dialog shows it whole
        rows.add(Rows.Row.item("about", getString(R.string.fi_about), null).arrow(() -> Ui.about(this, ver)));
        mRows.submit(rows);
    }

    private void buildPack() {
        if (PackInstall.status() != null) return;
        if (!Flyme.hasStorageAccess()) {
            Ui.toast(this, R.string.fi_need_access);
            return;
        }
        if (!PackInstall.canInstall(this)) return;
        final Store store = Store.get(this);
        String[] items = {getString(R.string.fi_pack_light), getString(R.string.fi_pack_dark)};
        Ui.choose(this, getString(R.string.fi_pack_variant), items,
                store.prefs().getBoolean(Store.PREF_PACK_NIGHT, false) ? 1 : 0, w -> runPack(w == 1));
    }

    /** The build outlives this screen: it keeps to the application context and PackInstall's state. */
    private void runPack(final boolean night) {
        final Context app = getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        PackInstall.setStatus(getString(R.string.fi_pack_working, 0, 0));
        new Thread(() -> {
            try {
                PackBuilder.Result r = PackBuilder.build(app, night, (done, total) ->
                        ui.post(() -> PackInstall.setStatus(app.getString(R.string.fi_pack_working, done, total))));
                ui.post(() -> PackInstall.setStatus(app.getString(R.string.fi_pack_installing)));
                PackInstall.install(app, r, night);
            } catch (Throwable e) {
                // OutOfMemoryError included: the row must not stay disabled
                android.util.Log.w(Icons.TAG, "pack", e);
                ui.post(() -> {
                    PackInstall.setStatus(null);
                    Ui.toast(app, app.getString(R.string.fi_pack_failed, String.valueOf(e.getMessage())));
                });
            }
        }, "fi-pack").start();
    }

    private void removeAll() {
        final Context app = getApplicationContext();
        new Thread(() -> {
            ArrayList<String> pkgs = new ArrayList<>();
            Store store = Store.get(app);
            store.beginBatch();
            try {
                for (Store.Entry e : store.entries()) {
                    if (Icons.isSelf(app, e.pkg)) continue;
                    Icons.remove(app, e.pkg);
                    pkgs.add(e.pkg);
                }
            } finally {
                store.endBatch();
                if (!pkgs.isEmpty()) Flyme.notifyLauncher(app, pkgs);
                runOnUiThread(() -> {
                    Ui.toast(app, R.string.fi_removed);
                    if (!isDestroyed()) build();
                });
            }
        }, "fi-remove").start();
    }
}
