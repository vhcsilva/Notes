package com.cadernolivre.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;

/** Paleta de cores do app, com tema claro e escuro (segue o sistema). */
final class Th {
    static boolean dark;
    static int bg, surface, surface2, on, on2, outline, primary, onPrimary, primarySoft, desk, danger;

    private Th() {}

    static void init(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        primary = dark ? 0xFF8C9EFF : 0xFF4C5FD5;
        onPrimary = dark ? 0xFF10163A : 0xFFFFFFFF;
        danger = dark ? 0xFFFF8A80 : 0xFFD32F2F;
        if (dark) {
            bg = 0xFF111216; surface = 0xFF1C1D23; surface2 = 0xFF26272F;
            on = 0xFFECECF1; on2 = 0xFF9EA0AB; outline = 0x26FFFFFF;
            primarySoft = 0x338C9EFF; desk = 0xFF0B0B0E;
        } else {
            bg = 0xFFF6F6FA; surface = 0xFFFFFFFF; surface2 = 0xFFEEEFF5;
            on = 0xFF1B1C22; on2 = 0xFF6A6C78; outline = 0x1F000000;
            primarySoft = 0x224C5FD5; desk = 0xFFE4E6EC;
        }
    }

    @SuppressWarnings("deprecation")
    static void apply(Activity a, int barColor) {
        Window w = a.getWindow();
        w.setStatusBarColor(barColor);
        w.setNavigationBarColor(barColor);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(flags);
    }
}
