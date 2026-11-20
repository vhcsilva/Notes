package com.cadernolivre.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor do Quadro (tela infinita para diagramas e esboços). */
public class BoardActivity extends Activity implements BoardView.Listener {
    private static final int REQ_IMAGE = 1, REQ_EXPORT = 2, REQ_EXPORT_PDF = 3;
    private static final String[] ICONS = {"cursor", "hand", "square", "diamond", "circle", "arrow", "line", "pen", "text", "eraser"};
    private static final String[] NAMES = {"Selecionar", "Mover tela", "Retângulo", "Losango", "Elipse", "Seta", "Linha", "Desenho livre", "Texto", "Borracha"};
    static final int[] STROKES = {0xFF1E1E1E, 0xFFE03131, 0xFF2F9E44, 0xFF1971C2, 0xFFF08C00, 0xFF9C36B5, 0xFF868E96, 0xFFFFFFFF};
    static final int[] FILLS = {0, 0xFFFFC9C9, 0xFFB2F2BB, 0xFFA5D8FF, 0xFFFFEC99, 0xFFE5DBFF, 0xFFDEE2E6, 0xFF1E1E1E};

    private Model.Notebook nb;
    private File dir;
    private BoardView bv;
    private BoardRenderer renderer;
    private PageRenderer.ImageCache images;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean dirty, changedThisSession;

    private ImageView undoBtn, redoBtn, lockBtn;
    private final ImageView[] toolBtns = new ImageView[ICONS.length];
    private TextView zoomChip;
    private LinearLayout selectionBar;
    private final Runnable autosave = this::saveAsync;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Th.init(this);
        try {
            nb = Storage.load(this, getIntent().getStringExtra(NotebookActivity.EXTRA_ID));
        } catch (Exception e) {
            Ui.toast(this, "Não foi possível abrir o quadro: " + e.getMessage());
            finish();
            return;
        }
        if (nb.pages.isEmpty()) nb.pages.add(new Model.Page());
        dir = Storage.dir(this, nb.id);
        images = new PageRenderer.ImageCache();
        renderer = new BoardRenderer(new PageRenderer(dir, images));
        prefs = getSharedPreferences("board", MODE_PRIVATE);
        Th.apply(this, Th.surface);
        buildUi();
        loadStyle();
        bv.setPage(nb.pages.get(0));
        selectTool(BoardView.T_SELECT);
        onHistoryChanged();
    }

    // ================================================================ interface
    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BoardView.PAPER);

        LinearLayout top = new LinearLayout(this);
        top.setBackgroundColor(Th.surface);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        top.setElevation(Ui.dp(this, 2));
        top.addView(Ui.iconButton(this, "back", Th.on, v -> finish()));
        final TextView title = Ui.text(this, nb.title, 16, Th.on, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        title.setBackground(Ui.ripple(0, Ui.dp(this, 8)));
        title.setOnClickListener(v -> Ui.prompt(this, "Renomear quadro", nb.title, s -> {
            if (!s.isEmpty()) { nb.title = s; title.setText(s); markDirty(); }
        }));
        top.addView(title, Ui.weight());
        undoBtn = Ui.iconButton(this, "undo", Th.on, v -> bv.undo());
        redoBtn = Ui.iconButton(this, "redo", Th.on, v -> bv.redo());
        top.addView(undoBtn);
        top.addView(redoBtn);
        top.addView(Ui.iconButton(this, "palette", Th.on, v -> styleSheet()));
        top.addView(Ui.iconButton(this, "more", Th.on, v -> menu()));
        col.addView(top);

        FrameLayout area = new FrameLayout(this);
        bv = new BoardView(this);
        bv.setRenderer(renderer);
        bv.setListener(this);
        area.addView(bv, new FrameLayout.LayoutParams(-1, -1));

        zoomChip = Ui.text(this, "100%", 12, Th.on, true);
        zoomChip.setBackground(Ui.ripple(Th.surface, Ui.dp(this, 14)));
        zoomChip.setElevation(Ui.dp(this, 3));
        zoomChip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        zoomChip.setOnClickListener(v -> new Ui.Sheet(this, "Zoom")
                .item("fit", "Ajustar ao conteúdo", bv::fitContent)
                .item("search", "100%", () -> bv.setZoom(1f))
                .item("plus", "Aproximar", () -> bv.setZoom(bv.zoom() * 1.5f))
                .item("minus", "Afastar", () -> bv.setZoom(bv.zoom() / 1.5f))
                .show());
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        zl.setMargins(0, Ui.dp(this, 12), Ui.dp(this, 12), 0);
        area.addView(zoomChip, zl);

        selectionBar = new LinearLayout(this);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setBackground(Ui.round(Th.surface, Ui.dp(this, 26)));
        selectionBar.setElevation(Ui.dp(this, 8));
        selectionBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        selectionBar.addView(Ui.iconButton(this, "palette", Th.primary, v -> styleSheet()));
        selectionBar.addView(Ui.iconButton(this, "text", Th.on, v -> editSelectedLabel()));
        selectionBar.addView(Ui.iconButton(this, "copy", Th.on, v -> bv.duplicateSelection()));
        selectionBar.addView(Ui.iconButton(this, "front", Th.on, v -> bv.bringToFront()));
        selectionBar.addView(Ui.iconButton(this, "backward", Th.on, v -> bv.sendToBack()));
        selectionBar.addView(Ui.iconButton(this, "trash", Th.danger, v -> bv.deleteSelection()));
        selectionBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = Ui.dp(this, 88);
        area.addView(selectionBar, sl);

        HorizontalScrollView dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(Ui.round(Th.surface, Ui.dp(this, 30)));
        dock.setElevation(Ui.dp(this, 10));
        dock.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        lockBtn = new ImageView(this);
        int lp = Ui.dp(this, 12);
        lockBtn.setPadding(lp, lp, lp, lp);
        lockBtn.setContentDescription("Manter ferramenta");
        lockBtn.setOnClickListener(v -> {
            bv.lockTool = !bv.lockTool;
            refreshLock();
            Ui.toast(this, bv.lockTool ? "Ferramenta travada: desenhe várias formas seguidas." : "Depois de cada forma, volta para Selecionar.");
        });
        dock.addView(lockBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        for (int i = 0; i < ICONS.length; i++) {
            final int t = i;
            ImageView b = new ImageView(this);
            int p = Ui.dp(this, 11);
            b.setPadding(p, p, p, p);
            b.setContentDescription(NAMES[i]);
            b.setOnClickListener(v -> {
                if (bv.tool == t && t >= BoardView.T_RECT && t != BoardView.T_ERASER) styleSheet();
                else selectTool(t);
            });
            b.setOnLongClickListener(v -> { Ui.toast(this, NAMES[t]); return true; });
            dock.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
            toolBtns[i] = b;
        }
        ImageView img = Ui.iconButton(this, "image", Th.on, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            try { startActivityForResult(i, REQ_IMAGE); } catch (Exception e) { Ui.toast(this, "Nenhum seletor de arquivos disponível."); }
        });
        img.setContentDescription("Imagem");
        dock.addView(img, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        dockScroll.addView(dock);
        dockScroll.setClipToPadding(false);
        dockScroll.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = Ui.dp(this, 6);
        area.addView(dockScroll, dl);

        col.addView(area, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(col);
        refreshLock();
    }

    private void refreshLock() {
        lockBtn.setImageDrawable(Icons.of(bv.lockTool ? "lock" : "unlock", bv.lockTool ? Th.primary : Th.on2));
        lockBtn.setBackground(bv.lockTool ? Ui.ripple(Th.primarySoft, Ui.dp(this, 22)) : Ui.ripple(0, Ui.dp(this, 22)));
    }

    private void selectTool(int t) {
        bv.setTool(t);
        highlight(t);
    }

    private void highlight(int t) {
        for (int i = 0; i < toolBtns.length; i++) {
            boolean sel = i == t;
            toolBtns[i].setImageDrawable(Icons.of(ICONS[i], sel ? Th.primary : Th.on));
            toolBtns[i].setBackground(sel ? Ui.ripple(Th.primarySoft, Ui.dp(this, 23)) : Ui.ripple(0, Ui.dp(this, 23)));
        }
    }

    private void menu() {
        new Ui.Sheet(this, nb.title)
                .item("export", "Exportar como imagem (PNG)", () -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/png");
                    i.putExtra(Intent.EXTRA_TITLE, NotebookActivity.safeName(nb.title) + ".png");
                    try { startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { Ui.toast(this, "Nenhum seletor de arquivos disponível."); }
                })
                .item("doc", "Exportar como PDF", "Vetorial: nítido em qualquer zoom", false, () -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/pdf");
                    i.putExtra(Intent.EXTRA_TITLE, NotebookActivity.safeName(nb.title) + ".pdf");
                    try { startActivityForResult(i, REQ_EXPORT_PDF); } catch (Exception e) { Ui.toast(this, "Nenhum seletor de arquivos disponível."); }
                })
                .item("share", "Compartilhar imagem", () -> share(false))
                .item("share", "Compartilhar como PDF", () -> share(true))
                .item("fit", "Ajustar ao conteúdo", bv::fitContent)
                .item("selectall", "Selecionar tudo", bv::selectAll)
                .item("grid", "Grade de pontos", bv.showGrid ? "Visível" : "Oculta", false, () -> {
                    bv.showGrid = !bv.showGrid;
                    prefs.edit().putBoolean("grid", bv.showGrid).apply();
                    bv.invalidate();
                })
                .item("trash", "Limpar quadro", null, true, () -> Ui.confirm(this, "Limpar o quadro?",
                        "Tudo será apagado. Você pode desfazer em seguida.", "Limpar", bv::clearAll))
                .show();
    }

    // ================================================================ estilo
    private void loadStyle() {
        BoardItem.Style s = bv.style;
        s.stroke = prefs.getInt("stroke", s.stroke);
        s.fill = prefs.getInt("fill", s.fill);
        s.fillStyle = prefs.getInt("fillStyle", s.fillStyle);
        s.width = prefs.getFloat("width", s.width);
        s.dash = prefs.getInt("dash", s.dash);
        s.rough = prefs.getInt("rough", s.rough);
        s.rounded = prefs.getBoolean("rounded", s.rounded);
        s.fontSize = prefs.getFloat("fontSize", s.fontSize);
        bv.showGrid = prefs.getBoolean("grid", true);
    }

    private void saveStyle() {
        BoardItem.Style s = bv.style;
        prefs.edit().putInt("stroke", s.stroke).putInt("fill", s.fill).putInt("fillStyle", s.fillStyle)
                .putFloat("width", s.width).putInt("dash", s.dash).putInt("rough", s.rough)
                .putBoolean("rounded", s.rounded).putFloat("fontSize", s.fontSize).apply();
    }

    interface StyleEdit { void apply(BoardItem.Style s); }

    /** Painel de estilo: vale para a seleção (se houver) e para os próximos elementos. */
    private void styleSheet() {
        List<Model.Item> sel = bv.getSelection();
        for (Model.Item it : sel) if (it instanceof BoardItem) { bv.style = ((BoardItem) it).style.copy(); break; }
        final Ui.Sheet sheet = new Ui.Sheet(this, sel.isEmpty() ? "Estilo dos próximos elementos" : "Estilo da seleção");
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        sheet.view(body);
        final Runnable[] build = new Runnable[1];
        build[0] = () -> {
            body.removeAllViews();
            final BoardItem.Style s = bv.style;
            body.addView(section("Traço"));
            body.addView(colorRow(STROKES, s.stroke, c -> edit(x -> x.stroke = c, build[0])));
            body.addView(section("Fundo"));
            body.addView(colorRow(FILLS, s.fill, c -> edit(x -> { x.fill = c; if (c != 0 && x.fillStyle == BoardItem.FILL_NONE) x.fillStyle = BoardItem.FILL_HACHURE; }, build[0])));
            body.addView(section("Preenchimento"));
            body.addView(chips(new String[]{"Hachurado", "Cruzado", "Sólido"}, s.fillStyle - 1,
                    i -> edit(x -> x.fillStyle = i + 1, build[0])));
            body.addView(section("Espessura"));
            float[] widths = {1.5f, 2.5f, 4.5f};
            int wi = s.width <= 1.8f ? 0 : s.width <= 3f ? 1 : 2;
            body.addView(chips(new String[]{"Fina", "Média", "Grossa"}, wi, i -> edit(x -> x.width = widths[i], build[0])));
            body.addView(section("Estilo do traço"));
            body.addView(chips(new String[]{"Contínuo", "Tracejado", "Pontilhado"}, s.dash, i -> edit(x -> x.dash = i, build[0])));
            body.addView(section("Traço à mão"));
            body.addView(chips(new String[]{"Limpo", "À mão", "Rabiscado"}, s.rough, i -> edit(x -> x.rough = i, build[0])));
            body.addView(section("Cantos"));
            body.addView(chips(new String[]{"Retos", "Arredondados"}, s.rounded ? 1 : 0, i -> edit(x -> x.rounded = i == 1, build[0])));
            body.addView(section("Tamanho do texto"));
            float[] sizes = {16, 24, 36, 56};
            int si = 0;
            for (int i = 0; i < sizes.length; i++) if (Math.abs(sizes[i] - s.fontSize) < Math.abs(sizes[si] - s.fontSize)) si = i;
            body.addView(chips(new String[]{"P", "M", "G", "GG"}, si, i -> edit(x -> x.fontSize = sizes[i], build[0])));
        };
        build[0].run();
        sheet.show();
    }

    private void edit(StyleEdit e, Runnable rebuild) {
        BoardItem.Style s = bv.style.copy();
        e.apply(s);
        bv.style = s;
        saveStyle();
        if (!bv.getSelection().isEmpty()) bv.applyStyle(s);
        rebuild.run();
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, s, 12, Th.on2, true);
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 12), 0, Ui.dp(this, 2));
        return t;
    }

    private View colorRow(int[] colors, int current, final Ui.IntCallback cb) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (final int c : colors) {
            View v;
            if (c == 0) {
                ImageView none = new ImageView(this);
                none.setImageDrawable(Icons.of("close", Th.on2));
                int p = Ui.dp(this, 6);
                none.setPadding(p, p, p, p);
                none.setBackground(Ui.stroke(Th.surface, c == current ? Th.primary : Th.outline, Ui.dp(this, c == current ? 3 : 1), Ui.dp(this, 16)));
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
                l.setMargins(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
                none.setLayoutParams(l);
                v = none;
            } else v = Ui.swatch(this, c, c == current, 32);
            v.setOnClickListener(x -> cb.on(c));
            row.addView(v);
        }
        View custom = Ui.swatch(this, Color.TRANSPARENT, false, 32);
        custom.setBackground(Ui.stroke(0, Th.on2, Ui.dp(this, 1), Ui.dp(this, 16)));
        ImageView plus = new ImageView(this);
        plus.setImageDrawable(Icons.of("plus", Th.on2));
        int p = Ui.dp(this, 6);
        plus.setPadding(p, p, p, p);
        plus.setBackground(Ui.stroke(0, Th.on2, Ui.dp(this, 1), Ui.dp(this, 16)));
        plus.setLayoutParams(custom.getLayoutParams());
        plus.setOnClickListener(x -> Ui.customColor(this, current == 0 ? 0xFF888888 : current, cb));
        row.addView(plus);
        hs.addView(row);
        return hs;
    }

    interface IdxCb { void on(int i); }

    private View chips(String[] labels, int sel, final IdxCb cb) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            row.addView(Ui.chip(this, labels[i], i == sel, v -> cb.on(idx)));
        }
        hs.addView(row);
        return hs;
    }

    // ================================================================ texto
    private void editSelectedLabel() {
        List<Model.Item> sel = bv.getSelection();
        if (sel.size() == 1 && sel.get(0) instanceof BoardItem) {
            BoardItem b = (BoardItem) sel.get(0);
            if (b.isBox() || b.type == BoardItem.TEXT) { onTextRequest(b.x1, b.y1, b); return; }
        }
        Ui.toast(this, "Selecione uma forma ou um texto para escrever nele.");
    }

    @Override public void onTextRequest(final float x, final float y, final BoardItem existing) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(2);
        et.setGravity(Gravity.TOP);
        if (existing != null) {
            et.setText(existing.label);
            et.setSelection(et.getText().length());
        }
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 22);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.addView(et);
        String title = existing == null ? "Texto" : existing.type == BoardItem.TEXT ? "Editar texto" : "Texto dentro da forma";
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setView(f)
                .setPositiveButton("OK", (d, w) -> {
                    String s = et.getText().toString();
                    if (existing == null) {
                        if (s.trim().isEmpty()) return;
                        bv.addAndSelect(new BoardItem(BoardItem.TEXT, x, y, x, y, bv.style.copy(), s, BoardItem.newSeed()));
                        highlight(BoardView.T_SELECT);
                    } else if (existing.type == BoardItem.TEXT && s.trim().isEmpty()) bv.replace(existing, null);
                    else bv.replace(existing, existing.withLabel(s));
                })
                .setNegativeButton("Cancelar", null);
        AlertDialog d = b.create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        et.requestFocus();
    }

    // ================================================================ callbacks
    @Override public void onChanged() { markDirty(); }

    @Override public void onSelectionChanged(int count) {
        selectionBar.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    @Override public void onToolChanged(int tool) { highlight(tool); }

    @Override public void onHistoryChanged() {
        undoBtn.setAlpha(bv.canUndo() ? 1f : 0.3f);
        redoBtn.setAlpha(bv.canRedo() ? 1f : 0.3f);
    }

    @Override public void onZoomChanged(float zoom) { zoomChip.setText(Math.round(zoom * 100) + "%"); }

    @Override public void onTemporaryEraser(boolean active) { highlight(active ? BoardView.T_ERASER : bv.tool); }

    // ================================================================ imagens e exportação
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_IMAGE) {
            bg.execute(() -> {
                try {
                    final String name = "images/img_" + System.currentTimeMillis() + ".img";
                    File f = new File(dir, name);
                    Storage.copyToFile(getContentResolver().openInputStream(uri), f);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                    if (o.outWidth <= 0) { f.delete(); throw new Exception("imagem inválida"); }
                    final float ratio = o.outHeight / (float) o.outWidth;
                    main.post(() -> {
                        float[] c = bv.viewCenter();
                        float w = 320, h = w * ratio;
                        bv.addAndSelect(new Model.ImageItem(name, new RectF(c[0] - w / 2, c[1] - h / 2, c[0] + w / 2, c[1] + h / 2)));
                    });
                } catch (Throwable e) {
                    main.post(() -> Ui.toast(this, "Não foi possível inserir a imagem: " + e.getMessage()));
                }
            });
        } else if (req == REQ_EXPORT_PDF) {
            Ui.toast(this, "Exportando PDF…");
            final List<Model.Item> items = new java.util.ArrayList<>(bv.items());
            bg.execute(() -> {
                String msg;
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    try { BoardView.renderPdf(items, new BoardRenderer(new PageRenderer(dir, images)), os); } finally { os.close(); }
                    msg = "PDF exportado.";
                } catch (Throwable e) { msg = "Falha ao exportar: " + e.getMessage(); }
                final String m = msg;
                main.post(() -> Ui.toast(this, m));
            });
        } else if (req == REQ_EXPORT) {
            Ui.toast(this, "Exportando…");
            final List<Model.Item> items = new java.util.ArrayList<>(bv.items());
            bg.execute(() -> {
                String msg;
                try {
                    Bitmap bmp = BoardView.render(items, new BoardRenderer(new PageRenderer(dir, images)), 3000, false);
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    try { bmp.compress(Bitmap.CompressFormat.PNG, 100, os); } finally { os.close(); }
                    bmp.recycle();
                    msg = "Imagem exportada.";
                } catch (Throwable e) { msg = "Falha ao exportar: " + e.getMessage(); }
                final String m = msg;
                main.post(() -> Ui.toast(this, m));
            });
        }
    }

    private void share(final boolean pdf) {
        final List<Model.Item> items = new java.util.ArrayList<>(bv.items());
        bg.execute(() -> {
            try {
                final File out = new File(ShareProvider.shareDir(this), NotebookActivity.safeName(nb.title) + (pdf ? ".pdf" : ".png"));
                BoardRenderer r = new BoardRenderer(new PageRenderer(dir, images));
                OutputStream os = new FileOutputStream(out);
                try {
                    if (pdf) BoardView.renderPdf(items, r, os);
                    else {
                        Bitmap bmp = BoardView.render(items, r, 2400, false);
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                        bmp.recycle();
                    }
                } finally { os.close(); }
                main.post(() -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType(pdf ? "application/pdf" : "image/png");
                    s.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(out));
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Compartilhar quadro"));
                });
            } catch (Throwable e) {
                main.post(() -> Ui.toast(this, "Falha: " + e.getMessage()));
            }
        });
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

    @Override protected void onPause() {
        super.onPause();
        if (nb == null) return;
        main.removeCallbacks(autosave);
        boolean wasDirty = dirty;
        saveAsync();
        if (wasDirty || !new File(dir, "thumb.png").exists()) {
            final List<Model.Item> items = new java.util.ArrayList<>(nb.pages.get(0).items);
            bg.execute(() -> {
                try {
                    Bitmap b = BoardView.render(items, new BoardRenderer(new PageRenderer(dir, images)), 420, true);
                    OutputStream os = new FileOutputStream(new File(dir, "thumb.png"));
                    try { b.compress(Bitmap.CompressFormat.PNG, 90, os); } finally { os.close(); }
                    b.recycle();
                } catch (Throwable ignored) {}
            });
        }
        if (changedThisSession && AutoBackup.folder(this) != null) {
            changedThisSession = false;
            final String id = nb.id, title = nb.title;
            final android.content.Context app = getApplicationContext();
            bg.execute(() -> {
                String err = AutoBackup.run(app, id, title);
                if (err != null) main.post(() -> Ui.toast(app, "Backup automático falhou: " + err));
            });
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        bg.shutdown();
    }
}
