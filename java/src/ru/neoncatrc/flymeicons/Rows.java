// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import flyme.support.v7.widget.MzRecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * List built from the Flyme 12 "immersive" preference templates of the UI library:
 * cards are runs of rows drawn with immersive_mz_preference_{head,middle,foot,alone}.
 */
final class Rows extends RecyclerView.Adapter<Rows.Holder> implements MzRecyclerView.m {
    static final int T_CATEGORY = 0, T_ITEM = 1, T_SEEK = 3, T_NOTE = 4;
    static final int P_ALONE = 0, P_HEAD = 1, P_MIDDLE = 2, P_FOOT = 3;
    static final int W_NONE = 0, W_ARROW = 1, W_SWITCH = 2, W_VALUE = 3;

    static final class Row {
        int type = T_ITEM;
        String key;
        CharSequence title, summary, value;
        Drawable icon;
        int widget = W_NONE;
        boolean checked, enabled = true;
        /** colour dot before the value, 0 = none */
        int swatch;
        Runnable onClick;
        Consumer<Boolean> onCheck;
        int seekMin, seekMax, seekValue, seekStep = 1;
        IntConsumer onSeek;
        /** value text for the seek bar, default "N%" */
        java.util.function.IntFunction<String> seekFormat;
        int pos;

        static Row category(CharSequence title) {
            Row r = new Row();
            r.type = T_CATEGORY;
            r.title = title;
            return r;
        }

        static Row item(String key, CharSequence title, CharSequence summary) {
            Row r = new Row();
            r.key = key;
            r.title = title;
            r.summary = summary;
            return r;
        }

        static Row note(CharSequence text) {
            Row r = new Row();
            r.type = T_NOTE;
            r.summary = text;
            return r;
        }

        Row arrow(Runnable click) {
            widget = W_ARROW;
            onClick = click;
            return this;
        }

        Row value(CharSequence v, Runnable click) {
            widget = W_VALUE;
            value = v;
            onClick = click;
            return this;
        }

        Row toggle(boolean on, Consumer<Boolean> check) {
            widget = W_SWITCH;
            checked = on;
            onCheck = check;
            return this;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) {
            super(v);
        }
    }

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final List<Row> mRows = new ArrayList<>();
    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** seek value not yet handed to its row's onSeek */
    private Runnable mPendingSeek;

    Rows(Context c) {
        mContext = c;
        mInflater = LayoutInflater.from(c);
    }

    void submit(List<Row> rows) {
        mRows.clear();
        mRows.addAll(rows);
        // card positions: consecutive card rows form one card
        for (int i = 0; i < mRows.size(); i++) {
            Row r = mRows.get(i);
            if (!isCard(r)) continue;
            boolean prev = i > 0 && isCard(mRows.get(i - 1));
            boolean next = i + 1 < mRows.size() && isCard(mRows.get(i + 1));
            r.pos = prev ? (next ? P_MIDDLE : P_FOOT) : (next ? P_HEAD : P_ALONE);
        }
        notifyDataSetChanged();
    }

    List<Row> rows() {
        return mRows;
    }

    /** Deliver a seek value still waiting for the debounce (call before acting on the options). */
    void flushSeek() {
        if (mPendingSeek == null) return;
        Runnable r = mPendingSeek;
        mPendingSeek = null;
        mHandler.removeCallbacks(r);
        r.run();
    }

    private void postSeek(final Row r, long delay) {
        if (mPendingSeek != null) mHandler.removeCallbacks(mPendingSeek);
        final int value = r.seekValue;
        mPendingSeek = () -> {
            mPendingSeek = null;
            if (r.onSeek != null) r.onSeek.accept(value);
        };
        if (delay <= 0) flushSeek();
        else mHandler.postDelayed(mPendingSeek, delay);
    }

    int indexOf(String key) {
        for (int i = 0; i < mRows.size(); i++) if (key.equals(mRows.get(i).key)) return i;
        return -1;
    }

    private static boolean isCard(Row r) {
        return r.type == T_ITEM || r.type == T_SEEK;
    }

    /** MzRecyclerView draws its own full-width press selector unless the row is disabled here. */
    @Override
    public boolean isEnabled(int position) {
        Row r = mRows.get(position);
        return r.type == T_ITEM && r.enabled && (r.onClick != null || r.widget == W_SWITCH);
    }

    @Override
    public boolean d(int position) {
        return false;
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @Override
    public int getItemViewType(int position) {
        Row r = mRows.get(position);
        return r.type * 10 + (isCard(r) ? r.pos : 0);
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        int type = viewType / 10, pos = viewType % 10;
        View v;
        switch (type) {
            case T_CATEGORY:
                v = mInflater.inflate(R.layout.mz_preference_category_immersive, parent, false);
                break;
            case T_NOTE:
                v = mInflater.inflate(R.layout.fi_note, parent, false);
                break;
            case T_SEEK:
                v = mInflater.inflate(R.layout.fi_seek, parent, false);
                v.setBackgroundResource(cardBackground(pos));
                break;
            default:
                v = mInflater.inflate(itemLayout(pos), parent, false);
                ViewGroup frame = v.findViewById(android.R.id.widget_frame);
                mInflater.inflate(R.layout.fi_widget_arrow, frame, true);
                mInflater.inflate(R.layout.mc_preference_widget_switch, frame, true);
                break;
        }
        return new Holder(v);
    }

    private static int itemLayout(int pos) {
        switch (pos) {
            case P_HEAD: return R.layout.immersive_mz_preference_head;
            case P_MIDDLE: return R.layout.immersive_mz_preference_middle;
            case P_FOOT: return R.layout.immersive_mz_preference_foot;
            default: return R.layout.immersive_mz_preference_alone;
        }
    }

    private static int cardBackground(int pos) {
        switch (pos) {
            case P_HEAD: return R.drawable.immersive_item_bkg_head;
            case P_MIDDLE: return R.drawable.immersive_item_bkg_middle;
            case P_FOOT: return R.drawable.immersive_item_bkg_foot;
            default: return R.drawable.immersive_item_bkg_alone;
        }
    }

    @Override
    public void onBindViewHolder(Holder h, int position) {
        final Row r = mRows.get(position);
        View v = h.itemView;
        switch (r.type) {
            case T_CATEGORY: {
                // an untitled category is just the gap between two cards
                TextView t = v.findViewById(android.R.id.title);
                t.setText(r.title);
                t.setVisibility(r.title == null || r.title.length() == 0 ? View.GONE : View.VISIBLE);
                return;
            }
            case T_NOTE:
                ((TextView) v.findViewById(android.R.id.summary)).setText(r.summary);
                return;
            case T_SEEK:
                bindSeek(v, r);
                return;
            default:
                bindItem(v, r);
        }
    }

    private void bindSeek(View v, final Row r) {
        ((TextView) v.findViewById(android.R.id.title)).setText(r.title);
        final TextView value = v.findViewById(R.id.fi_value);
        value.setText(r.value);
        SeekBar sb = v.findViewById(R.id.fi_seek);
        sb.setOnSeekBarChangeListener(null);
        sb.setMax((r.seekMax - r.seekMin) / r.seekStep);
        sb.setProgress((r.seekValue - r.seekMin) / r.seekStep);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                r.seekValue = r.seekMin + p * r.seekStep;
                r.value = r.seekFormat != null ? r.seekFormat.apply(r.seekValue) : r.seekValue + "%";
                value.setText(r.value);
                // a tap on the track may skip onStopTrackingTouch on some seek bars
                postSeek(r, 150);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                postSeek(r, 0);
            }
        });
    }

    private void bindItem(View v, final Row r) {
        TextView title = v.findViewById(android.R.id.title);
        TextView summary = v.findViewById(android.R.id.summary);
        title.setText(r.title);
        summary.setText(r.summary);
        summary.setVisibility(r.summary == null || r.summary.length() == 0 ? View.GONE : View.VISIBLE);

        View iconFrame = v.findViewById(android.R.id.icon_frame);
        ImageView icon = v.findViewById(android.R.id.icon);
        iconFrame.setVisibility(r.icon == null ? View.GONE : View.VISIBLE);
        icon.setImageDrawable(r.icon);

        View arrow = v.findViewById(R.id.fi_arrow);
        TextView value = v.findViewById(R.id.fi_value);
        View swatch = v.findViewById(R.id.fi_swatch);
        final CompoundButton sw = v.findViewById(R.id.switchWidget);
        arrow.setVisibility(r.widget == W_ARROW || (r.widget == W_VALUE && r.onClick != null) ? View.VISIBLE : View.GONE);
        value.setVisibility(r.widget == W_VALUE ? View.VISIBLE : View.GONE);
        value.setText(r.value);
        if (r.swatch != 0) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(r.swatch | 0xff000000);
            swatch.setBackground(dot);
            swatch.setVisibility(View.VISIBLE);
        } else {
            swatch.setVisibility(View.GONE);
        }
        sw.setOnCheckedChangeListener(null);
        sw.setVisibility(r.widget == W_SWITCH ? View.VISIBLE : View.GONE);
        sw.setChecked(r.checked);
        sw.setEnabled(r.enabled);
        sw.setOnCheckedChangeListener((b, on) -> {
            r.checked = on;
            if (r.onCheck != null) r.onCheck.accept(on);
        });

        boolean clickable = r.enabled && (r.onClick != null || r.widget == W_SWITCH);
        v.setEnabled(r.enabled);
        v.setAlpha(r.enabled ? 1f : 0.4f);
        if (clickable) {
            v.setOnClickListener(x -> {
                if (r.widget == W_SWITCH) sw.toggle();
                else r.onClick.run();
            });
        } else {
            v.setOnClickListener(null);
            v.setClickable(false);
        }
    }
}
