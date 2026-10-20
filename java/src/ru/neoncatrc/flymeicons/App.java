// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class App extends Application {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Activity mResumed;

    @Override
    public void onCreate() {
        super.onCreate();
        RestoreJob.schedule(this);
        // while we are alive, react to installs/updates faster than the periodic job
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getData() != null) PackInstall.onPackageChanged(App.this, i.getData().getSchemeSpecificPart());
                mHandler.removeCallbacksAndMessages(null);
                mHandler.postDelayed(() -> RestoreJob.runNow(App.this), Icons.SETTLE_MS + 5000);
            }
        }, f, Context.RECEIVER_NOT_EXPORTED);
        // icon pack install results: here rather than in the settings screen, so they still
        // arrive when the user has left it while the pack was being built
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                PackInstall.onStatus(App.this, i);
            }
        }, new IntentFilter(PackInstall.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                mResumed = a;
                PackInstall.onResumed(App.this, a);
            }

            @Override
            public void onActivityPaused(Activity a) {
                if (mResumed == a) mResumed = null;
            }

            @Override
            public void onActivityCreated(Activity a, Bundle state) {
            }

            @Override
            public void onActivityStarted(Activity a) {
            }

            @Override
            public void onActivityStopped(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle state) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
    }

    /** Our activity on screen right now, or null. */
    Activity resumed() {
        return mResumed;
    }

    /** Flyme UI library: follow the system night mode. */
    public int mzNightModeUseOf() {
        return 0;
    }
}
