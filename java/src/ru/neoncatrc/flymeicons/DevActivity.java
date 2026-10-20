// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Developer aid for tools/devtool.py. The manifest guards it with android.permission.DUMP: the
 * adb shell holds it, ordinary apps cannot get it.
 *   am start -n ru.neoncatrc.flymeicons/.DevActivity --ez fi_dump true
 *   am start -n ru.neoncatrc.flymeicons/.DevActivity --ez fi_pack_dump true [--ez night true]
 * Stays (transparent) on screen while it works, so the process keeps running in the foreground.
 */
public class DevActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final Context app = getApplicationContext();
        final Intent in = getIntent();
        new Thread(() -> {
            try {
                if (in.getBooleanExtra("fi_dump", false)) Debug.dump(app);
                if (in.getBooleanExtra("fi_pack_dump", false)) Debug.packDump(app, in.getBooleanExtra("night", false));            } finally {
                runOnUiThread(this::finish);
            }
        }, "fi-dev").start();
    }
}
