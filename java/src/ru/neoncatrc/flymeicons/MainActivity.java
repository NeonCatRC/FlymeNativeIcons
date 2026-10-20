// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import flyme.support.v7.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int MENU_SETTINGS = 1;
    private static final int ICON_BATCH = 24;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final Map<String, Drawable> mIcons = new HashMap<>();
    /** What each loaded icon was drawn for (written on the UI thread, read in the background). */
    private final Map<String, String> mStamps = new ConcurrentHashMap<>();
    private Rows mRows;
    private Button mPrimary;
    private View mBar;
    private List<Icons.App> mApps = new ArrayList<>();
    private boolean mBusy;
    private volatile int mGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.fi_list);
        RecyclerView list = findViewById(R.id.fi_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        mRows = new Rows(this);
        list.setAdapter(mRows);
        mBar = findViewById(R.id.fi_bottom_bar);
        Ui.pinBottomBar(this, mBar);
        mPrimary = mBar.findViewById(R.id.fi_btn_primary);
        mPrimary.setOnClickListener(v -> convertAll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        // a running reload stops at its next check; queued work (a running "Style all") finishes
        mGeneration++;
        mBg.shutdown();
        mUi.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SETTINGS, 0, R.string.fi_settings)
                .setIcon(R.drawable.mz_titlebar_ic_setting)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        final int gen = ++mGeneration;
        final Context app = getApplicationContext();
        // what the launcher's drawing of every icon depends on besides the app itself
        final String look = Flyme.nightIcons(this) + ":" + Flyme.flymeIconsOn(getContentResolver()) + ":"
                + Flyme.cornerSize(getContentResolver()) + ":" + Flyme.contentSize(getContentResolver());
        mBg.execute(() -> {
            if (gen != mGeneration) return;
            final List<Icons.App> apps = Icons.scan(app);
            Store store = Store.get(app);
            if (Flyme.hasStorageAccess()) {
                ArrayList<String> changed = Icons.restore(app, store.restoreOn(), false, apps);
                for (Icons.App a : apps) if (changed.contains(a.pkg)) Icons.refreshState(app, store, a);
            }
            Icons.forgetUninstalled(app, apps);
            if (gen != mGeneration) return;
            mUi.post(() -> {
                if (gen != mGeneration) return;
                mApps = apps;
                show();
            });
            // icons as the launcher draws them now, only where something changed since the last
            // visit; posted in batches
            List<String> pkgs = new ArrayList<>();
            List<Drawable> icons = new ArrayList<>();
            List<String> stamps = new ArrayList<>();
            for (Icons.App a : apps) {
                if (gen != mGeneration) return;
                Store.Entry e = store.entry(a.pkg);
                String stamp = look + ":" + a.state + ":" + a.lastUpdate + ":" + (e != null ? e.time : 0);
                if (stamp.equals(mStamps.get(a.pkg))) continue;
                pkgs.add(a.pkg);
                icons.add(Icons.current(app, a));
                stamps.add(stamp);
                if (pkgs.size() == ICON_BATCH) {
                    postIcons(pkgs, icons, stamps);
                    pkgs = new ArrayList<>();
                    icons = new ArrayList<>();
                    stamps = new ArrayList<>();
                }
            }
            postIcons(pkgs, icons, stamps);
        });
    }

    private void postIcons(final List<String> pkgs, final List<Drawable> icons, final List<String> stamps) {
        if (pkgs.isEmpty()) return;
        mUi.post(() -> {
            if (isDestroyed()) return;
            for (int k = 0; k < pkgs.size(); k++) {
                String pkg = pkgs.get(k);
                mIcons.put(pkg, icons.get(k));
                mStamps.put(pkg, stamps.get(k));
                int i = mRows.indexOf(pkg);
                if (i >= 0) {
                    mRows.rows().get(i).icon = icons.get(k);
                    mRows.notifyItemChanged(i);
                }
            }
        });
    }

    private void show() {
        List<Rows.Row> rows = new ArrayList<>();
        boolean access = Flyme.hasStorageAccess();
        if (!access) {
            rows.add(Rows.Row.item("perm", getString(R.string.fi_perm_title), getString(R.string.fi_perm_summary))
                    .arrow(() -> Ui.requestStorage(this)));
        }
        if (!Flyme.flymeIconsOn(getContentResolver())) {
            rows.add(Rows.Row.item("off", getString(R.string.fi_off_title), getString(R.string.fi_off_summary)));
        }

        List<Icons.App> none = new ArrayList<>(), ours = new ArrayList<>(), flyme = new ArrayList<>();
        for (Icons.App a : mApps) {
            if (a.state == Icons.ST_NONE) none.add(a);
            else if (a.state == Icons.ST_OURS) ours.add(a);
            else flyme.add(a);
        }

        rows.add(Rows.Row.category(getString(R.string.fi_cat_status)));
        int color = Flyme.iconColor(getContentResolver());
        rows.add(Rows.Row.item("mode", getString(R.string.fi_mode_title), null).value(getString(
                color == Flyme.COLOR_DARK ? R.string.fi_mode_dark
                        : color == Flyme.COLOR_LIGHT ? R.string.fi_mode_light : R.string.fi_mode_system), null));
        rows.add(Rows.Row.item("count", getString(R.string.fi_count_title), null)
                .value(getString(R.string.fi_count_value, ours.size() + flyme.size(), mApps.size()), null));

        if (!none.isEmpty()) {
            rows.add(Rows.Row.category(getString(R.string.fi_cat_none) + " · " + none.size()));
            for (Icons.App a : none) rows.add(appRow(a));
        }
        if (!ours.isEmpty()) {
            rows.add(Rows.Row.category(getString(R.string.fi_cat_ours) + " · " + ours.size()));
            for (Icons.App a : ours) rows.add(appRow(a));
        }
        if (!flyme.isEmpty()) {
            rows.add(Rows.Row.category(getString(R.string.fi_cat_flyme) + " · " + flyme.size()));
            for (Icons.App a : flyme) rows.add(appRow(a));
        }
        if (none.isEmpty()) rows.add(Rows.Row.note(getString(R.string.fi_empty)));
        mRows.submit(rows);

        int todo = 0;
        Store store = Store.get(this);
        for (Icons.App a : none) if (!store.ignored(a.pkg)) todo++;
        View bar = mBar;
        bar.setVisibility(access && todo > 0 ? View.VISIBLE : View.GONE);
        if (!mBusy) mPrimary.setText(getString(R.string.fi_convert_all, todo));
        RecyclerView list = findViewById(R.id.fi_list);
        int pad = bar.getVisibility() == View.VISIBLE
                ? getResources().getDimensionPixelSize(R.dimen.mz_action_bar_default_height_appcompat_split) : 0;
        list.setPadding(0, 0, 0, pad + getResources().getDimensionPixelSize(R.dimen.fd_sys_margin_heavy));
    }

    private Rows.Row appRow(final Icons.App a) {
        String summary;
        Store store = Store.get(this);
        switch (a.state) {
            case Icons.ST_OURS: {
                Store.Entry e = store.entry(a.pkg);
                boolean day = e != null && e.writes(Store.DAY_FG), night = e != null && e.writes(Store.NIGHT_FG);
                summary = getString(day && night ? R.string.fi_ours_both : night ? R.string.fi_ours_night : R.string.fi_ours_day);
                break;
            }
            case Icons.ST_SYSTEM:
                summary = getString(R.string.fi_flyme_system);
                break;
            case Icons.ST_CLOUD:
                summary = getString(R.string.fi_flyme_cloud);
                break;
            default:
                summary = null;
        }
        if (a.flymeNoNight) summary += ", " + getString(R.string.fi_flyme_no_night);
        if (a.state == Icons.ST_NONE && store.ignored(a.pkg)) summary = getString(R.string.fi_ignored);
        Rows.Row r = Rows.Row.item(a.pkg, a.label, summary).arrow(() ->
                startActivity(new Intent(this, AppActivity.class).putExtra(AppActivity.EXTRA_PKG, a.pkg)
                        .putExtra(AppActivity.EXTRA_LABEL, a.label)));
        r.icon = mIcons.get(a.pkg);
        return r;
    }

    private void convertAll() {
        if (mBusy) return;
        if (!Flyme.hasStorageAccess()) {
            Ui.toast(this, R.string.fi_need_access);
            return;
        }
        final List<Icons.App> todo = new ArrayList<>();
        Store store = Store.get(this);
        for (Icons.App a : mApps) if (a.state == Icons.ST_NONE && !store.ignored(a.pkg)) todo.add(a);
        if (todo.isEmpty()) return;
        Ui.confirm(this, getString(R.string.fi_convert_title, todo.size()), getString(R.string.fi_convert_msg),
                getString(R.string.fi_apply), () -> runConvert(todo));
    }

    private void runConvert(final List<Icons.App> todo) {
        mBusy = true;
        mPrimary.setEnabled(false);
        final Context app = getApplicationContext();
        mBg.execute(() -> {
            ArrayList<String> done = new ArrayList<>();
            Store store = Store.get(app);
            store.beginBatch();
            try {
                int i = 0;
                for (Icons.App a : todo) {
                    final int n = ++i;
                    mUi.post(() -> mPrimary.setText(app.getString(R.string.fi_working, n, todo.size())));
                    try {
                        if (Icons.apply(app, a, new Options(), false) != null) done.add(a.pkg);
                    } catch (Exception | OutOfMemoryError e) {
                        android.util.Log.w(Icons.TAG, "convert " + a.pkg, e);
                    }
                }
            } finally {
                // whatever happened, the button comes back
                store.endBatch();
                if (!done.isEmpty()) Flyme.notifyLauncher(app, done);
                mUi.post(() -> {
                    mBusy = false;
                    mPrimary.setEnabled(true);
                    Ui.toast(app, app.getString(R.string.fi_done_count, done.size()));
                    if (!isDestroyed()) reload();
                });
            }
        });
    }
}
