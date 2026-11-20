package com.cadernolivre.app;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Seletores visuais reutilizáveis (modelos de papel com miniatura). */
final class Pickers {
    private Pickers() {}

    /** Miniatura de um modelo de papel. */
    static final class TemplateTile extends View {
        final Model.Page page = new Model.Page();
        private final PageRenderer r = new PageRenderer(null, null);
        boolean selected;

        TemplateTile(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float pad = Ui.dp(getContext(), 3);
            float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
            float s = Math.min(w / Model.PAGE_W, h / page.height);
            c.save();
            c.translate(pad + (w - Model.PAGE_W * s) / 2, pad);
            c.clipRect(0, 0, Model.PAGE_W * s, page.height * s);
            c.scale(s, s);
            r.drawBackground(c, page, null);
            c.restore();
        }
    }

    /**
     * Linha rolável de modelos + linha de cores de papel. sel[0] = índice do modelo, sel[1] = índice da cor.
     */
    static View paperPicker(final Context c, final int[] sel) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(c);
        row.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
        hs.addView(row);
        box.addView(hs);
        final LinearLayout colors = new LinearLayout(c);
        colors.setPadding(Ui.dp(c, 18), Ui.dp(c, 8), Ui.dp(c, 18), 0);
        colors.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(colors);
        final Runnable[] build = new Runnable[1];
        build[0] = () -> {
            row.removeAllViews();
            for (int i = 0; i < Model.TEMPLATES.length; i++) {
                final int idx = i;
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
                TemplateTile t = new TemplateTile(c);
                t.page.template = Model.TEMPLATES[i];
                t.page.paperColor = Model.PAPER_COLORS[sel[1]];
                t.setBackground(i == sel[0] ? Ui.stroke(0, Th.primary, Ui.dp(c, 2), Ui.dp(c, 8))
                        : Ui.stroke(0, Th.outline, Ui.dp(c, 1), Ui.dp(c, 8)));
                cell.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, 62), Ui.dp(c, 86)));
                TextView l = Ui.text(c, Model.TEMPLATE_NAMES[i], 11, i == sel[0] ? Th.primary : Th.on2, i == sel[0]);
                l.setPadding(0, Ui.dp(c, 4), 0, 0);
                cell.addView(l);
                cell.setOnClickListener(v -> { sel[0] = idx; build[0].run(); });
                row.addView(cell);
            }
            colors.removeAllViews();
            colors.addView(Ui.text(c, "Papel:", 13, Th.on2, false));
            for (int i = 0; i < Model.PAPER_COLORS.length; i++) {
                final int idx = i;
                View s = Ui.swatch(c, Model.PAPER_COLORS[i], i == sel[1], 28);
                s.setOnClickListener(v -> { sel[1] = idx; build[0].run(); });
                colors.addView(s);
            }
        };
        build[0].run();
        return box;
    }
}
