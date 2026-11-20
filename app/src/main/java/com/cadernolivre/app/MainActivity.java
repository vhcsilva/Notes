package com.cadernolivre.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.TextView;

import java.io.File;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Biblioteca: pastas e cadernos. */
public class MainActivity extends Activity {
    private static final int REQ_IMPORT_PDF = 1, REQ_IMPORT_BACKUP = 2, REQ_EXPORT_BACKUP = 3, REQ_BACKUP_TREE = 4;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(60);
    private SharedPreferences prefs;
    private Folders folders;
    private List<Storage.Meta> all = new ArrayList<>();
    private final List<Object> shown = new ArrayList<>();
    private String currentFolder = "";
    private int sortMode;
    private boolean searching;

    private Adapter adapter;
    private EditText search;
    private TextView titleView, pathView;
    private ImageView backBtn;
    private LinearLayout emptyView, chipRow;
    private String backupId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Th.init(this);
        Th.apply(this, Th.bg);
        prefs = getSharedPreferences("library", MODE_PRIVATE);
        sortMode = prefs.getInt("sort", 0);
        currentFolder = prefs.getString("folder", "");
        folders = new Folders(this);
        if (!folders.exists(currentFolder)) currentFolder = "";

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Th.bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Cabeçalho
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 6), 0);
        backBtn = Ui.iconButton(this, "back", Th.on, v -> goUp());
        head.addView(backBtn);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titleView = Ui.text(this, "Biblioteca", 28, Th.on, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        pathView = Ui.text(this, "", 12, Th.on2, false);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        titles.addView(titleView);
        titles.addView(pathView);
        head.addView(titles, Ui.weight());
        head.addView(Ui.iconButton(this, "more", Th.on, v -> overflow()));
        col.addView(head);

        // Busca
        search = new EditText(this);
        search.setHint("Buscar em todos os cadernos e pastas");
        search.setHintTextColor(Th.on2);
        search.setTextColor(Th.on);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(Ui.round(Th.surface2, Ui.dp(this, 24)));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        Drawable si = Icons.of("search", Th.on2);
        si.setBounds(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
        search.setCompoundDrawablesRelative(si, null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(this, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        col.addView(search, Ui.lp(-1, -2, Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0));

        // Ordenação
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), 0);
        hs.addView(chipRow);
        col.addView(hs);

        FrameLayout content = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 150));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 18));
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 96));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((a, v, pos, id) -> {
            Object o = shown.get(pos);
            if (o instanceof Folders.Folder) openFolder(((Folders.Folder) o).id);
            else openNotebook(((Storage.Meta) o).id);
        });
        grid.setOnItemLongClickListener((a, v, pos, id) -> {
            Object o = shown.get(pos);
            if (o instanceof Folders.Folder) folderOptions((Folders.Folder) o);
            else notebookOptions((Storage.Meta) o);
            return true;
        });
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new LinearLayout(this);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyView.setPadding(Ui.dp(this, 40), Ui.dp(this, 70), Ui.dp(this, 40), 0);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -2));
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Botão flutuante
        ImageView fab = new ImageView(this);
        fab.setImageDrawable(Icons.of("plus", Th.onPrimary));
        int fp = Ui.dp(this, 16);
        fab.setPadding(fp, fp, fp, fp);
        fab.setBackground(Ui.ripple(Th.primary, Ui.dp(this, 18)));
        fab.setElevation(Ui.dp(this, 6));
        fab.setContentDescription("Criar");
        fab.setOnClickListener(v -> createSheet());
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58), Gravity.BOTTOM | Gravity.END);
        fl.setMargins(0, 0, Ui.dp(this, 20), Ui.dp(this, 24));
        root.addView(fab, fl);

        setContentView(root);
        handleIncoming(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIncoming(i);
    }

    /** Abre PDFs enviados por outros apps ("Abrir com" / "Compartilhar"). */
    private void handleIncoming(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) importPdf(u);
        i.setAction(null);
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    @Override public void onBackPressed() {
        if (search.getText().length() > 0) search.setText("");
        else if (!currentFolder.isEmpty()) goUp();
        else super.onBackPressed();
    }

    // ================================================================ navegação e listagem
    private void reload() {
        folders = new Folders(this);
        if (!folders.exists(currentFolder)) currentFolder = "";
        all = Storage.list(this);
        thumbs.evictAll();
        refresh();
    }

    private String folderOf(Storage.Meta m) { return folders.exists(m.folder) ? m.folder : ""; }

    private void openFolder(String id) {
        currentFolder = id;
        prefs.edit().putString("folder", id).apply();
        if (search.getText().length() > 0) search.setText("");
        else refresh();
    }

    private void goUp() {
        Folders.Folder f = folders.get(currentFolder);
        openFolder(f == null ? "" : f.parent);
    }

    private void refresh() {
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        searching = !q.isEmpty();
        shown.clear();
        List<Folders.Folder> fs = new ArrayList<>();
        List<Storage.Meta> ns = new ArrayList<>();
        if (searching) {
            for (Folders.Folder f : folders.all) if (f.name.toLowerCase(Locale.getDefault()).contains(q)) fs.add(f);
            for (Storage.Meta m : all) if (m.title.toLowerCase(Locale.getDefault()).contains(q)) ns.add(m);
        } else {
            fs.addAll(folders.children(currentFolder));
            for (Storage.Meta m : all) if (folderOf(m).equals(currentFolder)) ns.add(m);
        }
        if (sortMode == 1) Collections.sort(ns, (a, b) -> a.title.compareToIgnoreCase(b.title));
        else Collections.sort(ns, (a, b) -> Long.compare(b.updated, a.updated));
        shown.addAll(fs);
        shown.addAll(ns);

        Folders.Folder cf = folders.get(currentFolder);
        boolean inFolder = cf != null && !searching;
        backBtn.setVisibility(inFolder ? View.VISIBLE : View.GONE);
        titleView.setText(searching ? "Resultados" : cf == null ? "Biblioteca" : cf.name);
        titleView.setPadding(inFolder ? 0 : Ui.dp(this, 8), 0, 0, 0);
        pathView.setVisibility(inFolder ? View.VISIBLE : View.GONE);
        if (inFolder) pathView.setText(folders.path(cf.parent));
        pathView.setPadding(inFolder ? 0 : Ui.dp(this, 8), 0, 0, 0);

        chipRow.removeAllViews();
        chipRow.addView(Ui.chip(this, "Recentes", sortMode == 0, v -> setSort(0)));
        chipRow.addView(Ui.chip(this, "Nome A–Z", sortMode == 1, v -> setSort(1)));
        if (!searching) {
            TextView nf = Ui.chip(this, "＋ Pasta", false, v -> folderDialog(null));
            chipRow.addView(nf);
        }

        emptyView.removeAllViews();
        if (shown.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.addView(Icons.view(this, searching ? "search" : cf == null ? "notebook" : "folder", Th.on2, 56));
            String msg = searching ? "Nada encontrado para \"" + q + "\"."
                    : cf == null ? "Sua biblioteca está vazia.\nToque em ＋ para criar um caderno, uma nota rápida ou uma pasta, ou importe um PDF."
                    : "Esta pasta está vazia.\nToque em ＋ para criar algo aqui, ou mova cadernos para cá.";
            TextView t = Ui.text(this, msg, 15, Th.on2, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 14), 0, 0);
            emptyView.addView(t);
        } else emptyView.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void setSort(int s) {
        sortMode = s;
        prefs.edit().putInt("sort", s).apply();
        refresh();
    }

    private void openNotebook(String id) {
        startActivity(new Intent(this, NotebookActivity.class).putExtra(NotebookActivity.EXTRA_ID, id));
    }

    private void open(String type, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        try { startActivityForResult(i, req); } catch (Exception e) { Ui.toast(this, "Nenhum seletor de arquivos disponível."); }
    }

    // ================================================================ criação
    private void createSheet() {
        String where = currentFolder.isEmpty() ? "Na biblioteca" : "Em \"" + folders.get(currentFolder).name + "\"";
        new Ui.Sheet(this, "Criar")
                .item("notebook", "Novo caderno", where, false, this::newNotebookSheet)
                .item("note", "Nota rápida", "Uma página em branco, pronta para escrever", false, this::quickNote)
                .item("folder_plus", "Nova pasta", where, false, () -> folderDialog(null))
                .item("import", "Importar PDF", "Para ler e anotar", false, () -> open("application/pdf", REQ_IMPORT_PDF))
                .show();
    }

    private void quickNote() {
        Model.Notebook nb = new Model.Notebook();
        nb.id = Storage.newId();
        nb.title = "Nota " + new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date());
        nb.coverColor = 0;
        nb.folder = currentFolder;
        Model.Page p = new Model.Page();
        p.template = Model.T_DOTS;
        nb.pages.add(p);
        try {
            Storage.save(this, nb);
            openNotebook(nb.id);
        } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
    }

    private void newNotebookSheet() {
        final Ui.Sheet s = new Ui.Sheet(this, "Novo caderno");
        final EditText name = new EditText(this);
        name.setHint("Título");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText("Caderno " + (all.size() + 1));
        name.setSelectAllOnFocus(true);
        name.setTextColor(Th.on);
        s.view(wrap(name));
        s.view(label("Capa"));
        final int[] cover = {Model.COVER_COLORS[0]};
        final LinearLayout covers = new LinearLayout(this);
        HorizontalScrollView chs = new HorizontalScrollView(this);
        chs.setHorizontalScrollBarEnabled(false);
        covers.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        chs.addView(covers);
        buildCovers(covers, cover);
        s.view(chs);
        s.view(label("Papel"));
        final int[] paper = {1, 0};
        s.view(Pickers.paperPicker(this, paper));
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), 0);
        btns.addView(Ui.pill(this, "Criar caderno", true, v -> {
            Model.Notebook nb = new Model.Notebook();
            nb.id = Storage.newId();
            String t = name.getText().toString().trim();
            nb.title = t.isEmpty() ? "Sem título" : t;
            nb.coverColor = cover[0];
            nb.folder = currentFolder;
            Model.Page pg = new Model.Page();
            pg.template = Model.TEMPLATES[paper[0]];
            pg.paperColor = Model.PAPER_COLORS[paper[1]];
            nb.pages.add(pg);
            s.dismiss();
            try {
                Storage.save(this, nb);
                openNotebook(nb.id);
            } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
        }));
        s.view(btns);
        s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        s.show();
    }

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        f.addView(v);
        return f;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Th.on2, true);
        t.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 6));
        return t;
    }

    private void buildCovers(final LinearLayout row, final int[] sel) {
        row.removeAllViews();
        for (final int c : Model.COVER_COLORS) {
            CoverMini cm = new CoverMini(this, c, c == sel[0]);
            cm.setOnClickListener(v -> { sel[0] = c; buildCovers(row, sel); });
            row.addView(cm, Ui.lp(Ui.dp(this, 46), Ui.dp(this, 62), Ui.dp(this, 3), 0, Ui.dp(this, 3), 0));
        }
        CoverMini pageCover = new CoverMini(this, 0, sel[0] == 0);
        pageCover.setOnClickListener(v -> { sel[0] = 0; buildCovers(row, sel); });
        row.addView(pageCover, Ui.lp(Ui.dp(this, 46), Ui.dp(this, 62), Ui.dp(this, 3), 0, Ui.dp(this, 3), 0));
    }

    /** Miniatura de capa para o seletor. Cor 0 = "usar a primeira página". */
    static final class CoverMini extends View {
        private final int color;
        private final boolean sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CoverMini(Context c, int color, boolean sel) {
            super(c);
            this.color = color;
            this.sel = sel;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 6), in = Ui.dp(getContext(), 4);
            RectF body = new RectF(in, in, w - in, h - in);
            if (sel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(getContext(), 2.5f));
                p.setColor(Th.primary);
                c.drawRoundRect(new RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f), r + 2, r + 2, p);
            }
            p.setStyle(Paint.Style.FILL);
            if (color == 0) {
                p.setColor(Color.WHITE);
                c.drawRoundRect(body, r, r, p);
                p.setColor(0x334A6FA5);
                for (float y = body.top + body.height() * 0.3f; y < body.bottom - 4; y += body.height() / 8) c.drawRect(body.left + 4, y, body.right - 4, y + 1.5f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1);
                p.setColor(Th.outline);
                c.drawRoundRect(body, r, r, p);
            } else {
                p.setColor(color);
                c.drawRoundRect(body, r, r, p);
                p.setColor(0x33000000);
                c.drawRect(body.left + Ui.dp(getContext(), 5), body.top, body.left + Ui.dp(getContext(), 8), body.bottom, p);
            }
        }
    }

    // ================================================================ pastas
    private void folderDialog(final Folders.Folder existing) {
        final Ui.Sheet s = new Ui.Sheet(this, existing == null ? "Nova pasta" : "Editar pasta");
        final EditText name = new EditText(this);
        name.setHint("Nome da pasta");
        name.setSingleLine(true);
        name.setTextColor(Th.on);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(existing == null ? "" : existing.name);
        s.view(wrap(name));
        s.view(label("Cor"));
        final int[] color = {existing == null ? Ui.FOLDER_COLORS[(folders.all.size()) % Ui.FOLDER_COLORS.length] : existing.color};
        final LinearLayout row = new LinearLayout(this);
        row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        final Runnable[] build = new Runnable[1];
        build[0] = () -> {
            row.removeAllViews();
            for (final int c : Ui.FOLDER_COLORS) {
                View sw = Ui.swatch(this, c, c == color[0], 32);
                sw.setOnClickListener(v -> { color[0] = c; build[0].run(); });
                row.addView(sw);
            }
        };
        build[0].run();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        s.view(hs);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), 0);
        btns.addView(Ui.pill(this, existing == null ? "Criar pasta" : "Salvar", true, v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { name.setError("Digite um nome"); return; }
            if (existing == null) folders.create(n, currentFolder, color[0]);
            else {
                existing.name = n;
                existing.color = color[0];
                folders.save();
            }
            s.dismiss();
            refresh();
        }));
        s.view(btns);
        s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        s.show();
        name.requestFocus();
    }

    private void folderOptions(final Folders.Folder f) {
        new Ui.Sheet(this, f.name)
                .item("folder", "Abrir", () -> openFolder(f.id))
                .item("edit", "Renomear / mudar cor", () -> folderDialog(f))
                .item("move", "Mover para…", () -> chooseDestination("Mover \"" + f.name + "\" para", folders.subtree(f.id), dest -> {
                    f.parent = dest;
                    folders.save();
                    refresh();
                }))
                .item("trash", "Excluir pasta", "Os cadernos dentro dela não são apagados", true, () -> {
                    Folders.Folder parent = folders.get(f.parent);
                    String pname = parent == null ? "a biblioteca" : "\"" + parent.name + "\"";
                    Ui.confirm(this, "Excluir a pasta \"" + f.name + "\"?",
                            "Subpastas e cadernos dentro dela serão movidos para " + pname + ".", "Excluir", () -> {
                                final String up = folders.remove(f.id);
                                final List<String> ids = new ArrayList<>();
                                for (Storage.Meta m : all) if (f.id.equals(m.folder)) ids.add(m.id);
                                bg.execute(() -> {
                                    for (String id : ids) try { Storage.edit(this, id, nb -> nb.folder = up); } catch (Exception ignored) {}
                                    main.post(this::reload);
                                });
                                if (currentFolder.equals(f.id)) currentFolder = up;
                                refresh();
                            });
                })
                .show();
    }

    interface Dest { void on(String folderId); }

    /** Seletor de pasta de destino, em árvore. */
    private void chooseDestination(String title, final Set<String> exclude, final Dest cb) {
        final Ui.Sheet s = new Ui.Sheet(this, title);
        s.item("notebook", "Biblioteca (raiz)", () -> cb.on(""));
        for (final Folders.Folder f : folders.tree()) {
            if (exclude != null && exclude.contains(f.id)) continue;
            int depth = folders.depth(f.id) - 1;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 24 + depth * 22), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12));
            row.setBackground(Ui.ripple(0, 0));
            row.addView(Icons.view(this, "folder_fill", f.color, 24));
            TextView t = Ui.text(this, f.name, 15, Th.on, false);
            t.setPadding(Ui.dp(this, 18), 0, 0, 0);
            row.addView(t);
            row.setOnClickListener(v -> { s.dismiss(); cb.on(f.id); });
            s.view(row);
        }
        s.item("folder_plus", "Nova pasta aqui…", () -> Ui.prompt(this, "Nova pasta", "", n -> {
            if (n.isEmpty()) return;
            Folders.Folder nf = folders.create(n, "", Ui.FOLDER_COLORS[folders.all.size() % Ui.FOLDER_COLORS.length]);
            cb.on(nf.id);
        }));
        s.show();
    }

    // ================================================================ opções de caderno
    private void notebookOptions(final Storage.Meta m) {
        String where = folders.path(folderOf(m));
        new Ui.Sheet(this, m.title)
                .item("notebook", "Abrir", where, false, () -> openNotebook(m.id))
                .item("edit", "Renomear", () -> Ui.prompt(this, "Renomear", m.title, s -> {
                    if (s.isEmpty()) return;
                    try { Storage.edit(this, m.id, nb -> nb.title = s); reload(); } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
                }))
                .item("move", "Mover para pasta…", () -> chooseDestination("Mover \"" + m.title + "\" para", null, dest -> {
                    try {
                        Storage.edit(this, m.id, nb -> nb.folder = dest);
                        reload();
                        Folders.Folder f = folders.get(dest);
                        Ui.toast(this, "Movido para " + (f == null ? "a biblioteca" : "\"" + f.name + "\"") + ".");
                    } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
                }))
                .item("palette", "Mudar capa", () -> changeCover(m))
                .item("copy", "Duplicar", () -> {
                    try { Storage.duplicate(this, m.id); reload(); } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
                })
                .item("export", "Fazer backup (.zip)", () -> {
                    backupId = m.id;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_TITLE, NotebookActivity.safeName(m.title) + ".zip");
                    startActivityForResult(i, REQ_EXPORT_BACKUP);
                })
                .item("trash", "Excluir", null, true, () -> Ui.confirm(this, "Excluir \"" + m.title + "\"?",
                        "O caderno e todas as suas páginas, imagens e gravações serão apagados.", "Excluir",
                        () -> { Storage.delete(this, m.id); reload(); }))
                .show();
    }

    private void changeCover(final Storage.Meta m) {
        final int[] sel = {m.cover};
        final Ui.Sheet s = new Ui.Sheet(this, "Capa");
        final LinearLayout row = new LinearLayout(this);
        row.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        buildCovers(row, sel);
        s.view(hs);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), 0);
        btns.addView(Ui.pill(this, "Aplicar", true, v -> {
            s.dismiss();
            try { Storage.edit(this, m.id, nb -> nb.coverColor = sel[0]); reload(); } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
        }));
        s.view(btns).show();
    }

    // ================================================================ menu geral
    private void overflow() {
        String bk = AutoBackup.folderName(this);
        new Ui.Sheet(this, "Caderno Livre")
                .item("cloud", "Backup automático", bk == null ? "Desativado. Toque para escolher uma pasta (ex.: Google Drive)"
                        : "Ativado em \"" + bk + "\"", false, this::autoBackupSheet)
                .item("import", "Restaurar backup (.zip)", () -> open("*/*", REQ_IMPORT_BACKUP))
                .item("info", "Sobre e dicas", this::about)
                .show();
    }

    private void autoBackupSheet() {
        Ui.Sheet s = new Ui.Sheet(this, "Backup automático");
        TextView t = Ui.text(this, "Ao fechar um caderno alterado, uma cópia .zip é salva na pasta escolhida. "
                + "Escolha uma pasta do Google Drive, OneDrive ou Dropbox para ter backup na nuvem.", 13, Th.on2, false);
        t.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 8));
        s.view(t);
        s.item("folder", AutoBackup.folder(this) == null ? "Escolher pasta" : "Trocar pasta", () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { startActivityForResult(i, REQ_BACKUP_TREE); } catch (Exception e) { Ui.toast(this, "Seletor de pastas indisponível."); }
        });
        if (AutoBackup.folder(this) != null) {
            s.item("export", "Fazer backup de tudo agora", this::backupAllNow);
            s.item("close", "Desativar", null, true, () -> { AutoBackup.setFolder(this, null); Ui.toast(this, "Backup automático desativado."); });
        }
        s.show();
    }

    private void backupAllNow() {
        final List<Storage.Meta> list = new ArrayList<>(all);
        Ui.toast(this, "Fazendo backup de " + list.size() + " cadernos…");
        final Context app = getApplicationContext();
        bg.execute(() -> {
            int fails = 0;
            for (Storage.Meta m : list) if (AutoBackup.run(app, m.id, m.title) != null) fails++;
            final int f = fails;
            main.post(() -> Ui.toast(app, f == 0 ? "Backup concluído." : "Backup concluído com " + f + " falha(s)."));
        });
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Caderno Livre")
                .setMessage("Caderno digital para escrita à mão, anotação de PDFs e gravação de áudio.\n\n"
                        + "Gratuito, sem anúncios, sem conta. Seus cadernos ficam no aparelho; use o backup automático "
                        + "para guardar cópias numa pasta do Drive ou outro serviço.\n\n"
                        + "A conversão de escrita em texto baixa um modelo de idioma do Google na primeira vez e depois "
                        + "funciona offline, no próprio aparelho.\n\n"
                        + "Dicas:\n"
                        + "• Dois dedos: zoom e rolagem\n"
                        + "• Toque de novo numa ferramenta para mudar cor e espessura\n"
                        + "• Botão da S Pen apaga\n"
                        + "• Modo só caneta: o dedo rola a página sem riscar\n"
                        + "• Laço → botão de texto: converte escrita em texto digitado\n"
                        + "• Toque e segure um caderno ou pasta para mais opções")
                .setPositiveButton("OK", null).show();
    }

    // ================================================================ importação / exportação
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_IMPORT_PDF) importPdf(uri);
        else if (req == REQ_BACKUP_TREE) {
            try {
                AutoBackup.setFolder(this, uri);
                Ui.toast(this, "Backup automático ativado em \"" + AutoBackup.folderName(this) + "\".");
                backupAllNow();
            } catch (Exception e) { Ui.toast(this, "Não foi possível usar essa pasta: " + e.getMessage()); }
        } else if (req == REQ_IMPORT_BACKUP) {
            final String dest = currentFolder;
            bg.execute(() -> {
                try {
                    final String id = Storage.importZip(this, getContentResolver().openInputStream(uri));
                    Storage.edit(this, id, nb -> nb.folder = dest);
                    main.post(() -> { reload(); Ui.toast(this, "Backup restaurado."); });
                } catch (Throwable e) {
                    main.post(() -> Ui.toast(this, "Não foi possível restaurar: " + e.getMessage()));
                }
            });
        } else if (req == REQ_EXPORT_BACKUP && backupId != null) {
            final String id = backupId;
            bg.execute(() -> {
                String msg;
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    try { Storage.exportZip(this, id, os); } finally { os.close(); }
                    msg = "Backup salvo.";
                } catch (Throwable e) { msg = "Falha no backup: " + e.getMessage(); }
                final String m = msg;
                main.post(() -> Ui.toast(this, m));
            });
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {}
        if (name == null) name = "Documento";
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        return name;
    }

    private void importPdf(final Uri uri) {
        Ui.toast(this, "Importando PDF…");
        final String title = displayName(uri);
        final String folder = currentFolder;
        bg.execute(() -> {
            String id = Storage.newId();
            try {
                File d = Storage.dir(this, id);
                d.mkdirs();
                String file = "doc_" + System.currentTimeMillis() + ".pdf";
                File f = new File(d, file);
                Storage.copyToFile(getContentResolver().openInputStream(uri), f);
                float[] ratios = PdfHelper.pageRatios(f);
                Model.Notebook nb = new Model.Notebook();
                nb.id = id;
                nb.title = title;
                nb.coverColor = 0;
                nb.folder = folder;
                for (int i = 0; i < ratios.length; i++) {
                    Model.Page p = new Model.Page();
                    p.pdf = file;
                    p.pdfIndex = i;
                    p.height = Model.PAGE_W * ratios[i];
                    nb.pages.add(p);
                }
                if (nb.pages.isEmpty()) throw new Exception("PDF vazio");
                Storage.save(this, nb);
                main.post(() -> openNotebook(id));
            } catch (Throwable e) {
                Storage.delete(this, id);
                main.post(() -> Ui.toast(this, "Não foi possível importar o PDF (talvez protegido por senha)."));
            }
        });
    }

    // ================================================================ grade
    private int countIn(String folderId) {
        int n = folders.children(folderId).size();
        for (Storage.Meta m : all) if (folderId.equals(folderOf(m))) n++;
        return n;
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 1; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Context c = MainActivity.this;
            LinearLayout card;
            CoverView cover;
            TextView t1, t2;
            if (convert == null) {
                card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                cover = new CoverView(c);
                card.addView(cover, new LinearLayout.LayoutParams(-1, Ui.dp(c, 196)));
                t1 = Ui.text(c, "", 14, Th.on, true);
                t1.setMaxLines(2);
                t1.setEllipsize(TextUtils.TruncateAt.END);
                t1.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), 0);
                card.addView(t1);
                t2 = Ui.text(c, "", 12, Th.on2, false);
                t2.setSingleLine(true);
                t2.setEllipsize(TextUtils.TruncateAt.END);
                t2.setPadding(Ui.dp(c, 4), Ui.dp(c, 1), Ui.dp(c, 4), 0);
                card.addView(t2);
            } else {
                card = (LinearLayout) convert;
                cover = (CoverView) card.getChildAt(0);
                t1 = (TextView) card.getChildAt(1);
                t2 = (TextView) card.getChildAt(2);
            }
            Object o = shown.get(pos);
            if (o instanceof Folders.Folder) {
                Folders.Folder f = (Folders.Folder) o;
                int n = countIn(f.id);
                t1.setText(f.name);
                t2.setText(searching ? folders.path(f.parent) : n == 0 ? "Vazia" : n + (n == 1 ? " item" : " itens"));
                cover.setFolder(f, n);
            } else {
                Storage.Meta m = (Storage.Meta) o;
                t1.setText(m.title);
                String date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(m.updated));
                t2.setText(searching ? folders.path(folderOf(m)) : m.pages + (m.pages == 1 ? " pág. · " : " págs. · ") + date);
                cover.setNotebook(m, m.cover == 0 ? thumb(m) : null);
            }
            return card;
        }
    }

    private Bitmap thumb(Storage.Meta m) {
        Bitmap b = thumbs.get(m.id);
        if (b == null) {
            File f = new File(Storage.dir(this, m.id), "thumb.png");
            if (f.exists()) {
                b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) thumbs.put(m.id, b);
            }
        }
        return b;
    }

    /** Capa: caderno colorido com lombada e etiqueta, miniatura da 1ª página, ou pasta. */
    static final class CoverView extends View {
        private Storage.Meta meta;
        private Folders.Folder folder;
        private int count;
        private Bitmap thumb;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        CoverView(Context c) { super(c); }

        void setNotebook(Storage.Meta m, Bitmap t) { meta = m; folder = null; thumb = t; invalidate(); }
        void setFolder(Folders.Folder f, int n) { folder = f; meta = null; count = n; thumb = null; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 10);
            float d = Ui.dp(getContext(), 1);
            if (folder != null) { drawFolder(c, w, h, r); return; }
            if (meta == null) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Th.dark ? 0x55000000 : 0x1A000000);
            c.drawRoundRect(new RectF(2 * d, 4 * d, w, h), r, r, p);
            RectF body = new RectF(0, 0, w - 2 * d, h - 4 * d);
            if (meta.cover == 0) {
                p.setColor(Color.WHITE);
                c.drawRoundRect(body, r, r, p);
                if (thumb != null) {
                    c.save();
                    Path clip = new Path();
                    clip.addRoundRect(body, r, r, Path.Direction.CW);
                    c.clipPath(clip);
                    float s = Math.max(body.width() / thumb.getWidth(), body.height() / thumb.getHeight());
                    float tw = thumb.getWidth() * s, th = thumb.getHeight() * s;
                    c.drawBitmap(thumb, null, new RectF((body.width() - tw) / 2, 0, (body.width() + tw) / 2, th), p);
                    c.restore();
                } else {
                    Drawable ic = Icons.of("doc", 0xFFB0B4C0);
                    int is = Ui.dp(getContext(), 40);
                    ic.setBounds((int) (w - is) / 2, (int) (h - is) / 2, (int) (w + is) / 2, (int) (h + is) / 2);
                    ic.draw(c);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(d);
                p.setColor(Th.outline);
                c.drawRoundRect(body, r, r, p);
                return;
            }
            p.setColor(meta.cover);
            c.drawRoundRect(body, r, r, p);
            // brilho suave
            p.setColor(0x14FFFFFF);
            c.drawRoundRect(new RectF(body.left, body.top, body.right, body.height() * 0.45f), r, r, p);
            // lombada
            p.setColor(0x30000000);
            c.drawRect(Ui.dp(getContext(), 12), 0, Ui.dp(getContext(), 18), body.bottom, p);
            // etiqueta
            float lw = body.width() * 0.66f, lh = Ui.dp(getContext(), 44);
            float lx = (body.width() - lw) / 2 + Ui.dp(getContext(), 7);
            RectF label = new RectF(lx, body.height() * 0.2f, lx + lw, body.height() * 0.2f + lh);
            p.setColor(0xF5FFFFFF);
            c.drawRoundRect(label, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), p);
            tp.setColor(0xFF2A2B33);
            tp.setTextSize(Ui.dp(getContext(), 12.5f));
            tp.setTextAlign(Paint.Align.CENTER);
            String t = TextUtils.ellipsize(meta.title, tp, lw - Ui.dp(getContext(), 12), TextUtils.TruncateAt.END).toString();
            c.drawText(t, label.centerX(), label.centerY() + tp.getTextSize() / 3, tp);
        }

        private void drawFolder(Canvas c, float w, float h, float r) {
            p.setStyle(Paint.Style.FILL);
            int col = folder.color;
            p.setColor((col & 0x00FFFFFF) | (Th.dark ? 0x40000000 : 0x26000000));
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            // desenho de pasta
            float fw = w * 0.62f, fh = fw * 0.74f;
            float fx = (w - fw) / 2, fy = (h - fh) / 2 - Ui.dp(getContext(), 6);
            Path back = new Path();
            back.moveTo(fx, fy + fh * 0.12f);
            back.lineTo(fx, fy);
            back.lineTo(fx + fw * 0.38f, fy);
            back.lineTo(fx + fw * 0.48f, fy + fh * 0.12f);
            back.close();
            p.setColor(darken(col));
            c.drawPath(back, p);
            c.drawRoundRect(new RectF(fx, fy + fh * 0.1f, fx + fw, fy + fh * 0.5f), Ui.dp(getContext(), 5), Ui.dp(getContext(), 5), p);
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(new RectF(fx + fw * 0.06f, fy + fh * 0.18f, fx + fw * 0.94f, fy + fh * 0.6f), 4, 4, p);
            p.setColor(col);
            c.drawRoundRect(new RectF(fx, fy + fh * 0.28f, fx + fw, fy + fh), Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), p);
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(Ui.dp(getContext(), 13));
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setFakeBoldText(true);
            if (count > 0) c.drawText(String.valueOf(count), fx + fw / 2, fy + fh * 0.72f, tp);
        }

        private static int darken(int c) {
            return Color.rgb((int) (Color.red(c) * 0.78f), (int) (Color.green(c) * 0.78f), (int) (Color.blue(c) * 0.78f));
        }
    }
}
