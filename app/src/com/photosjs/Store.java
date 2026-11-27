package com.photosjs;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * photosJS's own memory: albums (collections that move or copy nothing, one
 * photo can be in many) and hidden photos (hidden here only; the files and
 * other apps are untouched). Photos are kept by their media key.
 */
final class Store extends SQLiteOpenHelper {
    static final class Album {
        long id;
        String name = "";
        long created;
        int pos;                                      // place when arranged by hand
        final List<Long> keys = new ArrayList<>();   // newest added first
    }

    Store(Context c) {
        super(c.getApplicationContext(), "photosjs.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE albums (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL, pos INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE album_items (album INTEGER NOT NULL, item INTEGER NOT NULL, added INTEGER NOT NULL, PRIMARY KEY (album, item))");
        db.execSQL("CREATE TABLE hidden (item INTEGER PRIMARY KEY, at INTEGER NOT NULL)");
        createOrders(db);
    }

    /** 3: photos (and folder boards) arranged by hand, per view: "all", "f:<bucket>", "a:<album>", "folders" */
    private static void createOrders(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS orders (view TEXT NOT NULL, item INTEGER NOT NULL, pos INTEGER NOT NULL, PRIMARY KEY (view, item))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // 2: albums can be arranged by hand
        if (from < 2) db.execSQL("ALTER TABLE albums ADD COLUMN pos INTEGER NOT NULL DEFAULT 0");
        if (from < 3) createOrders(db);
    }

    // ---- orders by hand ----

    /** every view's order: view -> (key -> place) */
    Map<String, Map<Long, Integer>> orders() {
        Map<String, Map<Long, Integer>> out = new HashMap<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT view, item, pos FROM orders", null);
        try {
            while (c.moveToNext()) {
                Map<Long, Integer> m = out.get(c.getString(0));
                if (m == null) {
                    m = new HashMap<>();
                    out.put(c.getString(0), m);
                }
                m.put(c.getLong(1), c.getInt(2));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** this view in this order, from now on */
    void setOrder(String view, List<Long> keys) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("orders", "view = ?", new String[] {view});
            android.database.sqlite.SQLiteStatement ins =
                    db.compileStatement("INSERT OR REPLACE INTO orders (view, item, pos) VALUES (?, ?, ?)");
            for (int i = 0; i < keys.size(); i++) {
                ins.bindString(1, view);
                ins.bindLong(2, keys.get(i));
                ins.bindLong(3, i);
                ins.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- albums ----

    List<Album> albums() {
        Map<Long, Album> byId = new HashMap<>();
        List<Album> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, name, created, pos FROM albums ORDER BY pos ASC, created DESC", null);
        try {
            while (c.moveToNext()) {
                Album a = new Album();
                a.id = c.getLong(0);
                a.name = c.getString(1);
                a.created = c.getLong(2);
                a.pos = c.getInt(3);
                byId.put(a.id, a);
                out.add(a);
            }
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT album, item FROM album_items ORDER BY added DESC", null);
        try {
            while (c.moveToNext()) {
                Album a = byId.get(c.getLong(0));
                if (a != null) a.keys.add(c.getLong(1));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** a new album comes first among the arranged ones */
    long createAlbum(String name) {
        SQLiteDatabase db = getWritableDatabase();
        int first = 0;
        Cursor c = db.rawQuery("SELECT MIN(pos) FROM albums", null);
        try {
            if (c.moveToFirst() && !c.isNull(0)) first = c.getInt(0);
        } finally {
            c.close();
        }
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        v.put("pos", first - 1);
        return db.insert("albums", null, v);
    }

    /** the albums in this order, by hand */
    void setOrder(List<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("pos", i);
                db.update("albums", v, "id = ?", new String[] {String.valueOf(ids.get(i))});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void renameAlbum(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("albums", v, "id = ?", new String[] {String.valueOf(id)});
    }

    /** Removes the album (never the photos). Returns what is needed to bring it back. */
    Album deleteAlbum(long id) {
        Album keep = null;
        for (Album a : albums()) if (a.id == id) keep = a;
        SQLiteDatabase db = getWritableDatabase();
        db.delete("album_items", "album = ?", new String[] {String.valueOf(id)});
        db.delete("albums", "id = ?", new String[] {String.valueOf(id)});
        return keep;
    }

    void restoreAlbum(Album a) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("id", a.id);
            v.put("name", a.name);
            v.put("created", a.created);
            v.put("pos", a.pos);
            db.insertWithOnConflict("albums", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            long now = System.currentTimeMillis();
            for (int i = 0; i < a.keys.size(); i++) {
                ContentValues iv = new ContentValues();
                iv.put("album", a.id);
                iv.put("item", a.keys.get(i));
                iv.put("added", now - i);
                db.insertWithOnConflict("album_items", null, iv, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Adds photos; returns how many were new to the album. */
    int addToAlbum(long album, List<Long> keys) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            for (int i = 0; i < keys.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("album", album);
                v.put("item", keys.get(i));
                v.put("added", now + i);
                if (db.insertWithOnConflict("album_items", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    void removeFromAlbum(long album, List<Long> keys) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long k : keys) {
                db.delete("album_items", "album = ? AND item = ?",
                        new String[] {String.valueOf(album), String.valueOf(k)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- hidden ----

    Set<Long> hidden() {
        Set<Long> out = new HashSet<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT item FROM hidden", null);
        try {
            while (c.moveToNext()) out.add(c.getLong(0));
        } finally {
            c.close();
        }
        return out;
    }

    void hide(List<Long> keys) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            for (long k : keys) {
                ContentValues v = new ContentValues();
                v.put("item", k);
                v.put("at", now);
                db.insertWithOnConflict("hidden", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void unhide(List<Long> keys) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long k : keys) db.delete("hidden", "item = ?", new String[] {String.valueOf(k)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
