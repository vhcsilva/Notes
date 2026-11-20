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
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Área de escrita com rolagem contínua entre páginas: caneta com pressão, marca-texto,
 * borracha (parcial ou por traço), laço, texto, formas, zoom e desfazer/refazer.
 */
public class CanvasView extends View {
    public interface Listener {
        void onContentChanged();
        void onTextRequest(Model.Page page, float x, float y, Model.TextItem existing);
        void onSelectionChanged(boolean hasSelection);
        void onHistoryChanged();
        void onVisiblePageChanged(int index);
    }

    /** Fornece bitmaps de páginas de PDF de forma assíncrona (chame setPdfBitmap quando pronto). */
    public interface PdfSource { void request(Model.Page page, int widthPx); }

    public static final int TOOL_PEN = 0, TOOL_HIGHLIGHTER = 1, TOOL_ERASER = 2, TOOL_LASSO = 3, TOOL_TEXT = 4, TOOL_SHAPE = 5;
    public static final int SHAPE_LINE = 0, SHAPE_RECT = 1, SHAPE_OVAL = 2, SHAPE_ARROW = 3;
    public static final float GAP = 36f;

    private static final int M_NONE = 0, M_DRAW = 1, M_PAN = 2, M_MULTI = 3, M_ERASE = 4, M_LASSO = 5,
            M_DRAG = 6, M_SHAPE = 7, M_TAP = 8;

    public int tool = TOOL_PEN;
    public int penColor = 0xFF1A237E, hlColor = 0xFFFFEB3B, textColor = 0xFF000000;
    public float penWidth = 4f, hlWidth = 26f, eraserSize = 18f, textSize = 34f;
    public int shapeType = SHAPE_RECT;
    public boolean stylusOnly = false;
    public boolean partialEraser = true;

    private Listener listener;
    private PdfSource pdfSource;
    private PageRenderer renderer;
    private Model.Notebook nb;
    private float[] tops = new float[0];
    private float docH;
    private final Map<Model.Page, Bitmap> pdfCache = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Model.Page> requested = new HashSet<>();

    private final Matrix matrix = new Matrix(), inverse = new Matrix();
    private boolean needsFit = true;
    private int pendingScrollPage = -1;
    private int visibleIndex = -1;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private int flingLastX, flingLastY;

    private static final class Entry {
        final Model.Page page;
        final List<Model.Item> items;
        Entry(Model.Page p, List<Model.Item> i) { page = p; items = i; }
    }

    private final ArrayDeque<Entry> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    // estado do gesto atual
    private int mode = M_NONE;
    private Model.Page gPage;
    private float gTop;
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
    private Model.Page selPage;
    private RectF selBounds;
    private float dragDx, dragDy, shX0, shY0;

    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CanvasView(Context c) {
        super(c);
        overlay.setStyle(Paint.Style.STROKE);
        scroller = new OverScroller(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (mode != M_MULTI) return false;
                float cur = scale();
                float target = Math.max(fitScale() * 0.4f, Math.min(fitScale() * 8f, cur * d.getScaleFactor()));
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
    public void setPdfSource(PdfSource s) { pdfSource = s; }

    public void setNotebook(Model.Notebook n, int startPage) {
        nb = n;
        relayout();
        needsFit = true;
        pendingScrollPage = startPage;
        invalidate();
    }

    /** Recalcula a posição das páginas (após adicionar, remover ou reordenar). */
    public void relayout() {
        if (nb == null) return;
        tops = new float[nb.pages.size()];
        float y = 0;
        for (int i = 0; i < tops.length; i++) {
            tops[i] = y;
            y += nb.pages.get(i).height + GAP;
        }
        docH = Math.max(1, y - GAP);
        Set<Model.Page> live = new HashSet<>(nb.pages);
        Iterator<Map.Entry<Model.Page, Bitmap>> it = pdfCache.entrySet().iterator();
        while (it.hasNext()) if (!live.contains(it.next().getKey())) it.remove();
        requested.retainAll(live);
        if (selPage != null && !live.contains(selPage)) clearSelection();
        Iterator<Entry> u = undo.iterator();
        while (u.hasNext()) if (!live.contains(u.next().page)) u.remove();
        Iterator<Entry> r = redo.iterator();
        while (r.hasNext()) if (!live.contains(r.next().page)) r.remove();
        visibleIndex = -1;
        if (getWidth() > 0) clampMatrix();
        invalidate();
        if (listener != null) listener.onHistoryChanged();
    }

    public void setPdfBitmap(Model.Page p, Bitmap b) {
        requested.remove(p);
        if (b == null || nb == null || !nb.pages.contains(p)) return;
        pdfCache.put(p, b);
        while (pdfCache.size() > 8) {
            Iterator<Map.Entry<Model.Page, Bitmap>> it = pdfCache.entrySet().iterator();
            it.next();
            it.remove();
        }
        invalidate();
    }

    /** Força renderizar de novo (ex.: depois de mudar o modelo de papel). */
    public void refresh() { invalidate(); }

    public void setTool(int t) {
        if (t != TOOL_LASSO) clearSelection();
        tool = t;
        invalidate();
    }

    public int visiblePage() { return Math.max(0, visibleIndex); }

    // ================================================================ matriz de visualização
    private float scale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private float fitScale() {
        if (nb == null || getWidth() == 0) return 1f;
        float m = getWidth() * 0.03f;
        float sw = (getWidth() - 2 * m) / Model.PAGE_W;
        if (getWidth() > getHeight() * 1.1f) {
            float h = nb.pages.get(Math.max(0, Math.min(visiblePage(), nb.pages.size() - 1))).height;
            sw = Math.min(sw, (getHeight() - 2 * m) / h);
        }
        return sw;
    }

    public void fit() {
        if (nb == null || getWidth() == 0) { needsFit = true; return; }
        int page = pendingScrollPage >= 0 ? pendingScrollPage : visiblePage();
        float s = fitScale();
        matrix.reset();
        matrix.postScale(s, s);
        needsFit = false;
        scrollToPage(page);
        pendingScrollPage = -1;
    }

    public void scrollToPage(int i) {
        if (nb == null || i < 0 || i >= tops.length) return;
        scroller.forceFinished(true);
        float s = scale();
        float tx = (getWidth() - Model.PAGE_W * s) / 2f;
        float ty = getWidth() * 0.03f - tops[i] * s;
        float[] v = new float[9];
        matrix.getValues(v);
        if (Model.PAGE_W * s > getWidth()) tx = v[Matrix.MTRANS_X];
        matrix.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y]);
        clampMatrix();
        invalidate();
    }

    private void clampMatrix() {
        if (nb == null) return;
        RectF r = new RectF(0, 0, Model.PAGE_W, docH);
        matrix.mapRect(r);
        float w = getWidth(), h = getHeight(), dx = 0, dy = 0;
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > w * 0.2f) dx = w * 0.2f - r.left;
        else if (r.right < w * 0.8f) dx = w * 0.8f - r.right;
        if (r.height() <= h * 0.9f) {
            float m = w * 0.03f;
            if (r.top > h - r.height() - m) dy = Math.max(m, h - r.height() - m) - r.top;
            if (r.top + dy < m && r.height() < h) dy = m - r.top;
        } else if (r.top > h * 0.35f) dy = h * 0.35f - r.top;
        else if (r.bottom < h * 0.65f) dy = h * 0.65f - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    private float[] toDoc(float x, float y) {
        matrix.invert(inverse);
        float[] p = {x, y};
        inverse.mapPoints(p);
        return p;
    }

    private int pageIndexAt(float docY) {
        if (tops.length == 0) return -1;
        for (int i = 0; i < tops.length; i++) {
            float bottom = tops[i] + nb.pages.get(i).height;
            if (docY < bottom + GAP / 2f) return i;
        }
        return tops.length - 1;
    }

    /** Página visível e ponto central dela, em coordenadas da página: {índice, x, y}. */
    public float[] visibleCenter() {
        float[] d = toDoc(getWidth() / 2f, getHeight() / 2f);
        int i = Math.max(0, pageIndexAt(d[1]));
        Model.Page p = nb.pages.get(i);
        float x = Math.max(150, Math.min(Model.PAGE_W - 150, d[0]));
        float y = Math.max(150, Math.min(p.height - 150, d[1] - tops[i]));
        return new float[]{i, x, y};
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (ow == 0) needsFit = true;
        else { pendingScrollPage = visiblePage(); needsFit = true; }
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int x = scroller.getCurrX(), y = scroller.getCurrY();
            matrix.postTranslate(x - flingLastX, y - flingLastY);
            flingLastX = x;
            flingLastY = y;
            clampMatrix();
            postInvalidateOnAnimation();
        }
    }

    // ================================================================ desenho
    @Override protected void onDraw(Canvas c) {
        c.drawColor(Th.desk);
        if (nb == null || renderer == null) return;
        if (needsFit) fit();
        float s = scale();
        float[] a = toDoc(0, 0), b = toDoc(0, getHeight());
        c.save();
        c.concat(matrix);
        shadow.setColor(Th.dark ? 0x66000000 : 0x22000000);
        int center = pageIndexAt(toDoc(getWidth() / 2f, getHeight() / 2f)[1]);
        for (int i = 0; i < tops.length; i++) {
            Model.Page p = nb.pages.get(i);
            float top = tops[i];
            if (top > b[1] || top + p.height < a[1]) continue;
            c.save();
            c.translate(0, top);
            c.drawRect(3 / s, 5 / s, Model.PAGE_W + 3 / s, p.height + 5 / s, shadow);
            Bitmap pb = null;
            if (p.pdf != null) {
                pb = pdfCache.get(p);
                if (pb == null && pdfSource != null && requested.add(p)) {
                    pdfSource.request(p, Math.min(2200, Math.max(1000, Math.round(Model.PAGE_W * s * 1.5f))));
                }
            }
            renderer.drawBackground(c, p, pb);
            c.save();
            c.clipRect(0, 0, Model.PAGE_W, p.height);
            boolean dragHere = mode == M_DRAG && p == selPage && !selection.isEmpty();
            renderer.drawItems(c, p.items, dragHere ? new HashSet<>(selection) : null);
            if (dragHere) {
                c.save();
                c.translate(dragDx, dragDy);
                renderer.drawItems(c, selection, null);
                c.restore();
            }
            if (preview != null && p == gPage) renderer.drawItem(c, preview);
            c.restore();
            drawOverlays(c, p, s);
            c.restore();
        }
        c.restore();
        if (center != visibleIndex && center >= 0) {
            visibleIndex = center;
            final int vi = center;
            post(() -> { if (listener != null) listener.onVisiblePageChanged(vi); });
        }
    }

    private void drawOverlays(Canvas c, Model.Page p, float s) {
        overlay.setStrokeWidth(2f / s);
        if (p == gPage && mode == M_LASSO && lasso.size() >= 4) {
            Path lp = new Path();
            lp.moveTo(lasso.get(0), lasso.get(1));
            for (int i = 2; i < lasso.size(); i += 2) lp.lineTo(lasso.get(i), lasso.get(i + 1));
            fillPaint.setColor(0x144C5FD5);
            c.drawPath(lp, fillPaint);
            overlay.setColor(Th.primary);
            overlay.setPathEffect(new DashPathEffect(new float[]{10 / s, 8 / s}, 0));
            c.drawPath(lp, overlay);
        }
        if (p == selPage && selBounds != null && !selection.isEmpty()) {
            RectF r = new RectF(selBounds);
            if (mode == M_DRAG) r.offset(dragDx, dragDy);
            r.inset(-10 / s, -10 / s);
            fillPaint.setColor(0x104C5FD5);
            c.drawRoundRect(r, 8 / s, 8 / s, fillPaint);
            overlay.setColor(Th.primary);
            overlay.setPathEffect(new DashPathEffect(new float[]{12 / s, 8 / s}, 0));
            c.drawRoundRect(r, 8 / s, 8 / s, overlay);
        }
        overlay.setPathEffect(null);
        if (p == gPage && mode == M_ERASE && eraserX >= 0) {
            fillPaint.setColor(0x22000000);
            c.drawCircle(eraserX, eraserY, eraserSize, fillPaint);
            overlay.setColor(0xFF757575);
            c.drawCircle(eraserX, eraserY, eraserSize, overlay);
        }
    }

    // ================================================================ toque
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (nb == null) return false;
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                scroller.forceFinished(true);
                int tt = e.getToolType(0);
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                if (stylusOnly && tt == MotionEvent.TOOL_TYPE_FINGER) {
                    mode = M_PAN;
                    if (velocity == null) velocity = VelocityTracker.obtain();
                    velocity.clear();
                    velocity.addMovement(e);
                    return true;
                }
                float[] d = toDoc(e.getX(), e.getY());
                int pi = pageIndexAt(d[1]);
                if (pi < 0) return true;
                gPage = nb.pages.get(pi);
                gTop = tops[pi];
                int t = tool;
                if (tt == MotionEvent.TOOL_TYPE_ERASER || (tt == MotionEvent.TOOL_TYPE_STYLUS
                        && (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)) t = TOOL_ERASER;
                startTool(t, new float[]{d[0], d[1] - gTop}, e);
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
                    if (velocity != null) velocity.addMovement(e);
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
                if (mode == M_PAN && velocity != null) {
                    velocity.addMovement(e);
                    velocity.computeCurrentVelocity(1000);
                    flingLastX = flingLastY = 0;
                    scroller.fling(0, 0, (int) velocity.getXVelocity(), (int) velocity.getYVelocity(),
                            -100000, 100000, -1000000, 1000000);
                    postInvalidateOnAnimation();
                } else if (mode != M_MULTI && mode != M_PAN) endTool(e);
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

    private float[] local(float x, float y) {
        float[] d = toDoc(x, y);
        d[1] -= gTop;
        return d;
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
                eraseSnapshot = new ArrayList<>(gPage.items);
                erased = false;
                eraseAt(p[0], p[1]);
                break;
            case TOOL_LASSO:
                if (selPage == gPage && selBounds != null && !selection.isEmpty()) {
                    RectF r = new RectF(selBounds);
                    r.inset(-24 / scale(), -24 / scale());
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
                    float[] p = local(e.getHistoricalX(h), e.getHistoricalY(h));
                    addPoint(p[0], p[1], pressure(e, h), false);
                }
                float[] p = local(e.getX(), e.getY());
                addPoint(p[0], p[1], pressure(e, -1), false);
                updatePreview();
                break;
            case M_ERASE:
                for (int h = 0; h < hs; h++) {
                    float[] q = local(e.getHistoricalX(h), e.getHistoricalY(h));
                    eraseAt(q[0], q[1]);
                }
                float[] q = local(e.getX(), e.getY());
                eraseAt(q[0], q[1]);
                break;
            case M_LASSO: {
                float[] l = local(e.getX(), e.getY());
                lasso.add(l[0]);
                lasso.add(l[1]);
                break;
            }
            case M_DRAG: {
                float[] d = local(e.getX(), e.getY());
                dragDx = d[0] - shX0;
                dragDy = d[1] - shY0;
                break;
            }
            case M_SHAPE: {
                float[] s = local(e.getX(), e.getY());
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
                    addItem(gPage, new Model.StrokeItem(curKind, col, w, pts));
                }
                preview = null;
                break;
            case M_ERASE:
                if (erased) {
                    pushUndo(gPage, eraseSnapshot);
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
                    dragDx = dragDy = 0;
                    transformSelection(it -> it.translated(dx, dy));
                }
                dragDx = dragDy = 0;
                break;
            case M_SHAPE:
                if (preview != null) {
                    RectF b = preview.bounds();
                    if (b.width() > 6 || b.height() > 6) addItem(gPage, preview);
                }
                preview = null;
                break;
            case M_TAP:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < 24 && listener != null) {
                    float[] p = local(e.getX(), e.getY());
                    Model.TextItem hit = null;
                    for (int i = gPage.items.size() - 1; i >= 0; i--) {
                        Model.Item it = gPage.items.get(i);
                        if (it instanceof Model.TextItem && it.bounds().contains(p[0], p[1])) { hit = (Model.TextItem) it; break; }
                    }
                    listener.onTextRequest(gPage, p[0], p[1], hit);
                }
                break;
        }
    }

    private void cancelCurrent() {
        if (mode == M_ERASE && erased) {
            pushUndo(gPage, eraseSnapshot);
            changed();
        }
        erased = false;
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
            float[] nb2 = new float[buf.length * 2];
            System.arraycopy(buf, 0, nb2, 0, bufLen);
            buf = nb2;
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
        List<Model.Item> items = gPage.items;
        for (int i = items.size() - 1; i >= 0; i--) {
            Model.Item it = items.get(i);
            if (it instanceof Model.StrokeItem) {
                Model.StrokeItem s = (Model.StrokeItem) it;
                if (partialEraser) {
                    List<Model.StrokeItem> parts = s.eraseSplit(x, y, eraserSize);
                    if (parts != null) {
                        items.remove(i);
                        items.addAll(i, parts);
                        erased = true;
                    }
                } else if (s.hit(x, y, eraserSize)) {
                    items.remove(i);
                    erased = true;
                }
            } else if (it instanceof Model.TextItem && it.bounds().contains(x, y)) {
                items.remove(i);
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

    // ================================================================ seleção (laço)
    private void finishLasso() {
        int n = lasso.size() / 2;
        if (n < 3) { lasso.clear(); return; }
        float[] poly = new float[n * 2];
        for (int i = 0; i < poly.length; i++) poly[i] = lasso.get(i);
        lasso.clear();
        selection.clear();
        for (Model.Item it : gPage.items) {
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
        selPage = gPage;
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

    public void selectOnly(Model.Page p, Model.Item it) {
        tool = TOOL_LASSO;
        selection.clear();
        selection.add(it);
        selPage = p;
        updateSelBounds();
    }

    public List<Model.Item> getSelection() { return new ArrayList<>(selection); }
    public Model.Page getSelectionPage() { return selPage; }
    public RectF getSelectionBounds() { return selBounds == null ? null : new RectF(selBounds); }

    public void clearSelection() {
        boolean had = !selection.isEmpty();
        selection.clear();
        selBounds = null;
        if (had && listener != null) listener.onSelectionChanged(false);
        invalidate();
    }

    interface Transform { Model.Item apply(Model.Item it); }

    private void transformSelection(Transform t) {
        if (selection.isEmpty() || selPage == null) return;
        pushUndo(selPage, new ArrayList<>(selPage.items));
        List<Model.Item> ns = new ArrayList<>();
        for (Model.Item it : selection) {
            Model.Item n = t.apply(it);
            int idx = selPage.items.indexOf(it);
            if (idx >= 0) selPage.items.set(idx, n);
            ns.add(n);
        }
        selection.clear();
        selection.addAll(ns);
        updateSelBounds();
        changed();
    }

    public void deleteSelection() {
        if (selection.isEmpty() || selPage == null) return;
        pushUndo(selPage, new ArrayList<>(selPage.items));
        selPage.items.removeAll(selection);
        clearSelection();
        changed();
    }

    public void duplicateSelection() {
        if (selection.isEmpty() || selPage == null) return;
        pushUndo(selPage, new ArrayList<>(selPage.items));
        List<Model.Item> ns = new ArrayList<>();
        for (Model.Item it : selection) {
            Model.Item n = it.translated(30, 30);
            selPage.items.add(n);
            ns.add(n);
        }
        selection.clear();
        selection.addAll(ns);
        updateSelBounds();
        changed();
    }

    /** Substitui os itens selecionados por outros (ex.: escrita convertida em texto). */
    public void replaceSelection(List<Model.Item> replacement) {
        if (selection.isEmpty() || selPage == null) return;
        pushUndo(selPage, new ArrayList<>(selPage.items));
        int at = selPage.items.size();
        for (Model.Item it : selection) at = Math.min(at, Math.max(0, selPage.items.indexOf(it)));
        selPage.items.removeAll(selection);
        at = Math.min(at, selPage.items.size());
        selPage.items.addAll(at, replacement);
        selection.clear();
        selection.addAll(replacement);
        updateSelBounds();
        changed();
    }

    public void recolorSelection(final int color) { transformSelection(it -> it.recolored(color)); }

    public void scaleSelection(final float s) {
        if (selBounds == null) return;
        final float cx = selBounds.centerX(), cy = selBounds.centerY();
        transformSelection(it -> it.scaled(s, cx, cy));
    }

    // ================================================================ edição e histórico
    public void addItem(Model.Page p, Model.Item it) {
        pushUndo(p, new ArrayList<>(p.items));
        p.items.add(it);
        changed();
    }

    public void replaceItem(Model.Page p, Model.Item old, Model.Item n) {
        int idx = p.items.indexOf(old);
        pushUndo(p, new ArrayList<>(p.items));
        if (idx < 0) { if (n != null) p.items.add(n); }
        else if (n == null) p.items.remove(idx);
        else p.items.set(idx, n);
        changed();
    }

    public void clearPage(Model.Page p) {
        if (p.items.isEmpty()) return;
        pushUndo(p, new ArrayList<>(p.items));
        p.items = new ArrayList<>();
        if (selPage == p) clearSelection();
        changed();
    }

    private void pushUndo(Model.Page p, List<Model.Item> snapshot) {
        undo.push(new Entry(p, snapshot));
        while (undo.size() > 150) undo.removeLast();
        redo.clear();
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        Entry e = undo.pop();
        redo.push(new Entry(e.page, new ArrayList<>(e.page.items)));
        e.page.items = e.items;
        clearSelection();
        ensureVisible(e.page);
        changed();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Entry e = redo.pop();
        undo.push(new Entry(e.page, new ArrayList<>(e.page.items)));
        e.page.items = e.items;
        clearSelection();
        ensureVisible(e.page);
        changed();
    }

    private void ensureVisible(Model.Page p) {
        int i = nb.pages.indexOf(p);
        if (i < 0) return;
        RectF r = new RectF(0, tops[i], Model.PAGE_W, tops[i] + p.height);
        matrix.mapRect(r);
        if (r.bottom < 0 || r.top > getHeight()) scrollToPage(i);
    }

    private void changed() {
        invalidate();
        if (listener != null) {
            listener.onContentChanged();
            listener.onHistoryChanged();
        }
    }
}
