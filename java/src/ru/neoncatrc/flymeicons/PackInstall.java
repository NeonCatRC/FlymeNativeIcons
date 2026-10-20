// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs the generated icon pack through a PackageInstaller session; App passes the session
 * status, package broadcasts and resumed screens here. The state below lives as long as the
 * process, main thread only (sPending is also set by the build thread before the commit).
 *
 * Flyme's own installer answers the confirmation: it installs the staged APK by itself and leaves
 * our session committed but unfinished, so no success status ever comes. The pack is taken as
 * installed when its PACKAGE_ADDED arrives, or, back from the installer, when it was updated
 * after the commit; the hanging session is then abandoned.
 */
final class PackInstall {
    static final String ACTION_STATUS = "ru.neoncatrc.flymeicons.PACK_STATUS";
    private static final String EXTRA_UNINSTALL = "uninstall";
    /** Back from the installer: how long to wait for its package broadcast before checking. */
    private static final long RETURN_CHECK_MS = 3000;

    /** The settings screen while it is shown. */
    interface Listener {
        void onPackChanged();
    }

    /** The install in progress. */
    private static final class Pending {
        int session;
        boolean night;
        int count, skipped;
        long start;
    }

    /** Progress text of a running build or install, null when idle. */
    private static String sStatus;
    private static Listener sListener;
    private static volatile Pending sPending;
    /** The system confirmation, kept until one of our screens is in front. */
    private static Intent sConfirm;
    /** The confirmation was shown: coming back means the user is done with it. */
    private static boolean sConfirmShown;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private PackInstall() {}

    static String status() {
        return sStatus;
    }

    static void setStatus(String s) {
        sStatus = s;
        if (sListener != null) sListener.onPackChanged();
    }

    static void setListener(Listener l) {
        sListener = l;
    }

    /** False when the user first has to allow installing apps from us (settings opened). */
    static boolean canInstall(Activity a) {
        if (a.getPackageManager().canRequestPackageInstalls()) return true;
        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + a.getPackageName())));
        return false;
    }

    /** Build thread. */
    static void install(Context c, PackBuilder.Result r, boolean night) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        // sessions left hanging by Flyme's installer (see above): their ABORTED status is ignored
        for (PackageInstaller.SessionInfo s : pi.getMySessions()) {
            if (PackBuilder.PACK_PKG.equals(s.getAppPackageName())) abandon(c, s.getSessionId());
        }
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(PackBuilder.PACK_PKG);
        p.setSize(r.apk.length());
        // updates of a pack we installed ourselves need no confirmation (not so after Flyme's
        // installer did it: then it is the pack's installer)
        p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("pack.apk", 0, r.apk.length()); InputStream in = new FileInputStream(r.apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            // what was built, saved to the settings only once it is really installed
            Pending pending = new Pending();
            pending.session = id;
            pending.night = night;
            pending.count = r.icons;
            pending.skipped = r.skipped;
            pending.start = System.currentTimeMillis();
            Intent cb = new Intent(ACTION_STATUS).setPackage(c.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(c, id, cb,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            // before the commit: its first status may come at once
            sPending = pending;
            try {
                s.commit(pend.getIntentSender());
            } catch (RuntimeException e) {
                sPending = null;
                throw e;
            }
        }
    }

    /** ACTION_DELETE needs REQUEST_DELETE_PACKAGES and silently does nothing without it. */
    static void uninstall(Context c) {
        Intent cb = new Intent(ACTION_STATUS).setPackage(c.getPackageName()).putExtra(EXTRA_UNINSTALL, true);
        PendingIntent pend = PendingIntent.getBroadcast(c, 0x7ffe, cb,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getPackageManager().getPackageInstaller().uninstall(PackBuilder.PACK_PKG, pend.getIntentSender());
    }

    /** Session status: asks the user to confirm when needed, reports the result. */
    static void onStatus(App app, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String why = msg == null ? "?" : msg;
        if (i.getBooleanExtra(EXTRA_UNINSTALL, false)) {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                confirm(app, i);
                return;
            }
            boolean ok = status == PackageInstaller.STATUS_SUCCESS;
            Ui.toast(app, ok ? app.getString(R.string.fi_pack_removed) : app.getString(R.string.fi_pack_remove_failed, why));
            setStatus(sStatus);
            return;
        }
        Pending p = sPending;
        int id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (p == null || id != p.session) {
            android.util.Log.i(Icons.TAG, "pack: status " + status + " of an old session " + id);
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirm(app, i);
            return;
        }
        finish(app, status == PackageInstaller.STATUS_SUCCESS, why);
    }

    /** A package was added or replaced. */
    static void onPackageChanged(App app, String pkg) {
        if (PackBuilder.PACK_PKG.equals(pkg) && sPending != null) finish(app, true, null);
    }

    /** One of our screens came to the front. */
    static void onResumed(final App app, Activity a) {
        final Pending p = sPending;
        if (sConfirmShown && p != null) {
            sConfirmShown = false;
            sHandler.postDelayed(() -> {
                if (sPending != p) return;
                // no package broadcast (yet): installed after the commit, or the user cancelled
                long updated = 0;
                try {
                    updated = app.getPackageManager().getPackageInfo(PackBuilder.PACK_PKG, 0).lastUpdateTime;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                finish(app, updated >= p.start, null);
            }, RETURN_CHECK_MS);
        }
        showConfirm(a);
    }

    private static void confirm(App app, Intent i) {
        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        if (confirm == null) return;
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        sConfirm = confirm;
        // from the background the system would block it: then on the next screen of ours
        Activity a = app.resumed();
        if (a != null) showConfirm(a);
    }

    /** Show a confirmation that is still waiting. */
    private static void showConfirm(Activity a) {
        Intent confirm = sConfirm;
        if (confirm == null) return;
        sConfirm = null;
        try {
            a.startActivity(confirm);
            sConfirmShown = true;
        } catch (RuntimeException e) {
            android.util.Log.w(Icons.TAG, "pack confirmation", e);
        }
    }

    /** why: the failure message, null for a cancelled install (no message then). */
    private static void finish(final App app, boolean ok, String why) {
        Pending p = sPending;
        if (p == null) return;
        sPending = null;
        sConfirm = null;
        sConfirmShown = false;
        // a session Flyme's installer left hanging (its ABORTED status is ignored, sPending is gone)
        abandon(app, p.session);
        if (ok) {
            Store.get(app).prefs().edit().putBoolean(Store.PREF_PACK_NIGHT, p.night)
                    .putInt(Store.PREF_PACK_COUNT, p.count).apply();
            Ui.toast(app, p.skipped > 0 ? app.getString(R.string.fi_pack_done_partial, p.skipped)
                    : app.getString(R.string.fi_pack_done));
            // Flyme wipes the pack's cloud icon on install, often only after this: then the restore
            // App schedules on the package broadcast (about 2 minutes) puts it back
            new Thread(() -> Icons.ensureSelf(app), "fi-self").start();
        } else if (why != null) {
            Ui.toast(app, app.getString(R.string.fi_pack_failed, why));
        }
        android.util.Log.i(Icons.TAG, "pack: session " + p.session + (ok ? " installed" : " not installed: " + why));
        setStatus(null);
    }

    private static void abandon(Context c, int session) {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        if (pi.getSessionInfo(session) == null) return;
        try {
            pi.abandonSession(session);
        } catch (RuntimeException ignored) {
        }
    }
}
