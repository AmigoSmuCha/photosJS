package com.photosjs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The device folders: a cover each, its name, how many photos, and (if wanted) its size. */
public class FolderView extends View {
    interface Listener {
        void onOpenFolder(Media.Folder f);
    }

    private final float dp;
    private final int gap, capH;
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint();
    private final Rect src = new Rect();
    private final RectF r = new RectF();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private Theme t;
    private Thumbs thumbs;
    private Listener listener;
    private List<Media.Folder> folders = new ArrayList<>();
    private boolean showSizes;
    private float scrollY;
    private boolean stoppedFling;
    private final Set<Long> waited = new HashSet<>();
    private final HashMap<Long, Long> arrived = new HashMap<>();

    public FolderView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        gap = Math.max(1, Math.round(dp * 1.34f));
        capH = Math.round(26 * dp);
        name.setTypeface(L.font(c));
        name.setTextSize(9.5f * dp);
        meta.setTypeface(L.font(c));
        meta.setTextSize(9.5f * dp);
        scroller = new OverScroller(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                scrollY = clamp(scrollY + dy);
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling(0, Math.round(scrollY), 0, Math.round(-vy), 0, 0, 0,
                        Math.round(maxScroll()));
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (stoppedFling) return true;
                int i = indexAt(e.getX(), e.getY() + scrollY);
                if (i >= 0 && listener != null) listener.onOpenFolder(folders.get(i));
                return true;
            }
        });
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setThumbs(Thumbs th) {
        thumbs = th;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setFolders(List<Media.Folder> list, boolean sizes, boolean toTop) {
        folders = list;
        showSizes = sizes;
        if (toTop) scrollY = 0;
        scrollY = clamp(scrollY);
        invalidate();
    }

    private float cell() {
        return (getWidth() - gap) / 2f;
    }

    private float rowH() {
        return cell() + capH;
    }

    private float contentH() {
        int rows = (folders.size() + 1) / 2;
        return rows * rowH() + Math.max(0, rows - 1) * gap;
    }

    private float maxScroll() {
        return Math.max(0, contentH() - getHeight());
    }

    private float clamp(float y) {
        return Math.max(0, Math.min(maxScroll(), y));
    }

    private int indexAt(float x, float y) {
        float rh = rowH() + gap;
        int row = (int) (y / rh);
        if (y - row * rh > rowH()) return -1;
        int col = x < getWidth() / 2f ? 0 : 1;
        int i = row * 2 + col;
        return i >= 0 && i < folders.size() ? i : -1;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        c.drawColor(t.bg);
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        if (scroller.computeScrollOffset()) {
            scrollY = clamp(scroller.getCurrY());
            again = true;
        }
        float cw = cell(), rh = rowH() + gap;
        int bucket = Thumbs.bucketFor(cw);
        int first = Math.max(0, (int) (scrollY / rh)) * 2;
        int placeholder = Theme.mix(t.bg, t.plate, 0.7f);
        if (thumbs != null) thumbs.frameStart();
        for (int i = first; i < folders.size(); i++) {
            int row = i / 2, col = i % 2;
            float y = row * rh - scrollY;
            if (y > getHeight()) break;
            float x = col == 0 ? 0 : cw + gap;
            Media.Folder f = folders.get(i);
            r.set(x, y, x + cw, y + cw);
            Bitmap b = null;
            if (thumbs != null && f.cover != null) {
                b = thumbs.best(f.cover, bucket);
                if (!thumbs.hasExact(f.cover, bucket)) thumbs.want(f.cover, bucket);
            }
            fill.setColor(placeholder);
            long k = f.bucket;
            if (b == null) {
                c.drawRect(r, fill);
                waited.add(k);
            } else {
                float a = 1f;
                if (waited.remove(k)) arrived.put(k, now);
                Long at = arrived.get(k);
                if (at != null) {
                    a = (now - at) / 220f;
                    if (a >= 1f) {
                        a = 1f;
                        arrived.remove(k);
                    } else {
                        again = true;
                        c.drawRect(r, fill);
                    }
                }
                cover(b, r);
                paint.setAlpha(Math.round(255 * Spring.out(a)));
                c.drawBitmap(b, src, r, paint);
            }
            // caption: name left, count (and size) right
            String m = String.valueOf(f.count) + (showSizes ? " · " + Media.size(f.size) : "");
            float mw = meta.measureText(m);
            float base = y + cw + capH / 2f + name.getTextSize() * 0.36f;
            float px = 6 * dp;
            meta.setColor(t.s1);
            c.drawText(m, x + cw - px - mw, base, meta);
            name.setColor(t.text);
            String nm = TextUtils.ellipsize(f.name, name, Math.max(0, cw - 2 * px - mw - 8 * dp),
                    TextUtils.TruncateAt.END).toString();
            c.drawText(nm, x + px, base, name);
        }
        if (thumbs != null) thumbs.frameEnd();
        if (again) postInvalidateOnAnimation();
    }

    private void cover(Bitmap b, RectF dst) {
        float bw = b.getWidth(), bh = b.getHeight();
        float da = dst.width() / Math.max(1f, dst.height()), ba = bw / Math.max(1f, bh);
        if (ba > da) {
            float sw = bh * da;
            src.set(Math.round((bw - sw) / 2), 0, Math.round((bw + sw) / 2), (int) bh);
        } else {
            float sh = bw / da;
            src.set(0, Math.round((bh - sh) / 2), (int) bw, Math.round((bh + sh) / 2));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            stoppedFling = !scroller.isFinished();
            scroller.forceFinished(true);
        }
        gestures.onTouchEvent(e);
        return true;
    }

    void thumbsArrived() {
        invalidate();
    }
}
