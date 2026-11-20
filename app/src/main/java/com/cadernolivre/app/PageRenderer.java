package com.cadernolivre.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;

import java.io.File;
import java.util.List;
import java.util.Set;

/** Desenha uma página (fundo, modelo de papel, PDF e elementos) em coordenadas de página. */
public class PageRenderer {
    private final File dir;
    private final ImageCache images;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    public PageRenderer(File dir, ImageCache images) {
        this.dir = dir;
        this.images = images;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        line.setStyle(Paint.Style.STROKE);
    }

    public static boolean isDark(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 110;
    }

    public void drawPage(Canvas c, Model.Page p, Bitmap pdfBmp, Set<Model.Item> skip) {
        drawBackground(c, p, pdfBmp);
        c.save();
        c.clipRect(0, 0, Model.PAGE_W, p.height);
        drawItems(c, p.items, skip);
        c.restore();
    }

    public void drawBackground(Canvas c, Model.Page p, Bitmap pdfBmp) {
        RectF r = new RectF(0, 0, Model.PAGE_W, p.height);
        if (p.pdf != null) {
            fill.setColor(Color.WHITE);
            c.drawRect(r, fill);
            if (pdfBmp != null) c.drawBitmap(pdfBmp, null, r, bmpPaint);
            return;
        }
        fill.setColor(p.paperColor);
        c.drawRect(r, fill);
        drawTemplate(c, p);
    }

    private void drawTemplate(Canvas c, Model.Page p) {
        boolean dark = isDark(p.paperColor);
        int lc = dark ? 0x40FFFFFF : 0x404A6FA5;
        int strong = dark ? 0x80FF8A80 : 0x80E57373;
        float W = Model.PAGE_W, H = p.height;
        line.setColor(lc);
        line.setStrokeWidth(1.6f);
        switch (p.template) {
            case Model.T_LINED:
                for (float y = 130; y < H - 30; y += 40) c.drawLine(30, y, W - 30, y, line);
                line.setColor(strong);
                c.drawLine(110, 0, 110, H, line);
                break;
            case Model.T_GRID:
                line.setStrokeWidth(1.1f);
                for (float x = 20; x <= W; x += 40) c.drawLine(x, 0, x, H, line);
                for (float y = 20; y <= H; y += 40) c.drawLine(0, y, W, y, line);
                break;
            case Model.T_DOTS:
                fill.setColor(dark ? 0x66FFFFFF : 0x664A6FA5);
                for (float x = 40; x < W; x += 40)
                    for (float y = 40; y < H; y += 40) c.drawCircle(x, y, 2.4f, fill);
                break;
            case Model.T_CORNELL: {
                float bottom = H - 260;
                for (float y = 190; y < bottom; y += 40) c.drawLine(30, y, W - 30, y, line);
                line.setColor(strong);
                line.setStrokeWidth(2.2f);
                c.drawLine(30, 150, W - 30, 150, line);
                c.drawLine(280, 150, 280, bottom, line);
                c.drawLine(30, bottom, W - 30, bottom, line);
                break;
            }
            case Model.T_MUSIC:
                line.setColor(dark ? 0x80FFFFFF : 0x80333333);
                line.setStrokeWidth(1.4f);
                for (float top = 120; top + 56 < H - 60; top += 130)
                    for (int i = 0; i < 5; i++) c.drawLine(50, top + i * 14, W - 50, top + i * 14, line);
                break;
            default:
                break;
        }
    }

    public void drawItems(Canvas c, List<Model.Item> items, Set<Model.Item> skip) {
        // Marca-texto primeiro, para ficar por baixo da escrita.
        for (Model.Item it : items) {
            if (skip != null && skip.contains(it)) continue;
            if (it instanceof Model.StrokeItem && ((Model.StrokeItem) it).kind == Model.StrokeItem.HIGHLIGHTER) drawItem(c, it);
        }
        for (Model.Item it : items) {
            if (skip != null && skip.contains(it)) continue;
            if (it instanceof Model.StrokeItem && ((Model.StrokeItem) it).kind == Model.StrokeItem.HIGHLIGHTER) continue;
            drawItem(c, it);
        }
    }

    public void drawItem(Canvas c, Model.Item it) {
        if (it instanceof Model.StrokeItem) {
            Model.StrokeItem s = (Model.StrokeItem) it;
            if (s.kind == Model.StrokeItem.PEN) {
                fill.setColor(s.color);
                c.drawPath(s.path(), fill);
            } else {
                stroke.setColor(s.kind == Model.StrokeItem.HIGHLIGHTER ? ((s.color & 0x00FFFFFF) | 0x66000000) : s.color);
                stroke.setStrokeWidth(s.width);
                stroke.setStrokeCap(s.kind == Model.StrokeItem.HIGHLIGHTER ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
                c.drawPath(s.path(), stroke);
            }
        } else if (it instanceof Model.TextItem) {
            Model.TextItem t = (Model.TextItem) it;
            text.setColor(t.color);
            text.setTextSize(t.size);
            String[] ls = t.lines();
            for (int i = 0; i < ls.length; i++) c.drawText(ls[i], t.x, t.y + t.size + i * t.lineHeight(), text);
        } else if (it instanceof Model.ImageItem) {
            Model.ImageItem im = (Model.ImageItem) it;
            Bitmap b = images.get(new File(dir, im.file));
            if (b != null) c.drawBitmap(b, null, im.rect, bmpPaint);
            else {
                fill.setColor(0x22000000);
                c.drawRect(im.rect, fill);
            }
        }
    }

    /** Renderiza uma página inteira num bitmap (para miniaturas e exportação PNG). */
    public Bitmap renderBitmap(Model.Page p, int widthPx, PdfHelper pdf) {
        int h = Math.round(widthPx * p.height / Model.PAGE_W);
        Bitmap out = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = widthPx / Model.PAGE_W;
        c.scale(s, s);
        Bitmap pdfBmp = p.pdf != null && pdf != null ? pdf.render(p.pdf, p.pdfIndex, widthPx) : null;
        drawPage(c, p, pdfBmp, null);
        if (pdfBmp != null) pdfBmp.recycle();
        return out;
    }

    // ---------------------------------------------------------------- cache de imagens
    public static class ImageCache {
        private final LruCache<String, Bitmap> cache;

        public ImageCache() {
            int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
            cache = new LruCache<String, Bitmap>(kb) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
            };
        }

        public synchronized Bitmap get(File f) {
            String k = f.getAbsolutePath();
            Bitmap b = cache.get(k);
            if (b == null && f.exists()) {
                b = decode(f, 1600);
                if (b != null) cache.put(k, b);
            }
            return b;
        }

        public static Bitmap decode(File f, int max) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / s > max || o.outHeight / s > max) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        }
    }
}
