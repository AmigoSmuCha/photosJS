package com.photosjs;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Thumbnails from Android's own thumbnail cache, in three sizes. Requests are
 * served newest-first (what you are looking at now), and anything that has
 * scrolled out of view by the time a worker gets to it is skipped.
 */
final class Thumbs {
    static final int[] SIZES = {192, 384, 640};

    interface Listener {
        void onThumbsArrived();
    }

    private final ContentResolver cr;
    private final LruCache<Long, Bitmap> cache;
    private final LinkedBlockingDeque<Media.Item[]> queue = new LinkedBlockingDeque<>();
    private final Set<Long> pending = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private final Set<Long> failed = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Set<Long> wanted = new HashSet<>();
    /** asked for since the last frameEnd: wanted too, until the next swap */
    private final Set<Long> fresh = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private Set<Long> building = new HashSet<>();
    private final Set<Listener> listeners = Collections.newSetFromMap(new ConcurrentHashMap<Listener, Boolean>());
    private boolean notifyPosted;

    Thumbs(ContentResolver cr) {
        this.cr = cr;
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, 160L * 1024 * 1024);
        cache = new LruCache<Long, Bitmap>(max) {
            @Override
            protected int sizeOf(Long key, Bitmap b) {
                return b.getAllocationByteCount();
            }
        };
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    work();
                }
            }, "thumbs-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    static int bucketFor(float px) {
        for (int i = 0; i < SIZES.length; i++) if (px <= SIZES[i] * 1.05f) return i;
        return SIZES.length - 1;
    }

    private static long key(Media.Item it, int bucket) {
        return it.key() * 4 + bucket;
    }

    /** The exact size if cached, otherwise the nearest other size we have. */
    Bitmap best(Media.Item it, int bucket) {
        Bitmap b = cache.get(key(it, bucket));
        if (b != null) return b;
        for (int d = 1; d < SIZES.length; d++) {
            if (bucket + d < SIZES.length) {
                b = cache.get(key(it, bucket + d));
                if (b != null) return b;
            }
            if (bucket - d >= 0) {
                b = cache.get(key(it, bucket - d));
                if (b != null) return b;
            }
        }
        return null;
    }

    boolean hasExact(Media.Item it, int bucket) {
        return cache.get(key(it, bucket)) != null;
    }

    // ---- per frame: what's on screen now ----

    void frameStart() {
        building = new HashSet<>();
    }

    void want(Media.Item it, int bucket) {
        long k = key(it, bucket);
        building.add(k);
        fresh.add(k);
        if (cache.get(k) != null || failed.contains(k) || pending.contains(k)) return;
        pending.add(k);
        buckets.put(k, bucket);
        queue.offerFirst(new Media.Item[] {it, null});
    }

    /** Called after a frame (plus the prefetch around it) asked for its thumbnails. */
    void frameEnd() {
        Set<Long> keep = building;
        keep.addAll(extra);
        wanted = keep;
        fresh.clear();
        extra.clear();
    }

    /** Prefetch outside the visible area: wanted, but asked for after the visible ones. */
    void prefetch(Media.Item it, int bucket) {
        long k = key(it, bucket);
        extra.add(k);
        fresh.add(k);
        if (cache.get(k) != null || failed.contains(k) || pending.contains(k)) return;
        pending.add(k);
        buckets.put(k, bucket);
        queue.offerLast(new Media.Item[] {it, null});
    }

    private final Set<Long> extra = new HashSet<>();
    private final ConcurrentHashMap<Long, Integer> buckets = new ConcurrentHashMap<>();

    private void work() {
        while (true) {
            Media.Item it;
            try {
                it = queue.takeFirst()[0];
            } catch (InterruptedException e) {
                return;
            }
            // which size(s) of this item are pending and still wanted
            for (int b = 0; b < SIZES.length; b++) {
                long k = key(it, b);
                if (!pending.contains(k) || buckets.get(k) == null) continue;
                if (!wanted.contains(k) && !fresh.contains(k)) {
                    pending.remove(k);
                    buckets.remove(k);
                    continue;
                }
                try {
                    Bitmap bm = cr.loadThumbnail(it.uri(), new Size(SIZES[b], SIZES[b]), null);
                    if (bm != null) cache.put(k, bm);
                    else failed.add(k);
                } catch (Throwable e) {
                    failed.add(k);
                }
                pending.remove(k);
                buckets.remove(k);
                postNotify();
            }
        }
    }

    private void postNotify() {
        synchronized (this) {
            if (notifyPosted) return;
            notifyPosted = true;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (Thumbs.this) {
                    notifyPosted = false;
                }
                for (Listener l : listeners) l.onThumbsArrived();
            }
        }, 16);
    }

    /** after a photo changed on disk: drop what we had of it */
    void forget(Media.Item it) {
        for (int b = 0; b < SIZES.length; b++) cache.remove(key(it, b));
    }

    void clearFailures() {
        failed.clear();
    }
}
