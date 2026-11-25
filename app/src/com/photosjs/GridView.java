package com.photosjs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The photo feed, drawn by hand so every pixel stays where it should.
 * Pinterest first: at few columns, whole photos in their true shape packed
 * into equal-width columns (each photo drops into the shortest one); at many
 * columns, an overview of squares. One pinch moves through all of it, and
 * the photo under the fingers stays under the fingers while the rest spring
 * from where they were to where they go.
 */
public class GridView extends View {
    interface Listener {
        void onOpen(GridView from, Media.Item it);

        void onColumnsChanged(int cols);

        /** a long press on a photo: where it is on screen, and the finger (screen coords) */
        void onHoldStart(GridView from, Media.Item it, RectF screen, Bitmap bm, float radius, float x, float y);

        void onHoldMove(float x, float y);

        void onHoldEnd(float x, float y, boolean cancel);

        void onSelectionChanged(GridView from, int count);

        /** both fingers down and up again quickly, without pinching: select (it may be null) */
        void onTwoFingerTap(GridView from, Media.Item it);
    }

    static final int[] LEVELS = {1, 2, 3, 4, 5, 6, 8};
    static final int KEEP = 0, MORPH = 1, TOP = 2;

    private static final float STEP = 1.32f;      // pinch ratio for one level
    private static final long MORPH_MS = 480;
    private static final long SETTLE_MS = 240;

    private final float dp;
    private Theme t;
    private Thumbs thumbs;
    private Listener listener;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shaded = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF srcF = new RectF();
    private final RectF tmp = new RectF(), tmp2 = new RectF();
    private final Matrix matrix = new Matrix();
    private final WeakHashMap<Bitmap, BitmapShader> shaders = new WeakHashMap<>();

    // look
    private boolean rounded = true, airy = true;
    private int dateMode = 2;
    private boolean dateNumbers;
    private int heading;
    private float textScale = 1f;
    private float gap, margin, radius;
    private int labelH;

    // data
    private List<Media.Item> items = new ArrayList<>();
    private boolean dated = true, byModified;
    private int level = 1;
    private int pinterestUpTo = 3;

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
    private float fromRadius;
    private long morphStart = -1, morphMs = MORPH_MS;
    private boolean fadeAll;

    // arriving thumbnails settle in
    private final Set<Long> waited = new HashSet<>();
    private final HashMap<Long, Long> arrived = new HashMap<>();
    private boolean settling;

    // selecting
    private boolean selecting;
    private final Set<Long> selected = new HashSet<>();
    private final HashMap<Long, Long> selAt = new HashMap<>();
    private int strokeState;            // 0 undecided, 1 selecting stroke, 2 scrolling
    private int strokeAnchor = -1, strokeLast = -1;
    private boolean strokeOn;
    private Set<Long> strokeBase = new HashSet<>();
    private float downX, downY, lastX, lastY;
    private final int slop;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    // holding (the long-press fan)
    private boolean holding;
    // a two-finger tap
    private long twoAt = -1;
    private boolean twoValid;
    private float twoX, twoY, twoD;
    private int twoLevel;
    // the photo that is open in the viewer leaves its place empty
    private long ghost = Long.MIN_VALUE;

    // fast scroll
    private long lastScrollAt = -10000;
    private boolean dragFast;
    private final float fastW, fastH, fastZone;

    public GridView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        fastW = Math.round(3.5f * dp);
        fastH = Math.round(36 * dp);
        fastZone = 30 * dp;
        slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        ring.setStyle(Paint.Style.STROKE);
        label.setTypeface(L.font(c));
        small.setTypeface(L.font(c));
        applyTextScale();
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
                if (en < 0 || eItem[en] < 0) return true;
                Media.Item it = items.get(eItem[en]);
                if (selecting) {
                    toggle(it.key());
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.onSelectionChanged(GridView.this, selected.size());
                } else if (listener != null) {
                    listener.onOpen(GridView.this, it);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (selecting || pinchedThisTouch || pinching || listener == null) return;
                int en = entryAt(e.getX(), e.getY());
                if (en < 0 || eItem[en] < 0) return;
                Media.Item it = items.get(eItem[en]);
                RectF r = new RectF();
                screenRect(en, 1f, r);
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                r.offset(loc[0], loc[1]);
                Bitmap b = thumbs != null ? thumbs.best(it, bucket) : null;
                holding = true;
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onHoldStart(GridView.this, it, r, b, radius, e.getRawX(), e.getRawY());
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
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
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
                live = Motion.level() == Motion.OFF ? 1f : clamp(1f + (cum - 1f) * k, 0.88f, 1.14f);
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

    // ---- settings ----

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

    /** Corners, air between photos, date labels and text size, all at once. */
    void setLook(boolean rounded, boolean airy, int dateMode, boolean dateNumbers, int heading,
                 float textScale) {
        boolean change = rounded != this.rounded || airy != this.airy || dateMode != this.dateMode
                || textScale != this.textScale || dateNumbers != this.dateNumbers;
        this.rounded = rounded;
        this.airy = airy;
        this.dateMode = dateMode;
        this.dateNumbers = dateNumbers;
        this.heading = heading;
        this.textScale = textScale;
        invalidate();
        applyTextScale();
        if (change) relayoutKeeping(MORPH);
    }

    private void applyTextScale() {
        label.setTextSize(9.5f * dp * textScale);
        small.setTextSize(8 * dp * textScale);
        labelH = Math.round(22 * dp * textScale);
    }

    int columns() {
        return LEVELS[level];
    }

    boolean selecting() {
        return selecting;
    }

    void setSelecting(boolean on) {
        if (on == selecting) return;
        selecting = on;
        long now = SystemClock.uptimeMillis();
        for (Long k : selected) selAt.put(k, now);
        selected.clear();
        invalidate();
    }

    /** the selected photos, in the feed's order */
    List<Media.Item> selectedItems() {
        List<Media.Item> out = new ArrayList<>();
        for (Media.Item it : items) if (selected.contains(it.key())) out.add(it);
        return out;
    }

    void setGhost(long key) {
        ghost = key;
        invalidate();
    }

    float radiusNow() {
        return radius;
    }

    /** Scrolls (without animation) so the photo is on screen; true if it is in this feed. */
    boolean reveal(long key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key() != key) continue;
            int e = itemEntry[i];
            float top = scrollY, bottom = scrollY + getHeight();
            if (ey[e] < top || ey[e] + eh[e] > bottom) {
                scrollY = clamp(ey[e] + eh[e] / 2f - getHeight() / 2f, 0, maxScroll());
                scroller.forceFinished(true);
                invalidate();
            }
            return true;
        }
        return false;
    }

    /** Where the photo is on the screen (screen coordinates), or null. */
    RectF screenRectOf(long key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key() != key) continue;
            int e = itemEntry[i];
            RectF r = new RectF(ex[e], ey[e] - scrollY, ex[e] + ew[e], ey[e] + eh[e] - scrollY);
            if (r.bottom < 0 || r.top > getHeight()) return null;
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            r.offset(loc[0], loc[1]);
            return r;
        }
        return null;
    }

    int indexOf(long key) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).key() == key) return i;
        return -1;
    }

    Bitmap thumbOf(Media.Item it) {
        return thumbs == null ? null : thumbs.best(it, bucket);
    }

    int selectedCount() {
        return selected.size();
    }

    List<Media.Item> items() {
        return items;
    }

    /** select one photo (entering selecting with it) */
    void selectKey(long key) {
        if (selected.add(key)) {
            selAt.put(key, SystemClock.uptimeMillis());
            invalidate();
            if (listener != null) listener.onSelectionChanged(this, selected.size());
        }
    }

    private void toggle(long key) {
        if (!selected.remove(key)) selected.add(key);
        selAt.put(key, SystemClock.uptimeMillis());
        invalidate();
    }

    /** how selected a photo looks right now, 0..1 (animated) */
    private float selAmount(long key, long now) {
        boolean on = selected.contains(key);
        Long at = selAt.get(key);
        if (at == null) return on ? 1f : 0f;
        long d = Motion.ms(220);
        float u = d <= 0 ? 1f : (now - at) / (float) d;
        if (u >= 1f) {
            selAt.remove(key);
            return on ? 1f : 0f;
        }
        float e = Motion.ease(u);
        return on ? e : 1f - e;
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
        float oldRadius = radius;
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
        if (!selected.isEmpty()) {
            Set<Long> present = new HashSet<>();
            for (Media.Item it : list) present.add(it.key());
            if (selected.retainAll(present) && listener != null) {
                listener.onSelectionChanged(this, selected.size());
            }
        }
        relayout();
        if (how == TOP) {
            scrollY = 0;
            scroller.forceFinished(true);
            startMorph(new HashMap<Long, RectF>(), true, radius);
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
            if (old != null) startMorph(old, false, oldRadius);
        }
        invalidate();
    }

    private void startMorph(HashMap<Long, RectF> old, boolean fade, float oldRadius) {
        morphMs = Motion.ms(MORPH_MS);
        if (morphMs <= 0) {
            morphStart = -1;
            from = new HashMap<>();
            fadeAll = false;
            return;
        }
        from = old;
        fromRadius = oldRadius;
        fadeAll = fade;
        morphStart = SystemClock.uptimeMillis();
    }

    private void relayoutKeeping(int how) {
        setItems(items, dated, byModified, how);
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

    /** Air and corners shrink as the photos get smaller. */
    private void lookFor(int cols) {
        if (airy) {
            gap = Math.round((cols <= 3 ? 6f : cols <= 5 ? 4f : 2f) * dp);
            margin = gap;
        } else {
            gap = Math.max(1, Math.round(dp * 1.34f));
            margin = 0;
        }
        radius = !rounded ? 0 : (cols <= 3 ? 10f : cols <= 5 ? 6f : 3f) * dp;
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
        lookFor(cols);
        masonry = cols <= pinterestUpTo;
        float inner = W - 2 * margin;
        float[] colX = new float[cols], colR = new float[cols];
        for (int c = 0; c < cols; c++) {
            colX[c] = margin + Math.round((float) c * (inner + gap) / cols);
            colR[c] = margin + Math.round((float) (c + 1) * (inner + gap) / cols) - gap;
        }
        float tile = Math.round((inner - gap * (cols - 1)) / cols);
        bucket = Thumbs.bucketFor(masonry ? tile * 1.4f : tile);
        int gran;
        switch (dateMode) {
            case 1: gran = 0; break;
            case 2: gran = 1; break;
            case 3: gran = 2; break;
            case 4: gran = -1; break;
            default: gran = cols <= 3 ? 0 : cols <= 6 ? 1 : 2;
        }
        boolean labels = dated && gran >= 0;

        float[] colH = new float[cols];
        float y = margin;
        int col = 0;
        float rowY = y;
        long curKey = Long.MIN_VALUE;
        boolean open = false;
        Calendar cal = Calendar.getInstance();
        Calendar now = Calendar.getInstance();
        for (int i = 0; i < count; i++) {
            Media.Item it = items.get(i);
            long key = labels ? groupKey(it, gran, cal) : 0;
            if (!open || key != curKey) {
                if (open) y = closeGroup(colH, rowY, tile) + gap;
                curKey = key;
                open = true;
                gFirst[gCount] = n;
                gTop[gCount] = y;
                if (labels) {
                    eItem[n] = -1;
                    eLabel[n] = labelFor(it, gran, cal, now);
                    ex[n] = margin;
                    ey[n] = y;
                    ew[n] = inner;
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
        contentH = y + margin + gap;
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
        int m = cal.get(Calendar.MONTH), d = cal.get(Calendar.DAY_OF_MONTH);
        if (gran == 2) return String.valueOf(y);
        if (gran == 1) {
            if (dateNumbers) return (m + 1) + "." + y;
            return MONTH[m] + (thisYear ? "" : " " + y);
        }
        if (thisYear && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return "today";
        Calendar yd = (Calendar) now.clone();
        yd.add(Calendar.DAY_OF_YEAR, -1);
        if (y == yd.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == yd.get(Calendar.DAY_OF_YEAR)) {
            return "yesterday";
        }
        if (dateNumbers) {
            return DAYS[cal.get(Calendar.DAY_OF_WEEK)] + " " + d + "." + (m + 1) + "." + (thisYear ? "" : String.valueOf(y));
        }
        return DAYS[cal.get(Calendar.DAY_OF_WEEK)] + " " + d + " " + MON[m] + (thisYear ? "" : " " + y);
    }

    private String monthYear(Media.Item it) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(dateOf(it));
        if (dateNumbers) return (cal.get(Calendar.MONTH) + 1) + "." + cal.get(Calendar.YEAR);
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
        float u = morphMs <= 0 ? 1f : (now - morphStart) / (float) morphMs;
        if (u >= 1f) {
            morphStart = -1;
            from = new HashMap<>();
            fadeAll = false;
            return 1f;
        }
        return u;
    }

    private boolean screenRect(int e, float u, RectF out) {
        out.set(ex[e], ey[e] - scrollY, ex[e] + ew[e], ey[e] + eh[e] - scrollY);
        if (u >= 1f || eItem[e] < 0) return false;
        RectF f = from.get(items.get(eItem[e]).key());
        if (f == null) return false;
        float s = Motion.ease(u);
        out.set(f.left + (out.left - f.left) * s, f.top + (out.top - f.top) * s,
                f.right + (out.right - f.right) * s, f.bottom + (out.bottom - f.bottom) * s);
        return true;
    }

    private void applyLive(RectF r) {
        if (live == 1f) return;
        r.set(focusX + (r.left - focusX) * live, focusY + (r.top - focusY) * live,
                focusX + (r.right - focusX) * live, focusY + (r.bottom - focusY) * live);
    }

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
        float oldRadius = radius;
        boolean found = false;
        float best = Float.MAX_VALUE;
        float fv = 0.5f;
        long anchorKey = 0;
        for (Map.Entry<Long, RectF> en : old.entrySet()) {
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
                found = true;
                fv = clamp((focusY - r.top) / Math.max(1f, r.height()), 0f, 1f);
            }
        }
        level = newLevel;
        relayout();
        if (found) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).key() == anchorKey) {
                    int e = itemEntry[i];
                    scrollY = ey[e] + fv * eh[e] - focusY;
                    break;
                }
            }
        }
        scrollY = clamp(scrollY, 0, maxScroll());
        startMorph(old, false, oldRadius);
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
            long d = Motion.ms(300);
            float u = d <= 0 ? 1f : (now - liveStart) / (float) d;
            if (u >= 1f) {
                live = 1f;
                liveStart = -1;
            } else {
                live = liveFrom + (1f - liveFrom) * Motion.ease(u);
                again = true;
            }
        }
        if (edgeScroll()) again = true;
        float u = morphT(now);
        if (u < 1f) again = true;
        float rad = u < 1f ? fromRadius + (radius - fromRadius) * Motion.ease(u) : radius;

        int H = getHeight();
        float top = scrollY, bottom = scrollY + H;
        float reach = u < 1f ? H * 0.5f : 0f;
        c.save();
        if (live != 1f) c.scale(live, live, focusX, focusY);
        if (thumbs != null) thumbs.frameStart();
        int placeholder = Theme.mix(t.bg, t.plate, 0.7f);
        Heading.style(label, getContext(), heading, 9.5f * dp * textScale);
        label.setColor(t.s1);
        float labelBase = labelH * 0.58f + label.getTextSize() * 0.36f;
        settling = false;

        for (int g = groupAt(top - reach); g < gCount && gTop[g] <= bottom + reach; g++) {
            for (int e = gFirst[g]; e <= gLast[g]; e++) {
                if (ey[e] + eh[e] < top - reach || ey[e] > bottom + reach) continue;
                if (eItem[e] < 0) {
                    float ly = ey[e] - scrollY;
                    if (ly + labelH < 0 || ly > H) continue;
                    label.setAlpha(u < 1f ? Math.round(255 * Motion.fade(u)) : 255);
                    float lx = ex[e] + (airy ? 3 * dp : 8 * dp);
                    Heading.draw(c, eLabel[e], heading, lx, ly + labelH * 0.56f, label);
                    continue;
                }
                Media.Item it = items.get(eItem[e]);
                boolean moving = screenRect(e, u, tmp);
                if (tmp.bottom < 0 || tmp.top > H) continue;
                float alpha = 1f;
                if (u < 1f && !moving && (fadeAll || !from.isEmpty())) alpha = Motion.fade(u);
                drawItem(c, it, tmp, alpha, placeholder, now, rad);
                if (alpha < 1f) again = true;
            }
        }
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
        if (settling) again = true;
        if (again) postInvalidateOnAnimation();
    }

    private void drawItem(Canvas c, Media.Item it, RectF r, float alpha, int placeholder, long now,
                          float rad) {
        long key = it.key();
        if (key == ghost) return;
        Bitmap b = null;
        if (thumbs != null) {
            b = thumbs.best(it, bucket);
            if (!thumbs.hasExact(it, bucket)) thumbs.want(it, bucket);
        }
        fill.setColor(placeholder);
        fill.setAlpha(Math.round(255 * alpha));
        if (b == null) {
            c.drawRoundRect(r, rad, rad, fill);
            waited.add(key);
            return;
        }
        float settle = 1f;
        if (waited.remove(key)) arrived.put(key, now);
        Long at = arrived.get(key);
        if (at != null) {
            long d = Motion.ms(SETTLE_MS);
            float s = d <= 0 ? 1f : (now - at) / (float) d;
            if (s >= 1f) {
                arrived.remove(key);
            } else {
                settle = s;
                settling = true;
                c.drawRoundRect(r, rad, rad, fill);
            }
        }
        float sel = selAmount(key, now);
        if (sel > 0f && sel < 1f) settling = true;
        float sc = settle < 1f ? 0.96f + 0.04f * Motion.ease(settle) : 1f;
        sc *= 1f - 0.1f * sel;
        tmp2.set(r);
        if (sc != 1f) tmp2.inset(r.width() * (1f - sc) / 2f, r.height() * (1f - sc) / 2f);
        drawPhoto(c, b, tmp2, rad * (1f - 0.3f * sel), Math.round(255 * alpha * Motion.fade(settle)));
        if (sel > 0f) {
            // the lamp colour marks what is chosen
            float sw = 2.2f * dp;
            ring.setStrokeWidth(sw);
            ring.setColor(t.accent);
            ring.setAlpha(Math.round(255 * sel * alpha));
            tmp2.set(r);
            tmp2.inset(sw / 2, sw / 2);
            c.drawRoundRect(tmp2, rad, rad, ring);
        }

        if (it.video && r.width() > 44 * dp) {
            String d = Media.duration(it.duration);
            float tw = small.measureText(d);
            float ph = 4 * dp, pv = 2 * dp;
            float bh = small.getTextSize() + 2 * pv;
            float inset = rad > 0 ? 5 * dp : 0;
            float right = r.right - inset, bottomY = r.bottom - inset;
            fill.setColor(t.bg);
            fill.setAlpha(Math.round(235 * alpha));
            float cr = rad > 0 ? 3 * dp : 0;
            c.drawRoundRect(right - tw - 2 * ph, bottomY - bh, right, bottomY, cr, cr, fill);
            small.setColor(t.text);
            small.setAlpha(Math.round(255 * alpha));
            c.drawText(d, right - tw - ph, bottomY - pv - small.getTextSize() * 0.2f, small);
        }
    }

    /** Centre-cropped into dst; through a shader when the corners are rounded. */
    private void drawPhoto(Canvas c, Bitmap b, RectF dst, float rad, int alpha) {
        float bw = b.getWidth(), bh = b.getHeight();
        float da = dst.width() / Math.max(1f, dst.height()), ba = bw / Math.max(1f, bh);
        if (ba > da) {
            float sw = bh * da;
            src.set(Math.round((bw - sw) / 2), 0, Math.round((bw + sw) / 2), (int) bh);
        } else {
            float sh = bw / da;
            src.set(0, Math.round((bh - sh) / 2), (int) bw, Math.round((bh + sh) / 2));
        }
        if (rad <= 0.5f) {
            paint.setAlpha(alpha);
            c.drawBitmap(b, src, dst, paint);
            return;
        }
        BitmapShader sh = shaders.get(b);
        if (sh == null) {
            sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(b, sh);
        }
        srcF.set(src);
        matrix.setRectToRect(srcF, dst, Matrix.ScaleToFit.FILL);
        sh.setLocalMatrix(matrix);
        shaded.setShader(sh);
        shaded.setAlpha(alpha);
        c.drawRoundRect(dst, rad, rad, shaded);
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
                Heading.style(label, getContext(), heading, 10 * dp * textScale);
                label.setColor(t.text);
                label.setAlpha(255);
                float tw = Heading.width(s, heading, label);
                float ph = 8 * dp, th = 26 * dp * textScale;
                float tx = x - 8 * dp - tw - 2 * ph;
                float ty = clamp(y + fastH / 2 - th / 2, 0, getHeight() - th);
                fill.setColor(t.plate);
                float cr = rounded ? 4 * dp : 0;
                c.drawRoundRect(tx, ty, tx + tw + 2 * ph, ty + th, cr, cr, fill);
                Heading.draw(c, s, heading, tx + ph, ty + th / 2, label);
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
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                dragFastTo(e.getY());
                return true;
            }
            if (wasFlinging) pinchedThisTouch = true;
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
        if (holding) {
            if (act == MotionEvent.ACTION_MOVE) {
                listener.onHoldMove(e.getRawX(), e.getRawY());
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                holding = false;
                listener.onHoldEnd(e.getRawX(), e.getRawY(), act == MotionEvent.ACTION_CANCEL);
            }
            return true;
        }
        twoFingerTap(e);
        if (selecting && strokeTouch(e)) return true;
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (pinching && act == MotionEvent.ACTION_CANCEL) {
                pinching = false;
                liveFrom = live;
                liveStart = SystemClock.uptimeMillis();
            }
            invalidate();
        }
        return true;
    }

    /** Two fingers down together and up again quickly, without moving or pinching. */
    private void twoFingerTap(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            twoValid = false;
            twoAt = -1;
        } else if (act == MotionEvent.ACTION_POINTER_DOWN) {
            pinchedThisTouch = true;     // nothing with two fingers is a tap on one photo
            if (e.getPointerCount() == 2) {
                twoValid = true;
                twoAt = SystemClock.uptimeMillis();
                twoX = (e.getX(0) + e.getX(1)) / 2f;
                twoY = (e.getY(0) + e.getY(1)) / 2f;
                twoD = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                twoLevel = level;
            } else {
                twoValid = false;
            }
        } else if (act == MotionEvent.ACTION_MOVE && twoValid && e.getPointerCount() >= 2) {
            float mx = (e.getX(0) + e.getX(1)) / 2f, my = (e.getY(0) + e.getY(1)) / 2f;
            float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
            if (Math.hypot(mx - twoX, my - twoY) > slop * 1.5f || Math.abs(d - twoD) > slop * 1.5f) {
                twoValid = false;
            }
        } else if (act == MotionEvent.ACTION_POINTER_UP) {
            if (twoValid && level == twoLevel && SystemClock.uptimeMillis() - twoAt < 350 && listener != null) {
                int en = entryAt(twoX, twoY);
                Media.Item it = en >= 0 && eItem[en] >= 0 ? items.get(eItem[en]) : null;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                listener.onTwoFingerTap(this, it);
            }
            twoValid = false;
        }
    }

    /**
     * While selecting: a drag that starts sideways is a selecting stroke (every
     * photo from where it started to where the finger is takes the first
     * photo's new state); a drag that starts up or down scrolls as usual.
     */
    private boolean strokeTouch(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            strokeState = 0;
            downX = e.getX();
            downY = e.getY();
            lastX = e.getX();
            lastY = e.getY();
            return false;
        }
        if (e.getPointerCount() > 1 || pinching) {
            if (strokeState == 1) endStroke();
            strokeState = 2;
            return false;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            lastX = e.getX();
            lastY = e.getY();
            if (strokeState == 0) {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    int en = nearestItemEntry(downX, downY);
                    if (en < 0) {
                        strokeState = 2;
                        return false;
                    }
                    strokeState = 1;
                    strokeAnchor = eItem[en];
                    strokeOn = !selected.contains(items.get(strokeAnchor).key());
                    strokeBase = new HashSet<>(selected);
                    strokeLast = -1;
                    // the detectors saw the start of this touch; tell them it is over
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    gestures.onTouchEvent(cancel);
                    cancel.recycle();
                    strokeTo(e.getX(), e.getY());
                    return true;
                } else if (Math.abs(dy) > slop) {
                    strokeState = 2;
                }
                return false;
            }
            if (strokeState == 1) {
                strokeTo(e.getX(), e.getY());
                invalidate();   // keeps the edge scrolling going
                return true;
            }
            return false;
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (strokeState == 1) {
                endStroke();
                return true;
            }
        }
        return strokeState == 1;
    }

    private void endStroke() {
        strokeState = 2;
        strokeAnchor = -1;
        invalidate();
    }

    private int nearestItemEntry(float x, float sy) {
        int en = entryAt(x, sy);
        if (en >= 0 && eItem[en] >= 0) return en;
        float y = sy + scrollY;
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int g = groupAt(y - getHeight()); g < gCount && gTop[g] <= y + getHeight(); g++) {
            for (int k = gFirst[g]; k <= gLast[g]; k++) {
                if (eItem[k] < 0) continue;
                float cx = ex[k] + ew[k] / 2, cy = ey[k] + eh[k] / 2;
                float d = (cx - x) * (cx - x) + (cy - y) * (cy - y);
                if (d < bd) {
                    bd = d;
                    best = k;
                }
            }
        }
        return best;
    }

    private void strokeTo(float x, float sy) {
        int en = nearestItemEntry(x, sy);
        if (en < 0) return;
        int cur = eItem[en];
        if (cur == strokeLast) return;
        strokeLast = cur;
        int lo = Math.min(strokeAnchor, cur), hi = Math.max(strokeAnchor, cur);
        long now = SystemClock.uptimeMillis();
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            long k = items.get(i).key();
            boolean want = (i >= lo && i <= hi) ? strokeOn : strokeBase.contains(k);
            boolean has = selected.contains(k);
            if (want != has) {
                if (want) selected.add(k);
                else selected.remove(k);
                selAt.put(k, now);
                changed = true;
            }
        }
        if (changed) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onSelectionChanged(this, selected.size());
        }
    }

    /** near the top or bottom edge during a stroke, the feed keeps moving */
    private boolean edgeScroll() {
        if (strokeState != 1) return false;
        float zone = 56 * dp, v = 0;
        if (lastY < zone) v = -(zone - lastY) / zone;
        else if (lastY > getHeight() - zone) v = (lastY - (getHeight() - zone)) / zone;
        if (v == 0) return false;
        float before = scrollY;
        scrollY = clamp(scrollY + v * 18 * dp, 0, maxScroll());
        if (scrollY != before) {
            strokeLast = -1;
            strokeTo(lastX, lastY);
        }
        return true;
    }

    private void dragFastTo(float y) {
        float range = getHeight() - fastH;
        float f = clamp((y - fastH / 2) / Math.max(1f, range), 0f, 1f);
        scrollY = f * maxScroll();
        lastScrollAt = SystemClock.uptimeMillis();
        invalidate();
    }
}
