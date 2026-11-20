package com.cadernolivre.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Pequenos utilitários de interface (a interface é montada em código, sem XML). */
final class Ui {
    static final int PRIMARY = 0xFF1E3A5F;
    static final int ACCENT = 0xFFF2A541;

    static final int[] INK_COLORS = {0xFF000000, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF, 0xFF1A237E, 0xFF1976D2,
            0xFF00897B, 0xFF388E3C, 0xFFFBC02D, 0xFFF57C00, 0xFFD32F2F, 0xFFC2185B, 0xFF7B1FA2, 0xFF795548};
    static final int[] HL_COLORS = {0xFFFFEB3B, 0xFF76FF03, 0xFF40C4FF, 0xFFFF4081, 0xFFFFAB40, 0xFFE040FB, 0xFF64FFDA};

    private Ui() {}

    interface IntCallback { void on(int v); }
    interface StrCallback { void on(String s); }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static Drawable ripple(Context c) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return c.getDrawable(tv.resourceId);
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static TextView button(Context c, String text, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 10);
        t.setPadding(p, p, p, p);
        t.setMinWidth(dp(c, 44));
        t.setBackground(ripple(c));
        t.setOnClickListener(l);
        return t;
    }

    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }

    static View swatch(Context c, int color, boolean selected, int sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(c, selected ? 3 : 1), selected ? ACCENT : 0x55000000);
        v.setBackground(g);
        int s = dp(c, sizeDp);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(s, s);
        int m = dp(c, 4);
        lp.setMargins(m, m, m, m);
        v.setLayoutParams(lp);
        return v;
    }

    static void prompt(Context c, String title, String initial, final StrCallback cb) {
        final EditText et = new EditText(c);
        et.setText(initial);
        et.setSelectAllOnFocus(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout f = new FrameLayout(c);
        int p = dp(c, 20);
        f.setPadding(p, dp(c, 8), p, 0);
        f.addView(et);
        new AlertDialog.Builder(c).setTitle(title).setView(f)
                .setPositiveButton("OK", (d, w) -> cb.on(et.getText().toString().trim()))
                .setNegativeButton("Cancelar", null).show();
    }

    /** Grade de cores com opção de cor personalizada. */
    static View colorGrid(final Context c, int[] palette, int current, final IntCallback cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GridLayout g = new GridLayout(c);
        g.setColumnCount(7);
        boolean found = false;
        for (final int col : palette) {
            boolean sel = (col | 0xFF000000) == (current | 0xFF000000);
            found |= sel;
            View s = swatch(c, col, sel, 34);
            s.setOnClickListener(v -> cb.on(col));
            g.addView(s);
        }
        if (!found) g.addView(swatch(c, current, true, 34));
        box.addView(g);
        TextView custom = new TextView(c);
        custom.setText("🎨  Cor personalizada…");
        custom.setTextSize(15);
        custom.setTextColor(PRIMARY);
        custom.setPadding(dp(c, 6), dp(c, 12), dp(c, 6), dp(c, 12));
        custom.setBackground(ripple(c));
        custom.setOnClickListener(v -> customColor(c, current, cb));
        box.addView(custom);
        return box;
    }

    static void customColor(Context c, int initial, final IntCallback cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 20);
        box.setPadding(p, p / 2, p, 0);
        final View preview = new View(c);
        preview.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48)));
        box.addView(preview);
        final int[] rgb = {Color.red(initial), Color.green(initial), Color.blue(initial)};
        preview.setBackground(round(initial | 0xFF000000, dp(c, 8)));
        String[] names = {"Vermelho", "Verde", "Azul"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView l = new TextView(c);
            l.setText(names[i]);
            l.setPadding(0, dp(c, 10), 0, 0);
            box.addView(l);
            SeekBar sb = new SeekBar(c);
            sb.setMax(255);
            sb.setProgress(rgb[i]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                    rgb[idx] = v;
                    preview.setBackground(round(Color.rgb(rgb[0], rgb[1], rgb[2]), dp(c, 8)));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            box.addView(sb);
        }
        new AlertDialog.Builder(c).setTitle("Cor personalizada").setView(box)
                .setPositiveButton("OK", (d, w) -> cb.on(Color.rgb(rgb[0], rgb[1], rgb[2])))
                .setNegativeButton("Cancelar", null).show();
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}
