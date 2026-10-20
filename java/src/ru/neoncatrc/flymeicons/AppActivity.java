// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import flyme.support.v7.app.ActionBar;
import flyme.support.v7.app.AppCompatActivity;

/** One app: preview of the generated day/night icon and its options. */
public class AppActivity extends AppCompatActivity {
    static final String EXTRA_PKG = "pkg", EXTRA_LABEL = "label";
    /** Flyme system palette (fd_sys_color_primary_*) plus neutrals. */
    private static final int[] PALETTE = {0xffe42d22, 0xfffa6400, 0xffffb000, 0xff1fb84c, 0xff00b8b8,
            0xff206cff, 0xff7b5cff, 0xffe8458b, 0xff8e8e93, 0xffeeeeee};

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    /** null until loaded in the background. */
    private Icons.App mApp;
    private Options mOptions;
    private Rows mRows;
    /** null while loading, and when the app's own icon cannot be read. */
    private Generator.Analysis mAnalysis;
    private Bitmap mNow, mDay, mNight;
    private int mBrand;
    private Button mApplyButton, mRemoveButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null) {
            finish();
            return;
        }
        CharSequence label = getIntent().getCharSequenceExtra(EXTRA_LABEL);
        if (label != null) setTitle(label);
        ActionBar ab = R();
        if (ab != null) ab.C(true);
        setContentView(R.layout.fi_app);
        RecyclerView list = findViewById(R.id.fi_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        mRows = new Rows(this);
        list.setAdapter(mRows);

        Store.Entry e = Store.get(this).entry(pkg);
        mOptions = e != null ? e.options.copy() : new Options();

        View bar = findViewById(R.id.fi_bottom_bar);
        Ui.pinBottomBar(this, bar);
        bar.setVisibility(View.VISIBLE);
        list.setPadding(0, list.getPaddingTop(), 0, getResources().getDimensionPixelSize(
                R.dimen.mz_action_bar_default_height_appcompat_split)
                + getResources().getDimensionPixelSize(R.dimen.fd_sys_margin_heavy));
        pinPreview(list);
        mApplyButton = bar.findViewById(R.id.fi_btn_primary);
        mApplyButton.setText(R.string.fi_apply);
        mApplyButton.setOnClickListener(v -> apply());
        mApplyButton.setEnabled(false);
        mRemoveButton = bar.findViewById(R.id.fi_btn_secondary);
        mRemoveButton.setText(R.string.fi_remove);
        mRemoveButton.setOnClickListener(v -> remove());
        mRemoveButton.setVisibility(View.GONE);

        // package manager calls and the icon analysis are too slow for the UI thread
        final android.content.Context ctx = getApplicationContext();
        mBg.execute(() -> {
            final Icons.App app = Icons.find(ctx, pkg);
            IconSource src = app == null ? null : IconSource.load(ctx, app.ai);
            final Generator.Analysis analysis = src == null ? null : Generator.analyze(src);
            final Bitmap now = app == null ? null : toBitmap(Icons.current(ctx, app), 192);
            mUi.post(() -> {
                if (isDestroyed()) return;
                if (app == null) {
                    finish();
                    return;
                }
                mApp = app;
                mAnalysis = analysis;
                mNow = now;
                setTitle(app.label);
                mRemoveButton.setVisibility(app.state == Icons.ST_OURS ? View.VISIBLE : View.GONE);
                // without the app's own icon there is nothing to draw from
                mApplyButton.setEnabled(analysis != null);
                showPreview();
                buildRows();
                updatePreview();
            });
        });
    }

    @Override
    protected void onDestroy() {
        // queued work still runs (an Apply pressed right before leaving), nothing new is taken
        mBg.shutdown();
        mUi.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void buildRows() {
        List<Rows.Row> rows = new ArrayList<>();

        if (mAnalysis == null) {
            rows.add(Rows.Row.note(getString(R.string.fi_unreadable)));
        } else if (mApp.state == Icons.ST_CLOUD || mApp.state == Icons.ST_SYSTEM) {
            rows.add(Rows.Row.note(getString(mApp.state == Icons.ST_CLOUD ? R.string.fi_flyme_cloud : R.string.fi_flyme_system)
                    + (mApp.flymeNoNight ? ", " + getString(R.string.fi_flyme_no_night) : "")));
        } else {
            rows.add(Rows.Row.note(getString(mAnalysis.legacy ? R.string.fi_kind_legacy
                    : mAnalysis.hasMono ? R.string.fi_kind_mono : R.string.fi_kind_adaptive)));
        }

        rows.add(Rows.Row.category(getString(R.string.fi_cat_variants)));
        rows.add(Rows.Row.item("night", getString(R.string.fi_opt_night), getString(R.string.fi_opt_night_summary))
                .toggle(mOptions.night, on -> {
                    mOptions.night = on;
                    buildRows();
                    updatePreview();
                }));
        if (mOptions.night) {
            rows.add(seek("dim", getString(R.string.fi_opt_dim), 0, Options.DIM_MAX, mOptions.dim, null, v -> {
                mOptions.dim = v;
                updatePreview();
            }));
        }
        final String[] days = {getString(R.string.fi_day_keep), getString(R.string.fi_day_original), getString(R.string.fi_day_light)};
        int day = effectiveDay();
        rows.add(Rows.Row.item("day", getString(R.string.fi_opt_day), null).value(days[day], () ->
                Ui.choose(this, getString(R.string.fi_opt_day), days, effectiveDay(), w -> {
                    mOptions.day = w;
                    buildRows();
                    updatePreview();
                })));

        rows.add(Rows.Row.category(getString(R.string.fi_cat_look)));
        final String[] glyphs = {getString(R.string.fi_glyph_auto), getString(R.string.fi_glyph_mono),
                getString(R.string.fi_glyph_fg), getString(R.string.fi_glyph_whole)};
        rows.add(Rows.Row.item("glyph", getString(R.string.fi_opt_glyph), null).value(glyphs[mOptions.glyph], () ->
                Ui.choose(this, getString(R.string.fi_opt_glyph), glyphs, mOptions.glyph, w -> {
                    mOptions.glyph = w;
                    buildRows();
                    updatePreview();
                })));
        Rows.Row color = Rows.Row.item("color", getString(R.string.fi_opt_color), null).value(
                getString(mOptions.color == 0 ? R.string.fi_color_auto : R.string.fi_color_custom), this::pickColor);
        color.swatch = mOptions.color != 0 ? mOptions.color : mBrand;
        rows.add(color);
        Rows.Row size = new Rows.Row();
        size.type = Rows.T_SEEK;
        size.key = "size";
        size.title = getString(R.string.fi_opt_size);
        size.seekMin = Options.SCALE_MIN;
        size.seekMax = Options.SCALE_MAX;
        size.seekStep = 5;
        size.seekValue = mOptions.scale;
        size.value = mOptions.scale + "%";
        size.onSeek = v -> {
            mOptions.scale = v;
            updatePreview();
        };
        rows.add(size);
        java.util.function.IntFunction<String> signed = v -> (v > 0 ? "+" : v < 0 ? "\u2212" : "") + Math.abs(v) + "%";
        rows.add(seek("dx", getString(R.string.fi_opt_dx), -Options.SHIFT_MAX, Options.SHIFT_MAX, mOptions.dx, signed, v -> {
            mOptions.dx = v;
            updatePreview();
        }));
        rows.add(seek("dy", getString(R.string.fi_opt_dy), -Options.SHIFT_MAX, Options.SHIFT_MAX, mOptions.dy, signed, v -> {
            mOptions.dy = v;
            updatePreview();
        }));
        if (mApp.state == Icons.ST_NONE || mApp.state == Icons.ST_OURS) {
            final Store store = Store.get(this);
            rows.add(Rows.Row.item("ignore", getString(R.string.fi_opt_ignore), null)
                    .toggle(store.ignored(mApp.pkg), on -> store.setIgnored(mApp.pkg, on)));
        }
        mRows.submit(rows);
    }

    private static Rows.Row seek(String key, String title, int min, int max, int value,
                                 java.util.function.IntFunction<String> format, java.util.function.IntConsumer onSeek) {
        Rows.Row r = new Rows.Row();
        r.type = Rows.T_SEEK;
        r.key = key;
        r.title = title;
        r.seekMin = min;
        r.seekMax = max;
        r.seekValue = value;
        r.seekFormat = format;
        r.value = format != null ? format.apply(value) : value + "%";
        r.onSeek = onSeek;
        return r;
    }

    private int effectiveDay() {
        if (mOptions.day != Options.DAY_AUTO) return mOptions.day;
        return mAnalysis != null ? Generator.autoDay(mAnalysis) : Options.DAY_LIGHT;
    }

    private void pickColor() {
        final String[] items = new String[PALETTE.length + 1];
        items[0] = getString(R.string.fi_color_auto) + "  " + (mBrand != 0 ? ColorKit.hex(mBrand) : "");
        for (int i = 0; i < PALETTE.length; i++) items[i + 1] = "●  " + ColorKit.hex(PALETTE[i]);
        int checked = 0;
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == mOptions.color) checked = i + 1;
        Ui.choose(this, getString(R.string.fi_opt_color), colored(items), checked, w -> {
            mOptions.color = w == 0 ? 0 : PALETTE[w - 1];
            buildRows();
            updatePreview();
        });
    }

    private CharSequence[] colored(String[] items) {
        CharSequence[] out = new CharSequence[items.length];
        out[0] = items[0];
        for (int i = 1; i < items.length; i++) {
            android.text.SpannableString s = new android.text.SpannableString(items[i]);
            s.setSpan(new android.text.style.ForegroundColorSpan(PALETTE[i - 1]), 0, 1, 0);
            out[i] = s;
        }
        return out;
    }

    /**
     * The preview lies over the list: the list starts below it and scrolls under it (even the
     * spring overscroll stays beneath), the fade under the card shows up once something is under.
     */
    private void pinPreview(final RecyclerView list) {
        final View wrap = findViewById(R.id.fi_preview_wrap);
        final View fade = findViewById(R.id.fi_preview_fade);
        final float fadeH = fade.getLayoutParams().height;
        final boolean[] touched = {false};
        wrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (list.getPaddingTop() == h) return;
            list.setPadding(0, h, 0, list.getPaddingBottom());
            // the padding arrives after the first layout and the list keeps its offset, which
            // would leave the first rows under the card: start from the top again
            if (!touched[0]) list.post(() -> list.scrollToPosition(0));
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state != 0) touched[0] = true;
            }

            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                fade.setAlpha(Math.max(0f, Math.min(1f, rv.computeVerticalScrollOffset() / fadeH)));
            }
        });
    }

    /** The pinned preview card above the options. */
    private void showPreview() {
        ((android.widget.ImageView) findViewById(R.id.fi_prev_now)).setImageBitmap(withShadow(mNow));
        ((android.widget.ImageView) findViewById(R.id.fi_prev_day)).setImageBitmap(withShadow(mDay));
        ((android.widget.ImageView) findViewById(R.id.fi_prev_night)).setImageBitmap(withShadow(mNight));
    }

    /** A soft drop shadow, so a white day tile does not melt into a white card. */
    static Bitmap withShadow(Bitmap icon) {
        if (icon == null) return null;
        int w = icon.getWidth(), pad = Math.round(w * 0.08f);
        Bitmap out = Bitmap.createBitmap(w + pad * 2, w + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x24000000);
        p.setMaskFilter(new android.graphics.BlurMaskFilter(w * 0.035f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawBitmap(icon.extractAlpha(), pad, pad + w * 0.02f, p);
        c.drawBitmap(icon, pad, pad, null);
        return out;
    }

    private void updatePreview() {
        final Generator.Analysis a = mAnalysis;
        if (a == null || mBg.isShutdown()) return;
        final Options o = mOptions.copy();
        mBg.execute(() -> {
            Generator.Result r = Generator.make(a, o);
            // what the launcher would show: our files, or the app's own icon when a slot is unused
            Bitmap day = r.dayFg != null ? Icons.render(this, r.dayFg, r.dayBg, false, 192)
                    : Icons.render(this, a.fgLayer, a.bgKind == Generator.BG_NONE ? null : a.bgLayer, false, 192);
            Bitmap night = r.nightFg != null ? Icons.render(this, r.nightFg, r.nightBg, true, 192) : day;
            final int brand = r.brand;
            mUi.post(() -> {
                if (isDestroyed()) return;
                mDay = day;
                mNight = night;
                mBrand = brand;
                showPreview();
                int c = mRows.indexOf("color");
                if (c >= 0) {
                    mRows.rows().get(c).swatch = mOptions.color != 0 ? mOptions.color : mBrand;
                    mRows.notifyItemChanged(c);
                }
            });
        });
    }

    private void apply() {
        if (mApp == null || mAnalysis == null) return;
        mRows.flushSeek();
        if (!Flyme.hasStorageAccess()) {
            Ui.toast(this, R.string.fi_need_access);
            Ui.requestStorage(this);
            return;
        }
        if (!mOptions.night && effectiveDay() == Options.DAY_KEEP) {
            // neither variant would be written: for an icon of ours that means removing it
            if (mApp.state == Icons.ST_OURS) remove();
            else Ui.toast(this, R.string.fi_nothing);
            return;
        }
        if (mApp.state == Icons.ST_CLOUD) {
            Ui.confirm(this, getString(R.string.fi_replace_title), getString(R.string.fi_replace_msg),
                    getString(R.string.fi_replace), () -> doApply(true));
        } else {
            doApply(false);
        }
    }

    private void doApply(final boolean force) {
        final Options o = mOptions.copy();
        final Icons.App app = mApp;
        final android.content.Context ctx = getApplicationContext();
        mApplyButton.setEnabled(false);
        mBg.execute(() -> {
            String error = null;
            boolean ok = false;
            try {
                ok = Icons.apply(ctx, app, o, force) != null;
            } catch (Exception | OutOfMemoryError e) {
                android.util.Log.w(Icons.TAG, "apply " + app.pkg, e);
                error = String.valueOf(e.getMessage());
            }
            if (ok) {
                ArrayList<String> pkgs = new ArrayList<>();
                pkgs.add(app.pkg);
                Flyme.notifyLauncher(ctx, pkgs);
            }
            final boolean done = ok;
            final String why = error;
            mUi.post(() -> {
                Ui.toast(ctx, done ? ctx.getString(R.string.fi_applied) : why != null
                        ? ctx.getString(R.string.fi_failed_reason, why) : ctx.getString(R.string.fi_failed));
                if (isDestroyed()) return;
                if (done) finish();
                else mApplyButton.setEnabled(true);
            });
        });
    }

    /** Also excludes the app from automatic styling, or it would get its icon back. */
    private void remove() {
        if (mApp == null) return;
        final Icons.App app = mApp;
        final android.content.Context ctx = getApplicationContext();
        mBg.execute(() -> {
            Icons.remove(ctx, app.pkg);
            Store.get(ctx).setIgnored(app.pkg, true);
            ArrayList<String> pkgs = new ArrayList<>();
            pkgs.add(app.pkg);
            Flyme.notifyLauncher(ctx, pkgs);
            mUi.post(() -> {
                Ui.toast(ctx, R.string.fi_removed);
                if (!isDestroyed()) finish();
            });
        });
    }

    static Bitmap toBitmap(Drawable d, int size) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }
}
