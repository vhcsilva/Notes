package com.cadernolivre.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;

/** Exportação para PDF (todas as páginas, com anotações) e PNG (uma página). */
final class Exporter {
    private Exporter() {}

    static void exportPdf(Model.Notebook nb, PageRenderer r, PdfHelper pdf, OutputStream out) throws IOException {
        PdfDocument doc = new PdfDocument();
        try {
            for (int i = 0; i < nb.pages.size(); i++) {
                Model.Page p = nb.pages.get(i);
                int w = 595;
                int h = Math.round(w * p.height / Model.PAGE_W);
                PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(w, h, i + 1).create());
                Canvas c = pg.getCanvas();
                float s = w / Model.PAGE_W;
                c.scale(s, s);
                Bitmap bmp = p.pdf != null ? pdf.render(p.pdf, p.pdfIndex, 1400) : null;
                r.drawPage(c, p, bmp, null);
                doc.finishPage(pg);
                if (bmp != null) bmp.recycle();
            }
            doc.writeTo(out);
        } finally {
            doc.close();
        }
    }

    static void exportPng(Model.Page p, PageRenderer r, PdfHelper pdf, OutputStream out) throws IOException {
        Bitmap b = r.renderBitmap(p, 2000, pdf);
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
    }
}
