// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Periodic check: Flyme wipes a package's cloud icons on every install/update. */
public class RestoreJob extends JobService {
    static final int ID_PERIODIC = 1, ID_ONCE = 2;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(ID_PERIODIC) != null) return;
        js.schedule(new JobInfo.Builder(ID_PERIODIC, new ComponentName(c, RestoreJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    static void runNow(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(ID_ONCE, new ComponentName(c, RestoreJob.class))
                .setOverrideDeadline(1000)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Store store = Store.get(this);
        if (!store.restoreOn() && !store.autoNewOn()) return false;
        new Thread(() -> {
            try {
                Icons.restore(this, store.restoreOn(), store.autoNewOn());
            } finally {
                jobFinished(params, false);
            }
        }, "fi-restore").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
