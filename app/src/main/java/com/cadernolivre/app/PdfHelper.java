package com.cadernolivre.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Renderiza páginas de PDFs importados (usando o PdfRenderer nativo do Android). */
public class PdfHelper {
    private final File dir;
    private final Map<String, PdfRenderer> renderers = new HashMap<>();
    private final Map<String, ParcelFileDescriptor> fds = new HashMap<>();

    public PdfHelper(File dir) { this.dir = dir; }

    private PdfRenderer renderer(String file) throws IOException {
        PdfRenderer r = renderers.get(file);
        if (r == null) {
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(dir, file), ParcelFileDescriptor.MODE_READ_ONLY);
            r = new PdfRenderer(fd);
            renderers.put(file, r);
            fds.put(file, fd);
        }
        return r;
    }

    public synchronized Bitmap render(String file, int index, int widthPx) {
        try {
            PdfRenderer r = renderer(file);
            if (index < 0 || index >= r.getPageCount()) return null;
            PdfRenderer.Page pg = r.openPage(index);
            try {
                int h = Math.max(1, Math.round(widthPx * pg.getHeight() / (float) pg.getWidth()));
                Bitmap bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } finally { pg.close(); }
        } catch (Throwable t) {
            return null;
        }
    }

    public synchronized void close() {
        for (PdfRenderer r : renderers.values()) try { r.close(); } catch (Exception ignored) {}
        for (ParcelFileDescriptor fd : fds.values()) try { fd.close(); } catch (Exception ignored) {}
        renderers.clear();
        fds.clear();
    }

    /** Altura relativa (altura/largura) de cada página do PDF. */
    public static float[] pageRatios(File pdf) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = new PdfRenderer(fd);
        try {
            float[] out = new float[r.getPageCount()];
            for (int i = 0; i < out.length; i++) {
                PdfRenderer.Page pg = r.openPage(i);
                out[i] = pg.getHeight() / (float) pg.getWidth();
                pg.close();
            }
            return out;
        } finally {
            r.close();
            fd.close();
        }
    }
}
