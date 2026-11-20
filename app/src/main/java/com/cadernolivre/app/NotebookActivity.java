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
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
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

/** Editor de um caderno. */
public class NotebookActivity extends Activity implements CanvasView.Listener {
    public static final String EXTRA_ID = "id";
    private static final int REQ_PDF = 1, REQ_IMAGE = 2, REQ_EXPORT_PDF = 3, REQ_EXPORT_PNG = 4, REQ_AUDIO_PERM = 5;

    private Model.Notebook nb;
    private File dir;
    private int pageIndex;
    private CanvasView cv;
    private PdfHelper pdf;
    private PageRenderer.ImageCache images;
    private PageRenderer renderer;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean dirty;

    private TextView titleView, pageLabel, undoBtn, redoBtn, recBtn;
    private final TextView[] toolBtns = new TextView[6];
    private LinearLayout swatchRow;
    private View selectionBar;

    private MediaRecorder recorder;
    private File recordingFile;
    private MediaPlayer player;

    private final Runnable autosave = this::saveAsync;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        getWindow().setStatusBarColor(Ui.PRIMARY);
        buildUi();
        loadToolPrefs();
        showPage(Math.max(0, Math.min(nb.lastPage, nb.pages.size() - 1)));
    }

    // ================================================================ interface
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFD9DEE4);

        // Barra superior
        LinearLayout top = new LinearLayout(this);
        top.setBackgroundColor(Ui.PRIMARY);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.button(this, "←", v -> finish()));
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17);
        titleView.setSingleLine(true);
        titleView.setText(nb.title);
        titleView.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        titleView.setOnClickListener(v -> Ui.prompt(this, "Renomear caderno", nb.title, s -> {
            if (!s.isEmpty()) { nb.title = s; titleView.setText(s); markDirty(); }
        }));
        top.addView(titleView, Ui.weight());
        undoBtn = Ui.button(this, "↶", v -> cv.undo());
        undoBtn.setTextSize(22);
        redoBtn = Ui.button(this, "↷", v -> cv.redo());
        redoBtn.setTextSize(22);
        recBtn = Ui.button(this, "●", v -> toggleRecording());
        recBtn.setVisibility(View.GONE);
        recBtn.setTextColor(0xFFFF5252);
        top.addView(recBtn);
        top.addView(undoBtn);
        top.addView(redoBtn);
        final TextView menuBtn = Ui.button(this, "⋮", null);
        menuBtn.setTextSize(22);
        menuBtn.setOnClickListener(v -> showMenu(menuBtn));
        top.addView(menuBtn);
        root.addView(top);

        // Barra de ferramentas
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFFF7F8FA);
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        String[] labels = {"✒️", "🖍️", "🧽", "➰", "T", "⬜"};
        String[] names = {"Caneta", "Marca-texto", "Borracha", "Laço", "Texto", "Formas"};
        for (int i = 0; i < labels.length; i++) {
            final int t = i;
            TextView btn = new TextView(this);
            btn.setText(labels[i] + "\n" + names[i]);
            btn.setTextSize(11);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(0xFF333333);
            int p = Ui.dp(this, 6);
            btn.setPadding(p * 2, p, p * 2, p);
            btn.setOnClickListener(v -> {
                if (cv.tool == t) showToolSettings();
                else selectTool(t);
            });
            toolBtns[i] = btn;
            tools.addView(btn);
        }
        TextView img = new TextView(this);
        img.setText("🖼️\nImagem");
        img.setTextSize(11);
        img.setGravity(Gravity.CENTER);
        img.setTextColor(0xFF333333);
        int p = Ui.dp(this, 6);
        img.setPadding(p * 2, p, p * 2, p);
        img.setBackground(Ui.ripple(this));
        img.setOnClickListener(v -> pickImage());
        tools.addView(img);
        View sep = new View(this);
        sep.setBackgroundColor(0x22000000);
        tools.addView(sep, new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 36)));
        swatchRow = new LinearLayout(this);
        swatchRow.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(swatchRow);
        hs.addView(tools);
        root.addView(hs);

        // Área de desenho
        FrameLayout area = new FrameLayout(this);
        cv = new CanvasView(this);
        cv.setRenderer(renderer);
        cv.setListener(this);
        area.addView(cv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout sel = new LinearLayout(this);
        sel.setBackground(Ui.round(0xEE263238, Ui.dp(this, 24)));
        sel.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        sel.addView(Ui.button(this, "🗑", v -> cv.deleteSelection()));
        sel.addView(Ui.button(this, "⧉", v -> cv.duplicateSelection()));
        sel.addView(Ui.button(this, "🎨", v -> pickColorDialog("Cor da seleção", Ui.INK_COLORS, 0xFF000000, cv::recolorSelection)));
        sel.addView(Ui.button(this, "－", v -> cv.scaleSelection(0.9f)));
        sel.addView(Ui.button(this, "＋", v -> cv.scaleSelection(1.1f)));
        sel.addView(Ui.button(this, "✓", v -> cv.clearSelection()));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        slp.topMargin = Ui.dp(this, 10);
        area.addView(sel, slp);
        sel.setVisibility(View.GONE);
        selectionBar = sel;
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Navegação de páginas
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(Ui.PRIMARY);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(Ui.button(this, "◀", v -> { if (pageIndex > 0) showPage(pageIndex - 1); }));
        pageLabel = Ui.button(this, "", v -> showPagesDialog());
        nav.addView(pageLabel, Ui.weight());
        nav.addView(Ui.button(this, "▶", v -> { if (pageIndex < nb.pages.size() - 1) showPage(pageIndex + 1); }));
        nav.addView(Ui.button(this, "＋ Página", v -> addPage(pageIndex + 1, nb.pages.get(pageIndex).blankLike())));
        root.addView(nav);

        setContentView(root);
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Páginas…");
        m.add(0, 2, 0, "Modelo / cor do papel");
        m.add(0, 3, 0, "Importar PDF neste caderno");
        m.add(0, 4, 0, "Inserir imagem");
        m.add(0, 5, 0, recorder == null ? "Gravar áudio" : "Parar gravação");
        m.add(0, 6, 0, "Gravações de áudio");
        m.add(0, 7, 0, "Exportar como PDF");
        m.add(0, 8, 0, "Compartilhar como PDF");
        m.add(0, 9, 0, "Exportar página como imagem");
        MenuItem so = m.add(0, 10, 0, "Modo só caneta (dedo rola)");
        so.setCheckable(true);
        so.setChecked(cv.stylusOnly);
        m.add(0, 11, 0, "Ajustar à tela");
        m.add(0, 12, 0, "Limpar página");
        m.add(0, 13, 0, "Excluir página");
        pm.setOnMenuItemClickListener(this::onMenu);
        pm.show();
    }

    private boolean onMenu(MenuItem it) {
        switch (it.getItemId()) {
            case 1: showPagesDialog(); break;
            case 2: templateDialog(); break;
            case 3: openDoc("application/pdf", REQ_PDF); break;
            case 4: pickImage(); break;
            case 5: toggleRecording(); break;
            case 6: recordingsDialog(); break;
            case 7: createDoc("application/pdf", safeName(nb.title) + ".pdf", REQ_EXPORT_PDF); break;
            case 8: sharePdf(); break;
            case 9: createDoc("image/png", safeName(nb.title) + "-p" + (pageIndex + 1) + ".png", REQ_EXPORT_PNG); break;
            case 10:
                cv.stylusOnly = !cv.stylusOnly;
                prefs.edit().putBoolean("stylusOnly", cv.stylusOnly).apply();
                Ui.toast(this, cv.stylusOnly ? "Só a caneta escreve; use o dedo para rolar." : "Dedo e caneta escrevem.");
                break;
            case 11: cv.fit(); break;
            case 12: cv.clearPage(); break;
            case 13: confirmDeletePage(pageIndex); break;
        }
        return true;
    }

    // ================================================================ ferramentas
    private void loadToolPrefs() {
        cv.penColor = prefs.getInt("penColor", 0xFF1A237E);
        cv.penWidth = prefs.getFloat("penWidth", 4f);
        cv.hlColor = prefs.getInt("hlColor", 0xFFFFEB3B);
        cv.hlWidth = prefs.getFloat("hlWidth", 26f);
        cv.eraserSize = prefs.getFloat("eraserSize", 18f);
        cv.textColor = prefs.getInt("textColor", 0xFF000000);
        cv.textSize = prefs.getFloat("textSize", 34f);
        cv.shapeType = prefs.getInt("shapeType", CanvasView.SHAPE_RECT);
        cv.stylusOnly = prefs.getBoolean("stylusOnly", false);
        selectTool(prefs.getInt("tool", CanvasView.TOOL_PEN));
    }

    private void saveToolPrefs() {
        prefs.edit().putInt("penColor", cv.penColor).putFloat("penWidth", cv.penWidth)
                .putInt("hlColor", cv.hlColor).putFloat("hlWidth", cv.hlWidth)
                .putFloat("eraserSize", cv.eraserSize).putInt("textColor", cv.textColor)
                .putFloat("textSize", cv.textSize).putInt("shapeType", cv.shapeType)
                .putInt("tool", cv.tool).apply();
    }

    private void selectTool(int t) {
        cv.setTool(t);
        for (int i = 0; i < toolBtns.length; i++) {
            toolBtns[i].setBackground(i == t ? Ui.round(0x332196F3, Ui.dp(this, 10)) : Ui.ripple(this));
        }
        refreshSwatches();
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
        refreshSwatches();
        saveToolPrefs();
    }

    private void refreshSwatches() {
        swatchRow.removeAllViews();
        int t = cv.tool;
        if (t == CanvasView.TOOL_ERASER || t == CanvasView.TOOL_LASSO) {
            TextView tip = new TextView(this);
            tip.setText(t == CanvasView.TOOL_ERASER ? "  Toque de novo na borracha para mudar o tamanho" : "  Circule itens para mover, redimensionar ou apagar");
            tip.setTextSize(12);
            tip.setTextColor(0xFF666666);
            swatchRow.addView(tip);
            return;
        }
        int[] pal = t == CanvasView.TOOL_HIGHLIGHTER ? Ui.HL_COLORS
                : new int[]{0xFF000000, 0xFF1A237E, 0xFF1976D2, 0xFFD32F2F, 0xFF388E3C, 0xFFF57C00, 0xFF7B1FA2};
        int cur = currentColor();
        for (final int c : pal) {
            View s = Ui.swatch(this, c, (c | 0xFF000000) == (cur | 0xFF000000), 28);
            s.setOnClickListener(v -> setCurrentColor(c));
            swatchRow.addView(s);
        }
        TextView more = new TextView(this);
        more.setText("⚙︎");
        more.setTextSize(20);
        more.setTextColor(0xFF333333);
        more.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        more.setOnClickListener(v -> showToolSettings());
        swatchRow.addView(more);
    }

    private void showToolSettings() {
        final int t = cv.tool;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        final AlertDialog[] dlg = new AlertDialog[1];

        if (t == CanvasView.TOOL_SHAPE) {
            RadioGroup rg = new RadioGroup(this);
            rg.setOrientation(RadioGroup.HORIZONTAL);
            String[] sh = {"Linha", "Retângulo", "Elipse", "Seta"};
            for (int i = 0; i < sh.length; i++) {
                RadioButton rb = new RadioButton(this);
                rb.setText(sh[i]);
                rb.setId(100 + i);
                rg.addView(rb);
            }
            rg.check(100 + cv.shapeType);
            rg.setOnCheckedChangeListener((g, id) -> { cv.shapeType = id - 100; saveToolPrefs(); });
            box.addView(rg);
        }
        if (t != CanvasView.TOOL_ERASER && t != CanvasView.TOOL_LASSO) {
            box.addView(Ui.colorGrid(this, t == CanvasView.TOOL_HIGHLIGHTER ? Ui.HL_COLORS : Ui.INK_COLORS, currentColor(), c -> {
                setCurrentColor(c);
                if (dlg[0] != null) dlg[0].dismiss();
            }));
        }
        if (t != CanvasView.TOOL_LASSO) {
            final TextView lbl = new TextView(this);
            box.addView(lbl);
            SeekBar sb = new SeekBar(this);
            final float min, max;
            final float[] val = new float[1];
            final String name;
            if (t == CanvasView.TOOL_HIGHLIGHTER) { min = 8; max = 70; val[0] = cv.hlWidth; name = "Espessura"; }
            else if (t == CanvasView.TOOL_ERASER) { min = 4; max = 80; val[0] = cv.eraserSize; name = "Tamanho da borracha"; }
            else if (t == CanvasView.TOOL_TEXT) { min = 14; max = 120; val[0] = cv.textSize; name = "Tamanho do texto"; }
            else { min = 1; max = 30; val[0] = cv.penWidth; name = "Espessura"; }
            sb.setMax(100);
            sb.setProgress(Math.round((val[0] - min) / (max - min) * 100));
            lbl.setText(name + ": " + Math.round(val[0]));
            lbl.setPadding(0, Ui.dp(this, 12), 0, 0);
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
            box.addView(sb);
        }
        dlg[0] = new AlertDialog.Builder(this).setTitle("Ajustes da ferramenta").setView(box)
                .setPositiveButton("OK", null).show();
    }

    private void pickColorDialog(String title, int[] pal, int cur, final Ui.IntCallback cb) {
        final AlertDialog[] d = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(Ui.colorGrid(this, pal, cur, c -> { cb.on(c); if (d[0] != null) d[0].dismiss(); }));
        d[0] = new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Fechar", null).show();
    }

    // ================================================================ páginas
    private void showPage(int i) {
        cv.clearSelection();
        pageIndex = i;
        nb.lastPage = i;
        final Model.Page p = nb.pages.get(i);
        cv.setPage(p);
        pageLabel.setText("Página " + (i + 1) + " de " + nb.pages.size());
        if (p.pdf != null) {
            final int w = Math.min(2200, Math.max(1200, getResources().getDisplayMetrics().widthPixels * 2));
            bg.execute(() -> {
                final Bitmap bmp = pdf.render(p.pdf, p.pdfIndex, w);
                main.post(() -> { if (cv.getPage() == p) cv.setPdfBitmap(bmp); });
            });
        }
        onHistoryChanged();
    }

    private void addPage(int at, Model.Page p) {
        nb.pages.add(at, p);
        markDirty();
        showPage(at);
    }

    private void confirmDeletePage(final int i) {
        new AlertDialog.Builder(this).setTitle("Excluir página " + (i + 1) + "?")
                .setMessage("Esta ação não pode ser desfeita.")
                .setPositiveButton("Excluir", (d, w) -> {
                    Model.Page removed = nb.pages.remove(i);
                    cv.forgetHistory(removed);
                    if (nb.pages.isEmpty()) nb.pages.add(new Model.Page());
                    markDirty();
                    showPage(Math.min(i, nb.pages.size() - 1));
                }).setNegativeButton("Cancelar", null).show();
    }

    private void showPagesDialog() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < nb.pages.size(); i++) {
            Model.Page p = nb.pages.get(i);
            String kind = p.pdf != null ? "PDF, pág. " + (p.pdfIndex + 1) : Model.templateName(p.template);
            rows.add((i == pageIndex ? "▶ " : "   ") + "Página " + (i + 1) + "  ·  " + kind + (p.items.isEmpty() ? "" : "  ·  " + p.items.size() + " itens"));
        }
        final ListView lv = new ListView(this);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Páginas (toque longo para opções)").setView(lv)
                .setNegativeButton("Fechar", null)
                .setPositiveButton("Nova página no fim", (x, w) -> addPage(nb.pages.size(), nb.pages.get(nb.pages.size() - 1).blankLike()))
                .create();
        lv.setOnItemClickListener((a, v, pos, id) -> { d.dismiss(); showPage(pos); });
        lv.setOnItemLongClickListener((a, v, pos, id) -> { d.dismiss(); pageOptions(pos); return true; });
        lv.setSelection(Math.max(0, pageIndex - 2));
        d.show();
    }

    private void pageOptions(final int i) {
        String[] opts = {"Ir para a página", "Duplicar", "Mover para cima", "Mover para baixo", "Inserir página em branco depois", "Excluir"};
        new AlertDialog.Builder(this).setTitle("Página " + (i + 1)).setItems(opts, (d, w) -> {
            switch (w) {
                case 0: showPage(i); break;
                case 1: addPage(i + 1, nb.pages.get(i).copy()); break;
                case 2: if (i > 0) { nb.pages.add(i - 1, nb.pages.remove(i)); markDirty(); showPage(i - 1); } break;
                case 3: if (i < nb.pages.size() - 1) { nb.pages.add(i + 1, nb.pages.remove(i)); markDirty(); showPage(i + 1); } break;
                case 4: addPage(i + 1, nb.pages.get(i).blankLike()); break;
                case 5: confirmDeletePage(i); break;
            }
        }).show();
    }

    private void templateDialog() {
        final Model.Page p = nb.pages.get(pageIndex);
        if (p.pdf != null) {
            Ui.toast(this, "Páginas de PDF não têm modelo de papel. Adicione uma nova página para escolher um modelo.");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        final Spinner tpl = new Spinner(this);
        tpl.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Model.TEMPLATE_NAMES));
        tpl.setSelection(Math.max(0, Arrays.asList(Model.TEMPLATES).indexOf(p.template)));
        final Spinner paper = new Spinner(this);
        paper.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Model.PAPER_NAMES));
        int pi = 0;
        for (int i = 0; i < Model.PAPER_COLORS.length; i++) if (Model.PAPER_COLORS[i] == p.paperColor) pi = i;
        paper.setSelection(pi);
        TextView l1 = new TextView(this); l1.setText("Modelo");
        TextView l2 = new TextView(this); l2.setText("Cor do papel");
        box.addView(l1); box.addView(tpl); box.addView(l2); box.addView(paper);
        new AlertDialog.Builder(this).setTitle("Papel").setView(box)
                .setPositiveButton("Esta página", (d, w) -> {
                    p.template = Model.TEMPLATES[tpl.getSelectedItemPosition()];
                    p.paperColor = Model.PAPER_COLORS[paper.getSelectedItemPosition()];
                    markDirty();
                    cv.invalidate();
                })
                .setNeutralButton("Todas as páginas", (d, w) -> {
                    for (Model.Page q : nb.pages) if (q.pdf == null) {
                        q.template = Model.TEMPLATES[tpl.getSelectedItemPosition()];
                        q.paperColor = Model.PAPER_COLORS[paper.getSelectedItemPosition()];
                    }
                    markDirty();
                    cv.invalidate();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // ================================================================ callbacks do canvas
    @Override public void onContentChanged() { markDirty(); }

    @Override public void onHistoryChanged() {
        if (undoBtn == null) return;
        undoBtn.setAlpha(cv.canUndo() ? 1f : 0.35f);
        redoBtn.setAlpha(cv.canRedo() ? 1f : 0.35f);
    }

    @Override public void onSelectionChanged(boolean has) {
        selectionBar.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    @Override public void onTextRequest(final float x, final float y, final Model.TextItem existing) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);
        if (existing != null) et.setText(existing.text);
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.addView(et);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(existing == null ? "Novo texto" : "Editar texto").setView(f)
                .setPositiveButton("OK", (d, w) -> {
                    String s = et.getText().toString();
                    if (s.trim().isEmpty()) { if (existing != null) cv.replaceItem(existing, null); return; }
                    if (existing == null) cv.addItem(new Model.TextItem(s, x, y - cv.textSize * 0.7f, cv.textSize, cv.textColor));
                    else cv.replaceItem(existing, new Model.TextItem(s, existing.x, existing.y, existing.size, existing.color));
                })
                .setNegativeButton("Cancelar", null);
        if (existing != null) b.setNeutralButton("Excluir", (d, w) -> cv.replaceItem(existing, null));
        AlertDialog d = b.create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        et.requestFocus();
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
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
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
                final Model.Page page = nb.pages.get(pageIndex);
                bg.execute(() -> {
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
        bg.execute(() -> {
            try {
                File out = new File(ShareProvider.shareDir(this), safeName(nb.title) + ".pdf");
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
                String name = "images/img_" + System.currentTimeMillis() + ".img";
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
                    float w = 500, h = w * ratio;
                    if (h > 700) { h = 700; w = h / ratio; }
                    Model.ImageItem it = new Model.ImageItem(name, new RectF(c[0] - w / 2, c[1] - h / 2, c[0] + w / 2, c[1] + h / 2));
                    cv.addItem(it);
                    selectTool(CanvasView.TOOL_LASSO);
                    cv.selectOnly(it);
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
                String name = "doc_" + System.currentTimeMillis() + ".pdf";
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
                    markDirty();
                    showPage(at);
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
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
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
            recBtn.setVisibility(View.VISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Ui.toast(this, "Gravando… toque no ● vermelho para parar.");
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
        recBtn.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.toast(this, "Gravação salva neste caderno.");
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_AUDIO_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) toggleRecording();
        else if (req == REQ_AUDIO_PERM) Ui.toast(this, "Permissão de microfone negada.");
    }

    private void recordingsDialog() {
        final File[] files = audioDir().listFiles();
        if (files == null || files.length == 0) {
            Ui.toast(this, "Nenhuma gravação ainda. Use ⋮ → Gravar áudio.");
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = "▶  " + files[i].getName().replace(".m4a", "");
        ListView lv = new ListView(this);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Gravações (toque longo para excluir)").setView(lv)
                .setNegativeButton("Fechar", null).create();
        d.setOnDismissListener(x -> stopPlayback());
        lv.setOnItemClickListener((a, v, pos, id) -> play(files[pos]));
        lv.setOnItemLongClickListener((a, v, pos, id) -> {
            new AlertDialog.Builder(this).setTitle("Excluir gravação?").setMessage(files[pos].getName())
                    .setPositiveButton("Excluir", (x, w) -> { stopPlayback(); files[pos].delete(); d.dismiss(); })
                    .setNegativeButton("Cancelar", null).show();
            return true;
        });
        d.show();
    }

    private void play(File f) {
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
            Ui.toast(this, "Reproduzindo… toque em outra gravação ou feche para parar.");
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
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 4000);
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
        stopPlayback();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        bg.execute(() -> { if (pdf != null) pdf.close(); });
        bg.shutdown();
    }

}
