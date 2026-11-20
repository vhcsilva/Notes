package com.cadernolivre.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Área de escrita: caneta com pressão, marca-texto, borracha, laço, texto, formas, zoom e rolagem. */
public class CanvasView extends View {
    public interface Listener {
        void onContentChanged();
        void onTextRequest(float x, float y, Model.TextItem existing);
        void onSelectionChanged(boolean hasSelection);
        void onHistoryChanged();
    }

    public static final int TOOL_PEN = 0, TOOL_HIGHLIGHTER = 1, TOOL_ERASER = 2, TOOL_LASSO = 3, TOOL_TEXT = 4, TOOL_SHAPE = 5;
    public static final int SHAPE_LINE = 0, SHAPE_RECT = 1, SHAPE_OVAL = 2, SHAPE_ARROW = 3;

    private static final int M_NONE = 0, M_DRAW = 1, M_PAN = 2, M_MULTI = 3, M_ERASE = 4, M_LASSO = 5,
            M_DRAG = 6, M_SHAPE = 7, M_TAP = 8;

    public int tool = TOOL_PEN;
    public int penColor = 0xFF1A237E, hlColor = 0xFFFFEB3B, textColor = 0xFF000000;
    public float penWidth = 4f, hlWidth = 26f, eraserSize = 18f, textSize = 34f;
    public int shapeType = SHAPE_RECT;
    public boolean stylusOnly = false;

    private Listener listener;
    private Model.Page page;
    private Bitmap pdfBitmap;
    private PageRenderer renderer;

    private final Matrix matrix = new Matrix(), inverse = new Matrix();
    private boolean needsFit = true;
    private final ScaleGestureDetector scaleDetector;

    private final Map<Model.Page, ArrayDeque<List<Model.Item>>> undo = new HashMap<>(), redo = new HashMap<>();

    private int mode = M_NONE;
    private float lastX, lastY, downX, downY;
    private float[] buf = new float[300];
    private int bufLen;
    private int curKind;
    private Model.StrokeItem preview;
    private List<Model.Item> eraseSnapshot;
    private boolean erased;
    private float eraserX = -1, eraserY = -1;
    private final List<Float> lasso = new ArrayList<>();
    private final List<Model.Item> selection = new ArrayList<>();
    private RectF selBounds;
    private float dragDx, dragDy;
    private float shX0, shY0;

    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CanvasView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        overlay.setStyle(Paint.Style.STROKE);
        shadow.setColor(0x30000000);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (mode != M_MULTI) return false;
                float cur = scale();
                float target = Math.max(fitScale() * 0.5f, Math.min(fitScale() * 8f, cur * d.getScaleFactor()));
                float f = target / cur;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                clampMatrix();
                invalidate();
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }
    public void setRenderer(PageRenderer r) { renderer = r; }

    public void setPage(Model.Page p) {
        page = p;
        pdfBitmap = null;
        preview = null;
        clearSelection();
        needsFit = true;
        invalidate();
        if (listener != null) listener.onHistoryChanged();
    }

    public Model.Page getPage() { return page; }

    public void setPdfBitmap(Bitmap b) {
        pdfBitmap = b;
        invalidate();
    }

    public void setTool(int t) {
        if (t != TOOL_LASSO) clearSelection();
        tool = t;
        invalidate();
    }

    // ---------------------------------------------------------------- matriz de visualização
    private float scale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private float fitScale() {
        if (page == null || getWidth() == 0) return 1f;
        float m = getWidth() * 0.03f;
        float sw = (getWidth() - 2 * m) / Model.PAGE_W;
        if (getWidth() > getHeight()) sw = Math.min(sw, (getHeight() - 2 * m) / page.height);
        return sw;
    }

    public void fit() {
        if (page == null || getWidth() == 0) { needsFit = true; return; }
        float s = fitScale();
        float pw = Model.PAGE_W * s, ph = page.height * s;
        float tx = (getWidth() - pw) / 2f;
        float ty = ph < getHeight() ? (getHeight() - ph) / 2f : getWidth() * 0.03f;
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate(tx, ty);
        needsFit = false;
        invalidate();
    }

    private void clampMatrix() {
        RectF r = new RectF(0, 0, Model.PAGE_W, page.height);
        matrix.mapRect(r);
        float w = getWidth(), h = getHeight(), dx = 0, dy = 0;
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > w * 0.25f) dx = w * 0.25f - r.left;
        else if (r.right < w * 0.75f) dx = w * 0.75f - r.right;
        if (r.top > h * 0.4f) dy = h * 0.4f - r.top;
        else if (r.bottom < h * 0.6f) dy = h * 0.6f - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    private float[] toPage(float x, float y) {
        matrix.invert(inverse);
        float[] p = {x, y};
        inverse.mapPoints(p);
        return p;
    }

    /** Centro da área visível, em coordenadas de página. */
    public float[] visibleCenter() {
        float[] p = toPage(getWidth() / 2f, getHeight() / 2f);
        p[0] = Math.max(100, Math.min(Model.PAGE_W - 100, p[0]));
        if (page != null) p[1] = Math.max(100, Math.min(page.height - 100, p[1]));
        return p;
    }

    public void panBy(float dy) {
        matrix.postTranslate(0, dy);
        clampMatrix();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        needsFit = true;
    }

    // ---------------------------------------------------------------- desenho
    @Override protected void onDraw(Canvas c) {
        c.drawColor(0xFFD9DEE4);
        if (page == null || renderer == null) return;
        if (needsFit) fit();
        float s = scale();
        c.save();
        c.concat(matrix);
        c.drawRect(4 / s, 6 / s, Model.PAGE_W + 4 / s, page.height + 6 / s, shadow);
        boolean dragging = mode == M_DRAG && !selection.isEmpty();
        renderer.drawBackground(c, page, pdfBitmap);
        c.save();
        c.clipRect(0, 0, Model.PAGE_W, page.height);
        renderer.drawItems(c, page.items, dragging ? new HashSet<>(selection) : null);
        if (dragging) {
            c.save();
            c.translate(dragDx, dragDy);
            renderer.drawItems(c, selection, null);
            c.restore();
        }
        if (preview != null) renderer.drawItem(c, preview);
        c.restore();

        overlay.setStrokeWidth(2f / s);
        if (mode == M_LASSO && lasso.size() >= 4) {
            Path lp = new Path();
            lp.moveTo(lasso.get(0), lasso.get(1));
            for (int i = 2; i < lasso.size(); i += 2) lp.lineTo(lasso.get(i), lasso.get(i + 1));
            overlay.setColor(0xFF1976D2);
            overlay.setPathEffect(new DashPathEffect(new float[]{10 / s, 8 / s}, 0));
            c.drawPath(lp, overlay);
        }
        if (selBounds != null && !selection.isEmpty()) {
            RectF r = new RectF(selBounds);
            if (dragging) r.offset(dragDx, dragDy);
            r.inset(-8 / s, -8 / s);
            overlay.setColor(0xFF1976D2);
            overlay.setPathEffect(new DashPathEffect(new float[]{12 / s, 8 / s}, 0));
            c.drawRect(r, overlay);
        }
        overlay.setPathEffect(null);
        if (mode == M_ERASE && eraserX >= 0) {
            overlay.setColor(0xFF757575);
            c.drawCircle(eraserX, eraserY, eraserSize, overlay);
        }
        c.restore();
    }

    // ---------------------------------------------------------------- toque
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (page == null) return false;
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                int tt = e.getToolType(0);
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                if (stylusOnly && tt == MotionEvent.TOOL_TYPE_FINGER) { mode = M_PAN; return true; }
                float[] p = toPage(e.getX(), e.getY());
                int t = tool;
                if (tt == MotionEvent.TOOL_TYPE_ERASER || (tt == MotionEvent.TOOL_TYPE_STYLUS
                        && (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)) t = TOOL_ERASER;
                startTool(t, p, e);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelCurrent();
                mode = M_MULTI;
                float[] f = focus(e, -1);
                lastX = f[0];
                lastY = f[1];
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == M_MULTI) {
                    float[] fc = focus(e, -1);
                    matrix.postTranslate(fc[0] - lastX, fc[1] - lastY);
                    lastX = fc[0];
                    lastY = fc[1];
                    clampMatrix();
                    invalidate();
                } else if (mode == M_PAN) {
                    matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX();
                    lastY = e.getY();
                    clampMatrix();
                    invalidate();
                } else moveTool(e);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == M_MULTI) {
                    float[] fu = focus(e, e.getActionIndex());
                    lastX = fu[0];
                    lastY = fu[1];
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mode != M_MULTI && mode != M_PAN) endTool(e);
                mode = M_NONE;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelCurrent();
                mode = M_NONE;
                invalidate();
                return true;
        }
        return true;
    }

    private static float[] focus(MotionEvent e, int skip) {
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sx += e.getX(i);
            sy += e.getY(i);
            n++;
        }
        return n == 0 ? new float[]{e.getX(), e.getY()} : new float[]{sx / n, sy / n};
    }

    private float pressure(MotionEvent e, int h) {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return 1f;
        float p = h < 0 ? e.getPressure() : e.getHistoricalPressure(h);
        return Math.max(0.05f, Math.min(1f, p));
    }

    private void startTool(int t, float[] p, MotionEvent e) {
        switch (t) {
            case TOOL_PEN:
            case TOOL_HIGHLIGHTER:
                mode = M_DRAW;
                curKind = t == TOOL_PEN ? Model.StrokeItem.PEN : Model.StrokeItem.HIGHLIGHTER;
                bufLen = 0;
                addPoint(p[0], p[1], pressure(e, -1), true);
                updatePreview();
                break;
            case TOOL_ERASER:
                mode = M_ERASE;
                eraseSnapshot = new ArrayList<>(page.items);
                erased = false;
                eraseAt(p[0], p[1]);
                break;
            case TOOL_LASSO:
                if (selBounds != null && !selection.isEmpty()) {
                    RectF r = new RectF(selBounds);
                    r.inset(-20 / scale(), -20 / scale());
                    if (r.contains(p[0], p[1])) {
                        mode = M_DRAG;
                        dragDx = dragDy = 0;
                        shX0 = p[0];
                        shY0 = p[1];
                        break;
                    }
                }
                clearSelection();
                mode = M_LASSO;
                lasso.clear();
                lasso.add(p[0]);
                lasso.add(p[1]);
                break;
            case TOOL_SHAPE:
                mode = M_SHAPE;
                shX0 = p[0];
                shY0 = p[1];
                break;
            case TOOL_TEXT:
                mode = M_TAP;
                break;
        }
        invalidate();
    }

    private void moveTool(MotionEvent e) {
        int hs = e.getHistorySize();
        switch (mode) {
            case M_DRAW:
                for (int h = 0; h < hs; h++) {
                    float[] p = toPage(e.getHistoricalX(h), e.getHistoricalY(h));
                    addPoint(p[0], p[1], pressure(e, h), false);
                }
                float[] p = toPage(e.getX(), e.getY());
                addPoint(p[0], p[1], pressure(e, -1), false);
                updatePreview();
                break;
            case M_ERASE:
                for (int h = 0; h < hs; h++) {
                    float[] q = toPage(e.getHistoricalX(h), e.getHistoricalY(h));
                    eraseAt(q[0], q[1]);
                }
                float[] q = toPage(e.getX(), e.getY());
                eraseAt(q[0], q[1]);
                break;
            case M_LASSO: {
                float[] l = toPage(e.getX(), e.getY());
                lasso.add(l[0]);
                lasso.add(l[1]);
                break;
            }
            case M_DRAG: {
                float[] d = toPage(e.getX(), e.getY());
                dragDx = d[0] - shX0;
                dragDy = d[1] - shY0;
                break;
            }
            case M_SHAPE: {
                float[] s = toPage(e.getX(), e.getY());
                preview = buildShape(shX0, shY0, s[0], s[1]);
                break;
            }
            default:
                return;
        }
        invalidate();
    }

    private void endTool(MotionEvent e) {
        switch (mode) {
            case M_DRAW:
                if (bufLen >= 3) {
                    float[] pts = new float[bufLen];
                    System.arraycopy(buf, 0, pts, 0, bufLen);
                    float w = curKind == Model.StrokeItem.PEN ? penWidth : hlWidth;
                    int col = curKind == Model.StrokeItem.PEN ? penColor : hlColor;
                    addItem(new Model.StrokeItem(curKind, col, w, pts));
                }
                preview = null;
                break;
            case M_ERASE:
                if (erased) {
                    pushUndo(eraseSnapshot);
                    changed();
                }
                eraseSnapshot = null;
                eraserX = -1;
                break;
            case M_LASSO:
                finishLasso();
                break;
            case M_DRAG:
                if (Math.abs(dragDx) > 0.5f || Math.abs(dragDy) > 0.5f) {
                    final float dx = dragDx, dy = dragDy;
                    transformSelection(it -> it.translated(dx, dy));
                }
                dragDx = dragDy = 0;
                break;
            case M_SHAPE:
                if (preview != null) {
                    RectF b = preview.bounds();
                    if (b.width() > 6 || b.height() > 6) addItem(preview);
                }
                preview = null;
                break;
            case M_TAP:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < 24 && listener != null) {
                    float[] p = toPage(e.getX(), e.getY());
                    Model.TextItem hit = null;
                    for (int i = page.items.size() - 1; i >= 0; i--) {
                        Model.Item it = page.items.get(i);
                        if (it instanceof Model.TextItem && it.bounds().contains(p[0], p[1])) { hit = (Model.TextItem) it; break; }
                    }
                    listener.onTextRequest(p[0], p[1], hit);
                }
                break;
        }
    }

    private void cancelCurrent() {
        if (mode == M_ERASE && erased) {
            pushUndo(eraseSnapshot);
            changed();
        }
        preview = null;
        bufLen = 0;
        lasso.clear();
        dragDx = dragDy = 0;
        eraserX = -1;
        invalidate();
    }

    private void addPoint(float x, float y, float p, boolean first) {
        if (!first && bufLen >= 3) {
            float dx = x - buf[bufLen - 3], dy = y - buf[bufLen - 2];
            if (dx * dx + dy * dy < 0.8f) return;
        }
        if (bufLen + 3 > buf.length) {
            float[] nb = new float[buf.length * 2];
            System.arraycopy(buf, 0, nb, 0, bufLen);
            buf = nb;
        }
        buf[bufLen++] = x;
        buf[bufLen++] = y;
        buf[bufLen++] = p;
    }

    private void updatePreview() {
        float[] pts = new float[bufLen];
        System.arraycopy(buf, 0, pts, 0, bufLen);
        boolean pen = curKind == Model.StrokeItem.PEN;
        preview = new Model.StrokeItem(curKind, pen ? penColor : hlColor, pen ? penWidth : hlWidth, pts);
    }

    private void eraseAt(float x, float y) {
        eraserX = x;
        eraserY = y;
        for (int i = page.items.size() - 1; i >= 0; i--) {
            Model.Item it = page.items.get(i);
            boolean hit = false;
            if (it instanceof Model.StrokeItem) hit = ((Model.StrokeItem) it).hit(x, y, eraserSize);
            else if (it instanceof Model.TextItem) hit = it.bounds().contains(x, y);
            if (hit) {
                page.items.remove(i);
                erased = true;
            }
        }
    }

    private Model.StrokeItem buildShape(float x0, float y0, float x1, float y1) {
        float[] pts;
        switch (shapeType) {
            case SHAPE_LINE:
                pts = new float[]{x0, y0, 1, x1, y1, 1};
                break;
            case SHAPE_OVAL: {
                int n = 64;
                pts = new float[(n + 1) * 3];
                float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, rx = Math.abs(x1 - x0) / 2, ry = Math.abs(y1 - y0) / 2;
                for (int i = 0; i <= n; i++) {
                    double a = 2 * Math.PI * i / n;
                    pts[i * 3] = cx + (float) Math.cos(a) * rx;
                    pts[i * 3 + 1] = cy + (float) Math.sin(a) * ry;
                    pts[i * 3 + 2] = 1;
                }
                break;
            }
            case SHAPE_ARROW: {
                double a = Math.atan2(y1 - y0, x1 - x0);
                float L = Math.max(22, penWidth * 5);
                float hx1 = x1 - L * (float) Math.cos(a - 0.45), hy1 = y1 - L * (float) Math.sin(a - 0.45);
                float hx2 = x1 - L * (float) Math.cos(a + 0.45), hy2 = y1 - L * (float) Math.sin(a + 0.45);
                pts = new float[]{x0, y0, 1, x1, y1, 1, hx1, hy1, 1, x1, y1, 1, hx2, hy2, 1};
                break;
            }
            default:
                pts = new float[]{x0, y0, 1, x1, y0, 1, x1, y1, 1, x0, y1, 1, x0, y0, 1};
        }
        return new Model.StrokeItem(Model.StrokeItem.SHAPE, penColor, penWidth, pts);
    }

    // ---------------------------------------------------------------- seleção (laço)
    private void finishLasso() {
        int n = lasso.size() / 2;
        if (n < 3) { lasso.clear(); return; }
        float[] poly = new float[n * 2];
        for (int i = 0; i < poly.length; i++) poly[i] = lasso.get(i);
        lasso.clear();
        selection.clear();
        for (Model.Item it : page.items) {
            if (it instanceof Model.StrokeItem) {
                Model.StrokeItem s = (Model.StrokeItem) it;
                int in = 0, c = s.count();
                for (int i = 0; i < c; i++) if (inside(poly, s.pts[i * 3], s.pts[i * 3 + 1])) in++;
                if (c > 0 && in * 2 >= c) selection.add(it);
            } else {
                RectF b = it.bounds();
                if (inside(poly, b.centerX(), b.centerY())) selection.add(it);
            }
        }
        updateSelBounds();
    }

    private static boolean inside(float[] poly, float x, float y) {
        boolean c = false;
        int n = poly.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = poly[i * 2], yi = poly[i * 2 + 1], xj = poly[j * 2], yj = poly[j * 2 + 1];
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) c = !c;
        }
        return c;
    }

    private void updateSelBounds() {
        if (selection.isEmpty()) selBounds = null;
        else {
            selBounds = new RectF(selection.get(0).bounds());
            for (Model.Item it : selection) selBounds.union(it.bounds());
        }
        if (listener != null) listener.onSelectionChanged(!selection.isEmpty());
        invalidate();
    }

    public void selectOnly(Model.Item it) {
        tool = TOOL_LASSO;
        selection.clear();
        selection.add(it);
        updateSelBounds();
    }

    public void clearSelection() {
        boolean had = !selection.isEmpty();
        selection.clear();
        selBounds = null;
        if (had && listener != null) listener.onSelectionChanged(false);
        invalidate();
    }

    interface Transform { Model.Item apply(Model.Item it); }

    private void transformSelection(Transform t) {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        List<Model.Item> ns = new ArrayList<>();
        for (Model.Item it : selection) {
            Model.Item n = t.apply(it);
            int idx = page.items.indexOf(it);
            if (idx >= 0) page.items.set(idx, n);
            ns.add(n);
        }
        selection.clear();
        selection.addAll(ns);
        updateSelBounds();
        changed();
    }

    public void deleteSelection() {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        page.items.removeAll(selection);
        clearSelection();
        changed();
    }

    public void duplicateSelection() {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        List<Model.Item> ns = new ArrayList<>();
        for (Model.Item it : selection) {
            Model.Item n = it.translated(30, 30);
            page.items.add(n);
            ns.add(n);
        }
        selection.clear();
        selection.addAll(ns);
        updateSelBounds();
        changed();
    }

    public void recolorSelection(final int color) { transformSelection(it -> it.recolored(color)); }

    public void scaleSelection(final float s) {
        if (selBounds == null) return;
        final float cx = selBounds.centerX(), cy = selBounds.centerY();
        transformSelection(it -> it.scaled(s, cx, cy));
    }

    // ---------------------------------------------------------------- edição e histórico
    public void addItem(Model.Item it) {
        pushUndo(new ArrayList<>(page.items));
        page.items.add(it);
        changed();
    }

    public void replaceItem(Model.Item old, Model.Item n) {
        int idx = page.items.indexOf(old);
        pushUndo(new ArrayList<>(page.items));
        if (idx < 0) { if (n != null) page.items.add(n); }
        else if (n == null) page.items.remove(idx);
        else page.items.set(idx, n);
        changed();
    }

    public void clearPage() {
        if (page.items.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        page.items = new ArrayList<>();
        clearSelection();
        changed();
    }

    private ArrayDeque<List<Model.Item>> stack(Map<Model.Page, ArrayDeque<List<Model.Item>>> m) {
        ArrayDeque<List<Model.Item>> s = m.get(page);
        if (s == null) { s = new ArrayDeque<>(); m.put(page, s); }
        return s;
    }

    private void pushUndo(List<Model.Item> snapshot) {
        ArrayDeque<List<Model.Item>> u = stack(undo);
        u.push(snapshot);
        while (u.size() > 100) u.removeLast();
        stack(redo).clear();
    }

    public boolean canUndo() { return page != null && !stack(undo).isEmpty(); }
    public boolean canRedo() { return page != null && !stack(redo).isEmpty(); }

    public void undo() {
        if (!canUndo()) return;
        stack(redo).push(new ArrayList<>(page.items));
        page.items = stack(undo).pop();
        clearSelection();
        changed();
    }

    public void redo() {
        if (!canRedo()) return;
        stack(undo).push(new ArrayList<>(page.items));
        page.items = stack(redo).pop();
        clearSelection();
        changed();
    }

    public void forgetHistory(Model.Page p) {
        undo.remove(p);
        redo.remove(p);
    }

    private void changed() {
        invalidate();
        if (listener != null) {
            listener.onContentChanged();
            listener.onHistoryChanged();
        }
    }
}
