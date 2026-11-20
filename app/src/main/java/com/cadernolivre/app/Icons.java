package com.cadernolivre.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Ícones de linha desenhados em código (grade 24×24), para não depender de imagens. */
final class Icons {
    private Icons() {}

    static Drawable of(String name, int color) { return new Icon(name, color); }

    static ImageView view(Context c, String name, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(of(name, color));
        int s = Ui.dp(c, sizeDp);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(s, s));
        return v;
    }

    static final class Icon extends Drawable {
        private final String name;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;

        Icon(String name, int color) {
            this.name = name;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
            f.setStyle(Paint.Style.FILL);
            f.setColor(color);
        }

        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }
        @Override public void setAlpha(int a) { alpha = a; p.setAlpha(a); f.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); f.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        private static Path poly(float... xy) {
            Path path = new Path();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            return path;
        }

        private static Path closed(float... xy) {
            Path path = poly(xy);
            path.close();
            return path;
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, p); }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            c.save();
            c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
            c.scale(s, s);
            switch (name) {
                case "back": line(c, 20, 12, 4, 12); c.drawPath(poly(10, 6, 4, 12, 10, 18), p); break;
                case "chevron": c.drawPath(poly(9, 6, 15, 12, 9, 18), p); break;
                case "undo": undo(c); break;
                case "redo": c.scale(-1, 1, 12, 12); undo(c); break;
                case "more":
                    c.drawCircle(12, 5, 1.8f, f); c.drawCircle(12, 12, 1.8f, f); c.drawCircle(12, 19, 1.8f, f); break;
                case "pen":
                    c.drawPath(closed(15.5f, 3.5f, 20.5f, 8.5f, 9, 20, 4, 20, 4, 15), p);
                    line(c, 13, 6, 18, 11); break;
                case "highlighter":
                    c.drawPath(closed(14, 3, 20, 9, 12, 17, 6, 11), p);
                    c.drawPath(closed(6, 11, 12, 17, 9, 20, 4, 20, 4, 15.5f), p);
                    line(c, 13, 21, 21, 21); break;
                case "eraser":
                    c.drawPath(closed(8, 20, 3, 15, 13.5f, 4.5f, 20.5f, 11.5f, 12, 20), p);
                    line(c, 8.5f, 9.5f, 15.5f, 16.5f); line(c, 12, 20, 21, 20); break;
                case "lasso": {
                    Paint d = new Paint(p);
                    d.setPathEffect(new DashPathEffect(new float[]{2.6f, 2.4f}, 0));
                    c.drawOval(new RectF(3, 4, 21, 15), d);
                    Path t = new Path();
                    t.moveTo(8, 14.5f);
                    t.cubicTo(6, 17, 9, 19, 8, 22);
                    c.drawPath(t, p);
                    break;
                }
                case "text": c.drawPath(poly(5, 7, 5, 4, 19, 4, 19, 7), p); line(c, 12, 4, 12, 20); line(c, 9, 20, 15, 20); break;
                case "shapes": c.drawCircle(9, 9, 5.5f, p); c.drawRoundRect(new RectF(11, 11, 21, 21), 1.5f, 1.5f, p); break;
                case "image":
                    c.drawRoundRect(new RectF(3, 4, 21, 20), 2.5f, 2.5f, p);
                    c.drawCircle(8.5f, 9.5f, 1.6f, f);
                    c.drawPath(poly(3.5f, 18, 9, 12.5f, 14, 17, 16.5f, 14.5f, 20.5f, 18.5f), p); break;
                case "plus": line(c, 12, 5, 12, 19); line(c, 5, 12, 19, 12); break;
                case "minus": line(c, 5, 12, 19, 12); break;
                case "close": line(c, 6, 6, 18, 18); line(c, 18, 6, 6, 18); break;
                case "check": c.drawPath(poly(5, 12.5f, 10, 17.5f, 19.5f, 7), p); break;
                case "folder": c.drawPath(closed(3, 6, 3, 18.5f, 21, 18.5f, 21, 8.5f, 11.5f, 8.5f, 9.5f, 6), p); break;
                case "folder_fill": c.drawPath(closed(3, 6, 3, 18.5f, 21, 18.5f, 21, 8.5f, 11.5f, 8.5f, 9.5f, 6), f); break;
                case "folder_plus":
                    c.drawPath(closed(3, 6, 3, 18.5f, 21, 18.5f, 21, 8.5f, 11.5f, 8.5f, 9.5f, 6), p);
                    line(c, 12, 11, 12, 16.5f); line(c, 9.25f, 13.75f, 14.75f, 13.75f); break;
                case "move":
                    c.drawPath(closed(3, 6, 3, 18.5f, 21, 18.5f, 21, 8.5f, 11.5f, 8.5f, 9.5f, 6), p);
                    line(c, 8.5f, 13.5f, 15.5f, 13.5f); c.drawPath(poly(13, 11, 15.5f, 13.5f, 13, 16), p); break;
                case "search": c.drawCircle(10.5f, 10.5f, 6, p); line(c, 15, 15, 20, 20); break;
                case "pages":
                    c.drawRoundRect(new RectF(8, 3, 20, 17), 1.5f, 1.5f, p);
                    c.drawPath(poly(5, 7, 5, 21, 16, 21), p); break;
                case "mic":
                    c.drawRoundRect(new RectF(9, 3, 15, 14), 3, 3, p);
                    c.drawArc(new RectF(5, 4, 19, 18), 0, 180, false, p);
                    line(c, 12, 18, 12, 21); break;
                case "play": c.drawPath(closed(8, 5, 19, 12, 8, 19), f); break;
                case "stop": c.drawRoundRect(new RectF(6, 6, 18, 18), 2, 2, f); break;
                case "record": c.drawCircle(12, 12, 6, f); break;
                case "trash":
                    line(c, 4, 7, 20, 7); c.drawPath(poly(9, 7, 9, 4, 15, 4, 15, 7), p);
                    c.drawPath(poly(6, 7, 7, 20, 17, 20, 18, 7), p);
                    line(c, 10, 11, 10, 16); line(c, 14, 11, 14, 16); break;
                case "copy":
                    c.drawRoundRect(new RectF(8, 8, 20, 20), 2, 2, p);
                    c.drawPath(poly(4, 16, 4, 4, 16, 4), p); break;
                case "palette":
                    c.drawCircle(12, 12, 9, p);
                    c.drawCircle(8, 10, 1.4f, f); c.drawCircle(12, 7, 1.4f, f); c.drawCircle(16, 10, 1.4f, f);
                    c.drawCircle(14.5f, 15.5f, 1.8f, p); break;
                case "tune":
                    line(c, 4, 7, 20, 7); line(c, 4, 17, 20, 17);
                    c.drawCircle(9, 7, 2.2f, f); c.drawCircle(15, 17, 2.2f, f); break;
                case "doc":
                    c.drawPath(closed(6, 3, 14, 3, 19, 8, 19, 21, 6, 21), p);
                    c.drawPath(poly(14, 3, 14, 8, 19, 8), p);
                    line(c, 9, 13, 16, 13); line(c, 9, 17, 14, 17); break;
                case "note":
                    c.drawPath(closed(5, 4, 19, 4, 19, 14, 14, 19, 5, 19), p);
                    c.drawPath(poly(14, 19, 14, 14, 19, 14), p); break;
                case "notebook":
                    c.drawRoundRect(new RectF(5, 3, 19, 21), 2, 2, p);
                    line(c, 9, 3, 9, 21); line(c, 12, 8, 16, 8); break;
                case "share":
                    c.drawCircle(17, 5.5f, 2.5f, p); c.drawCircle(6.5f, 12, 2.5f, p); c.drawCircle(17, 18.5f, 2.5f, p);
                    line(c, 8.8f, 10.8f, 14.7f, 6.8f); line(c, 8.8f, 13.2f, 14.7f, 17.2f); break;
                case "export":
                    line(c, 12, 15, 12, 4); c.drawPath(poly(8, 8, 12, 4, 16, 8), p);
                    c.drawPath(poly(5, 12, 5, 20, 19, 20, 19, 12), p); break;
                case "import":
                    line(c, 12, 4, 12, 15); c.drawPath(poly(8, 11, 12, 15, 16, 11), p);
                    c.drawPath(poly(5, 12, 5, 20, 19, 20, 19, 12), p); break;
                case "sort": line(c, 4, 7, 20, 7); line(c, 4, 12, 15, 12); line(c, 4, 17, 10, 17); break;
                case "fit":
                    c.drawPath(poly(4, 9, 4, 4, 9, 4), p); c.drawPath(poly(15, 4, 20, 4, 20, 9), p);
                    c.drawPath(poly(20, 15, 20, 20, 15, 20), p); c.drawPath(poly(9, 20, 4, 20, 4, 15), p); break;
                case "hand":
                    c.drawPath(poly(8, 13, 8, 5.5f), p); c.drawPath(poly(11, 12, 11, 3.5f), p);
                    c.drawPath(poly(14, 12, 14, 4.5f), p); c.drawPath(poly(17, 12.5f, 17, 7), p);
                    Path h = new Path();
                    h.moveTo(17, 12.5f); h.lineTo(17, 15); h.cubicTo(17, 19, 14.5f, 21, 12, 21);
                    h.cubicTo(9, 21, 7.5f, 19.5f, 5.5f, 16.5f); h.lineTo(4, 13.5f); h.cubicTo(3.5f, 12, 5.5f, 11, 8, 13);
                    c.drawPath(h, p); break;
                case "paper":
                    c.drawRoundRect(new RectF(5, 3, 19, 21), 2, 2, p);
                    line(c, 8, 8, 16, 8); line(c, 8, 12, 16, 12); line(c, 8, 16, 13, 16); break;
                case "convert":
                    Path w = new Path();
                    w.moveTo(3, 9); w.cubicTo(5, 4, 7, 4, 7.5f, 9); w.cubicTo(8, 13, 10, 13, 11, 8);
                    c.drawPath(w, p);
                    c.drawPath(poly(13, 16, 13, 14, 21, 14, 21, 16), p); line(c, 17, 14, 17, 21); break;
                case "cloud": {
                    Path cl = new Path();
                    cl.moveTo(7, 19); cl.lineTo(17.5f, 19);
                    cl.cubicTo(20.5f, 19, 22, 17, 22, 14.8f); cl.cubicTo(22, 12.5f, 20.2f, 10.8f, 18, 10.8f);
                    cl.cubicTo(17.3f, 7.5f, 14.8f, 5.5f, 12, 5.5f); cl.cubicTo(9, 5.5f, 6.6f, 7.7f, 6.2f, 10.6f);
                    cl.cubicTo(3.8f, 11, 2, 12.8f, 2, 15); cl.cubicTo(2, 17.3f, 4.2f, 19, 7, 19);
                    c.drawPath(cl, p); break;
                }
                case "info": c.drawCircle(12, 12, 9, p); line(c, 12, 11, 12, 16.5f); c.drawCircle(12, 7.8f, 1.1f, f); break;
                case "edit": c.drawPath(closed(15.5f, 3.5f, 20.5f, 8.5f, 9, 20, 4, 20, 4, 15), p); break;
                case "star": c.drawPath(closed(12, 3, 14.6f, 9, 21, 9.4f, 16, 13.6f, 17.6f, 20, 12, 16.5f, 6.4f, 20, 8, 13.6f, 3, 9.4f, 9.4f, 9), p); break;
                default: c.drawCircle(12, 12, 8, p);
            }
            c.restore();
        }

        private void undo(Canvas c) {
            c.drawPath(poly(9, 14, 4, 9, 9, 4), p);
            Path u = new Path();
            u.moveTo(4, 9);
            u.lineTo(14.5f, 9);
            u.arcTo(new RectF(10, 9, 20, 19), -90, 180, false);
            u.lineTo(10, 19);
            c.drawPath(u, p);
        }
    }
}
