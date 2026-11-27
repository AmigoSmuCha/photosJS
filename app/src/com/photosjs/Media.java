package com.photosjs;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Photos and videos from Android's media index, read once into plain objects. */
final class Media {
    private Media() {}

    static final class Item {
        long id;
        boolean video;
        long taken;      // ms
        long modified;   // ms
        String name = "";
        long size;
        int w, h;        // as displayed (orientation applied)
        long duration;   // ms, videos
        long bucket;
        String bucketName = "";
        String mime = "";

        Uri uri() {
            return ContentUris.withAppendedId(video
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        }

        /** width / height, kept to a sane range so nothing becomes a sliver. */
        float aspect() {
            if (w <= 0 || h <= 0) return 1f;
            float a = (float) w / h;
            return Math.max(0.33f, Math.min(3f, a));
        }

        /** unique across images and videos */
        long key() {
            return video ? -id - 1 : id;
        }
    }

    static final class Folder {
        long bucket;
        String name = "";
        int count;
        /** albums: the place it was arranged to (sorting by hand) */
        int order;
        long size;
        long newestTaken, newestModified;
        Item cover;
        /** up to three photos for the board's collage, newest first */
        final List<Item> covers = new ArrayList<>(3);
    }

    static List<Item> load(ContentResolver cr) {
        List<Item> out = new ArrayList<>();
        query(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out);
        query(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out);
        return out;
    }

    /** one photo or video by its content uri (another app's), or null */
    static Item one(ContentResolver cr, Uri uri, boolean video) {
        long id;
        try {
            id = ContentUris.parseId(uri);
        } catch (Throwable e) {
            return null;
        }
        if (id < 0) return null;
        List<Item> out = new ArrayList<>();
        query(cr, uri, video, out, null, null);
        if (out.isEmpty()) {
            // by the index's own address (the uri may name another volume)
            query(cr, video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    video, out, MediaStore.MediaColumns._ID + "=?", new String[]{String.valueOf(id)});
        }
        return out.isEmpty() ? null : out.get(0);
    }

    private static void query(ContentResolver cr, Uri uri, boolean video, List<Item> out) {
        query(cr, uri, video, out, null, null);
    }

    private static void query(ContentResolver cr, Uri uri, boolean video, List<Item> out, String sel, String[] args) {
        String[] proj = video
                ? new String[] {
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
                    MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.BUCKET_ID,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DURATION }
                : new String[] {
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
                    MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.BUCKET_ID,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE };
        Cursor c = null;
        try {
            c = cr.query(uri, proj, sel, args, null);
            if (c == null) return;
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.video = video;
                long added = c.getLong(3) * 1000L;
                it.modified = c.getLong(2) * 1000L;
                if (it.modified <= 0) it.modified = added;
                it.taken = c.getLong(1);
                if (it.taken <= 0) it.taken = it.modified > 0 ? it.modified : added;
                it.name = nz(c.getString(4));
                it.size = c.getLong(5);
                int w = c.getInt(6), h = c.getInt(7), o = c.isNull(8) ? 0 : c.getInt(8);
                if (o == 90 || o == 270) {
                    it.w = h;
                    it.h = w;
                } else {
                    it.w = w;
                    it.h = h;
                }
                it.bucket = c.getLong(9);
                it.bucketName = nz(c.getString(10));
                it.mime = nz(c.getString(11));
                if (video) it.duration = c.getLong(12);
                out.add(it);
            }
        } catch (Throwable ignored) {
            // no permission yet, or the index is busy: show what we have
        } finally {
            if (c != null) c.close();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---- sorting ----

    static final int BY_TAKEN = 0, BY_MODIFIED = 1, BY_NAME = 2, BY_SIZE = 3, BY_HAND = 4;
    // "by hand": the order things were arranged in (photos, folder boards, albums)
    static final String[] SORT_NAMES = {"date taken", "date modified", "name", "size", "by hand"};

    /** Sorts in place. descending = newest / z-a / largest first. */
    static void sort(List<Item> items, final int by, final boolean descending) {
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int r;
                switch (by) {
                    case BY_MODIFIED: r = Long.compare(a.modified, b.modified); break;
                    case BY_NAME: r = a.name.compareToIgnoreCase(b.name); break;
                    case BY_SIZE: r = Long.compare(a.size, b.size); break;
                    default: r = Long.compare(a.taken, b.taken);
                }
                if (r == 0) r = Long.compare(a.key(), b.key());
                return descending ? -r : r;
            }
        });
    }

    static List<Item> filter(List<Item> all, boolean videos, long bucket, java.util.Set<Long> hidden) {
        List<Item> out = new ArrayList<>(all.size());
        for (Item it : all) {
            if (!videos && it.video) continue;
            if (hidden != null && hidden.contains(it.key())) continue;
            if (bucket != 0 && it.bucket != bucket) continue;
            out.add(it);
        }
        return out;
    }

    /** Folders from (already filtered) items; cover = the first item in the given order. */
    static List<Folder> folders(List<Item> sortedItems) {
        Map<Long, Folder> map = new HashMap<>();
        List<Folder> out = new ArrayList<>();
        for (Item it : sortedItems) {
            Folder f = map.get(it.bucket);
            if (f == null) {
                f = new Folder();
                f.bucket = it.bucket;
                f.name = it.bucketName.isEmpty() ? "(no name)" : it.bucketName;
                f.cover = it;
                map.put(it.bucket, f);
                out.add(f);
            }
            if (f.covers.size() < 3) f.covers.add(it);
            f.count++;
            f.size += it.size;
            f.newestTaken = Math.max(f.newestTaken, it.taken);
            f.newestModified = Math.max(f.newestModified, it.modified);
        }
        return out;
    }

    /**
     * By hand: photos that were never arranged (new ones) first, newest first;
     * then the arranged ones in their places.
     */
    static void sortByHand(List<Item> items, final Map<Long, Integer> pos) {
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                Integer pa = pos != null ? pos.get(a.key()) : null, pb = pos != null ? pos.get(b.key()) : null;
                if (pa == null && pb == null) {
                    int r = Long.compare(b.taken, a.taken);
                    return r != 0 ? r : Long.compare(b.key(), a.key());
                }
                if (pa == null) return -1;
                if (pb == null) return 1;
                return Integer.compare(pa, pb);
            }
        });
    }

    static void sortFolders(List<Folder> folders, final int by, final boolean descending) {
        Collections.sort(folders, new Comparator<Folder>() {
            @Override
            public int compare(Folder a, Folder b) {
                int r;
                switch (by) {
                    case BY_HAND:
                        r = Integer.compare(a.order, b.order);
                        return r != 0 ? r : Long.compare(b.newestTaken, a.newestTaken);
                    case BY_MODIFIED: r = Long.compare(a.newestModified, b.newestModified); break;
                    case BY_NAME: r = a.name.compareToIgnoreCase(b.name); break;
                    case BY_SIZE: r = Long.compare(a.size, b.size); break;
                    default: r = Long.compare(a.newestTaken, b.newestTaken);
                }
                if (r == 0) r = Long.compare(a.bucket, b.bucket);
                return descending ? -r : r;
            }
        });
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " kb";
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f mb", bytes / 1048576.0);
        }
        return String.format(java.util.Locale.US, "%.2f gb", bytes / 1073741824.0);
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        }
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
