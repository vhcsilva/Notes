package com.cadernolivre.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor de um caderno, com rolagem contínua entre páginas. */
public class NotebookActivity extends Activity implements CanvasView.Listener {
    public static final String EXTRA_ID = "id";
    private static final int REQ_PDF = 1, REQ_IMAGE = 2, REQ_EXPORT_PDF = 3, REQ_EXPORT_PNG = 4, REQ_AUDIO_PERM = 5;
    private static final String[] TOOL_ICONS = {"pen", "highlighter", "eraser", "lasso", "text", "shapes"};
    private static final String[] TOOL_NAMES = {"Caneta", "Marca-texto", "Borracha", "Laço", "Texto", "Formas"};

    private Model.Notebook nb;
    private File dir;
    private CanvasView cv;
    private PdfHelper pdf;
    private PageRenderer.ImageCache images;
    private PageRenderer renderer;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService pdfExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean dirty, changedThisSession;
    private int current;

    private TextView titleView, subtitleView, pageChip, recChip;
    private ImageView undoBtn, redoBtn, colorDot;
    private final ImageView[] toolBtns = new ImageView[6];
    private LinearLayout selectionBar;
    private View convertBtn;
    private PopupWindow toolPopup;

    private MediaRecorder recorder;
    private File recordingFile;
    private long recordStart;
    private MediaPlayer player;
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(60);

    private final Runnable autosave = this::saveAsync;
    private final Runnable hideChip = () -> pageChip.animate().alpha(0.55f).setDuration(400).start();
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (recorder == null) return;
            long s = (SystemClock.elapsedRealtime() - recordStart) / 1000;
            recChip.setText(String.format(Locale.US, "●  %02d:%02d", s / 60, s % 60));
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Th.init(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        try {
            nb = Storage.load(this, id);
        } catch (Exception e) {
            Ui.toast(this, "Não foi possível abrir o caderno: " + e.getMessage());
            finish();
            return;
        }
        dir = Storage.dir(this, id);
        pdf = new PdfHelper(dir);
        images = new PageRenderer.ImageCache();
        renderer = new PageRenderer(dir, images);
        prefs = getSharedPreferences("tools", MODE_PRIVATE);
        Th.apply(this, Th.surface);
        buildUi();
        loadToolPrefs();
        current = Math.max(0, Math.min(nb.lastPage, nb.pages.size() - 1));
        cv.setNotebook(nb, current);
        onVisiblePageChanged(current);
    }

    // ================================================================ interface
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Th.desk);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Barra superior
        LinearLayout top = new LinearLayout(this);
        top.setBackgroundColor(Th.surface);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        top.setElevation(Ui.dp(this, 2));
        top.addView(Ui.iconButton(this, "back", Th.on, v -> finish()));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        titles.setBackground(Ui.ripple(0, Ui.dp(this, 8)));
        titleView = Ui.text(this, nb.title, 16, Th.on, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView = Ui.text(this, "", 12, Th.on2, false);
        subtitleView.setSingleLine(true);
        titles.addView(titleView);
        titles.addView(subtitleView);
        titles.setOnClickListener(v -> Ui.prompt(this, "Renomear caderno", nb.title, s -> {
            if (!s.isEmpty()) { nb.title = s; titleView.setText(s); markDirty(); }
        }));
        top.addView(titles, Ui.weight());
        recChip = Ui.text(this, "●  00:00", 13, Color.WHITE, true);
        recChip.setBackground(Ui.ripple(0xFFE53935, Ui.dp(this, 14)));
        recChip.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        recChip.setVisibility(View.GONE);
        recChip.setOnClickListener(v -> stopRecording());
        top.addView(recChip);
        undoBtn = Ui.iconButton(this, "undo", Th.on, v -> cv.undo());
        redoBtn = Ui.iconButton(this, "redo", Th.on, v -> cv.redo());
        top.addView(undoBtn);
        top.addView(redoBtn);
        top.addView(Ui.iconButton(this, "pages", Th.on, v -> showPagesSheet()));
        top.addView(Ui.iconButton(this, "more", Th.on, v -> showMenu()));
        col.addView(top);

        // Área de desenho
        FrameLayout area = new FrameLayout(this);
        cv = new CanvasView(this);
        cv.setRenderer(renderer);
        cv.setListener(this);
        cv.setPdfSource((page, w) -> pdfExec.execute(() -> {
            final Bitmap bmp = pdf.render(page.pdf, page.pdfIndex, w);
            main.post(() -> cv.setPdfBitmap(page, bmp));
        }));
        area.addView(cv, new FrameLayout.LayoutParams(-1, -1));

        pageChip = Ui.text(this, "", 12, Color.WHITE, true);
        pageChip.setBackground(Ui.round(0xCC1B1C22, Ui.dp(this, 14)));
        pageChip.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        pageChip.setOnClickListener(v -> showPagesSheet());
        FrameLayout.LayoutParams pcl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pcl.setMargins(0, Ui.dp(this, 12), Ui.dp(this, 12), 0);
        area.addView(pageChip, pcl);

        // Barra de seleção
        selectionBar = new LinearLayout(this);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setBackground(Ui.round(Th.surface, Ui.dp(this, 26)));
        selectionBar.setElevation(Ui.dp(this, 8));
        selectionBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        selectionBar.addView(Ui.iconButton(this, "trash", Th.danger, v -> cv.deleteSelection()));
        selectionBar.addView(Ui.iconButton(this, "copy", Th.on, v -> cv.duplicateSelection()));
        selectionBar.addView(Ui.iconButton(this, "palette", Th.on, v -> colorSheet("Cor da seleção", Ui.INK_COLORS, 0xFF000000, cv::recolorSelection)));
        selectionBar.addView(Ui.iconButton(this, "minus", Th.on, v -> cv.scaleSelection(0.9f)));
        selectionBar.addView(Ui.iconButton(this, "plus", Th.on, v -> cv.scaleSelection(1.1f)));
        convertBtn = Ui.iconButton(this, "convert", Th.primary, v -> convertToText());
        selectionBar.addView(convertBtn);
        selectionBar.addView(Ui.iconButton(this, "close", Th.on2, v -> cv.clearSelection()));
        selectionBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = Ui.dp(this, 88);
        area.addView(selectionBar, sl);

        // Dock de ferramentas flutuante
        HorizontalScrollView dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(Ui.round(Th.surface, Ui.dp(this, 30)));
        dock.setElevation(Ui.dp(this, 10));
        dock.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        for (int i = 0; i < TOOL_ICONS.length; i++) {
            final int t = i;
            ImageView b = new ImageView(this);
            int p = Ui.dp(this, 11);
            b.setPadding(p, p, p, p);
            b.setContentDescription(TOOL_NAMES[i]);
            b.setOnClickListener(v -> {
                if (cv.tool == t) showToolPopup(v);
                else selectTool(t);
            });
            b.setOnLongClickListener(v -> { selectTool(t); showToolPopup(v); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
            lp.setMargins(Ui.dp(this, 1), 0, Ui.dp(this, 1), 0);
            dock.addView(b, lp);
            toolBtns[i] = b;
        }
        ImageView img = Ui.iconButton(this, "image", Th.on, v -> pickImage());
        img.setContentDescription("Imagem");
        dock.addView(img, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        View sep = new View(this);
        sep.setBackgroundColor(Th.outline);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 28));
        spl.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        dock.addView(sep, spl);
        colorDot = new ImageView(this);
        colorDot.setContentDescription("Cor e espessura");
        colorDot.setOnClickListener(v -> showToolPopup(toolBtns[Math.min(cv.tool, 5)]));
        LinearLayout.LayoutParams cdl = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        cdl.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 6), 0);
        dock.addView(colorDot, cdl);
        dockScroll.addView(dock);
        dockScroll.setClipToPadding(false);
        dockScroll.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = Ui.dp(this, 6);
        area.addView(dockScroll, dl);

        col.addView(area, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void showMenu() {
        Model.Page p = nb.pages.get(current);
        Ui.Sheet s = new Ui.Sheet(this, nb.title);
        s.item("plus", "Adicionar página", "Logo após a página " + (current + 1), false, () -> addPage(current + 1, p.blankLike()));
        s.item("paper", "Papel e modelo", p.pdf != null ? "Página de PDF" : Model.templateName(p.template), false, this::templateSheet);
        s.item("import", "Importar PDF", "Adiciona as páginas ao final", false, () -> openDoc("application/pdf", REQ_PDF));
        s.item("image", "Inserir imagem", this::pickImage);
        s.item(recorder == null ? "mic" : "stop", recorder == null ? "Gravar áudio" : "Parar gravação", this::toggleRecording);
        s.item("play", "Gravações", this::recordingsSheet);
        s.item("export", "Exportar como PDF", () -> createDoc("application/pdf", safeName(nb.title) + ".pdf", REQ_EXPORT_PDF));
        s.item("share", "Compartilhar como PDF", this::sharePdf);
        s.item("image", "Salvar página como imagem", () -> createDoc("image/png", safeName(nb.title) + "-p" + (current + 1) + ".png", REQ_EXPORT_PNG));
        s.item("hand", "Modo só caneta", cv.stylusOnly ? "Ativado: o dedo só rola a página" : "Desativado: dedo e caneta escrevem", false, () -> {
            cv.stylusOnly = !cv.stylusOnly;
            prefs.edit().putBoolean("stylusOnly", cv.stylusOnly).apply();
            Ui.toast(this, cv.stylusOnly ? "Modo só caneta ativado." : "Modo só caneta desativado.");
        });
        s.item("fit", "Ajustar à tela", cv::fit);
        s.item("eraser", "Limpar página", null, false, () -> cv.clearPage(nb.pages.get(current)));
        s.item("trash", "Excluir página", null, true, () -> confirmDeletePage(current));
        s.show();
    }

    // ================================================================ ferramentas
    private void loadToolPrefs() {
        cv.penColor = prefs.getInt("penColor", 0xFF1A237E);
        cv.penWidth = prefs.getFloat("penWidth", 4f);
        cv.hlColor = prefs.getInt("hlColor", 0xFFFFEB3B);
        cv.hlWidth = prefs.getFloat("hlWidth", 26f);
        cv.eraserSize = prefs.getFloat("eraserSize", 18f);
        cv.partialEraser = prefs.getBoolean("partialEraser", true);
        cv.textColor = prefs.getInt("textColor", 0xFF000000);
        cv.textSize = prefs.getFloat("textSize", 34f);
        cv.shapeType = prefs.getInt("shapeType", CanvasView.SHAPE_RECT);
        cv.stylusOnly = prefs.getBoolean("stylusOnly", false);
        selectTool(prefs.getInt("tool", CanvasView.TOOL_PEN));
    }

    private void saveToolPrefs() {
        prefs.edit().putInt("penColor", cv.penColor).putFloat("penWidth", cv.penWidth)
                .putInt("hlColor", cv.hlColor).putFloat("hlWidth", cv.hlWidth)
                .putFloat("eraserSize", cv.eraserSize).putBoolean("partialEraser", cv.partialEraser)
                .putInt("textColor", cv.textColor).putFloat("textSize", cv.textSize)
                .putInt("shapeType", cv.shapeType).putInt("tool", cv.tool).apply();
    }

    private void selectTool(int t) {
        cv.setTool(t);
        for (int i = 0; i < toolBtns.length; i++) {
            boolean sel = i == t;
            toolBtns[i].setImageDrawable(Icons.of(TOOL_ICONS[i], sel ? Th.primary : Th.on));
            toolBtns[i].setBackground(sel ? Ui.ripple(Th.primarySoft, Ui.dp(this, 23)) : Ui.ripple(0, Ui.dp(this, 23)));
        }
        refreshColorDot();
        saveToolPrefs();
    }

    private int currentColor() {
        switch (cv.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: return cv.hlColor;
            case CanvasView.TOOL_TEXT: return cv.textColor;
            default: return cv.penColor;
        }
    }

    private void setCurrentColor(int c) {
        if (cv.tool == CanvasView.TOOL_HIGHLIGHTER) cv.hlColor = c;
        else if (cv.tool == CanvasView.TOOL_TEXT) cv.textColor = c;
        else cv.penColor = c;
        refreshColorDot();
        saveToolPrefs();
    }

    private void refreshColorDot() {
        boolean noColor = cv.tool == CanvasView.TOOL_ERASER || cv.tool == CanvasView.TOOL_LASSO;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(noColor ? Th.surface2 : currentColor());
        g.setStroke(Ui.dp(this, 2), Th.outline);
        colorDot.setImageDrawable(noColor ? Icons.of("tune", Th.on2) : null);
        int p = Ui.dp(this, 7);
        colorDot.setPadding(p, p, p, p);
        colorDot.setBackground(g);
    }

    /** Painel flutuante com cor, espessura e opções da ferramenta atual. */
    private void showToolPopup(final View anchor) {
        if (toolPopup != null) toolPopup.dismiss();
        final int t = cv.tool;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Th.surface, Ui.dp(this, 20)));
        box.setElevation(Ui.dp(this, 12));
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(this, TOOL_NAMES[t], 15, Th.on, true));

        if (t == CanvasView.TOOL_SHAPE) {
            LinearLayout chips = new LinearLayout(this);
            chips.setPadding(0, Ui.dp(this, 8), 0, 0);
            String[] sh = {"Linha", "Retângulo", "Elipse", "Seta"};
            for (int i = 0; i < sh.length; i++) {
                final int idx = i;
                chips.addView(Ui.chip(this, sh[i], cv.shapeType == i, v -> {
                    cv.shapeType = idx;
                    saveToolPrefs();
                    showToolPopup(anchor);
                }));
            }
            box.addView(chips);
        }
        if (t == CanvasView.TOOL_ERASER) {
            LinearLayout chips = new LinearLayout(this);
            chips.setPadding(0, Ui.dp(this, 8), 0, 0);
            chips.addView(Ui.chip(this, "Parcial", cv.partialEraser, v -> { cv.partialEraser = true; saveToolPrefs(); showToolPopup(anchor); }));
            chips.addView(Ui.chip(this, "Traço inteiro", !cv.partialEraser, v -> { cv.partialEraser = false; saveToolPrefs(); showToolPopup(anchor); }));
            box.addView(chips);
        }
        if (t == CanvasView.TOOL_LASSO) {
            TextView tip = Ui.text(this, "Circule itens para mover, redimensionar, recolorir, duplicar, apagar ou converter escrita em texto.", 13, Th.on2, false);
            tip.setPadding(0, Ui.dp(this, 6), 0, 0);
            tip.setMaxWidth(Ui.dp(this, 280));
            box.addView(tip);
        }
        if (t != CanvasView.TOOL_LASSO) {
            final float min, max;
            final float[] val = new float[1];
            final String name;
            if (t == CanvasView.TOOL_HIGHLIGHTER) { min = 8; max = 70; val[0] = cv.hlWidth; name = "Espessura"; }
            else if (t == CanvasView.TOOL_ERASER) { min = 4; max = 80; val[0] = cv.eraserSize; name = "Tamanho"; }
            else if (t == CanvasView.TOOL_TEXT) { min = 14; max = 120; val[0] = cv.textSize; name = "Tamanho do texto"; }
            else { min = 1; max = 30; val[0] = cv.penWidth; name = "Espessura"; }
            final TextView lbl = Ui.text(this, name + ": " + Math.round(val[0]), 13, Th.on2, false);
            lbl.setPadding(0, Ui.dp(this, 12), 0, 0);
            box.addView(lbl);
            SeekBar sb = new SeekBar(this);
            sb.setMax(100);
            sb.setProgress(Math.round((val[0] - min) / (max - min) * 100));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                    float x = min + (max - min) * v / 100f;
                    lbl.setText(name + ": " + Math.round(x));
                    if (t == CanvasView.TOOL_HIGHLIGHTER) cv.hlWidth = x;
                    else if (t == CanvasView.TOOL_ERASER) cv.eraserSize = x;
                    else if (t == CanvasView.TOOL_TEXT) cv.textSize = x;
                    else cv.penWidth = x;
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { saveToolPrefs(); }
            });
            box.addView(sb, new LinearLayout.LayoutParams(Ui.dp(this, 280), -2));
        }
        if (t != CanvasView.TOOL_ERASER && t != CanvasView.TOOL_LASSO) {
            View grid = Ui.colorGrid(this, t == CanvasView.TOOL_HIGHLIGHTER ? Ui.HL_COLORS : Ui.INK_COLORS, currentColor(), c -> {
                setCurrentColor(c);
                if (toolPopup != null) toolPopup.dismiss();
            });
            grid.setPadding(0, Ui.dp(this, 8), 0, 0);
            box.addView(grid);
        }
        FrameLayout wrap = new FrameLayout(this);
        int m = Ui.dp(this, 14);
        wrap.setPadding(m, m, m, m);
        wrap.addView(box);
        toolPopup = new PopupWindow(wrap, -2, -2, true);
        toolPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        toolPopup.setOutsideTouchable(true);
        toolPopup.setAnimationStyle(android.R.style.Animation_Dialog);
        wrap.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int x = Math.max(0, Math.min(getWindow().getDecorView().getWidth() - wrap.getMeasuredWidth(),
                loc[0] + anchor.getWidth() / 2 - wrap.getMeasuredWidth() / 2));
        int y = Math.max(0, loc[1] - wrap.getMeasuredHeight());
        toolPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private void colorSheet(String title, int[] pal, int cur, final Ui.IntCallback cb) {
        final Ui.Sheet[] s = new Ui.Sheet[1];
        s[0] = new Ui.Sheet(this, title);
        View grid = Ui.colorGrid(this, pal, cur, c -> { cb.on(c); s[0].dismiss(); });
        grid.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 8));
        s[0].view(grid).show();
    }

    // ================================================================ páginas
    @Override public void onVisiblePageChanged(int i) {
        if (i < 0 || i >= nb.pages.size()) return;
        current = i;
        nb.lastPage = i;
        pageChip.setText((i + 1) + " / " + nb.pages.size());
        subtitleView.setText("Página " + (i + 1) + " de " + nb.pages.size());
        pageChip.animate().cancel();
        pageChip.setAlpha(1f);
        main.removeCallbacks(hideChip);
        main.postDelayed(hideChip, 1500);
    }

    private void pagesChanged() {
        markDirty();
        cv.relayout();
        thumbCache.evictAll();
        onVisiblePageChanged(Math.min(current, nb.pages.size() - 1));
    }

    private void addPage(int at, Model.Page p) {
        nb.pages.add(at, p);
        pagesChanged();
        cv.scrollToPage(at);
        onVisiblePageChanged(at);
    }

    private void confirmDeletePage(final int i) {
        Ui.confirm(this, "Excluir página " + (i + 1) + "?", "Esta ação não pode ser desfeita.", "Excluir", () -> {
            nb.pages.remove(i);
            if (nb.pages.isEmpty()) nb.pages.add(new Model.Page());
            int to = Math.min(i, nb.pages.size() - 1);
            current = to;
            pagesChanged();
            cv.scrollToPage(to);
            onVisiblePageChanged(to);
        });
    }

    private String thumbKey(Model.Page p) {
        return System.identityHashCode(p) + ":" + System.identityHashCode(p.items) + ":" + p.items.size() + ":" + p.template + p.paperColor;
    }

    private void showPagesSheet() {
        final Ui.Sheet s = new Ui.Sheet(this, nb.pages.size() + (nb.pages.size() == 1 ? " página" : " páginas"), false);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        TextView add = Ui.pill(this, "＋  Nova página", true, v -> {
            s.dismiss();
            addPage(nb.pages.size(), nb.pages.get(nb.pages.size() - 1).blankLike());
        });
        actions.addView(add);
        TextView hint = Ui.text(this, "Toque e segure para opções", 12, Th.on2, false);
        hint.setPadding(Ui.dp(this, 14), 0, 0, 0);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(hint, new LinearLayout.LayoutParams(-2, -1));
        s.view(actions);

        final GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 104));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 12));
        grid.setHorizontalSpacing(Ui.dp(this, 10));
        grid.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        grid.setClipToPadding(false);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return nb.pages.size(); }
            @Override public Object getItem(int i) { return nb.pages.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout cell;
                ImageView iv;
                TextView label;
                if (convert == null) {
                    cell = new LinearLayout(NotebookActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                    iv = new ImageView(NotebookActivity.this);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    int pd = Ui.dp(NotebookActivity.this, 3);
                    iv.setPadding(pd, pd, pd, pd);
                    cell.addView(iv, new LinearLayout.LayoutParams(-1, Ui.dp(NotebookActivity.this, 136)));
                    label = Ui.text(NotebookActivity.this, "", 12, Th.on2, true);
                    label.setPadding(0, Ui.dp(NotebookActivity.this, 4), 0, 0);
                    cell.addView(label);
                } else {
                    cell = (LinearLayout) convert;
                    iv = (ImageView) cell.getChildAt(0);
                    label = (TextView) cell.getChildAt(1);
                }
                final Model.Page p = nb.pages.get(pos);
                boolean cur = pos == current;
                label.setText(String.valueOf(pos + 1));
                label.setTextColor(cur ? Th.primary : Th.on2);
                iv.setBackground(cur ? Ui.stroke(Th.surface2, Th.primary, Ui.dp(NotebookActivity.this, 2), Ui.dp(NotebookActivity.this, 6))
                        : Ui.stroke(Th.surface2, Th.outline, Ui.dp(NotebookActivity.this, 1), Ui.dp(NotebookActivity.this, 6)));
                final String key = thumbKey(p);
                Bitmap bm = thumbCache.get(key);
                iv.setImageBitmap(bm);
                iv.setTag(key);
                if (bm == null) {
                    final ImageView target = iv;
                    pdfExec.execute(() -> {
                        final Bitmap b = new PageRenderer(dir, images).renderBitmap(p, 220, pdf);
                        main.post(() -> {
                            thumbCache.put(key, b);
                            if (key.equals(target.getTag())) target.setImageBitmap(b);
                        });
                    });
                }
                return cell;
            }
        });
        grid.setOnItemClickListener((a, v, pos, id) -> { s.dismiss(); cv.scrollToPage(pos); onVisiblePageChanged(pos); });
        grid.setOnItemLongClickListener((a, v, pos, id) -> { s.dismiss(); pageOptions(pos); return true; });
        grid.setSelection(Math.max(0, current - 2));
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        s.view(grid);
        grid.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        s.show();
    }

    private void pageOptions(final int i) {
        Ui.Sheet s = new Ui.Sheet(this, "Página " + (i + 1));
        s.item("chevron", "Ir para a página", () -> { cv.scrollToPage(i); onVisiblePageChanged(i); });
        s.item("copy", "Duplicar", () -> addPage(i + 1, nb.pages.get(i).copy()));
        s.item("plus", "Inserir página em branco depois", () -> addPage(i + 1, nb.pages.get(i).blankLike()));
        if (i > 0) s.item("undo", "Mover para cima", () -> {
            nb.pages.add(i - 1, nb.pages.remove(i));
            pagesChanged();
            cv.scrollToPage(i - 1);
        });
        if (i < nb.pages.size() - 1) s.item("redo", "Mover para baixo", () -> {
            nb.pages.add(i + 1, nb.pages.remove(i));
            pagesChanged();
            cv.scrollToPage(i + 1);
        });
        s.item("trash", "Excluir", null, true, () -> confirmDeletePage(i));
        s.show();
    }

    private void templateSheet() {
        final Model.Page p = nb.pages.get(current);
        if (p.pdf != null) {
            Ui.toast(this, "Páginas de PDF não têm modelo de papel. Adicione uma nova página para escolher um modelo.");
            return;
        }
        int pi = 0;
        for (int i = 0; i < Model.PAPER_COLORS.length; i++) if (Model.PAPER_COLORS[i] == p.paperColor) pi = i;
        final int[] sel = {Math.max(0, Arrays.asList(Model.TEMPLATES).indexOf(p.template)), pi};
        final Ui.Sheet s = new Ui.Sheet(this, "Papel");
        s.view(Pickers.paperPicker(this, sel));
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), 0);
        TextView all = Ui.pill(this, "Todas as páginas", false, v -> {
            for (Model.Page q : nb.pages) if (q.pdf == null) {
                q.template = Model.TEMPLATES[sel[0]];
                q.paperColor = Model.PAPER_COLORS[sel[1]];
            }
            s.dismiss();
            pagesChanged();
        });
        btns.addView(all);
        TextView one = Ui.pill(this, "Esta página", true, v -> {
            p.template = Model.TEMPLATES[sel[0]];
            p.paperColor = Model.PAPER_COLORS[sel[1]];
            s.dismiss();
            pagesChanged();
        });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
        ol.leftMargin = Ui.dp(this, 10);
        btns.addView(one, ol);
        s.view(btns).show();
    }

    // ================================================================ callbacks do canvas
    @Override public void onContentChanged() { markDirty(); }

    @Override public void onHistoryChanged() {
        if (undoBtn == null) return;
        undoBtn.setAlpha(cv.canUndo() ? 1f : 0.3f);
        redoBtn.setAlpha(cv.canRedo() ? 1f : 0.3f);
    }

    @Override public void onSelectionChanged(boolean has) {
        if (has) {
            boolean ink = false;
            for (Model.Item it : cv.getSelection())
                if (it instanceof Model.StrokeItem && ((Model.StrokeItem) it).kind == Model.StrokeItem.PEN) ink = true;
            convertBtn.setVisibility(ink ? View.VISIBLE : View.GONE);
            if (selectionBar.getVisibility() != View.VISIBLE) {
                selectionBar.setVisibility(View.VISIBLE);
                selectionBar.setAlpha(0f);
                selectionBar.setTranslationY(Ui.dp(this, 12));
                selectionBar.animate().alpha(1f).translationY(0).setDuration(160).start();
            }
        } else selectionBar.setVisibility(View.GONE);
    }

    @Override public void onTextRequest(final Model.Page page, final float x, final float y, final Model.TextItem existing) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);
        if (existing != null) et.setText(existing.text);
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 22);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.addView(et);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(existing == null ? "Novo texto" : "Editar texto").setView(f)
                .setPositiveButton("OK", (d, w) -> {
                    String s = et.getText().toString();
                    if (s.trim().isEmpty()) { if (existing != null) cv.replaceItem(page, existing, null); return; }
                    if (existing == null) cv.addItem(page, new Model.TextItem(s, x, y - cv.textSize * 0.7f, cv.textSize, cv.textColor));
                    else cv.replaceItem(page, existing, new Model.TextItem(s, existing.x, existing.y, existing.size, existing.color));
                })
                .setNegativeButton("Cancelar", null);
        if (existing != null) b.setNeutralButton("Excluir", (d, w) -> cv.replaceItem(page, existing, null));
        AlertDialog d = b.create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        et.requestFocus();
    }

    // ================================================================ escrita → texto
    private void convertToText() {
        final List<Model.StrokeItem> ink = new ArrayList<>();
        final List<Model.Item> keep = new ArrayList<>();
        for (Model.Item it : cv.getSelection()) {
            if (it instanceof Model.StrokeItem && ((Model.StrokeItem) it).kind == Model.StrokeItem.PEN) ink.add((Model.StrokeItem) it);
            else keep.add(it);
        }
        if (ink.isEmpty()) { Ui.toast(this, "Selecione escrita feita com a caneta."); return; }
        Ui.toast(this, "Reconhecendo a escrita…");
        Recognizer.recognize(ink, new Recognizer.Callback() {
            @Override public void onResult(String text) {
                RectF b = new RectF(ink.get(0).bounds());
                for (Model.StrokeItem s : ink) b.union(s.bounds());
                final RectF bounds = b;
                final int color = ink.get(0).color;
                final EditText et = new EditText(NotebookActivity.this);
                et.setText(text);
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                FrameLayout f = new FrameLayout(NotebookActivity.this);
                int p = Ui.dp(NotebookActivity.this, 22);
                f.setPadding(p, Ui.dp(NotebookActivity.this, 8), p, 0);
                f.addView(et);
                new AlertDialog.Builder(NotebookActivity.this).setTitle("Texto reconhecido").setView(f)
                        .setPositiveButton("Substituir escrita", (d, w) -> {
                            String s = et.getText().toString().trim();
                            if (s.isEmpty()) return;
                            int lines = s.split("\n").length;
                            float size = Math.max(18, Math.min(110, bounds.height() / lines * 0.75f));
                            List<Model.Item> rep = new ArrayList<>(keep);
                            rep.add(new Model.TextItem(s, bounds.left, bounds.top, size, color));
                            cv.replaceSelection(rep);
                        })
                        .setNeutralButton("Copiar", (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("texto", et.getText().toString()));
                            Ui.toast(NotebookActivity.this, "Texto copiado.");
                        })
                        .setNegativeButton("Cancelar", null).show();
            }

            @Override public void onError(String message) { Ui.toast(NotebookActivity.this, message); }
        });
    }

    // ================================================================ imagens, PDFs, exportação
    private void pickImage() { openDoc("image/*", REQ_IMAGE); }

    private void openDoc(String type, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        try { startActivityForResult(i, req); } catch (Exception e) { Ui.toast(this, "Nenhum seletor de arquivos disponível."); }
    }

    private void createDoc(String type, String name, int req) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        i.putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(i, req); } catch (Exception e) { Ui.toast(this, "Nenhum seletor de arquivos disponível."); }
    }

    static String safeName(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\[\\]]", "_").trim();
        return r.isEmpty() ? "caderno" : r;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final ContentResolver cr = getContentResolver();
        switch (req) {
            case REQ_IMAGE: insertImage(uri); break;
            case REQ_PDF: importPdf(uri); break;
            case REQ_EXPORT_PDF:
            case REQ_EXPORT_PNG: {
                Ui.toast(this, "Exportando…");
                final boolean isPdf = req == REQ_EXPORT_PDF;
                final Model.Page page = nb.pages.get(current);
                pdfExec.execute(() -> {
                    String msg;
                    try {
                        OutputStream os = cr.openOutputStream(uri);
                        try {
                            if (isPdf) Exporter.exportPdf(nb, new PageRenderer(dir, images), pdf, os);
                            else Exporter.exportPng(page, new PageRenderer(dir, images), pdf, os);
                        } finally { os.close(); }
                        msg = "Exportado com sucesso.";
                    } catch (Throwable e) { msg = "Falha ao exportar: " + e.getMessage(); }
                    final String m = msg;
                    main.post(() -> Ui.toast(this, m));
                });
                break;
            }
        }
    }

    private void sharePdf() {
        Ui.toast(this, "Preparando PDF…");
        pdfExec.execute(() -> {
            try {
                final File out = new File(ShareProvider.shareDir(this), safeName(nb.title) + ".pdf");
                OutputStream os = new FileOutputStream(out);
                try { Exporter.exportPdf(nb, new PageRenderer(dir, images), pdf, os); } finally { os.close(); }
                main.post(() -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("application/pdf");
                    s.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(out));
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Compartilhar PDF"));
                });
            } catch (Throwable e) {
                main.post(() -> Ui.toast(this, "Falha: " + e.getMessage()));
            }
        });
    }

    private void insertImage(final Uri uri) {
        bg.execute(() -> {
            try {
                final String name = "images/img_" + System.currentTimeMillis() + ".img";
                File f = new File(dir, name);
                InputStream in = getContentResolver().openInputStream(uri);
                Storage.copyToFile(in, f);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (o.outWidth <= 0) { f.delete(); throw new Exception("imagem inválida"); }
                final float ratio = o.outHeight / (float) o.outWidth;
                main.post(() -> {
                    float[] c = cv.visibleCenter();
                    Model.Page page = nb.pages.get((int) c[0]);
                    float w = 500, h = w * ratio;
                    if (h > 700) { h = 700; w = h / ratio; }
                    Model.ImageItem it = new Model.ImageItem(name, new RectF(c[1] - w / 2, c[2] - h / 2, c[1] + w / 2, c[2] + h / 2));
                    cv.addItem(page, it);
                    selectTool(CanvasView.TOOL_LASSO);
                    cv.selectOnly(page, it);
                });
            } catch (Throwable e) {
                main.post(() -> Ui.toast(this, "Não foi possível inserir a imagem: " + e.getMessage()));
            }
        });
    }

    private void importPdf(final Uri uri) {
        Ui.toast(this, "Importando PDF…");
        bg.execute(() -> {
            try {
                final String name = "doc_" + System.currentTimeMillis() + ".pdf";
                File f = new File(dir, name);
                Storage.copyToFile(getContentResolver().openInputStream(uri), f);
                final float[] ratios = PdfHelper.pageRatios(f);
                main.post(() -> {
                    int at = nb.pages.size();
                    for (int i = 0; i < ratios.length; i++) {
                        Model.Page p = new Model.Page();
                        p.pdf = name;
                        p.pdfIndex = i;
                        p.height = Model.PAGE_W * ratios[i];
                        nb.pages.add(p);
                    }
                    pagesChanged();
                    cv.scrollToPage(at);
                    Ui.toast(this, ratios.length + " páginas adicionadas.");
                });
            } catch (Throwable e) {
                main.post(() -> Ui.toast(this, "Não foi possível abrir o PDF (talvez protegido por senha)."));
            }
        });
    }

    // ================================================================ áudio
    private File audioDir() {
        File d = new File(dir, "audio");
        d.mkdirs();
        return d;
    }

    private void toggleRecording() {
        if (recorder != null) { stopRecording(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERM);
            return;
        }
        try {
            String stamp = new SimpleDateFormat("dd-MM-yyyy HH'h'mm", Locale.US).format(new Date());
            recordingFile = new File(audioDir(), "Gravação " + stamp + ".m4a");
            MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(96000);
            r.setAudioSamplingRate(44100);
            r.setOutputFile(recordingFile.getAbsolutePath());
            r.prepare();
            r.start();
            recorder = r;
            recordStart = SystemClock.elapsedRealtime();
            recChip.setVisibility(View.VISIBLE);
            main.post(recTick);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception e) {
            recorder = null;
            Ui.toast(this, "Não foi possível gravar: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception e) { if (recordingFile != null) recordingFile.delete(); }
        recorder.release();
        recorder = null;
        recChip.setVisibility(View.GONE);
        main.removeCallbacks(recTick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.toast(this, "Gravação salva neste caderno.");
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_AUDIO_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) toggleRecording();
        else if (req == REQ_AUDIO_PERM) Ui.toast(this, "Permissão de microfone negada.");
    }

    private void recordingsSheet() {
        final File[] files = audioDir().listFiles();
        if (files == null || files.length == 0) {
            Ui.toast(this, "Nenhuma gravação ainda. Use ⋮ → Gravar áudio.");
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        final Ui.Sheet s = new Ui.Sheet(this, "Gravações");
        for (final File f : files) {
            final MediaPlayer probe = new MediaPlayer();
            String dur = "";
            try {
                probe.setDataSource(f.getAbsolutePath());
                probe.prepare();
                int sec = probe.getDuration() / 1000;
                dur = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
            } catch (Exception ignored) {
            } finally { probe.release(); }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
            final ImageView play = Ui.iconButton(this, "play", Th.primary, null);
            play.setOnClickListener(v -> {
                if (player != null && f.getAbsolutePath().equals(v.getTag())) {
                    stopPlayback();
                    v.setTag(null);
                    play.setImageDrawable(Icons.of("play", Th.primary));
                } else {
                    play(f);
                    v.setTag(f.getAbsolutePath());
                    play.setImageDrawable(Icons.of("stop", Th.primary));
                }
            });
            row.addView(play);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(this, 10), 0, 0, 0);
            texts.addView(Ui.text(this, f.getName().replace(".m4a", ""), 14, Th.on, false));
            texts.addView(Ui.text(this, dur, 12, Th.on2, false));
            row.addView(texts, Ui.weight());
            row.addView(Ui.iconButton(this, "trash", Th.danger, v -> Ui.confirm(this, "Excluir gravação?", f.getName(), "Excluir", () -> {
                stopPlayback();
                f.delete();
                s.dismiss();
                recordingsSheet();
            })));
            s.view(row);
        }
        s.dialog.setOnDismissListener(x -> stopPlayback());
        s.show();
    }

    private void play(File f) {
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
        } catch (Exception e) {
            Ui.toast(this, "Não foi possível reproduzir.");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    // ================================================================ salvamento
    private void markDirty() {
        dirty = true;
        changedThisSession = true;
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 3000);
    }

    private void saveAsync() {
        if (!dirty) return;
        try {
            final String[] data = Storage.serialize(nb);
            dirty = false;
            bg.execute(() -> {
                try { Storage.write(this, nb.id, data); } catch (Exception e) {
                    main.post(() -> Ui.toast(this, "Erro ao salvar: " + e.getMessage()));
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "Erro ao salvar: " + e.getMessage());
        }
    }

    private void saveThumbnail() {
        if (nb.pages.isEmpty()) return;
        final Model.Page first = nb.pages.get(0).copy();
        bg.execute(() -> {
            try {
                Bitmap b = new PageRenderer(dir, images).renderBitmap(first, 360, pdf);
                OutputStream os = new FileOutputStream(new File(dir, "thumb.png"));
                try { b.compress(Bitmap.CompressFormat.PNG, 90, os); } finally { os.close(); }
                b.recycle();
            } catch (Throwable ignored) {}
        });
    }

    @Override protected void onPause() {
        super.onPause();
        if (nb == null) return;
        main.removeCallbacks(autosave);
        boolean wasDirty = dirty;
        saveAsync();
        if (wasDirty || !new File(dir, "thumb.png").exists()) saveThumbnail();
        if (changedThisSession && AutoBackup.folder(this) != null) {
            changedThisSession = false;
            final String id = nb.id, title = nb.title;
            final android.content.Context app = getApplicationContext();
            bg.execute(() -> {
                String err = AutoBackup.run(app, id, title);
                if (err != null) main.post(() -> Ui.toast(app, "Backup automático falhou: " + err));
            });
        }
        stopPlayback();
        if (toolPopup != null) toolPopup.dismiss();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        main.removeCallbacksAndMessages(null);
        pdfExec.execute(() -> { if (pdf != null) pdf.close(); });
        pdfExec.shutdown();
        bg.shutdown();
    }
}
