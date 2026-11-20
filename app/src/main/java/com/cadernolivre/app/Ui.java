package com.cadernolivre.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Componentes de interface montados em código. */
final class Ui {
    static final int[] INK_COLORS = {0xFF000000, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF, 0xFF1A237E, 0xFF1976D2,
            0xFF00897B, 0xFF388E3C, 0xFFFBC02D, 0xFFF57C00, 0xFFD32F2F, 0xFFC2185B, 0xFF7B1FA2, 0xFF795548};
    static final int[] HL_COLORS = {0xFFFFEB3B, 0xFF76FF03, 0xFF40C4FF, 0xFFFF4081, 0xFFFFAB40, 0xFFE040FB, 0xFF64FFDA};
    static final int[] FOLDER_COLORS = {0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFFFA726, 0xFFAB47BC, 0xFF42A5F5, 0xFF8D6E63, 0xFF78909C};

    private Ui() {}

    interface IntCallback { void on(int v); }
    interface StrCallback { void on(String s); }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static GradientDrawable stroke(int fill, int strokeColor, int strokePx, float radius) {
        GradientDrawable g = round(fill, radius);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** Fundo com efeito de toque (ripple) sobre um formato arredondado. */
    static Drawable ripple(int fill, float radius) {
        GradientDrawable mask = round(Color.WHITE, radius);
        return new RippleDrawable(ColorStateList.valueOf(Th.dark ? 0x33FFFFFF : 0x1F000000),
                fill == 0 ? null : round(fill, radius), mask);
    }

    static ImageView iconButton(Context c, String icon, int color, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(Icons.of(icon, color));
        int p = dp(c, 10);
        v.setPadding(p, p, p, p);
        v.setBackground(ripple(0, dp(c, 22)));
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        return v;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Botão "pílula" preenchido ou contornado. */
    static TextView pill(Context c, String s, boolean filled, View.OnClickListener l) {
        TextView t = text(c, s, 14, filled ? Th.onPrimary : Th.primary, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        if (filled) t.setBackground(ripple(Th.primary, dp(c, 20)));
        else {
            GradientDrawable g = stroke(0, Th.outline, dp(c, 1), dp(c, 20));
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22888888), g, round(Color.WHITE, dp(c, 20))));
        }
        t.setOnClickListener(l);
        return t;
    }

    /** Chip selecionável. */
    static TextView chip(Context c, String s, boolean selected, View.OnClickListener l) {
        TextView t = text(c, s, 13, selected ? Th.primary : Th.on2, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        t.setBackground(selected ? stroke(Th.primarySoft, Th.primary, dp(c, 1), dp(c, 16))
                : stroke(0, Th.outline, dp(c, 1), dp(c, 16)));
        t.setOnClickListener(l);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(-2, -2);
        lp.setMargins(0, dp(c, 4), dp(c, 8), dp(c, 4));
        t.setLayoutParams(lp);
        return t;
    }

    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }

    static View swatch(Context c, int color, boolean selected, int sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (selected) g.setStroke(dp(c, 3), Th.primary);
        else g.setStroke(dp(c, 1), Th.outline);
        v.setBackground(g);
        int s = dp(c, sizeDp);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(s, s);
        int m = dp(c, 5);
        lp.setMargins(m, m, m, m);
        v.setLayoutParams(lp);
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Th.outline);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1) / 2)));
        return v;
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    static void prompt(Context c, String title, String initial, final StrCallback cb) {
        final EditText et = new EditText(c);
        et.setText(initial);
        et.setSelectAllOnFocus(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout f = new FrameLayout(c);
        int p = dp(c, 22);
        f.setPadding(p, dp(c, 8), p, 0);
        f.addView(et);
        AlertDialog d = new AlertDialog.Builder(c).setTitle(title).setView(f)
                .setPositiveButton("OK", (x, w) -> cb.on(et.getText().toString().trim()))
                .setNegativeButton("Cancelar", null).create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        et.requestFocus();
    }

    static void confirm(Context c, String title, String msg, String action, Runnable r) {
        new AlertDialog.Builder(c).setTitle(title).setMessage(msg)
                .setPositiveButton(action, (d, w) -> r.run()).setNegativeButton("Cancelar", null).show();
    }

    /** Grade de cores com opção de cor personalizada. */
    static View colorGrid(final Context c, int[] palette, final int current, final IntCallback cb) {
        GridLayout g = new GridLayout(c);
        g.setColumnCount(7);
        boolean found = false;
        for (final int col : palette) {
            boolean sel = (col | 0xFF000000) == (current | 0xFF000000);
            found |= sel;
            View s = swatch(c, col, sel, 32);
            s.setOnClickListener(v -> cb.on(col));
            g.addView(s);
        }
        if (!found) g.addView(swatch(c, current, true, 32));
        ImageView custom = new ImageView(c);
        custom.setImageDrawable(Icons.of("plus", Th.on2));
        int s = dp(c, 32), m = dp(c, 5), pd = dp(c, 6);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(s, s);
        lp.setMargins(m, m, m, m);
        custom.setLayoutParams(lp);
        custom.setPadding(pd, pd, pd, pd);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(c, 1), Th.on2);
        custom.setBackground(ring);
        custom.setOnClickListener(v -> customColor(c, current, cb));
        g.addView(custom);
        return g;
    }

    static void customColor(final Context c, int initial, final IntCallback cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 22);
        box.setPadding(p, p / 2, p, 0);
        final View preview = new View(c);
        preview.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48)));
        box.addView(preview);
        final int[] rgb = {Color.red(initial), Color.green(initial), Color.blue(initial)};
        preview.setBackground(round(initial | 0xFF000000, dp(c, 12)));
        String[] names = {"Vermelho", "Verde", "Azul"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView l = text(c, names[i], 13, Th.on2, false);
            l.setPadding(0, dp(c, 12), 0, 0);
            box.addView(l);
            SeekBar sb = new SeekBar(c);
            sb.setMax(255);
            sb.setProgress(rgb[i]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                    rgb[idx] = v;
                    preview.setBackground(round(Color.rgb(rgb[0], rgb[1], rgb[2]), dp(c, 12)));
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

    // ================================================================ folha inferior (bottom sheet)
    static final class Sheet {
        final Dialog dialog;
        final LinearLayout body;
        final Context c;

        Sheet(Context c, String title) { this(c, title, true); }

        Sheet(Context c, String title, boolean scroll) {
            this.c = c;
            dialog = new Dialog(c);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Th.surface);
            float r = dp(c, 24);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            root.setBackground(bg);
            root.setPadding(0, dp(c, 10), 0, dp(c, 16));
            View handle = new View(c);
            handle.setBackground(round(Th.outline, dp(c, 3)));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 4));
            hl.gravity = Gravity.CENTER_HORIZONTAL;
            hl.bottomMargin = dp(c, 8);
            root.addView(handle, hl);
            if (title != null) {
                TextView t = text(c, title, 17, Th.on, true);
                t.setPadding(dp(c, 24), dp(c, 6), dp(c, 24), dp(c, 8));
                t.setSingleLine(true);
                root.addView(t);
            }
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            if (scroll) {
                ScrollView sv = new ScrollView(c);
                sv.addView(body);
                root.addView(sv);
            } else root.addView(body);
            dialog.setContentView(root);
            Window w = dialog.getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(Math.min(c.getResources().getDisplayMetrics().widthPixels, dp(c, 560)), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setDimAmount(0.35f);
        }

        Sheet item(String icon, String label, Runnable r) { return item(icon, label, null, false, r); }

        Sheet item(String icon, String label, String sub, boolean danger, final Runnable r) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 12));
            row.setBackground(ripple(0, 0));
            int col = danger ? Th.danger : Th.on;
            ImageView iv = Icons.view(c, icon, danger ? Th.danger : Th.on2, 24);
            row.addView(iv);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(c, 20), 0, 0, 0);
            texts.addView(text(c, label, 15, col, false));
            if (sub != null) texts.addView(text(c, sub, 12, Th.on2, false));
            row.addView(texts, weight());
            row.setOnClickListener(v -> { dialog.dismiss(); r.run(); });
            body.addView(row);
            return this;
        }

        Sheet view(View v) {
            body.addView(v);
            return this;
        }

        Sheet show() {
            dialog.show();
            return this;
        }

        void dismiss() { dialog.dismiss(); }
    }
}
