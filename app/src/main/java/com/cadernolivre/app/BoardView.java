package com.cadernolivre.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Quadro infinito com formas editáveis, setas, texto e desenho livre. */
public class BoardView extends View {
    public interface Listener {
        void onChanged();
        void onSelectionChanged(int count);
        void onTextRequest(float x, float y, BoardItem existing);
        void onToolChanged(int tool);
        void onHistoryChanged();
        void onZoomChanged(float zoom);
        void onTemporaryEraser(boolean active);
    }

    public static final int T_SELECT = 0, T_HAND = 1, T_RECT = 2, T_DIAMOND = 3, T_ELLIPSE = 4,
            T_ARROW = 5, T_LINE = 6, T_PEN = 7, T_TEXT = 8, T_ERASER = 9;

    private static final int M_NONE = 0, M_PAN = 1, M_MULTI = 2, M_CREATE = 3, M_PEN = 4, M_MARQUEE = 5,
            M_MOVE = 6, M_RESIZE = 7, M_ERASE = 8, M_TAP = 9;
    private static final int SPEN_DOWN = 211, SPEN_UP = 212, SPEN_MOVE = 213;
    public static final int PAPER = 0xFFFCFCFD;

    public int tool = T_SELECT;
    public BoardItem.Style style = new BoardItem.Style();
    public boolean lockTool, showGrid = true;

    private Listener listener;
    private Model.Page page;
    private BoardRenderer renderer;
    private final float density;
    private final Matrix matrix = new Matrix(), inverse = new Matrix();
    private boolean needsFit = true;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private int flingX, flingY;

    private final ArrayDeque<List<Model.Item>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final List<Model.Item> selection = new ArrayList<>();

    // gesto
    private int mode = M_NONE;
    private float lastX, lastY, downX, downY, startBX, startBY;
    private BoardItem creating;
    private float[] buf = new float[300];
    private int bufLen;
    private Model.StrokeItem penPreview;
    private RectF marquee;
    private List<Model.Item> origSel;
    private List<Integer> origIdx;
    private RectF origBounds;
    private int handle = -1;
    private boolean moved, erased, tempEraser;
    private List<Model.Item> snapshot;
    private float eraserX = Float.NaN, eraserY;
    private long lastTapTime;
    private Model.Item lastTapItem;

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BoardView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        overlay.setStyle(Paint.Style.STROKE);
        handleFill.setStyle(Paint.Style.FILL);
        scroller = new OverScroller(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (mode != M_MULTI) return false;
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }
    public void setRenderer(BoardRenderer r) { renderer = r; }

    public void setPage(Model.Page p) {
        page = p;
        needsFit = true;
        invalidate();
    }

    public List<Model.Item> items() { return page.items; }

    public void setTool(int t) {
        if (t != T_SELECT) clearSelection();
        tool = t;
        invalidate();
    }

    // ================================================================ visualização
    private float scale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    public float zoom() { return scale() / density; }

    private void zoomBy(float f, float fx, float fy) {
        float cur = scale();
        float target = Math.max(density * 0.1f, Math.min(density * 8f, cur * f));
        matrix.postScale(target / cur, target / cur, fx, fy);
        invalidate();
        if (listener != null) listener.onZoomChanged(zoom());
    }

    public void setZoom(float z) { zoomBy(density * z / scale(), getWidth() / 2f, getHeight() / 2f); }

    public static RectF contentBounds(List<Model.Item> items) {
        RectF b = null;
        for (Model.Item it : items) {
            if (b == null) b = new RectF(it.bounds());
            else b.union(it.bounds());
        }
        return b;
    }

    /** Enquadra todo o conteúdo na tela (sem passar de 100%). */
    public void fitContent() {
        if (getWidth() == 0) { needsFit = true; return; }
        needsFit = false;
        RectF b = page == null ? null : contentBounds(page.items);
        matrix.reset();
        if (b == null) {
            matrix.postScale(density, density);
            matrix.postTranslate(getWidth() / 2f, getHeight() / 3f);
        } else {
            float m = 40 * density;
            float s = Math.min((getWidth() - 2 * m) / Math.max(1, b.width()), (getHeight() - 2 * m - 80 * density) / Math.max(1, b.height()));
            s = Math.max(density * 0.1f, Math.min(density, s));
            matrix.postScale(s, s);
            matrix.postTranslate(getWidth() / 2f - b.centerX() * s, (getHeight() - 70 * density) / 2f - b.centerY() * s);
        }
        invalidate();
        if (listener != null) listener.onZoomChanged(zoom());
    }

    private float[] toBoard(float x, float y) {
        matrix.invert(inverse);
        float[] p = {x, y};
        inverse.mapPoints(p);
        return p;
    }

    public float[] viewCenter() { return toBoard(getWidth() / 2f, getHeight() / 2.4f); }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            matrix.postTranslate(scroller.getCurrX() - flingX, scroller.getCurrY() - flingY);
            flingX = scroller.getCurrX();
            flingY = scroller.getCurrY();
            postInvalidateOnAnimation();
        }
    }

    // ================================================================ desenho
    @Override protected void onDraw(Canvas c) {
        c.drawColor(PAPER);
        if (page == null || renderer == null) return;
        if (needsFit) fitContent();
        float s = scale();
        if (showGrid) drawGrid(c, s);
        c.save();
        c.concat(matrix);
        renderer.drawAll(c, page.items, null);
        if (creating != null) renderer.draw(c, creating);
        if (penPreview != null) renderer.draw(c, penPreview);
        overlay.setStrokeWidth(1.5f / s);
        if (marquee != null) {
            handleFill.setColor(0x144C5FD5);
            c.drawRect(marquee, handleFill);
            overlay.setColor(0xFF4C5FD5);
            overlay.setPathEffect(null);
            c.drawRect(marquee, overlay);
        }
        if (!selection.isEmpty() && mode != M_RESIZE || (mode == M_RESIZE && !selection.isEmpty())) drawSelection(c, s);
        if (mode == M_ERASE && !Float.isNaN(eraserX)) {
            handleFill.setColor(0x22000000);
            c.drawCircle(eraserX, eraserY, 10 * density / s, handleFill);
        }
        c.restore();
    }

    private void drawGrid(Canvas c, float s) {
        float step = 20;
        while (step * s < 14) step *= 5;
        float[] a = toBoard(0, 0), b = toBoard(getWidth(), getHeight());
        float x0 = (float) Math.floor(a[0] / step) * step, y0 = (float) Math.floor(a[1] / step) * step;
        int nx = (int) ((b[0] - x0) / step) + 2, ny = (int) ((b[1] - y0) / step) + 2;
        if ((long) nx * ny > 20000) return;
        float[] pts = new float[nx * ny * 2];
        int k = 0;
        float[] v = new float[9];
        matrix.getValues(v);
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++) {
                pts[k++] = (x0 + i * step) * s + v[Matrix.MTRANS_X];
                pts[k++] = (y0 + j * step) * s + v[Matrix.MTRANS_Y];
            }
        grid.setColor(0xFFD3D6DD);
        grid.setStrokeWidth(Math.max(1.5f, 1.6f * density));
        grid.setStrokeCap(Paint.Cap.ROUND);
        c.drawPoints(pts, 0, k, grid);
    }

    private RectF selectionBounds() {
        if (selection.isEmpty()) return null;
        RectF b = new RectF(selection.get(0).bounds());
        for (Model.Item it : selection) b.union(it.bounds());
        return b;
    }

    private void drawSelection(Canvas c, float s) {
        overlay.setColor(0xFF4C5FD5);
        overlay.setStrokeWidth(1.5f / s);
        float hr = 6 * density / s;
        if (selection.size() == 1 && selection.get(0) instanceof BoardItem && ((BoardItem) selection.get(0)).isLinear()) {
            BoardItem b = (BoardItem) selection.get(0);
            drawHandle(c, b.x1, b.y1, hr);
            drawHandle(c, b.x2, b.y2, hr);
            return;
        }
        RectF r = new RectF(selectionBounds());
        float pad = 6 * density / s;
        r.inset(-pad, -pad);
        overlay.setPathEffect(selection.size() > 1 ? new DashPathEffect(new float[]{8 / s * density, 6 / s * density}, 0) : null);
        c.drawRect(r, overlay);
        overlay.setPathEffect(null);
        if (selection.size() == 1) {
            drawHandle(c, r.left, r.top, hr);
            drawHandle(c, r.right, r.top, hr);
            drawHandle(c, r.right, r.bottom, hr);
            drawHandle(c, r.left, r.bottom, hr);
        }
    }

    private void drawHandle(Canvas c, float x, float y, float r) {
        handleFill.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, r, handleFill);
        c.drawCircle(x, y, r, overlay);
    }

    /** Posições das alças em coordenadas do quadro: {x0,y0,x1,y1,...}. */
    private float[] handles() {
        if (selection.size() != 1) return new float[0];
        Model.Item it = selection.get(0);
        if (it instanceof BoardItem && ((BoardItem) it).isLinear()) {
            BoardItem b = (BoardItem) it;
            return new float[]{b.x1, b.y1, b.x2, b.y2};
        }
        RectF r = new RectF(it.bounds());
        float pad = 6 * density / scale();
        r.inset(-pad, -pad);
        return new float[]{r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom};
    }

    // ================================================================ toque
    private static boolean penButton(MotionEvent e) {
        int tt = e.getToolType(0);
        if (tt == MotionEvent.TOOL_TYPE_ERASER) return true;
        if (tt != MotionEvent.TOOL_TYPE_STYLUS) return false;
        return (e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY
                | MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY)) != 0;
    }

    private void setTempEraser(boolean on) {
        if (tempEraser == on) return;
        tempEraser = on;
        if (listener != null) listener.onTemporaryEraser(on);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (page == null) return false;
        MotionEvent e = ev;
        boolean samsung = false;
        int raw = ev.getActionMasked();
        if (raw == SPEN_DOWN || raw == SPEN_UP || raw == SPEN_MOVE) {
            e = MotionEvent.obtain(ev);
            e.setAction(raw == SPEN_DOWN ? MotionEvent.ACTION_DOWN : raw == SPEN_UP ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE);
            samsung = true;
        }
        try {
            return handle(e, samsung || penButton(e));
        } finally {
            if (e != ev) e.recycle();
        }
    }

    private boolean handle(MotionEvent e, boolean button) {
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                scroller.forceFinished(true);
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                float[] p = toBoard(e.getX(), e.getY());
                startBX = p[0];
                startBY = p[1];
                int t = tool;
                if (button) { t = T_ERASER; setTempEraser(true); }
                start(t, p, e);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                cancel();
                mode = M_MULTI;
                float[] f = focus(e, -1);
                lastX = f[0];
                lastY = f[1];
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mode == M_MULTI) {
                    float[] fc = focus(e, -1);
                    matrix.postTranslate(fc[0] - lastX, fc[1] - lastY);
                    lastX = fc[0];
                    lastY = fc[1];
                    invalidate();
                    return true;
                }
                if (mode == M_PAN) {
                    if (velocity != null) velocity.addMovement(e);
                    matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX();
                    lastY = e.getY();
                    invalidate();
                    return true;
                }
                if (button && mode != M_ERASE && mode != M_NONE) {
                    finish(e);
                    setTempEraser(true);
                    start(T_ERASER, toBoard(e.getX(), e.getY()), e);
                } else if (!button && tempEraser && mode == M_ERASE) {
                    finish(e);
                    setTempEraser(false);
                    mode = M_NONE;
                    return true;
                }
                move(e);
                return true;
            }
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
                    flingX = flingY = 0;
                    scroller.fling(0, 0, (int) velocity.getXVelocity(), (int) velocity.getYVelocity(),
                            -1000000, 1000000, -1000000, 1000000);
                    postInvalidateOnAnimation();
                } else if (mode != M_MULTI) finish(e);
                mode = M_NONE;
                setTempEraser(false);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                mode = M_NONE;
                setTempEraser(false);
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

    private float tol() { return 12 * density / scale(); }

    private Model.Item hitTop(float x, float y) {
        for (int i = page.items.size() - 1; i >= 0; i--) {
            Model.Item it = page.items.get(i);
            if (it.hitTest(x, y, tol())) return it;
        }
        return null;
    }

    private void start(int t, float[] p, MotionEvent e) {
        switch (t) {
            case T_HAND:
                mode = M_PAN;
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(e);
                break;
            case T_SELECT: {
                float[] h = handles();
                float ht = 18 * density / scale();
                for (int i = 0; i < h.length / 2; i++) {
                    if (Math.hypot(p[0] - h[i * 2], p[1] - h[i * 2 + 1]) <= ht) {
                        beginTransform(M_RESIZE);
                        handle = i;
                        return;
                    }
                }
                Model.Item hit = hitTop(p[0], p[1]);
                if (hit != null) {
                    if (!selection.contains(hit)) {
                        selection.clear();
                        selection.add(hit);
                        selChanged();
                    }
                    beginTransform(M_MOVE);
                    lastTapItem = hit;
                    return;
                }
                RectF sb = selectionBounds();
                if (sb != null && selection.size() > 1 && sb.contains(p[0], p[1])) { beginTransform(M_MOVE); return; }
                clearSelection();
                mode = M_MARQUEE;
                marquee = new RectF(p[0], p[1], p[0], p[1]);
                break;
            }
            case T_RECT: case T_DIAMOND: case T_ELLIPSE: case T_ARROW: case T_LINE: {
                mode = M_CREATE;
                int type = t == T_RECT ? BoardItem.RECT : t == T_DIAMOND ? BoardItem.DIAMOND : t == T_ELLIPSE ? BoardItem.ELLIPSE
                        : t == T_ARROW ? BoardItem.ARROW : BoardItem.LINE;
                creating = new BoardItem(type, p[0], p[1], p[0], p[1], style.copy(), "", BoardItem.newSeed());
                break;
            }
            case T_PEN:
                mode = M_PEN;
                bufLen = 0;
                addPoint(p[0], p[1], pressure(e));
                updatePen();
                break;
            case T_TEXT:
                mode = M_TAP;
                break;
            case T_ERASER:
                mode = M_ERASE;
                snapshot = new ArrayList<>(page.items);
                erased = false;
                eraseAt(p[0], p[1]);
                break;
        }
        invalidate();
    }

    private void beginTransform(int m) {
        mode = m;
        moved = false;
        snapshot = new ArrayList<>(page.items);
        origSel = new ArrayList<>(selection);
        origIdx = new ArrayList<>();
        for (Model.Item it : selection) origIdx.add(page.items.indexOf(it));
        origBounds = selectionBounds();
    }

    private void move(MotionEvent e) {
        float[] p = toBoard(e.getX(), e.getY());
        switch (mode) {
            case M_CREATE: {
                float x2 = p[0], y2 = p[1];
                creating = creating.withPoints(creating.x1, creating.y1, x2, y2);
                break;
            }
            case M_PEN:
                for (int h = 0; h < e.getHistorySize(); h++) {
                    float[] q = toBoard(e.getHistoricalX(h), e.getHistoricalY(h));
                    addPoint(q[0], q[1], pressure(e));
                }
                addPoint(p[0], p[1], pressure(e));
                updatePen();
                break;
            case M_MARQUEE:
                marquee = new RectF(Math.min(startBX, p[0]), Math.min(startBY, p[1]), Math.max(startBX, p[0]), Math.max(startBY, p[1]));
                break;
            case M_MOVE: {
                if (!moved && Math.hypot(e.getX() - downX, e.getY() - downY) < 6 * density) return;
                moved = true;
                float dx = p[0] - startBX, dy = p[1] - startBY;
                applyTransform(it -> it.translated(dx, dy));
                break;
            }
            case M_RESIZE:
                moved = true;
                resizeTo(p[0], p[1]);
                break;
            case M_ERASE:
                for (int h = 0; h < e.getHistorySize(); h++) {
                    float[] q = toBoard(e.getHistoricalX(h), e.getHistoricalY(h));
                    eraseAt(q[0], q[1]);
                }
                eraseAt(p[0], p[1]);
                break;
            default:
                return;
        }
        invalidate();
    }

    interface Fn { Model.Item apply(Model.Item it); }

    private void applyTransform(Fn fn) {
        List<Model.Item> ns = new ArrayList<>();
        for (int i = 0; i < origSel.size(); i++) {
            Model.Item n = fn.apply(origSel.get(i));
            int idx = origIdx.get(i);
            if (idx >= 0 && idx < page.items.size()) page.items.set(idx, n);
            ns.add(n);
        }
        selection.clear();
        selection.addAll(ns);
    }

    private void resizeTo(float px, float py) {
        Model.Item o = origSel.get(0);
        if (o instanceof BoardItem && ((BoardItem) o).isLinear()) {
            BoardItem b = (BoardItem) o;
            final BoardItem n = handle == 0 ? b.withPoints(px, py, b.x2, b.y2) : b.withPoints(b.x1, b.y1, px, py);
            applyTransform(it -> n);
            return;
        }
        // canto oposto fica fixo
        float[] corners = {origBounds.left, origBounds.top, origBounds.right, origBounds.top,
                origBounds.right, origBounds.bottom, origBounds.left, origBounds.bottom};
        int opp = (handle + 2) % 4;
        final float fx = corners[opp * 2], fy = corners[opp * 2 + 1];
        if (o instanceof BoardItem && ((BoardItem) o).isBox()) {
            BoardItem b = (BoardItem) o;
            RectF r = b.rect();
            float[] rc = {r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom};
            float ox = rc[opp * 2], oy = rc[opp * 2 + 1];
            final BoardItem n = b.withPoints(ox, oy, px, py);
            applyTransform(it -> n);
            return;
        }
        float d0 = (float) Math.hypot(corners[handle * 2] - fx, corners[handle * 2 + 1] - fy);
        float d1 = (float) Math.hypot(px - fx, py - fy);
        final float s = Math.max(0.05f, d1 / Math.max(1, d0));
        applyTransform(it -> it.scaled(s, fx, fy));
    }

    private void finish(MotionEvent e) {
        switch (mode) {
            case M_CREATE: {
                BoardItem b = creating;
                creating = null;
                float w = Math.abs(b.x2 - b.x1), h = Math.abs(b.y2 - b.y1);
                if (Math.max(w, h) * scale() < 8 * density) {
                    // toque simples: cria uma forma de tamanho padrão
                    if (b.isLinear()) b = b.withPoints(b.x1, b.y1, b.x1 + 160, b.y1);
                    else b = b.withPoints(b.x1 - 80, b.y1 - 50, b.x1 + 80, b.y1 + 50);
                } else if (b.isBox()) {
                    RectF r = b.rect();
                    b = b.withPoints(r.left, r.top, r.right, r.bottom);
                }
                add(b);
                selection.clear();
                selection.add(b);
                if (!lockTool) {
                    tool = T_SELECT;
                    if (listener != null) listener.onToolChanged(T_SELECT);
                }
                selChanged();
                break;
            }
            case M_PEN:
                if (bufLen >= 3) {
                    float[] pts = new float[bufLen];
                    System.arraycopy(buf, 0, pts, 0, bufLen);
                    add(new Model.StrokeItem(Model.StrokeItem.PEN, style.stroke, style.width * 1.4f, pts));
                }
                penPreview = null;
                break;
            case M_MARQUEE: {
                selection.clear();
                if (marquee != null && marquee.width() > 0 && marquee.height() > 0)
                    for (Model.Item it : page.items) if (marquee.contains(it.bounds())) selection.add(it);
                marquee = null;
                selChanged();
                break;
            }
            case M_MOVE:
            case M_RESIZE:
                if (moved) {
                    pushUndo(snapshot);
                    changed();
                    selChanged();
                } else if (mode == M_MOVE) {
                    long now = System.currentTimeMillis();
                    if (lastTapItem != null && selection.size() == 1 && selection.get(0) == lastTapItem
                            && now - lastTapTime < 350 && lastTapItem instanceof BoardItem && listener != null) {
                        BoardItem b = (BoardItem) lastTapItem;
                        if (b.isBox() || b.type == BoardItem.TEXT) listener.onTextRequest(b.x1, b.y1, b);
                        lastTapTime = 0;
                    } else lastTapTime = now;
                }
                origSel = null;
                break;
            case M_TAP:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < 10 * density && listener != null) {
                    float[] p = toBoard(e.getX(), e.getY());
                    Model.Item hit = hitTop(p[0], p[1]);
                    if (hit instanceof BoardItem && (((BoardItem) hit).type == BoardItem.TEXT || ((BoardItem) hit).isBox()))
                        listener.onTextRequest(p[0], p[1], (BoardItem) hit);
                    else listener.onTextRequest(p[0], p[1] - style.fontSize * 0.6f, null);
                }
                break;
            case M_ERASE:
                if (erased) {
                    pushUndo(snapshot);
                    changed();
                }
                eraserX = Float.NaN;
                break;
        }
    }

    private void cancel() {
        if (mode == M_MOVE || mode == M_RESIZE) {
            if (moved) { page.items = snapshot; selection.clear(); selChanged(); }
        } else if (mode == M_ERASE && erased) {
            pushUndo(snapshot);
            changed();
        }
        creating = null;
        penPreview = null;
        marquee = null;
        bufLen = 0;
        eraserX = Float.NaN;
        invalidate();
    }

    private float pressure(MotionEvent e) {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return 1f;
        return Math.max(0.05f, Math.min(1f, e.getPressure()));
    }

    private void addPoint(float x, float y, float pr) {
        if (bufLen >= 3) {
            float dx = x - buf[bufLen - 3], dy = y - buf[bufLen - 2];
            if (dx * dx + dy * dy < 0.6f / scale() * density) return;
        }
        if (bufLen + 3 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
        buf[bufLen++] = x;
        buf[bufLen++] = y;
        buf[bufLen++] = pr;
    }

    private void updatePen() {
        penPreview = new Model.StrokeItem(Model.StrokeItem.PEN, style.stroke, style.width * 1.4f, java.util.Arrays.copyOf(buf, bufLen));
    }

    private void eraseAt(float x, float y) {
        eraserX = x;
        eraserY = y;
        float r = 10 * density / scale();
        for (int i = page.items.size() - 1; i >= 0; i--) {
            if (page.items.get(i).hitTest(x, y, r)) {
                selection.remove(page.items.get(i));
                page.items.remove(i);
                erased = true;
            }
        }
    }

    // ================================================================ edição
    private void add(Model.Item it) {
        pushUndo(new ArrayList<>(page.items));
        page.items.add(it);
        changed();
    }

    public void addAndSelect(Model.Item it) {
        add(it);
        tool = T_SELECT;
        if (listener != null) listener.onToolChanged(T_SELECT);
        selection.clear();
        selection.add(it);
        selChanged();
    }

    public void replace(Model.Item old, Model.Item n) {
        int idx = page.items.indexOf(old);
        pushUndo(new ArrayList<>(page.items));
        if (idx < 0) { if (n != null) page.items.add(n); }
        else if (n == null) page.items.remove(idx);
        else page.items.set(idx, n);
        selection.remove(old);
        if (n != null) selection.add(n);
        changed();
        selChanged();
    }

    public List<Model.Item> getSelection() { return new ArrayList<>(selection); }

    public void selectAll() {
        tool = T_SELECT;
        if (listener != null) listener.onToolChanged(T_SELECT);
        selection.clear();
        selection.addAll(page.items);
        selChanged();
    }

    public void clearSelection() {
        if (selection.isEmpty()) return;
        selection.clear();
        selChanged();
    }

    /** Aplica um estilo a todos os itens selecionados (os traços livres recebem só a cor). */
    public void applyStyle(BoardItem.Style s) {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        List<Model.Item> ns = new ArrayList<>();
        for (Model.Item it : selection) {
            Model.Item n;
            if (it instanceof BoardItem) n = ((BoardItem) it).withStyle(s.copy());
            else if (it instanceof Model.StrokeItem) n = it.recolored(s.stroke);
            else n = it;
            int idx = page.items.indexOf(it);
            if (idx >= 0) page.items.set(idx, n);
            ns.add(n);
        }
        selection.clear();
        selection.addAll(ns);
        changed();
    }

    public void deleteSelection() {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        page.items.removeAll(selection);
        selection.clear();
        changed();
        selChanged();
    }

    public void duplicateSelection() {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        List<Model.Item> ns = new ArrayList<>();
        for (Model.Item it : selection) {
            Model.Item n = it.translated(24, 24);
            if (n instanceof BoardItem) n = new BoardItem(((BoardItem) n).type, ((BoardItem) n).x1, ((BoardItem) n).y1,
                    ((BoardItem) n).x2, ((BoardItem) n).y2, ((BoardItem) n).style, ((BoardItem) n).label, BoardItem.newSeed());
            page.items.add(n);
            ns.add(n);
        }
        selection.clear();
        selection.addAll(ns);
        changed();
        selChanged();
    }

    public void bringToFront() {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        page.items.removeAll(selection);
        page.items.addAll(selection);
        changed();
    }

    public void sendToBack() {
        if (selection.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        page.items.removeAll(selection);
        page.items.addAll(0, selection);
        changed();
    }

    public void clearAll() {
        if (page.items.isEmpty()) return;
        pushUndo(new ArrayList<>(page.items));
        page.items = new ArrayList<>();
        selection.clear();
        changed();
        selChanged();
    }

    private void pushUndo(List<Model.Item> snap) {
        undo.push(snap);
        while (undo.size() > 150) undo.removeLast();
        redo.clear();
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.push(new ArrayList<>(page.items));
        page.items = undo.pop();
        selection.clear();
        changed();
        selChanged();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.push(new ArrayList<>(page.items));
        page.items = redo.pop();
        selection.clear();
        changed();
        selChanged();
    }

    private void selChanged() {
        invalidate();
        if (listener != null) listener.onSelectionChanged(selection.size());
    }

    private void changed() {
        invalidate();
        if (listener != null) {
            listener.onChanged();
            listener.onHistoryChanged();
        }
    }

    // ================================================================ exportação
    /** Renderiza todo o conteúdo do quadro num bitmap (fundo branco, margem). */
    public static Bitmap render(List<Model.Item> items, BoardRenderer r, int maxDim, boolean grid) {
        RectF b = contentBounds(items);
        if (b == null) b = new RectF(0, 0, 400, 300);
        b.inset(-40, -40);
        float s = Math.min(maxDim / b.width(), maxDim / b.height());
        s = Math.min(s, 3f);
        int w = Math.max(1, Math.round(b.width() * s)), h = Math.max(1, Math.round(b.height() * s));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(PAPER);
        c.scale(s, s);
        c.translate(-b.left, -b.top);
        if (grid) {
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(0xFFDDE0E6);
            for (float x = (float) Math.floor(b.left / 20) * 20; x < b.right; x += 20)
                for (float y = (float) Math.floor(b.top / 20) * 20; y < b.bottom; y += 20) c.drawCircle(x, y, 1.2f, g);
        }
        r.drawAll(c, items, null);
        return out;
    }
}
