package com.cadernolivre.app;

import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

/** Elemento editável do Quadro: retângulo, losango, elipse, linha, seta ou texto. */
public class BoardItem extends Model.Item {
    public static final int RECT = 0, DIAMOND = 1, ELLIPSE = 2, LINE = 3, ARROW = 4, TEXT = 5;
    public static final int FILL_NONE = 0, FILL_HACHURE = 1, FILL_CROSS = 2, FILL_SOLID = 3;
    public static final int DASH_SOLID = 0, DASH_DASHED = 1, DASH_DOTTED = 2;

    /** Estilo visual (tratado como imutável depois de criado o item). */
    public static final class Style {
        public int stroke = 0xFF1E1E1E;
        public int fill = 0;            // 0 = transparente
        public int fillStyle = FILL_HACHURE;
        public float width = 2.5f;
        public int dash = DASH_SOLID;
        public int rough = 1;           // 0 = limpo, 1 = à mão, 2 = bem rabiscado
        public boolean rounded = true;
        public float fontSize = 24f;

        public Style copy() {
            Style s = new Style();
            s.stroke = stroke; s.fill = fill; s.fillStyle = fillStyle; s.width = width;
            s.dash = dash; s.rough = rough; s.rounded = rounded; s.fontSize = fontSize;
            return s;
        }

        void put(JSONObject o) throws JSONException {
            o.put("sc", stroke); o.put("fc", fill); o.put("fs", fillStyle); o.put("sw", (double) width);
            o.put("da", dash); o.put("ro", rough); o.put("rd", rounded); o.put("fz", (double) fontSize);
        }

        static Style from(JSONObject o) {
            Style s = new Style();
            s.stroke = o.optInt("sc", s.stroke); s.fill = o.optInt("fc", 0); s.fillStyle = o.optInt("fs", FILL_HACHURE);
            s.width = (float) o.optDouble("sw", 2.5); s.dash = o.optInt("da", 0); s.rough = o.optInt("ro", 1);
            s.rounded = o.optBoolean("rd", true); s.fontSize = (float) o.optDouble("fz", 24);
            return s;
        }
    }

    public final int type;
    public final float x1, y1, x2, y2;
    public final Style style;
    public final String label;
    public final int seed;
    /** Identificador estável (usado pelos vínculos das setas). */
    public final String id;
    /** Rotação em radianos em torno do centro (formas e texto). */
    public final float angle;
    /** Ids das formas às quais o início/fim da seta/linha está preso ("" = solto). */
    public final String startBind, endBind;

    /** Cache de desenho (preenchido pelo BoardRenderer). */
    Object cache;
    private RectF bounds;

    public BoardItem(int type, float x1, float y1, float x2, float y2, Style style, String label, int seed) {
        this(type, x1, y1, x2, y2, style, label, seed, newId(), 0f, "", "");
    }

    public BoardItem(int type, float x1, float y1, float x2, float y2, Style style, String label, int seed,
                     String id, float angle, String startBind, String endBind) {
        this.type = type;
        this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        this.style = style;
        this.label = label == null ? "" : label;
        this.seed = seed;
        this.id = id == null || id.isEmpty() ? newId() : id;
        this.angle = type == LINE || type == ARROW ? 0f : angle;
        this.startBind = startBind == null ? "" : startBind;
        this.endBind = endBind == null ? "" : endBind;
    }

    public static String newId() { return Long.toString(Math.abs(new Random().nextLong()), 36); }

    public boolean bindable() { return isBox() || type == TEXT; }

    public static int newSeed() { return new Random().nextInt(Integer.MAX_VALUE); }

    public boolean isLinear() { return type == LINE || type == ARROW; }
    public boolean isBox() { return type == RECT || type == DIAMOND || type == ELLIPSE; }

    public RectF rect() {
        return new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
    }

    static Typeface typeface(int rough) {
        return rough > 0 ? Typeface.create("casual", Typeface.NORMAL) : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    /** Tamanho do texto livre (largura, altura). */
    float[] textSize() {
        Paint p = new Paint();
        p.setTextSize(style.fontSize);
        p.setTypeface(typeface(style.rough));
        String[] lines = label.split("\n", -1);
        float w = 0;
        for (String l : lines) w = Math.max(w, p.measureText(l));
        return new float[]{Math.max(w, style.fontSize * 0.5f), lines.length * style.fontSize * 1.25f};
    }

    /** Retângulo sem rotação (para texto: área ocupada pelo texto). */
    public RectF baseRect() {
        if (type == TEXT) {
            float[] s = textSize();
            return new RectF(x1, y1, x1 + s[0], y1 + s[1]);
        }
        return rect();
    }

    public float[] center() {
        RectF r = baseRect();
        return new float[]{r.centerX(), r.centerY()};
    }

    /** Gira (x, y) em torno de (cx, cy) pelo ângulo a. */
    public static float[] rotate(float x, float y, float cx, float cy, float a) {
        if (a == 0) return new float[]{x, y};
        double cs = Math.cos(a), sn = Math.sin(a);
        float dx = x - cx, dy = y - cy;
        return new float[]{cx + (float) (dx * cs - dy * sn), cy + (float) (dx * sn + dy * cs)};
    }

    /** Cantos do retângulo (com folga pad), já girados: TL, TR, BR, BL. */
    public float[] corners(float pad) {
        RectF r = new RectF(baseRect());
        r.inset(-pad, -pad);
        float cx = r.centerX(), cy = r.centerY();
        float[] out = new float[8];
        float[][] c = {{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}};
        for (int i = 0; i < 4; i++) {
            float[] p = rotate(c[i][0], c[i][1], cx, cy, angle);
            out[i * 2] = p[0];
            out[i * 2 + 1] = p[1];
        }
        return out;
    }

    @Override public RectF bounds() {
        if (bounds == null) {
            if (type == TEXT || angle != 0) {
                float pad = type == TEXT ? 0 : style.width / 2f;
                float[] c = corners(pad);
                RectF b = new RectF(c[0], c[1], c[0], c[1]);
                for (int i = 1; i < 4; i++) b.union(c[i * 2], c[i * 2 + 1]);
                bounds = b;
            } else {
                bounds = rect();
                float pad = style.width / 2f + (type == ARROW ? Math.max(12, style.width * 4) : 0);
                bounds.inset(-pad, -pad);
            }
        }
        return bounds;
    }

    public BoardItem withPoints(float a, float b, float c, float d) { return new BoardItem(type, a, b, c, d, style, label, seed, id, angle, startBind, endBind); }
    public BoardItem withStyle(Style s) { return new BoardItem(type, x1, y1, x2, y2, s, label, seed, id, angle, startBind, endBind); }
    public BoardItem withLabel(String l) { return new BoardItem(type, x1, y1, x2, y2, style, l, seed, id, angle, startBind, endBind); }
    public BoardItem withAngle(float a) { return new BoardItem(type, x1, y1, x2, y2, style, label, seed, id, a, startBind, endBind); }
    public BoardItem withBinds(String sb, String eb) { return new BoardItem(type, x1, y1, x2, y2, style, label, seed, id, angle, sb, eb); }
    /** Cópia independente (novo id e nova semente), deslocada. */
    public BoardItem cloneMoved(float dx, float dy, String sb, String eb) {
        return new BoardItem(type, x1 + dx, y1 + dy, x2 + dx, y2 + dy, style, label, newSeed(), newId(), angle, sb, eb);
    }

    @Override public Model.Item translated(float dx, float dy) { return withPoints(x1 + dx, y1 + dy, x2 + dx, y2 + dy); }

    @Override public Model.Item scaled(float s, float cx, float cy) {
        Style st = style;
        if (type == TEXT) { st = style.copy(); st.fontSize = Math.max(6, style.fontSize * s); }
        return new BoardItem(type, cx + (x1 - cx) * s, cy + (y1 - cy) * s, cx + (x2 - cx) * s, cy + (y2 - cy) * s, st, label, seed,
                id, angle, startBind, endBind);
    }

    @Override public Model.Item recolored(int color) {
        Style s = style.copy();
        s.stroke = color;
        return withStyle(s);
    }

    /** Teste de toque (tolerância em unidades do quadro). */
    public boolean hit(float px, float py, float tol) {
        if (angle != 0) {
            float[] c = center();
            float[] q = rotate(px, py, c[0], c[1], -angle);
            px = q[0];
            py = q[1];
        }
        RectF r = rect();
        float t = tol + style.width / 2f;
        switch (type) {
            case TEXT:
                RectF b = new RectF(baseRect());
                b.inset(-tol, -tol);
                return b.contains(px, py);
            case LINE:
            case ARROW:
                return Model.StrokeItem.segDist(px, py, x1, y1, x2, y2) <= t;
            case RECT: {
                boolean filled = style.fill != 0 && style.fillStyle != FILL_NONE;
                RectF outer = new RectF(r);
                outer.inset(-t, -t);
                if (!outer.contains(px, py)) return false;
                if (filled || !label.isEmpty()) return true;
                RectF inner = new RectF(r);
                inner.inset(t, t);
                return inner.width() <= 0 || inner.height() <= 0 || !inner.contains(px, py);
            }
            case ELLIPSE: {
                float rx = Math.max(1, r.width() / 2), ry = Math.max(1, r.height() / 2);
                float dx = (px - r.centerX()) / rx, dy = (py - r.centerY()) / ry;
                double d = Math.sqrt(dx * dx + dy * dy);
                boolean filled = (style.fill != 0 && style.fillStyle != FILL_NONE) || !label.isEmpty();
                double tolN = t / Math.min(rx, ry);
                return filled ? d <= 1 + tolN : Math.abs(d - 1) <= tolN;
            }
            case DIAMOND: {
                float cx = r.centerX(), cy = r.centerY();
                float[] p = {cx, r.top, r.right, cy, cx, r.bottom, r.left, cy};
                boolean filled = (style.fill != 0 && style.fillStyle != FILL_NONE) || !label.isEmpty();
                float hw = Math.max(1, r.width() / 2), hh = Math.max(1, r.height() / 2);
                if (filled && Math.abs(px - cx) / hw + Math.abs(py - cy) / hh <= 1) return true;
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) % 4;
                    if (Model.StrokeItem.segDist(px, py, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1]) <= t) return true;
                }
                return false;
            }
        }
        return false;
    }

    /** Ponto na borda desta forma, na direção do centro para (rx, ry), afastado por gap. */
    public float[] boundaryToward(float rx, float ry, float gap) {
        float[] c = center();
        float[] q = rotate(rx, ry, c[0], c[1], -angle);
        float dx = q[0] - c[0], dy = q[1] - c[1];
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) return c;
        RectF r = baseRect();
        float hw = r.width() / 2 + gap, hh = r.height() / 2 + gap;
        double t;
        if (type == ELLIPSE) t = 1 / Math.sqrt((dx / hw) * (dx / hw) + (dy / hh) * (dy / hh));
        else if (type == DIAMOND) t = 1 / (Math.abs(dx) / Math.max(1, hw) + Math.abs(dy) / Math.max(1, hh));
        else {
            double tx = Math.abs(dx) < 0.001f ? Double.MAX_VALUE : hw / Math.abs(dx);
            double ty = Math.abs(dy) < 0.001f ? Double.MAX_VALUE : hh / Math.abs(dy);
            t = Math.min(tx, ty);
        }
        float lx = c[0] + (float) (dx * t), ly = c[1] + (float) (dy * t);
        return rotate(lx, ly, c[0], c[1], angle);
    }

    /** O ponto (px, py) está sobre/dentro da forma (para prender setas)? */
    public boolean containsForBind(float px, float py, float tol) {
        if (!bindable()) return false;
        float[] c = center();
        float[] q = rotate(px, py, c[0], c[1], -angle);
        RectF r = new RectF(baseRect());
        r.inset(-tol, -tol);
        return r.contains(q[0], q[1]);
    }

    @Override JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("t", "b");
        o.put("ty", type);
        o.put("x1", (double) x1); o.put("y1", (double) y1);
        o.put("x2", (double) x2); o.put("y2", (double) y2);
        o.put("lb", label);
        o.put("sd", seed);
        o.put("id", id);
        if (angle != 0) o.put("an", (double) angle);
        if (!startBind.isEmpty()) o.put("bs", startBind);
        if (!endBind.isEmpty()) o.put("be", endBind);
        style.put(o);
        return o;
    }

    static BoardItem fromJson(JSONObject o) {
        return new BoardItem(o.optInt("ty"), (float) o.optDouble("x1"), (float) o.optDouble("y1"),
                (float) o.optDouble("x2"), (float) o.optDouble("y2"), Style.from(o), o.optString("lb", ""), o.optInt("sd", 1),
                o.optString("id", ""), (float) o.optDouble("an", 0), o.optString("bs", ""), o.optString("be", ""));
    }
}
