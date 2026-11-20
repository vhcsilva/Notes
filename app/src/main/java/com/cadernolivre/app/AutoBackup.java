package com.cadernolivre.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.OutputStream;

/**
 * Backup automático: ao fechar um caderno, grava um .zip dele numa pasta escolhida pelo usuário.
 * Se a pasta estiver no Google Drive, OneDrive, Dropbox etc., isso vira um backup na nuvem.
 */
final class AutoBackup {
    private static final String PREF = "autobackup", KEY = "tree";

    private AutoBackup() {}

    static Uri folder(Context c) {
        String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null);
        return s == null ? null : Uri.parse(s);
    }

    static String folderName(Context c) {
        Uri u = folder(c);
        if (u == null) return null;
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(u, DocumentsContract.getTreeDocumentId(u));
            Cursor cur = c.getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try { if (cur.moveToFirst()) return cur.getString(0); } finally { cur.close(); }
            }
        } catch (Exception ignored) {}
        return "pasta escolhida";
    }

    static void setFolder(Context c, Uri tree) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Uri old = folder(c);
        if (old != null) {
            try {
                c.getContentResolver().releasePersistableUriPermission(old,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        if (tree == null) { p.edit().remove(KEY).apply(); return; }
        c.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        p.edit().putString(KEY, tree.toString()).apply();
    }

    /** Executa o backup (chamar fora do thread principal). Devolve null se ok, ou a mensagem de erro. */
    static String run(Context c, String id, String title) {
        Uri tree = folder(c);
        if (tree == null) return null;
        ContentResolver cr = c.getContentResolver();
        try {
            String treeId = DocumentsContract.getTreeDocumentId(tree);
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
            String suffix = " [" + id + "].zip";
            String name = NotebookActivity.safeName(title) + suffix;
            Uri target = null;
            Cursor cur = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cur != null) {
                try {
                    while (cur.moveToNext()) {
                        String n = cur.getString(1);
                        if (n == null || !n.endsWith(suffix)) continue;
                        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                        if (n.equals(name) && target == null) target = u;
                        else try { DocumentsContract.deleteDocument(cr, u); } catch (Exception ignored) {}
                    }
                } finally { cur.close(); }
            }
            if (target == null) target = DocumentsContract.createDocument(cr, dir, "application/zip", name);
            if (target == null) return "não foi possível criar o arquivo";
            OutputStream os;
            try { os = cr.openOutputStream(target, "wt"); } catch (Exception e) { os = cr.openOutputStream(target, "w"); }
            if (os == null) return "não foi possível gravar";
            try { Storage.exportZip(c, id, os); } finally { os.close(); }
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }
}
