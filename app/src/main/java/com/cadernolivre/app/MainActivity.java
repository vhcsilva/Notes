package com.cadernolivre.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Biblioteca de cadernos. */
public class MainActivity extends Activity {
    private static final int REQ_IMPORT_PDF = 1, REQ_IMPORT_BACKUP = 2, REQ_EXPORT_BACKUP = 3;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(40);
    private List<Storage.Meta> all = new ArrayList<>();
    private final List<Storage.Meta> shown = new ArrayList<>();
    private Adapter adapter;
    private EditText search;
    private TextView empty;
    private String backupId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.PRIMARY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F4F7);

        LinearLayout top = new LinearLayout(this);
        top.setBackgroundColor(Ui.PRIMARY);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        TextView title = new TextView(this);
        title.setText("Caderno Livre");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        top.addView(title, Ui.weight());
        top.addView(Ui.button(this, "＋ Novo", v -> newNotebookDialog()));
        top.addView(Ui.button(this, "PDF", v -> open("application/pdf", REQ_IMPORT_PDF)));
        final TextView more = Ui.button(this, "⋮", null);
        more.setTextSize(22);
        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, more);
            Menu m = pm.getMenu();
            m.add(0, 1, 0, "Importar PDF como caderno");
            m.add(0, 2, 0, "Restaurar backup (.zip)");
            m.add(0, 3, 0, "Sobre");
            pm.setOnMenuItemClickListener(it -> {
                if (it.getItemId() == 1) open("application/pdf", REQ_IMPORT_PDF);
                else if (it.getItemId() == 2) open("*/*", REQ_IMPORT_BACKUP);
                else about();
                return true;
            });
            pm.show();
        });
        top.addView(more);
        root.addView(top);

        search = new EditText(this);
        search.setHint("🔎  Buscar cadernos");
        search.setSingleLine(true);
        search.setBackground(Ui.round(Color.WHITE, Ui.dp(this, 22)));
        search.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 4));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        root.addView(search, slp);

        empty = new TextView(this);
        empty.setText("Nenhum caderno ainda.\nToque em ＋ Novo para criar um, ou em PDF para importar um documento e anotar.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(16);
        empty.setTextColor(0xFF777777);
        empty.setPadding(Ui.dp(this, 32), Ui.dp(this, 60), Ui.dp(this, 32), 0);
        root.addView(empty);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 150));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setHorizontalSpacing(Ui.dp(this, 10));
        grid.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 20));
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((a, v, pos, id) -> openNotebook(shown.get(pos).id));
        grid.setOnItemLongClickListener((a, v, pos, id) -> { options(shown.get(pos)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        handleIncoming(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIncoming(i);
    }

    /** Abre PDFs compartilhados ou enviados ao app a partir de outros apps. */
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

    private void reload() {
        all = Storage.list(this);
        thumbs.evictAll();
        filter();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (Storage.Meta m : all) if (q.isEmpty() || m.title.toLowerCase(Locale.getDefault()).contains(q)) shown.add(m);
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
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

    // ---------------------------------------------------------------- novo caderno
    private void newNotebookDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        final EditText name = new EditText(this);
        name.setHint("Título");
        name.setSingleLine(true);
        name.setText("Caderno " + (all.size() + 1));
        name.setSelectAllOnFocus(true);
        box.addView(name);
        TextView l0 = new TextView(this); l0.setText("Capa"); l0.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(l0);
        final int[] cover = {Model.COVER_COLORS[0]};
        final GridLayout covers = new GridLayout(this);
        covers.setColumnCount(6);
        buildCovers(covers, cover);
        box.addView(covers);
        TextView l1 = new TextView(this); l1.setText("Modelo de página"); l1.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(l1);
        final Spinner tpl = new Spinner(this);
        tpl.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Model.TEMPLATE_NAMES));
        tpl.setSelection(1);
        box.addView(tpl);
        TextView l2 = new TextView(this); l2.setText("Cor do papel");
        box.addView(l2);
        final Spinner paper = new Spinner(this);
        paper.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Model.PAPER_NAMES));
        box.addView(paper);
        new AlertDialog.Builder(this).setTitle("Novo caderno").setView(box)
                .setPositiveButton("Criar", (d, w) -> {
                    Model.Notebook nb = new Model.Notebook();
                    nb.id = Storage.newId();
                    String t = name.getText().toString().trim();
                    nb.title = t.isEmpty() ? "Sem título" : t;
                    nb.coverColor = cover[0];
                    Model.Page pg = new Model.Page();
                    pg.template = Model.TEMPLATES[tpl.getSelectedItemPosition()];
                    pg.paperColor = Model.PAPER_COLORS[paper.getSelectedItemPosition()];
                    nb.pages.add(pg);
                    try {
                        Storage.save(this, nb);
                        openNotebook(nb.id);
                    } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void buildCovers(final GridLayout g, final int[] sel) {
        g.removeAllViews();
        for (final int c : Model.COVER_COLORS) {
            View s = Ui.swatch(this, c, c == sel[0], 34);
            s.setOnClickListener(v -> { sel[0] = c; buildCovers(g, sel); });
            g.addView(s);
        }
        TextView pageCover = new TextView(this);
        pageCover.setText(sel[0] == 0 ? "✓ 1ª página" : "1ª página");
        pageCover.setTextSize(12);
        pageCover.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
        pageCover.setOnClickListener(v -> { sel[0] = 0; buildCovers(g, sel); });
        g.addView(pageCover);
    }

    // ---------------------------------------------------------------- opções
    private void options(final Storage.Meta m) {
        String[] opts = {"Abrir", "Renomear", "Mudar capa", "Duplicar", "Fazer backup (.zip)", "Excluir"};
        new AlertDialog.Builder(this).setTitle(m.title).setItems(opts, (d, w) -> {
            try {
                switch (w) {
                    case 0: openNotebook(m.id); break;
                    case 1:
                        Ui.prompt(this, "Renomear", m.title, s -> {
                            if (s.isEmpty()) return;
                            try {
                                Model.Notebook nb = Storage.load(this, m.id);
                                nb.title = s;
                                Storage.save(this, nb);
                                reload();
                            } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
                        });
                        break;
                    case 2: changeCover(m); break;
                    case 3: Storage.duplicate(this, m.id); reload(); break;
                    case 4: {
                        backupId = m.id;
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("application/zip");
                        i.putExtra(Intent.EXTRA_TITLE, NotebookActivity.safeName(m.title) + ".zip");
                        startActivityForResult(i, REQ_EXPORT_BACKUP);
                        break;
                    }
                    case 5:
                        new AlertDialog.Builder(this).setTitle("Excluir \"" + m.title + "\"?")
                                .setMessage("O caderno e todas as suas páginas, imagens e gravações serão apagados.")
                                .setPositiveButton("Excluir", (x, y) -> { Storage.delete(this, m.id); reload(); })
                                .setNegativeButton("Cancelar", null).show();
                        break;
                }
            } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
        }).show();
    }

    private void changeCover(final Storage.Meta m) {
        final int[] sel = {m.cover};
        final GridLayout g = new GridLayout(this);
        g.setColumnCount(6);
        int p = Ui.dp(this, 20);
        g.setPadding(p, p / 2, p, 0);
        buildCovers(g, sel);
        new AlertDialog.Builder(this).setTitle("Capa").setView(g).setPositiveButton("OK", (d, w) -> {
            try {
                Model.Notebook nb = Storage.load(this, m.id);
                nb.coverColor = sel[0];
                Storage.save(this, nb);
                reload();
            } catch (Exception e) { Ui.toast(this, "Erro: " + e.getMessage()); }
        }).setNegativeButton("Cancelar", null).show();
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Caderno Livre")
                .setMessage("Caderno digital para escrita à mão, anotação de PDFs e gravação de áudio.\n\n"
                        + "100% gratuito, sem anúncios, sem conta e sem coleta de dados. Tudo fica salvo no seu aparelho; "
                        + "use \"Fazer backup (.zip)\" para guardar cópias onde quiser (Drive, e-mail, etc.).\n\n"
                        + "Dicas:\n• Dois dedos: zoom e rolagem\n• Toque de novo numa ferramenta para ajustar cor e espessura\n"
                        + "• Botão da caneta (S Pen) apaga\n• Modo só caneta: o dedo rola sem riscar")
                .setPositiveButton("OK", null).show();
    }

    // ---------------------------------------------------------------- importação / exportação
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_IMPORT_PDF) importPdf(uri);
        else if (req == REQ_IMPORT_BACKUP) {
            bg.execute(() -> {
                try {
                    final String id = Storage.importZip(this, getContentResolver().openInputStream(uri));
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

    // ---------------------------------------------------------------- grade
    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

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
                card.addView(cover, new LinearLayout.LayoutParams(-1, Ui.dp(c, 200)));
                t1 = new TextView(c);
                t1.setTextSize(15);
                t1.setTextColor(0xFF222222);
                t1.setMaxLines(2);
                t1.setEllipsize(TextUtils.TruncateAt.END);
                t1.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4), 0);
                card.addView(t1);
                t2 = new TextView(c);
                t2.setTextSize(12);
                t2.setTextColor(0xFF888888);
                t2.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
                card.addView(t2);
            } else {
                card = (LinearLayout) convert;
                cover = (CoverView) card.getChildAt(0);
                t1 = (TextView) card.getChildAt(1);
                t2 = (TextView) card.getChildAt(2);
            }
            Storage.Meta m = shown.get(pos);
            t1.setText(m.title);
            t2.setText(m.pages + (m.pages == 1 ? " página" : " páginas") + " · "
                    + DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(m.updated)));
            cover.set(m, m.cover == 0 ? thumb(m) : null);
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

    /** Capa desenhada: cor com lombada e etiqueta, ou miniatura da primeira página. */
    static class CoverView extends View {
        private Storage.Meta meta;
        private Bitmap thumb;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        CoverView(Context c) { super(c); }

        void set(Storage.Meta m, Bitmap t) { meta = m; thumb = t; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (meta == null) return;
            float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 8);
            p.setColor(0x26000000);
            c.drawRoundRect(new RectF(3, 5, w, h), r, r, p);
            RectF body = new RectF(0, 0, w - 3, h - 5);
            if (meta.cover == 0) {
                p.setColor(Color.WHITE);
                c.drawRoundRect(body, r, r, p);
                if (thumb != null) {
                    float s = Math.min(body.width() / thumb.getWidth(), body.height() / thumb.getHeight());
                    float tw = thumb.getWidth() * s, th = thumb.getHeight() * s;
                    float l = (body.width() - tw) / 2, t = (body.height() - th) / 2;
                    c.drawBitmap(thumb, null, new RectF(l, t, l + tw, t + th), p);
                } else {
                    p.setColor(0xFFBBBBBB);
                    p.setTextSize(Ui.dp(getContext(), 30));
                    p.setTextAlign(Paint.Align.CENTER);
                    c.drawText("📄", w / 2, h / 2, p);
                }
                return;
            }
            p.setColor(meta.cover);
            c.drawRoundRect(body, r, r, p);
            p.setColor(0x33000000);
            c.drawRect(Ui.dp(getContext(), 10), 0, Ui.dp(getContext(), 16), body.bottom, p);
            float lw = body.width() * 0.7f, lh = Ui.dp(getContext(), 48);
            RectF label = new RectF((body.width() - lw) / 2 + Ui.dp(getContext(), 6), body.height() * 0.22f,
                    (body.width() + lw) / 2 + Ui.dp(getContext(), 6), body.height() * 0.22f + lh);
            p.setColor(0xF2FFFFFF);
            c.drawRoundRect(label, r / 2, r / 2, p);
            p.setColor(0xFF333333);
            p.setTextSize(Ui.dp(getContext(), 13));
            p.setTextAlign(Paint.Align.CENTER);
            String t = TextUtils.ellipsize(meta.title, new android.text.TextPaint(p), lw - Ui.dp(getContext(), 12),
                    TextUtils.TruncateAt.END).toString();
            c.drawText(t, label.centerX(), label.centerY() + p.getTextSize() / 3, p);
        }
    }
}
