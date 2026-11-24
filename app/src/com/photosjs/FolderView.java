package com.photosjs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The device folders as boards, the way Pinterest shows them: a collage of
 * the folder's newest photos (one large, two small), the name, and how many
 * photos (and, if wanted, how much space). Pinch for bigger or smaller boards.
 */
public class FolderView extends View {
    interface Listener {
        void onOpenFolder(Media.Folder f);

        void onBoardColumnsChanged(int cols);

        /** a long press on a board (only where boards can be changed: albums) */
        void onBoardHold(FolderView from, Media.Folder f, RectF screen, float radius, float x, float y);

        void onHoldMove(float x, float y);

        void onHoldEnd(float x, float y, boolean cancel);
    }

    static final int[] LEVELS = {1, 2, 3};
    private static final float STEP = 1.32f;

    private final float dp;
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF r = new RectF(), piece = new RectF();
    private final Path clip = new Path();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private Theme t;
    private Thumbs thumbs;
    private Listener listener;
    private List<Media.Folder> folders = new ArrayList<>();
    private boolean showSizes, rounded = true, airy = true;
    private float textScale = 1f;
    private int level = 1;
    private float scrollY;
    private boolean stoppedFling, pinching, pinchedThisTouch, holdable, holding;
    private String empty = "";
    private float cum = 1f, focusX, focusY;
    private final Set<Long> waited = new HashSet<>();
    private final HashMap<Long, Long> arrived = new HashMap<>();
    // morph when the board size changes
    private HashMap<Long, RectF> from = new HashMap<>();
    private long morphStart = -1, morphMs;

    public FolderView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        name.setTypeface(L.font(c));
        meta.setTypeface(L.font(c));
        applyText();
        scroller = new OverScroller(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (pinching || pinchedThisTouch || e2.getPointerCount() > 1) return true;
                scrollY = clamp(scrollY + dy);
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (pinching || pinchedThisTouch) return true;
                scroller.fling(0, Math.round(scrollY), 0, Math.round(-vy), 0, 0, 0,
                        Math.round(maxScroll()));
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (stoppedFling || pinchedThisTouch) return true;
                int i = indexAt(e.getX(), e.getY());
                if (i >= 0 && listener != null) listener.onOpenFolder(folders.get(i));
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!holdable || pinchedThisTouch || pinching || listener == null) return;
                int i = indexAt(e.getX(), e.getY());
                if (i < 0) return;
                coverRect(i, r);
                r.offset(0, -scrollY);
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                RectF scr = new RectF(r);
                scr.offset(loc[0], loc[1]);
                holding = true;
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onBoardHold(FolderView.this, folders.get(i), scr, radius(), e.getRawX(), e.getRawY());
            }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                pinching = true;
                pinchedThisTouch = true;
                cum = 1f;
                scroller.forceFinished(true);
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                cum *= d.getScaleFactor();
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                if (cum > STEP && level > 0) changeLevel(level - 1);
                else if (cum < 1f / STEP && level < LEVELS.length - 1) changeLevel(level + 1);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
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

    void setLook(boolean rounded, boolean airy, float textScale) {
        this.rounded = rounded;
        this.airy = airy;
        this.textScale = textScale;
        applyText();
        scrollY = clamp(scrollY);
        invalidate();
    }

    private void applyText() {
        name.setTextSize(10 * dp * textScale);
        meta.setTextSize(8.5f * dp * textScale);
    }

    void setHoldable(boolean h) {
        holdable = h;
    }

    /** a quiet line when there is nothing to show */
    void setEmptyText(String s) {
        empty = s;
        invalidate();
    }

    void setColumns(int cols) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i] == cols) level = i;
        invalidate();
    }

    void setFolders(List<Media.Folder> list, boolean sizes, boolean toTop) {
        folders = list;
        showSizes = sizes;
        if (toTop) scrollY = 0;
        scrollY = clamp(scrollY);
        invalidate();
    }

    // ---- layout ----

    private int cols() {
        return LEVELS[level];
    }

    private float margin() {
        return airy ? 6 * dp : 0;
    }

    private float gap() {
        return airy ? 6 * dp : Math.max(1, Math.round(1.34f * dp));
    }

    private float cardW() {
        int c = cols();
        return (getWidth() - 2 * margin() - (c - 1) * gap()) / c;
    }

    private float coverH() {
        return Math.round(cardW() * (cols() == 1 ? 0.6f : 0.72f));
    }

    private float capH() {
        return Math.round(36 * dp * textScale);
    }

    private float rowH() {
        return coverH() + capH();
    }

    private float radius() {
        if (!rounded) return 0;
        return (cols() == 3 ? 7 : 10) * dp;
    }

    /** Cover rect of board i, in content coordinates. */
    private void coverRect(int i, RectF out) {
        int c = cols();
        int row = i / c, col = i % c;
        float x = margin() + col * (cardW() + gap());
        float y = margin() + row * (rowH() + gap());
        out.set(x, y, x + cardW(), y + coverH());
    }

    private float contentH() {
        int rows = (folders.size() + cols() - 1) / cols();
        return 2 * margin() + rows * rowH() + Math.max(0, rows - 1) * gap();
    }

    private float maxScroll() {
        return Math.max(0, contentH() - getHeight());
    }

    private float clamp(float y) {
        return Math.max(0, Math.min(maxScroll(), y));
    }

    private int indexAt(float x, float sy) {
        float y = sy + scrollY;
        for (int i = 0; i < folders.size(); i++) {
            coverRect(i, r);
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom + capH()) return i;
        }
        return -1;
    }

    private void changeLevel(int newLevel) {
        // remember where every board is, and which one is under the fingers
        HashMap<Long, RectF> old = new HashMap<>();
        int anchor = -1;
        float fv = 0.5f, best = Float.MAX_VALUE;
        for (int i = 0; i < folders.size(); i++) {
            coverRect(i, r);
            r.offset(0, -scrollY);
            if (r.bottom + capH() < -getHeight() * 0.25f || r.top > getHeight() * 1.25f) continue;
            old.put(folders.get(i).bucket, new RectF(r));
            float cy = Math.max(r.top, Math.min(r.bottom + capH(), focusY));
            float cx = Math.max(r.left, Math.min(r.right, focusX));
            float d = (cx - focusX) * (cx - focusX) + (cy - focusY) * (cy - focusY);
            if (d < best) {
                best = d;
                anchor = i;
                fv = (focusY - r.top) / Math.max(1f, r.height() + capH());
            }
        }
        level = newLevel;
        if (anchor >= 0) {
            coverRect(anchor, r);
            scrollY = clamp(r.top + fv * (r.height() + capH()) - focusY);
        } else {
            scrollY = clamp(scrollY);
        }
        cum = 1f;
        morphMs = Motion.ms(420);
        if (morphMs > 0) {
            from = old;
            morphStart = SystemClock.uptimeMillis();
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onBoardColumnsChanged(cols());
        invalidate();
    }

    // ---- drawing ----

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
        float u = 1f;
        if (morphStart >= 0) {
            u = (now - morphStart) / (float) Math.max(1, morphMs);
            if (u >= 1f) {
                u = 1f;
                morphStart = -1;
                from = new HashMap<>();
            } else {
                again = true;
            }
        }
        float e = Motion.ease(u);
        int bucket = Thumbs.bucketFor(cardW() * 0.7f);
        int placeholder = Theme.mix(t.bg, t.plate, 0.7f);
        float rad = radius();
        if (thumbs != null) thumbs.frameStart();
        for (int i = 0; i < folders.size(); i++) {
            Media.Folder f = folders.get(i);
            coverRect(i, r);
            r.offset(0, -scrollY);
            float alpha = 1f;
            if (u < 1f) {
                RectF o = from.get(f.bucket);
                if (o != null) {
                    r.set(o.left + (r.left - o.left) * e, o.top + (r.top - o.top) * e,
                            o.right + (r.right - o.right) * e, o.bottom + (r.bottom - o.bottom) * e);
                } else {
                    alpha = Motion.fade(u);
                }
            }
            if (r.bottom + capH() < 0 || r.top > getHeight()) continue;
            again |= drawBoard(c, f, r, rad, bucket, placeholder, alpha, now);
        }
        if (thumbs != null) thumbs.frameEnd();
        if (folders.isEmpty() && !empty.isEmpty()) {
            name.setColor(t.s2);
            name.setAlpha(255);
            c.drawText(empty, (getWidth() - name.measureText(empty)) / 2f, getHeight() / 2f, name);
        }
        if (again) postInvalidateOnAnimation();
    }

    private boolean drawBoard(Canvas c, Media.Folder f, RectF cover, float rad, int bucket,
                              int placeholder, float alpha, long now) {
        boolean again = false;
        c.save();
        if (rad > 0) {
            clip.reset();
            clip.addRoundRect(cover, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
        } else {
            c.clipRect(cover);
        }
        int k = f.covers.size();
        // one photo fills the cover; with more, the gaps between pieces are the ground colour
        fill.setColor(k > 1 ? t.bg : placeholder);
        fill.setAlpha(Math.round(255 * alpha));
        c.drawRect(cover, fill);
        fill.setColor(placeholder);
        fill.setAlpha(Math.round(255 * alpha));
        float in = Math.max(1, Math.round(1.5f * dp));
        for (int p = 0; p < k; p++) {
            if (k == 1) {
                piece.set(cover);
            } else if (k == 2) {
                float mid = cover.left + (cover.width() - in) / 2f;
                if (p == 0) piece.set(cover.left, cover.top, mid, cover.bottom);
                else piece.set(mid + in, cover.top, cover.right, cover.bottom);
            } else {
                float big = cover.left + Math.round((cover.width() - in) * 0.64f);
                float midY = cover.top + (cover.height() - in) / 2f;
                if (p == 0) piece.set(cover.left, cover.top, big, cover.bottom);
                else if (p == 1) piece.set(big + in, cover.top, cover.right, midY);
                else piece.set(big + in, midY + in, cover.right, cover.bottom);
            }
            Media.Item it = f.covers.get(p);
            int b2 = p == 0 ? bucket : Math.max(0, bucket - 1);
            Bitmap b = null;
            if (thumbs != null) {
                b = thumbs.best(it, b2);
                if (!thumbs.hasExact(it, b2)) thumbs.want(it, b2);
            }
            long key = it.key();
            if (b == null) {
                c.drawRect(piece, fill);
                waited.add(key);
                continue;
            }
            float a = 1f;
            if (waited.remove(key)) arrived.put(key, now);
            Long at = arrived.get(key);
            if (at != null) {
                long d = Motion.ms(240);
                a = d <= 0 ? 1f : (now - at) / (float) d;
                if (a >= 1f) {
                    a = 1f;
                    arrived.remove(key);
                } else {
                    again = true;
                }
            }
            if (a < 1f) c.drawRect(piece, fill);
            cover(b, piece);
            paint.setAlpha(Math.round(255 * alpha * Motion.fade(a)));
            c.drawBitmap(b, src, piece, paint);
        }
        c.restore();

        // caption: name, then count (and size)
        float px = airy ? 2 * dp : 6 * dp;
        float l1 = cover.bottom + capH() * 0.42f, l2 = cover.bottom + capH() * 0.82f;
        name.setColor(t.text);
        name.setAlpha(Math.round(255 * alpha));
        c.drawText(TextUtils.ellipsize(f.name, name, Math.max(0, cover.width() - 2 * px),
                TextUtils.TruncateAt.END).toString(), cover.left + px, l1, name);
        String m = f.count + (f.count == 1 ? " photo" : " photos") + (showSizes ? " · " + Media.size(f.size) : "");
        meta.setColor(t.s1);
        meta.setAlpha(Math.round(255 * alpha));
        c.drawText(TextUtils.ellipsize(m, meta, Math.max(0, cover.width() - 2 * px),
                TextUtils.TruncateAt.END).toString(), cover.left + px, l2, meta);
        return again;
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
        if (holding) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_MOVE) {
                listener.onHoldMove(e.getRawX(), e.getRawY());
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                holding = false;
                listener.onHoldEnd(e.getRawX(), e.getRawY(), act == MotionEvent.ACTION_CANCEL);
            }
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            stoppedFling = !scroller.isFinished();
            pinchedThisTouch = false;
            scroller.forceFinished(true);
        }
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) pinching = false;
        return true;
    }
}
