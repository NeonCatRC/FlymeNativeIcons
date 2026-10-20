// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Not exported: the system delivers these two to it anyway. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        RestoreJob.schedule(c);
        RestoreJob.runNow(c);
    }
}
