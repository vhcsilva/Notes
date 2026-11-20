package com.cadernolivre.app;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Desenha os elementos do Quadro. No modo "à mão" cada linha é desenhada duas vezes com pequenas
 * variações determinísticas (a partir de uma semente por elemento), imitando um traço de caneta.
 */
public class BoardRenderer {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final PageRenderer inkRenderer;

    public BoardRenderer(PageRenderer inkRenderer) {
        this.inkRenderer = inkRenderer;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    private static final class Cache {
        Path outline;      // contorno (limpo) para recorte e preenchimento sólido
        Path strokes;      // traços visíveis
        Path hatch;        // hachuras
    }

    public void drawAll(Canvas c, List<Model.Item> items, Set<Model.Item> skip) {
        for (Model.Item it : items) {
            if (skip != null && skip.contains(it)) continue;
            draw(c, it);
        }
    }

    public void draw(Canvas c, Model.Item it) {
        if (it instanceof BoardItem) drawBoardItem(c, (BoardItem) it);
        else inkRenderer.drawItem(c, it);
    }

    private void drawBoardItem(Canvas c, BoardItem b) {
        BoardItem.Style st = b.style;
        if (b.type == BoardItem.TEXT) {
            drawText(c, b.label, b.x1, b.y1, -1, 0, st);
            return;
        }
        Cache k = b.cache instanceof Cache ? (Cache) b.cache : null;
        if (k == null) {
            k = build(b);
            b.cache = k;
        }
        // Preenchimento
        if (st.fill != 0 && st.fillStyle != BoardItem.FILL_NONE && b.isBox()) {
            if (st.fillStyle == BoardItem.FILL_SOLID) {
                fill.setColor(st.fill);
                c.drawPath(k.outline, fill);
            } else if (k.hatch != null) {
                c.save();
                c.clipPath(k.outline);
                stroke.setPathEffect(null);
                stroke.setColor(st.fill);
                stroke.setStrokeWidth(Math.max(1.2f, st.width * 0.6f));
                c.drawPath(k.hatch, stroke);
                c.restore();
            }
        }
        // Contorno
        stroke.setColor(st.stroke);
        stroke.setStrokeWidth(st.width);
        if (st.dash == BoardItem.DASH_DASHED) stroke.setPathEffect(new DashPathEffect(new float[]{st.width * 4 + 4, st.width * 3 + 4}, 0));
        else if (st.dash == BoardItem.DASH_DOTTED) stroke.setPathEffect(new DashPathEffect(new float[]{0.1f, st.width * 2.5f + 3}, 0));
        else stroke.setPathEffect(null);
        c.drawPath(k.strokes, stroke);
        stroke.setPathEffect(null);
        // Rótulo dentro da forma
        if (!b.label.isEmpty() && b.isBox()) {
            RectF r = b.rect();
            float pad = b.type == BoardItem.RECT ? 10 : r.width() * 0.16f;
            drawText(c, b.label, r.left + pad, r.top, Math.max(20, r.width() - 2 * pad), r.height(), st);
        }
    }

    /** Desenha texto. Se width > 0, quebra linhas e centraliza numa caixa de altura boxH. */
    private void drawText(Canvas c, String s, float x, float y, float width, float boxH, BoardItem.Style st) {
        text.setColor(st.stroke);
        text.setTextSize(st.fontSize);
        text.setTypeface(BoardItem.typeface(st.rough));
        if (width <= 0) {
            String[] lines = s.split("\n", -1);
            Paint.FontMetrics fm = text.getFontMetrics();
            float lh = st.fontSize * 1.25f;
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], x, y + i * lh + (lh - (fm.descent - fm.ascent)) / 2 - fm.ascent, text);
            return;
        }
        StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), text, (int) width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.1f).build();
        c.save();
        c.translate(x, y + Math.max(0, (boxH - layout.getHeight()) / 2f));
        layout.draw(c);
        c.restore();
    }

    // ================================================================ geometria
    private Cache build(BoardItem b) {
        Cache k = new Cache();
        Random rnd = new Random(b.seed);
        float rough = b.style.rough;
        RectF r = b.rect();
        Path out = new Path(), st = new Path();
        switch (b.type) {
            case BoardItem.RECT: {
                float rad = b.style.rounded ? Math.min(Math.min(r.width(), r.height()) * 0.25f, 24) : 0;
                out.addRoundRect(r, rad, rad, Path.Direction.CW);
                if (rough == 0) st.addPath(out);
                else {
                    float[][] pts = {{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}};
                    for (int i = 0; i < 4; i++) {
                        float[] a = pts[i], z = pts[(i + 1) % 4];
                        roughLine(st, a[0], a[1], z[0], z[1], rough, rnd);
                    }
                }
                break;
            }
            case BoardItem.DIAMOND: {
                float cx = r.centerX(), cy = r.centerY();
                float[] p = {cx, r.top, r.right, cy, cx, r.bottom, r.left, cy};
                out.moveTo(p[0], p[1]);
                for (int i = 1; i < 4; i++) out.lineTo(p[i * 2], p[i * 2 + 1]);
                out.close();
                if (rough == 0) st.addPath(out);
                else for (int i = 0; i < 4; i++) {
                    int j = (i + 1) % 4;
                    roughLine(st, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], rough, rnd);
                }
                break;
            }
            case BoardItem.ELLIPSE: {
                out.addOval(r, Path.Direction.CW);
                if (rough == 0) st.addPath(out);
                else {
                    roughEllipse(st, r, rough, rnd);
                    roughEllipse(st, r, rough, rnd);
                }
                break;
            }
            case BoardItem.LINE:
            case BoardItem.ARROW: {
                if (rough == 0) { st.moveTo(b.x1, b.y1); st.lineTo(b.x2, b.y2); }
                else roughLine(st, b.x1, b.y1, b.x2, b.y2, rough, rnd);
                if (b.type == BoardItem.ARROW) {
                    double ang = Math.atan2(b.y2 - b.y1, b.x2 - b.x1);
                    float len = (float) Math.hypot(b.x2 - b.x1, b.y2 - b.y1);
                    float L = Math.min(Math.max(14, b.style.width * 5), len * 0.45f);
                    for (double sgn : new double[]{-1, 1}) {
                        float hx = b.x2 - L * (float) Math.cos(ang + sgn * 0.5);
                        float hy = b.y2 - L * (float) Math.sin(ang + sgn * 0.5);
                        if (rough == 0) { st.moveTo(b.x2, b.y2); st.lineTo(hx, hy); }
                        else roughLine(st, b.x2, b.y2, hx, hy, rough * 0.6f, rnd);
                    }
                }
                break;
            }
        }
        k.outline = out;
        k.strokes = st;
        if (b.isBox() && b.style.fill != 0 && (b.style.fillStyle == BoardItem.FILL_HACHURE || b.style.fillStyle == BoardItem.FILL_CROSS)) {
            Path h = new Path();
            float gap = Math.max(7, b.style.width * 4);
            hatch(h, r, gap, true, rough, rnd);
            if (b.style.fillStyle == BoardItem.FILL_CROSS) hatch(h, r, gap, false, rough, rnd);
            k.hatch = h;
        }
        return k;
    }

    /** Linhas paralelas inclinadas cobrindo o retângulo (serão recortadas pelo contorno). */
    private static void hatch(Path p, RectF r, float gap, boolean dir, float rough, Random rnd) {
        float w = r.width(), h = r.height();
        float span = w + h;
        for (float d = 0; d < span; d += gap) {
            float x0, y0, x1, y1;
            if (dir) { x0 = r.left + d - h; y0 = r.bottom; x1 = r.left + d; y1 = r.top; }
            else { x0 = r.left + d - h; y0 = r.top; x1 = r.left + d; y1 = r.bottom; }
            if (rough == 0) { p.moveTo(x0, y0); p.lineTo(x1, y1); }
            else singleRough(p, x0, y0, x1, y1, rough * 0.7f, rnd);
        }
    }

    /** Linha "à mão": duas passadas levemente curvas e deslocadas. */
    private static void roughLine(Path p, float x1, float y1, float x2, float y2, float rough, Random rnd) {
        singleRough(p, x1, y1, x2, y2, rough, rnd);
        singleRough(p, x1, y1, x2, y2, rough, rnd);
    }

    private static void singleRough(Path p, float x1, float y1, float x2, float y2, float rough, Random rnd) {
        float len = (float) Math.hypot(x2 - x1, y2 - y1);
        if (len < 0.5f) return;
        float amp = rough * Math.min(2.6f, 0.6f + len * 0.012f);
        float nx = -(y2 - y1) / len, ny = (x2 - x1) / len;
        float j = amp * 0.6f;
        float sx = x1 + j(rnd, j), sy = y1 + j(rnd, j);
        float ex = x2 + j(rnd, j), ey = y2 + j(rnd, j);
        float o1 = j(rnd, amp), o2 = j(rnd, amp);
        float c1x = x1 + (x2 - x1) * 0.4f + nx * o1, c1y = y1 + (y2 - y1) * 0.4f + ny * o1;
        float c2x = x1 + (x2 - x1) * 0.75f + nx * o2, c2y = y1 + (y2 - y1) * 0.75f + ny * o2;
        p.moveTo(sx, sy);
        p.cubicTo(c1x, c1y, c2x, c2y, ex, ey);
    }

    private static void roughEllipse(Path p, RectF r, float rough, Random rnd) {
        float cx = r.centerX(), cy = r.centerY(), rx = r.width() / 2, ry = r.height() / 2;
        int n = 18;
        double start = rnd.nextDouble() * Math.PI * 2;
        double sweep = Math.PI * 2 + 0.25 + rnd.nextDouble() * 0.2; // passa um pouco do início
        float amp = rough * Math.min(3f, 0.8f + (rx + ry) * 0.01f);
        float[] xs = new float[n + 1], ys = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            double a = start + sweep * i / n;
            float k = 1 + j(rnd, amp) / Math.max(rx, ry);
            xs[i] = cx + (float) Math.cos(a) * rx * k;
            ys[i] = cy + (float) Math.sin(a) * ry * k;
        }
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            float mx = (xs[i] + xs[i + 1]) / 2, my = (ys[i] + ys[i + 1]) / 2;
            p.quadTo(xs[i], ys[i], mx, my);
        }
        p.lineTo(xs[n], ys[n]);
    }

    private static float j(Random rnd, float amp) { return (rnd.nextFloat() * 2 - 1) * amp; }
}
