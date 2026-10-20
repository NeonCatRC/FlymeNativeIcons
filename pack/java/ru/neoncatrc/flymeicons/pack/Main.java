// SPDX-FileCopyrightText: 2026 NeonCatRC
// SPDX-License-Identifier: AGPL-3.0-only

package ru.neoncatrc.flymeicons.pack;

/** Lets launchers find the pack (org.adw.launcher.THEMES); opening it opens the companion app. */
public class Main extends android.app.Activity {
    @Override
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new android.content.Intent()
                    .setClassName("ru.neoncatrc.flymeicons", "ru.neoncatrc.flymeicons.SettingsActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        finish();
    }
}
