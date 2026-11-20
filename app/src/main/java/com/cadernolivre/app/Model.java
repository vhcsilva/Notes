package com.cadernolivre.app;

import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Modelo de dados: cadernos, páginas e elementos (traços, textos, imagens). */
public final class Model {
    public static final float PAGE_W = 1000f;
    public static final float A4_H = 1414f;

    public static final String T_BLANK = "blank", T_LINED = "lined", T_GRID = "grid",
            T_DOTS = "dots", T_CORNELL = "cornell", T_MUSIC = "music";
    public static final String[] TEMPLATES = {T_BLANK, T_LINED, T_GRID, T_DOTS, T_CORNELL, T_MUSIC};
    public static final String[] TEMPLATE_NAMES = {"Em branco", "Pautado", "Quadriculado", "Pontilhado", "Cornell", "Partitura"};
    public static final int[] PAPER_COLORS = {0xFFFFFFFF, 0xFFFFF8E1, 0xFFEEF2F6, 0xFF263238};
    public static final String[] PAPER_NAMES = {"Branco", "Creme", "Cinza claro", "Escuro"};
    public static final int[] COVER_COLORS = {0xFF1E3A5F, 0xFF8E2C48, 0xFF2E7D32, 0xFFE65100,
            0xFF6A1B9A, 0xFF00838F, 0xFF37474F, 0xFFC62828, 0xFFF9A825};

    private Model() {}

    public static String templateName(String t) {
        for (int i = 0; i < TEMPLATES.length; i++) if (TEMPLATES[i].equals(t)) return TEMPLATE_NAMES[i];
        return t;
    }

    // ------------------------------------------------------------------ Caderno
    public static class Notebook {
        public String id;
        public String title = "Sem título";
        /** 0 = usar miniatura da primeira página como capa. */
        public int coverColor = COVER_COLORS[0];
        public long created, updated;
        public int lastPage;
        /** Id da pasta onde o caderno está ("" = raiz da biblioteca). */
        public String folder = "";
        /** "notebook" (caderno com páginas) ou "board" (quadro infinito). */
        public String kind = "notebook";
        public final List<Page> pages = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("title", title);
            o.put("cover", coverColor);
            o.put("created", created);
            o.put("updated", updated);
            o.put("lastPage", lastPage);
            o.put("folder", folder);
            o.put("kind", kind);
            JSONArray arr = new JSONArray();
            for (Page p : pages) arr.put(p.toJson());
            o.put("pages", arr);
            return o;
        }

        public static Notebook fromJson(String id, JSONObject o) throws JSONException {
            Notebook nb = new Notebook();
            nb.id = id;
            nb.title = o.optString("title", "Sem título");
            nb.coverColor = o.optInt("cover", COVER_COLORS[0]);
            nb.created = o.optLong("created");
            nb.updated = o.optLong("updated");
            nb.lastPage = o.optInt("lastPage", 0);
            nb.folder = o.optString("folder", "");
            nb.kind = o.optString("kind", "notebook");
            JSONArray arr = o.optJSONArray("pages");
            if (arr != null) for (int i = 0; i < arr.length(); i++) nb.pages.add(Page.fromJson(arr.getJSONObject(i)));
            if (nb.pages.isEmpty()) nb.pages.add(new Page());
            return nb;
        }
    }

    // ------------------------------------------------------------------ Página
    public static class Page {
        public String template = T_LINED;
        public int paperColor = PAPER_COLORS[0];
        /** Nome do arquivo PDF (na pasta do caderno) usado como fundo, ou null. */
        public String pdf;
        public int pdfIndex = -1;
        public float height = A4_H;
        public List<Item> items = new ArrayList<>();

        public Page copy() {
            Page p = new Page();
            p.template = template;
            p.paperColor = paperColor;
            p.pdf = pdf;
            p.pdfIndex = pdfIndex;
            p.height = height;
            p.items = new ArrayList<>(items);
            return p;
        }

        public Page blankLike() {
            Page p = new Page();
            p.template = pdf != null ? T_LINED : template;
            p.paperColor = pdf != null ? PAPER_COLORS[0] : paperColor;
            return p;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("tpl", template);
            o.put("paper", paperColor);
            if (pdf != null) {
                o.put("pdf", pdf);
                o.put("pdfIndex", pdfIndex);
            }
            o.put("h", (double) height);
            JSONArray arr = new JSONArray();
            for (Item it : items) arr.put(it.toJson());
            o.put("items", arr);
            return o;
        }

        static Page fromJson(JSONObject o) throws JSONException {
            Page p = new Page();
            p.template = o.optString("tpl", T_LINED);
            p.paperColor = o.optInt("paper", PAPER_COLORS[0]);
            if (o.has("pdf")) {
                p.pdf = o.getString("pdf");
                p.pdfIndex = o.optInt("pdfIndex", 0);
            }
            p.height = (float) o.optDouble("h", A4_H);
            JSONArray arr = o.optJSONArray("items");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Item it = Item.fromJson(arr.getJSONObject(i));
                if (it != null) p.items.add(it);
            }
            return p;
        }
    }

    // ------------------------------------------------------------------ Elementos
    public abstract static class Item {
        public abstract RectF bounds();
        /** Teste de toque genérico (usado pelo Quadro). */
        public boolean hitTest(float x, float y, float tol) {
            if (this instanceof StrokeItem) return ((StrokeItem) this).hit(x, y, tol);
            if (this instanceof BoardItem) return ((BoardItem) this).hit(x, y, tol);
            RectF b = new RectF(bounds());
            b.inset(-tol, -tol);
            return b.contains(x, y);
        }
        public abstract Item translated(float dx, float dy);
        public abstract Item scaled(float s, float cx, float cy);
        public Item recolored(int color) { return this; }
        abstract JSONObject toJson() throws JSONException;

        static Item fromJson(JSONObject o) throws JSONException {
            String t = o.optString("t");
            if ("s".equals(t)) {
                String[] parts = o.optString("p").split(",");
                float[] pts = new float[parts.length / 3 * 3];
                for (int i = 0; i < pts.length; i++) pts[i] = Float.parseFloat(parts[i]);
                return new StrokeItem(o.optInt("k"), o.optInt("c"), (float) o.optDouble("w", 4), pts);
            } else if ("x".equals(t)) {
                return new TextItem(o.optString("text"), (float) o.optDouble("x"), (float) o.optDouble("y"),
                        (float) o.optDouble("size", 32), o.optInt("c", 0xFF000000));
            } else if ("b".equals(t)) {
                return BoardItem.fromJson(o);
            } else if ("i".equals(t)) {
                return new ImageItem(o.optString("file"), new RectF((float) o.optDouble("l"), (float) o.optDouble("tp"),
                        (float) o.optDouble("r"), (float) o.optDouble("b")));
            }
            return null;
        }
    }

    public static class StrokeItem extends Item {
        public static final int PEN = 0, HIGHLIGHTER = 1, SHAPE = 2;
        public final int kind;
        public final int color;
        public final float width;
        /** Triplas x, y, pressão. */
        public final float[] pts;
        private Path path;
        private RectF bounds;

        public StrokeItem(int kind, int color, float width, float[] pts) {
            this.kind = kind;
            this.color = color;
            this.width = width;
            this.pts = pts;
        }

        public int count() { return pts.length / 3; }

        private float w(int i) { return width * (0.4f + 0.6f * pts[i * 3 + 2]); }

        public Path path() {
            if (path == null) path = kind == PEN ? buildOutline() : buildLine();
            return path;
        }

        private Path buildLine() {
            Path p = new Path();
            int n = count();
            if (n == 0) return p;
            p.moveTo(pts[0], pts[1]);
            if (n == 1) { p.lineTo(pts[0] + 0.1f, pts[1]); return p; }
            if (kind == SHAPE || n == 2) {
                for (int i = 1; i < n; i++) p.lineTo(pts[i * 3], pts[i * 3 + 1]);
                return p;
            }
            for (int i = 1; i < n - 1; i++) {
                float mx = (pts[i * 3] + pts[(i + 1) * 3]) / 2f, my = (pts[i * 3 + 1] + pts[(i + 1) * 3 + 1]) / 2f;
                p.quadTo(pts[i * 3], pts[i * 3 + 1], mx, my);
            }
            p.lineTo(pts[(n - 1) * 3], pts[(n - 1) * 3 + 1]);
            return p;
        }

        /** Contorno preenchido com espessura variável conforme a pressão da caneta. */
        private Path buildOutline() {
            Path p = new Path();
            int n = count();
            if (n == 0) return p;
            float[] x = new float[n], y = new float[n];
            for (int i = 0; i < n; i++) {
                if (i == 0 || i == n - 1) { x[i] = pts[i * 3]; y[i] = pts[i * 3 + 1]; }
                else {
                    x[i] = (pts[(i - 1) * 3] + 2 * pts[i * 3] + pts[(i + 1) * 3]) / 4f;
                    y[i] = (pts[(i - 1) * 3 + 1] + 2 * pts[i * 3 + 1] + pts[(i + 1) * 3 + 1]) / 4f;
                }
            }
            RectF b = bounds();
            if (n == 1 || (b.width() < width * 1.6f && b.height() < width * 1.6f)) {
                p.addCircle(x[0], y[0], Math.max(w(0), width * 0.7f) / 2f, Path.Direction.CW);
                return p;
            }
            float[] lx = new float[n], ly = new float[n], rx = new float[n], ry = new float[n];
            float[] nxs = new float[n], nys = new float[n];
            float pnx = 0, pny = -1;
            for (int i = 0; i < n; i++) {
                int a = Math.max(0, i - 1), c = Math.min(n - 1, i + 1);
                float dx = x[c] - x[a], dy = y[c] - y[a];
                float len = (float) Math.hypot(dx, dy);
                float nx, ny;
                if (len < 0.0001f) { nx = pnx; ny = pny; } else { nx = -dy / len; ny = dx / len; }
                pnx = nx; pny = ny;
                nxs[i] = nx; nys[i] = ny;
                float hw = w(i) / 2f;
                lx[i] = x[i] + nx * hw; ly[i] = y[i] + ny * hw;
                rx[i] = x[i] - nx * hw; ry[i] = y[i] - ny * hw;
            }
            p.moveTo(lx[0], ly[0]);
            for (int i = 1; i < n; i++) p.lineTo(lx[i], ly[i]);
            cap(p, x[n - 1], y[n - 1], w(n - 1) / 2f, nxs[n - 1], nys[n - 1], false);
            for (int i = n - 1; i >= 0; i--) p.lineTo(rx[i], ry[i]);
            cap(p, x[0], y[0], w(0) / 2f, nxs[0], nys[0], true);
            p.close();
            return p;
        }

        private static void cap(Path p, float cx, float cy, float r, float nx, float ny, boolean start) {
            double a0 = Math.atan2(ny, nx);
            for (int k = 1; k <= 5; k++) {
                double a = start ? a0 + Math.PI - k * Math.PI / 6 : a0 - k * Math.PI / 6;
                p.lineTo(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
            }
        }

        @Override public RectF bounds() {
            if (bounds == null) {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int i = 0; i < pts.length; i += 3) {
                    minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]);
                    minY = Math.min(minY, pts[i + 1]); maxY = Math.max(maxY, pts[i + 1]);
                }
                if (pts.length == 0) { minX = minY = maxX = maxY = 0; }
                float h = width / 2f;
                bounds = new RectF(minX - h, minY - h, maxX + h, maxY + h);
            }
            return bounds;
        }

        public boolean hit(float px, float py, float r) {
            RectF b = bounds();
            if (px < b.left - r || px > b.right + r || py < b.top - r || py > b.bottom + r) return false;
            float rr = r + width / 2f;
            int n = count();
            if (n == 1) return Math.hypot(px - pts[0], py - pts[1]) <= rr;
            for (int i = 0; i < n - 1; i++) {
                if (segDist(px, py, pts[i * 3], pts[i * 3 + 1], pts[(i + 1) * 3], pts[(i + 1) * 3 + 1]) <= rr) return true;
            }
            return false;
        }

        static float segDist(float px, float py, float ax, float ay, float bx, float by) {
            float dx = bx - ax, dy = by - ay;
            float l2 = dx * dx + dy * dy;
            float t = l2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / l2;
            t = Math.max(0, Math.min(1, t));
            return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
        }

        /**
         * Borracha parcial: remove apenas o trecho dentro do círculo (x, y, r).
         * Devolve null se o traço não foi tocado, ou a lista de pedaços restantes (pode ser vazia).
         */
        public List<StrokeItem> eraseSplit(float ex, float ey, float r) {
            if (!hit(ex, ey, r)) return null;
            float rr = r + width * 0.35f;
            float step = Math.max(1.5f, r / 3f);
            List<StrokeItem> out = new ArrayList<>();
            float[] run = new float[64];
            int len = 0;
            int n = count();
            for (int i = 0; i < n; i++) {
                float x1 = pts[i * 3], y1 = pts[i * 3 + 1], p1 = pts[i * 3 + 2];
                int sub = 1;
                float x0 = x1, y0 = y1, p0 = p1;
                if (i > 0) {
                    x0 = pts[(i - 1) * 3]; y0 = pts[(i - 1) * 3 + 1]; p0 = pts[(i - 1) * 3 + 2];
                    sub = Math.max(1, (int) Math.ceil(Math.hypot(x1 - x0, y1 - y0) / step));
                }
                for (int k = (i == 0 ? sub : 1); k <= sub; k++) {
                    float t = k / (float) sub;
                    float x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t, pr = p0 + (p1 - p0) * t;
                    boolean erased = Math.hypot(x - ex, y - ey) <= rr;
                    if (erased) {
                        if (len >= 6) out.add(new StrokeItem(kind, color, width, java.util.Arrays.copyOf(run, len)));
                        len = 0;
                    } else {
                        if (len + 3 > run.length) run = java.util.Arrays.copyOf(run, run.length * 2);
                        run[len++] = x; run[len++] = y; run[len++] = pr;
                    }
                }
            }
            if (len >= 6) out.add(new StrokeItem(kind, color, width, java.util.Arrays.copyOf(run, len)));
            return out;
        }

        @Override public Item translated(float dx, float dy) {
            float[] np = pts.clone();
            for (int i = 0; i < np.length; i += 3) { np[i] += dx; np[i + 1] += dy; }
            return new StrokeItem(kind, color, width, np);
        }

        @Override public Item scaled(float s, float cx, float cy) {
            float[] np = pts.clone();
            for (int i = 0; i < np.length; i += 3) { np[i] = cx + (np[i] - cx) * s; np[i + 1] = cy + (np[i + 1] - cy) * s; }
            return new StrokeItem(kind, color, width * s, np);
        }

        @Override public Item recolored(int c) { return new StrokeItem(kind, c, width, pts); }

        @Override JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", "s");
            o.put("k", kind);
            o.put("c", color);
            o.put("w", (double) width);
            StringBuilder sb = new StringBuilder(pts.length * 5);
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sb.append(',');
                if (i % 3 == 2) sb.append(Math.round(pts[i] * 100) / 100.0);
                else sb.append(Math.round(pts[i] * 10) / 10.0);
            }
            o.put("p", sb.toString());
            return o;
        }
    }

    public static class TextItem extends Item {
        public final String text;
        public final float x, y, size;
        public final int color;
        private RectF bounds;

        public TextItem(String text, float x, float y, float size, int color) {
            this.text = text; this.x = x; this.y = y; this.size = size; this.color = color;
        }

        public String[] lines() { return text.split("\n", -1); }
        public float lineHeight() { return size * 1.3f; }

        @Override public RectF bounds() {
            if (bounds == null) {
                Paint p = new Paint();
                p.setTextSize(size);
                float w = 0;
                String[] ls = lines();
                for (String l : ls) w = Math.max(w, p.measureText(l));
                bounds = new RectF(x, y, x + Math.max(w, size), y + size * 1.3f + (ls.length - 1) * lineHeight());
            }
            return bounds;
        }

        @Override public Item translated(float dx, float dy) { return new TextItem(text, x + dx, y + dy, size, color); }
        @Override public Item scaled(float s, float cx, float cy) {
            return new TextItem(text, cx + (x - cx) * s, cy + (y - cy) * s, size * s, color);
        }
        @Override public Item recolored(int c) { return new TextItem(text, x, y, size, c); }

        @Override JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", "x");
            o.put("text", text);
            o.put("x", (double) x);
            o.put("y", (double) y);
            o.put("size", (double) size);
            o.put("c", color);
            return o;
        }
    }

    public static class ImageItem extends Item {
        public final String file;
        public final RectF rect;

        public ImageItem(String file, RectF rect) { this.file = file; this.rect = rect; }

        @Override public RectF bounds() { return rect; }
        @Override public Item translated(float dx, float dy) {
            RectF r = new RectF(rect);
            r.offset(dx, dy);
            return new ImageItem(file, r);
        }
        @Override public Item scaled(float s, float cx, float cy) {
            return new ImageItem(file, new RectF(cx + (rect.left - cx) * s, cy + (rect.top - cy) * s,
                    cx + (rect.right - cx) * s, cy + (rect.bottom - cy) * s));
        }

        @Override JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", "i");
            o.put("file", file);
            o.put("l", (double) rect.left);
            o.put("tp", (double) rect.top);
            o.put("r", (double) rect.right);
            o.put("b", (double) rect.bottom);
            return o;
        }
    }
}
