package com.cadernolivre.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pastas da biblioteca (podem ser aninhadas). Guardadas em files/folders.json. */
final class Folders {
    static final class Folder {
        String id, name, parent = "";
        int color;
        long created;
    }

    private final Context c;
    final List<Folder> all = new ArrayList<>();

    Folders(Context c) {
        this.c = c;
        load();
    }

    private File file() { return new File(c.getFilesDir(), "folders.json"); }

    private void load() {
        all.clear();
        try {
            JSONArray arr = new JSONArray(Storage.read(file()));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Folder f = new Folder();
                f.id = o.getString("id");
                f.name = o.optString("name", "Pasta");
                f.parent = o.optString("parent", "");
                f.color = o.optInt("color", Ui.FOLDER_COLORS[0]);
                f.created = o.optLong("created");
                all.add(f);
            }
        } catch (Exception ignored) {
        }
        // Pastas cujo pai sumiu vão para a raiz.
        for (Folder f : all) if (!f.parent.isEmpty() && get(f.parent) == null) f.parent = "";
    }

    void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Folder f : all) {
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("parent", f.parent);
                o.put("color", f.color);
                o.put("created", f.created);
                arr.put(o);
            }
            Storage.writeAtomic(file(), arr.toString());
        } catch (Exception ignored) {
        }
    }

    Folder get(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Folder f : all) if (f.id.equals(id)) return f;
        return null;
    }

    boolean exists(String id) { return id == null || id.isEmpty() || get(id) != null; }

    List<Folder> children(String parent) {
        List<Folder> out = new ArrayList<>();
        for (Folder f : all) if (f.parent.equals(parent)) out.add(f);
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    Folder create(String name, String parent, int color) {
        Folder f = new Folder();
        f.id = "f" + Storage.newId();
        f.name = name;
        f.parent = parent == null ? "" : parent;
        f.color = color;
        f.created = System.currentTimeMillis();
        all.add(f);
        save();
        return f;
    }

    /** Ids da pasta e de todas as subpastas. */
    Set<String> subtree(String id) {
        Set<String> out = new HashSet<>();
        out.add(id);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Folder f : all) if (out.contains(f.parent) && out.add(f.id)) grew = true;
        }
        return out;
    }

    /** Remove a pasta; subpastas e cadernos sobem para a pasta-mãe. Devolve o id da mãe. */
    String remove(String id) {
        Folder f = get(id);
        if (f == null) return "";
        for (Folder x : all) if (x.parent.equals(id)) x.parent = f.parent;
        all.remove(f);
        save();
        return f.parent;
    }

    /** Caminho legível, ex.: "Biblioteca › Faculdade › Cálculo". */
    String path(String id) {
        List<String> parts = new ArrayList<>();
        Folder f = get(id);
        int guard = 0;
        while (f != null && guard++ < 50) {
            parts.add(0, f.name);
            f = get(f.parent);
        }
        StringBuilder sb = new StringBuilder("Biblioteca");
        for (String p : parts) sb.append("  ›  ").append(p);
        return sb.toString();
    }

    int depth(String id) {
        int d = 0;
        Folder f = get(id);
        while (f != null && d < 50) { d++; f = get(f.parent); }
        return d;
    }

    /** Lista em árvore (pré-ordem) para seletores de destino. */
    List<Folder> tree() {
        List<Folder> out = new ArrayList<>();
        addTree("", out);
        return out;
    }

    private void addTree(String parent, List<Folder> out) {
        for (Folder f : children(parent)) {
            out.add(f);
            addTree(f.id, out);
        }
    }
}
