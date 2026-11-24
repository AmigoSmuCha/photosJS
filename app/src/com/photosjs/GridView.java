package com.photosjs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The photo grid, drawn by hand so every pixel stays where it should.
 * Two layouts on one pinch: squares, and at few columns the Pinterest view
 * (whole photos in their true shape, packed into equal-width columns).
 * When the column count changes, the photo under the fingers stays under the
 * fingers and everything else springs from where it was to where it goes.
 */
public class GridView extends View {
    interface Listener {
        void onOpen(Media.Item it);

        void onColumnsChanged(int cols);
    }

    static final int[] LEVELS = {1, 2, 3, 4, 6, 8};
    static final int KEEP = 0, MORPH = 1, TOP = 2;

    private static final float STEP = 1.32f;      // pinch ratio for one level
    private static final long MORPH_MS = 480;
    private static final long FADE_MS = 220;

    private final float dp;
    private final int gap, labelH, pad;
    private Theme t;
    private Thumbs thumbs;
    private Listener listener;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF tmp = new RectF(), tmp2 = new RectF();

    // data
    private List<Media.Item> items = new ArrayList<>();
    private boolean dated = true, byModified;
    private int level = 3;
    private int pinterestUpTo = 2;

    // layout, in content coordinates
    private int n;
    private int[] eItem = new int[0];
    private String[] eLabel = new String[0];
    private float[] ex = new float[0], ey = new float[0], ew = new float[0], eh = new float[0];
    private int[] itemEntry = new int[0];
    private int gCount;
    private int[] gFirst = new int[0], gLast = new int[0];
    private float[] gTop = new float[0], gBottom = new float[0];
    private float contentH;
    private int bucket;
    private boolean masonry;

    // scrolling
    private float scrollY;
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private boolean pinchedThisTouch;

    // pinch
    private boolean pinching;
    private float cum = 1f, live = 1f, liveFrom = 1f, focusX, focusY;
    private long liveStart = -1;

    // morph between layouts
    private HashMap<Long, RectF> from = new HashMap<>();
    private long morphStart = -1;
    private boolean fadeAll;

    // arriving thumbnails settle in
    private final Set<Long> waited = new HashSet<>();
    private final HashMap<Long, Long> arrived = new HashMap<>();

    // fast scroll
    private long lastScrollAt = -10000;
    private boolean dragFast;
    private final float fastW, fastH, fastZone;

    public GridView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        gap = Math.max(1, Math.round(dp * 1.34f));
        labelH = Math.round(20 * dp);
        pad = Math.round(8 * dp);
        fastW = Math.round(3.5f * dp);
        fastH = Math.round(36 * dp);
        fastZone = 30 * dp;
        label.setTypeface(L.font(c));
        label.setTextSize(9 * dp);
        small.setTypeface(L.font(c));
        small.setTextSize(8 * dp);
        scroller = new OverScroller(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (pinching || pinchedThisTouch || e2.getPointerCount() > 1) return true;
                scrollTo(scrollY + dy);
                lastScrollAt = SystemClock.uptimeMillis();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (pinching || pinchedThisTouch) return true;
                scroller.fling(0, Math.round(scrollY), 0, Math.round(-vy), 0, 0, 0,
                        Math.round(maxScroll()));
                lastScrollAt = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (pinchedThisTouch) return true;
                int en = entryAt(e.getX(), e.getY());
                if (en >= 0 && eItem[en] >= 0 && listener != null) {
                    listener.onOpen(items.get(eItem[en]));
                }
                return true;
            }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                pinching = true;
                pinchedThisTouch = true;
                scroller.forceFinished(true);
                cum = 1f;
                liveStart = -1;
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                cum *= d.getScaleFactor();
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                if (cum > STEP && level > 0) {
                    changeLevel(level - 1);
                } else if (cum < 1f / STEP && level < LEVELS.length - 1) {
                    changeLevel(level + 1);
                }
                boolean end = (cum > 1f && level == 0) || (cum < 1f && level == LEVELS.length - 1);
                float k = end ? 0.18f : 0.55f;
                live = clamp(1f + (cum - 1f) * k, 0.88f, 1.14f);
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
                liveFrom = live;
                liveStart = SystemClock.uptimeMillis();
                invalidate();
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

    int columns() {
        return LEVELS[level];
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    void setColumns(int cols) {
        int best = 0;
        for (int i = 0; i < LEVELS.length; i++) {
            if (Math.abs(LEVELS[i] - cols) < Math.abs(LEVELS[best] - cols)) best = i;
        }
        if (best == level) return;
        level = best;
        relayoutKeeping(KEEP);
    }

    void setPinterestUpTo(int upTo) {
        if (upTo == pinterestUpTo) return;
        pinterestUpTo = upTo;
        relayoutKeeping(MORPH);
    }

    /**
     * New content. KEEP: stay on the same photo (a reload). MORPH: stay on the
     * same photo and let the rest spring to their new places (videos on/off).
     * TOP: start from the top, photos fading in (a new sort, another folder).
     */
    void setItems(List<Media.Item> list, boolean dated, boolean byModified, int how) {
        HashMap<Long, RectF> old = how == MORPH ? screenRects() : null;
        long anchorKey = 0;
        float anchorY = 0;
        boolean haveAnchor = false;
        if (how != TOP) {
            int a = firstVisibleItemEntry();
            if (a >= 0) {
                anchorKey = items.get(eItem[a]).key();
                anchorY = ey[a] - scrollY;
                haveAnchor = true;
            }
        }
        items = list;
        this.dated = dated;
        this.byModified = byModified;
        relayout();
        if (how == TOP) {
            scrollY = 0;
            scroller.forceFinished(true);
            from = new HashMap<>();
            fadeAll = true;
            morphStart = SystemClock.uptimeMillis();
        } else {
            if (haveAnchor) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).key() == anchorKey) {
                        scrollY = ey[itemEntry[i]] - anchorY;
                        break;
                    }
                }
            }
            scrollY = clamp(scrollY, 0, maxScroll());
            if (old != null) {
                from = old;
                fadeAll = false;
                morphStart = SystemClock.uptimeMillis();
            }
        }
        invalidate();
    }

    private void relayoutKeeping(int how) {
        setItems(items, dated, byModified, how == TOP ? TOP : how);
    }

    // ---- layout ----

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int a = firstVisibleItemEntry();
        long key = a >= 0 ? items.get(eItem[a]).key() : 0;
        relayout();
        if (a >= 0) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).key() == key) {
                    scrollY = clamp(ey[itemEntry[i]], 0, maxScroll());
                    break;
                }
            }
        }
    }

    private void relayout() {
        int W = getWidth();
        int count = items.size();
        int cap = count * 2 + 2;
        if (eItem.length < cap) {
            eItem = new int[cap];
            eLabel = new String[cap];
            ex = new float[cap];
            ey = new float[cap];
            ew = new float[cap];
            eh = new float[cap];
            gFirst = new int[cap];
            gLast = new int[cap];
            gTop = new float[cap];
            gBottom = new float[cap];
        }
        if (itemEntry.length < count) itemEntry = new int[count];
        n = 0;
        gCount = 0;
        contentH = 0;
        if (W <= 0) return;

        int cols = LEVELS[level];
        masonry = cols <= pinterestUpTo;
        float[] colX = new float[cols], colR = new float[cols];
        for (int c = 0; c < cols; c++) {
            colX[c] = Math.round((float) c * (W + gap) / cols);
            colR[c] = Math.round((float) (c + 1) * (W + gap) / cols) - gap;
        }
        float tile = Math.round((float) (W - gap * (cols - 1)) / cols);
        bucket = Thumbs.bucketFor(masonry ? tile * 1.4f : tile);
        int gran = cols <= 3 ? 0 : cols <= 6 ? 1 : 2;

        float[] colH = new float[cols];
        float y = 0;
        int col = 0;
        float rowY = 0;
        long curKey = Long.MIN_VALUE;
        boolean open = false;
        Calendar cal = Calendar.getInstance();
        Calendar now = Calendar.getInstance();
        for (int i = 0; i < count; i++) {
            Media.Item it = items.get(i);
            long key = dated ? groupKey(it, gran, cal) : 0;
            if (!open || key != curKey) {
                if (open) y = closeGroup(colH, rowY, tile) + gap;
                curKey = key;
                open = true;
                gFirst[gCount] = n;
                gTop[gCount] = y;
                if (dated) {
                    eItem[n] = -1;
                    eLabel[n] = labelFor(it, gran, cal, now);
                    ex[n] = pad;
                    ey[n] = y;
                    ew[n] = W - 2 * pad;
                    eh[n] = labelH;
                    n++;
                    y += labelH;
                }
                for (int c = 0; c < cols; c++) colH[c] = y;
                col = 0;
                rowY = y;
            }
            if (masonry) {
                int k = 0;
                for (int c = 1; c < cols; c++) if (colH[c] < colH[k] - 0.5f) k = c;
                float w = colR[k] - colX[k];
                float h = Math.max(1, Math.round(w / it.aspect()));
                put(i, colX[k], colH[k], w, h);
                colH[k] += h + gap;
            } else {
                if (col == cols) {
                    col = 0;
                    rowY += tile + gap;
                }
                put(i, colX[col], rowY, colR[col] - colX[col], tile);
                col++;
            }
        }
        if (open) y = closeGroup(colH, rowY, tile);
        contentH = y + gap;
    }

    private void put(int item, float x, float y, float w, float h) {
        eItem[n] = item;
        eLabel[n] = null;
        ex[n] = x;
        ey[n] = y;
        ew[n] = w;
        eh[n] = h;
        itemEntry[item] = n;
        n++;
    }

    private float closeGroup(float[] colH, float rowY, float tile) {
        float bottom;
        if (masonry) {
            bottom = 0;
            for (float h : colH) bottom = Math.max(bottom, h - gap);
        } else {
            bottom = rowY + tile;
        }
        gLast[gCount] = n - 1;
        gBottom[gCount] = bottom;
        gCount++;
        return bottom;
    }

    private long dateOf(Media.Item it) {
        return byModified ? it.modified : it.taken;
    }

    private long groupKey(Media.Item it, int gran, Calendar cal) {
        cal.setTimeInMillis(dateOf(it));
        int y = cal.get(Calendar.YEAR);
        if (gran == 2) return y;
        if (gran == 1) return y * 100L + cal.get(Calendar.MONTH);
        return y * 1000L + cal.get(Calendar.DAY_OF_YEAR);
    }

    private static final String[] DAYS = {"", "sun", "mon", "tue", "wed", "thu", "fri", "sat"};
    private static final String[] MON = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug",
            "sep", "oct", "nov", "dec"};
    private static final String[] MONTH = {"january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"};

    private String labelFor(Media.Item it, int gran, Calendar cal, Calendar now) {
        cal.setTimeInMillis(dateOf(it));
        int y = cal.get(Calendar.YEAR);
        boolean thisYear = y == now.get(Calendar.YEAR);
        if (gran == 2) return String.valueOf(y);
        if (gran == 1) return MONTH[cal.get(Calendar.MONTH)] + (thisYear ? "" : " " + y);
        if (thisYear && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return "today";
        Calendar yd = (Calendar) now.clone();
        yd.add(Calendar.DAY_OF_YEAR, -1);
        if (y == yd.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == yd.get(Calendar.DAY_OF_YEAR)) {
            return "yesterday";
        }
        return DAYS[cal.get(Calendar.DAY_OF_WEEK)] + " " + cal.get(Calendar.DAY_OF_MONTH) + " "
                + MON[cal.get(Calendar.MONTH)] + (thisYear ? "" : " " + y);
    }

    /** "nov 2026" for the fast-scroll tag. */
    private String monthYear(Media.Item it) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(dateOf(it));
        return MON[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR);
    }

    // ---- geometry helpers ----

    private float maxScroll() {
        return Math.max(0, contentH - getHeight());
    }

    private void scrollTo(float y) {
        scrollY = clamp(y, 0, maxScroll());
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** First group whose bottom is at or below y. */
    private int groupAt(float y) {
        int lo = 0, hi = gCount - 1, ans = gCount;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (gBottom[mid] >= y) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans;
    }

    private int firstVisibleItemEntry() {
        if (n == 0) return -1;
        float top = scrollY, bottom = scrollY + getHeight();
        int best = -1;
        for (int g = groupAt(top); g < gCount && gTop[g] <= bottom; g++) {
            for (int e = gFirst[g]; e <= gLast[g]; e++) {
                if (eItem[e] < 0) continue;
                if (ey[e] + eh[e] <= top || ey[e] >= bottom) continue;
                if (best < 0 || ey[e] < ey[best] - 0.5f || (Math.abs(ey[e] - ey[best]) < 0.5f && ex[e] < ex[best])) {
                    best = e;
                }
            }
            if (best >= 0) return best;
        }
        return best;
    }

    /** Entry under a screen point, or -1. */
    private int entryAt(float x, float sy) {
        float y = sy + scrollY;
        for (int g = groupAt(y); g < gCount && gTop[g] <= y; g++) {
            for (int e = gFirst[g]; e <= gLast[g]; e++) {
                if (x >= ex[e] && x < ex[e] + ew[e] && y >= ey[e] && y < ey[e] + eh[e]) return e;
            }
        }
        return -1;
    }

    private float morphT(long now) {
        if (morphStart < 0) return 1f;
        float u = (now - morphStart) / (float) MORPH_MS;
        if (u >= 1f) {
            morphStart = -1;
            from = new HashMap<>();
            fadeAll = false;
            return 1f;
        }
        return u;
    }

    /** Where an entry is drawn right now on screen (morph included, live scale not). */
    private boolean screenRect(int e, float u, RectF out) {
        out.set(ex[e], ey[e] - scrollY, ex[e] + ew[e], ey[e] + eh[e] - scrollY);
        if (u >= 1f || eItem[e] < 0) return false;
        RectF f = from.get(items.get(eItem[e]).key());
        if (f == null) return false;
        float s = Spring.ease(u);
        out.set(f.left + (out.left - f.left) * s, f.top + (out.top - f.top) * s,
                f.right + (out.right - f.right) * s, f.bottom + (out.bottom - f.bottom) * s);
        return true;
    }

    private void applyLive(RectF r) {
        if (live == 1f) return;
        r.set(focusX + (r.left - focusX) * live, focusY + (r.top - focusY) * live,
                focusX + (r.right - focusX) * live, focusY + (r.bottom - focusY) * live);
    }

    /** Screen rects of everything visible now, by item key, as the eye sees them. */
    private HashMap<Long, RectF> screenRects() {
        HashMap<Long, RectF> m = new HashMap<>();
        if (n == 0) return m;
        float u = morphT(SystemClock.uptimeMillis());
        float top = scrollY - getHeight() * 0.25f, bottom = scrollY + getHeight() * 1.25f;
        for (int g = groupAt(top); g < gCount && gTop[g] <= bottom; g++) {
            for (int e = gFirst[g]; e <= gLast[g]; e++) {
                if (eItem[e] < 0 || ey[e] + eh[e] < top || ey[e] > bottom) continue;
                RectF r = new RectF();
                screenRect(e, u, r);
                applyLive(r);
                m.put(items.get(eItem[e]).key(), r);
            }
        }
        return m;
    }

    private void changeLevel(int newLevel) {
        HashMap<Long, RectF> old = screenRects();
        // the photo under the fingers, and where in it the fingers are
        int a = -1;
        float best = Float.MAX_VALUE;
        float fv = 0.5f;
        long anchorKey = 0;
        for (java.util.Map.Entry<Long, RectF> en : old.entrySet()) {
            RectF r = en.getValue();
            float d;
            if (r.contains(focusX, focusY)) {
                d = -1;
            } else {
                float dx = Math.max(Math.max(r.left - focusX, 0), focusX - r.right);
                float dy = Math.max(Math.max(r.top - focusY, 0), focusY - r.bottom);
                d = dx * dx + dy * dy;
            }
            if (d < best) {
                best = d;
                anchorKey = en.getKey();
                a = 1;
                fv = clamp((focusY - r.top) / Math.max(1f, r.height()), 0f, 1f);
            }
        }
        level = newLevel;
        relayout();
        if (a >= 0) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).key() == anchorKey) {
                    int e = itemEntry[i];
                    scrollY = ey[e] + fv * eh[e] - focusY;
                    break;
                }
            }
        }
        scrollY = clamp(scrollY, 0, maxScroll());
        from = old;
        fadeAll = false;
        morphStart = SystemClock.uptimeMillis();
        cum = 1f;
        live = 1f;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onColumnsChanged(LEVELS[level]);
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        c.drawColor(t.bg);
        long now = SystemClock.uptimeMillis();
        boolean again = false;

        if (scroller.computeScrollOffset()) {
            scrollY = clamp(scroller.getCurrY(), 0, maxScroll());
            lastScrollAt = now;
            again = true;
        }
        if (!pinching && liveStart >= 0) {
            float u = (now - liveStart) / 300f;
            if (u >= 1f) {
                live = 1f;
                liveStart = -1;
            } else {
                live = liveFrom + (1f - liveFrom) * Spring.ease(u);
                again = true;
            }
        }
        float u = morphT(now);
        if (u < 1f) again = true;

        int H = getHeight();
        float top = scrollY, bottom = scrollY + H;
        // during a morph, include what flies in from off-screen positions
        float reach = u < 1f ? H * 0.5f : 0f;
        c.save();
        if (live != 1f) c.scale(live, live, focusX, focusY);
        if (thumbs != null) thumbs.frameStart();
        int placeholder = Theme.mix(t.bg, t.plate, 0.7f);
        label.setColor(t.s1);
        float labelBase = labelH * 0.5f + label.getTextSize() * 0.36f;

        for (int g = groupAt(top - reach); g < gCount && gTop[g] <= bottom + reach; g++) {
            for (int e = gFirst[g]; e <= gLast[g]; e++) {
                if (ey[e] + eh[e] < top - reach || ey[e] > bottom + reach) continue;
                if (eItem[e] < 0) {
                    float ly = ey[e] - scrollY;
                    if (ly + labelH < 0 || ly > H) continue;
                    label.setAlpha(u < 1f ? Math.round(255 * Spring.out(u)) : 255);
                    c.drawText(eLabel[e], ex[e] + 4 * dp, ly + labelBase, label);
                    continue;
                }
                Media.Item it = items.get(eItem[e]);
                boolean moving = screenRect(e, u, tmp);
                if (tmp.bottom < 0 || tmp.top > H) continue;
                float alpha = 1f;
                if (u < 1f && !moving) alpha = fadeAll || !from.isEmpty() ? Spring.out(u) : 1f;
                drawItem(c, it, tmp, alpha, placeholder, now);
                if (alpha < 1f) again = true;
            }
        }
        // prefetch about a screen ahead and half a screen behind
        if (thumbs != null) {
            float pTop = top - H * 0.5f, pBottom = bottom + H;
            for (int g = groupAt(pTop); g < gCount && gTop[g] <= pBottom; g++) {
                for (int e = gFirst[g]; e <= gLast[g]; e++) {
                    if (eItem[e] < 0) continue;
                    if (ey[e] + eh[e] >= top && ey[e] <= bottom) continue;
                    if (ey[e] + eh[e] < pTop || ey[e] > pBottom) continue;
                    thumbs.prefetch(items.get(eItem[e]), bucket);
                }
            }
            thumbs.frameEnd();
        }
        c.restore();

        again |= drawFastScroll(c, now);
        if (!arrivedSettling.isEmpty()) again = true;
        arrivedSettling.clear();
        if (again) postInvalidateOnAnimation();
    }

    private final Set<Long> arrivedSettling = new HashSet<>();

    private void drawItem(Canvas c, Media.Item it, RectF r, float alpha, int placeholder, long now) {
        long key = it.key();
        Bitmap b = null;
        if (thumbs != null) {
            b = thumbs.best(it, bucket);
            if (!thumbs.hasExact(it, bucket)) thumbs.want(it, bucket);
        }
        fill.setColor(placeholder);
        fill.setAlpha(Math.round(255 * alpha));
        if (b == null) {
            c.drawRect(r, fill);
            waited.add(key);
            return;
        }
        float settle = 1f;
        if (waited.remove(key)) arrived.put(key, now);
        Long at = arrived.get(key);
        if (at != null) {
            float s = (now - at) / (float) FADE_MS;
            if (s >= 1f) {
                arrived.remove(key);
            } else {
                settle = s;
                arrivedSettling.add(key);
                c.drawRect(r, fill);
            }
        }
        // the thumbnail eases in with a small spring from 96 %
        float sc = settle < 1f ? 0.96f + 0.04f * Spring.ease(settle) : 1f;
        tmp2.set(r);
        if (sc != 1f) {
            float dx = r.width() * (1f - sc) / 2f, dy = r.height() * (1f - sc) / 2f;
            tmp2.inset(dx, dy);
        }
        cover(b, tmp2);
        paint.setAlpha(Math.round(255 * alpha * Spring.out(settle)));
        c.drawBitmap(b, src, tmp2, paint);

        if (it.video && r.width() > 44 * dp) {
            String d = Media.duration(it.duration);
            float tw = small.measureText(d);
            float ph = 3 * dp, pv = 1.5f * dp;
            float bh = small.getTextSize() + 2 * pv;
            fill.setColor(t.bg);
            fill.setAlpha(Math.round(255 * alpha));
            c.drawRect(r.right - tw - 2 * ph, r.bottom - bh, r.right, r.bottom, fill);
            small.setColor(t.text);
            small.setAlpha(Math.round(255 * alpha));
            c.drawText(d, r.right - tw - ph, r.bottom - pv - small.getTextSize() * 0.2f, small);
        }
    }

    /** Centre-crop source rect so the bitmap covers dst without stretching. */
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

    // ---- fast scroll ----

    private boolean fastUseful() {
        return contentH > getHeight() * 4;
    }

    private float fastAlpha(long now) {
        if (!fastUseful()) return 0f;
        if (dragFast) return 1f;
        long idle = now - lastScrollAt;
        if (idle < 1200) return 1f;
        if (idle > 1500) return 0f;
        return 1f - (idle - 1200) / 300f;
    }

    private float fastY() {
        float range = getHeight() - fastH;
        float m = maxScroll();
        return m <= 0 ? 0 : range * scrollY / m;
    }

    private boolean drawFastScroll(Canvas c, long now) {
        float a = fastAlpha(now);
        if (a <= 0f) return false;
        float y = fastY();
        float x = getWidth() - fastW;
        fill.setColor(dragFast ? t.text : t.s1);
        fill.setAlpha(Math.round(255 * a));
        c.drawRect(x, y, getWidth(), y + fastH, fill);
        if (dragFast) {
            int e = firstVisibleItemEntry();
            if (e >= 0 && dated) {
                String s = monthYear(items.get(eItem[e]));
                label.setColor(t.text);
                label.setAlpha(255);
                label.setTextSize(10 * dp);
                float tw = label.measureText(s);
                float ph = 8 * dp, th = 26 * dp;
                float tx = x - 8 * dp - tw - 2 * ph;
                float ty = clamp(y + fastH / 2 - th / 2, 0, getHeight() - th);
                fill.setColor(t.plate);
                c.drawRect(tx, ty, tx + tw + 2 * ph, ty + th, fill);
                c.drawText(s, tx + ph, ty + th / 2 + label.getTextSize() * 0.36f, label);
                label.setTextSize(9 * dp);
            }
        }
        return true;
    }

    // ---- touch ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            pinchedThisTouch = false;
            boolean wasFlinging = !scroller.isFinished();
            scroller.forceFinished(true);
            if (fastUseful() && e.getX() > getWidth() - fastZone
                    && fastAlpha(SystemClock.uptimeMillis()) > 0.2f) {
                dragFast = true;
                dragFastTo(e.getY());
                return true;
            }
            if (wasFlinging) pinchedThisTouch = true; // a touch that stops a fling isn't a tap
        }
        if (dragFast) {
            if (act == MotionEvent.ACTION_MOVE) {
                dragFastTo(e.getY());
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                dragFast = false;
                lastScrollAt = SystemClock.uptimeMillis();
                invalidate();
            }
            return true;
        }
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) invalidate();
        return true;
    }

    private void dragFastTo(float y) {
        float range = getHeight() - fastH;
        float f = clamp((y - fastH / 2) / Math.max(1f, range), 0f, 1f);
        scrollY = f * maxScroll();
        lastScrollAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Called when new thumbnails are in the cache. */
    void thumbsArrived() {
        invalidate();
    }
}
