package com.cadernolivre.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Armazenamento local: cada caderno é uma pasta com notebook.json, meta.json, PDFs, imagens e áudios. */
public final class Storage {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Storage() {}

    public static class Meta {
        public String id, title, folder = "";
        public int cover, pages;
        public long updated;
    }

    public static File root(Context c) {
        File f = new File(c.getFilesDir(), "notebooks");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File dir(Context c, String id) { return new File(root(c), id); }

    public static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(46656), 36);
    }

    public static List<Meta> list(Context c) {
        List<Meta> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            try {
                JSONObject o = new JSONObject(read(new File(d, "meta.json")));
                Meta m = new Meta();
                m.id = d.getName();
                m.title = o.optString("title");
                m.cover = o.optInt("cover");
                m.pages = o.optInt("pages");
                m.updated = o.optLong("updated");
                m.folder = o.optString("folder", "");
                out.add(m);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    public static Model.Notebook load(Context c, String id) throws Exception {
        return Model.Notebook.fromJson(id, new JSONObject(read(new File(dir(c, id), "notebook.json"))));
    }

    /** Serializa no thread atual (deve ser o principal) e devolve o texto pronto para gravar. */
    public static String[] serialize(Model.Notebook nb) throws Exception {
        nb.updated = System.currentTimeMillis();
        if (nb.created == 0) nb.created = nb.updated;
        JSONObject meta = new JSONObject();
        meta.put("title", nb.title);
        meta.put("cover", nb.coverColor);
        meta.put("pages", nb.pages.size());
        meta.put("updated", nb.updated);
        meta.put("folder", nb.folder);
        return new String[]{nb.toJson().toString(), meta.toString()};
    }

    public static void write(Context c, String id, String[] data) throws IOException {
        File d = dir(c, id);
        d.mkdirs();
        writeAtomic(new File(d, "notebook.json"), data[0]);
        writeAtomic(new File(d, "meta.json"), data[1]);
    }

    public static void save(Context c, Model.Notebook nb) throws Exception {
        write(c, nb.id, serialize(nb));
    }

    public static void delete(Context c, String id) { deleteRec(dir(c, id)); }

    /** Altera dados do caderno sem mudar a data de modificação. */
    public interface Edit { void apply(Model.Notebook nb); }

    public static void edit(Context c, String id, Edit e) throws Exception {
        Model.Notebook nb = load(c, id);
        long upd = nb.updated;
        e.apply(nb);
        String[] data = serialize(nb);
        nb.updated = upd;
        JSONObject meta = new JSONObject(data[1]);
        meta.put("updated", upd);
        JSONObject full = new JSONObject(data[0]);
        full.put("updated", upd);
        write(c, id, new String[]{full.toString(), meta.toString()});
    }

    public static String duplicate(Context c, String id) throws Exception {
        String nid = newId();
        copyDir(dir(c, id), dir(c, nid));
        Model.Notebook nb = load(c, nid);
        nb.title = nb.title + " (cópia)";
        nb.created = 0;
        save(c, nb);
        return nid;
    }

    // ---------------------------------------------------------------- Backup (.zip)
    public static void exportZip(Context c, String id, OutputStream os) throws IOException {
        File d = dir(c, id);
        ZipOutputStream zos = new ZipOutputStream(os);
        zipDir(d, "", zos);
        zos.finish();
        zos.flush();
    }

    private static void zipDir(File d, String prefix, ZipOutputStream zos) throws IOException {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String name = prefix + f.getName();
            if (f.isDirectory()) zipDir(f, name + "/", zos);
            else {
                zos.putNextEntry(new ZipEntry(name));
                InputStream in = new FileInputStream(f);
                try { copy(in, zos); } finally { in.close(); }
                zos.closeEntry();
            }
        }
    }

    /** Importa um backup; devolve o id do novo caderno. */
    public static String importZip(Context c, InputStream is) throws Exception {
        String nid = newId();
        File d = dir(c, nid);
        d.mkdirs();
        String canon = d.getCanonicalPath() + File.separator;
        ZipInputStream zis = new ZipInputStream(is);
        ZipEntry e;
        boolean ok = false;
        while ((e = zis.getNextEntry()) != null) {
            File out = new File(d, e.getName());
            if (!out.getCanonicalPath().startsWith(canon)) continue; // proteção contra zip-slip
            if (e.isDirectory()) { out.mkdirs(); continue; }
            out.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(out);
            try { copy(zis, os); } finally { os.close(); }
            if (e.getName().equals("notebook.json")) ok = true;
        }
        if (!ok) {
            deleteRec(d);
            throw new IOException("Arquivo de backup inválido");
        }
        Model.Notebook nb = load(c, nid);
        save(c, nb);
        return nid;
    }

    // ---------------------------------------------------------------- utilidades
    public static String read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            copy(in, bos);
            return new String(bos.toByteArray(), UTF8);
        } finally { in.close(); }
    }

    static void writeAtomic(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(s.getBytes(UTF8));
            fos.getFD().sync();
        } finally { fos.close(); }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Falha ao salvar " + f.getName());
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    public static void copyToFile(InputStream in, File f) throws IOException {
        f.getParentFile().mkdirs();
        OutputStream os = new FileOutputStream(f);
        try { copy(in, os); } finally { os.close(); in.close(); }
    }

    static void copyDir(File src, File dst) throws IOException {
        dst.mkdirs();
        File[] fs = src.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            File t = new File(dst, f.getName());
            if (f.isDirectory()) copyDir(f, t);
            else copyToFile(new FileInputStream(f), t);
        }
    }

    public static void deleteRec(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteRec(x);
        f.delete();
    }
}
