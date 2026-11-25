package com.photosjs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One photo, the whole screen. It grows out of its thumbnail and shrinks back
 * into it. Sideways: the next and previous photo, following the finger.
 * Down: back to the feed. Pinch or double tap: zoom. A tap shows the few
 * controls, another tap hides them. Hold: the same fan as in the feed.
 */
public class ViewerView extends View {
    interface Listener {
        /** where this photo sits in the feed right now (screen coords), or null */
        RectF thumbRect(Media.Item it);

        float thumbRadius();

        Bitmap thumbFor(Media.Item it);

        void onViewerIndex(Media.Item it);

        void onViewerClosed();

        void onViewerAction(String action, Media.Item it);

        void onViewerHold(Media.Item it, RectF screen, Bitmap bm, float x, float y);

        void onHoldMove(float x, float y);

        void onHoldEnd(float x, float y, boolean cancel);

        void playVideo(Media.Item it);

        /** picking several for another app: is this photo among them? */
        boolean chosen(Media.Item it);
    }

    static final String[] ACTIONS = {"album", "edit", "share", "aside", "trash", "info"};
    private String[] actions = ACTIONS;

    private final float dp;
    private Theme t;
    private Listener listener;
    private boolean rounded = true, dateNumbers;
    private float textScale = 1f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF r = new RectF(), fit = new RectF(), tmp = new RectF();
    private final Path clip = new Path();
    private final RectF[] actionRects = new RectF[8];

    private List<Media.Item> items = new ArrayList<>();
    private int index;
    private boolean open;

    // the full-size pictures around the current one
    private final HashMap<Long, Bitmap> full = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();
    // bumped when a photo is replaced, so a decode of the old file that is still running is dropped
    private final HashMap<Long, Integer> gen = new HashMap<>();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // zoom and pan of the current photo
    private float scale = 1f, tx, ty;
    // sideways paging
    private float pageX;
    // pulling down to close
    private float dragX, dragY;
    private int axis;               // 0 undecided, 1 paging/panning, 2 dismissing
    private boolean pinching, holding;

    // open / close flight
    private final RectF flightFrom = new RectF();
    private float flightRadius;
    private float flight = 1f;      // 0 = at the thumbnail, 1 = in place
    private boolean closing;
    private ValueAnimator anim;

    // a message in the viewer's top line (the app's own line is underneath)
    private String note;
    private long noteUntil;
    private Runnable noteUndo;

    private float chrome = 0f;      // 0 hidden .. 1 shown
    private boolean chromeOn;
    private ValueAnimator chromeAnim;

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;

    public ViewerView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        text.setTypeface(L.font(c));
        for (int i = 0; i < actionRects.length; i++) actionRects[i] = new RectF();
        setVisibility(INVISIBLE);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                tap(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (current() == null || current().video) return true;
                float target = scale > 1.05f ? 1f : 2.5f;
                zoomTo(target, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (pinching || e2.getPointerCount() > 1 || closing) return true;
                drag(dx, dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                flingV[0] = vx;
                flingV[1] = vy;
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                Media.Item it = current();
                if (it == null || listener == null || pinching || axis != 0) return;
                holding = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                photoRect(index, r);
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                RectF scr = new RectF(r);
                scr.offset(loc[0], loc[1]);
                listener.onViewerHold(it, scr, listener.thumbFor(it), e.getRawX(), e.getRawY());
            }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (current() == null || current().video || axis == 2) return false;
                pinching = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
                if (scale < 1f) zoomTo(1f, getWidth() / 2f, getHeight() / 2f);
            }
        });
    }

    private final float[] flingV = new float[2];

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** what the bottom row offers (picking for another app offers "use", maybe "choose") */
    void setActions(String[] a) {
        actions = a == null || a.length == 0 ? ACTIONS : a;
        invalidate();
    }

    void setLook(boolean rounded, boolean dateNumbers, float textScale) {
        this.rounded = rounded;
        this.dateNumbers = dateNumbers;
        this.textScale = textScale;
        text.setTextSize(10.5f * dp * textScale);
        invalidate();
    }

    boolean isOpen() {
        return open;
    }

    void note(String text, long ms, Runnable undo) {
        note = text;
        noteUndo = undo;
        noteUntil = SystemClock.uptimeMillis() + ms;
        if (!chromeOn) setChrome(true);
        invalidate();
        postInvalidateDelayed(ms + 20);
    }

    void clearNote() {
        note = null;
        noteUndo = null;
        invalidate();
    }

    private boolean noteShowing() {
        if (note == null) return false;
        if (SystemClock.uptimeMillis() > noteUntil) {
            note = null;
            noteUndo = null;
            return false;
        }
        return true;
    }

    Media.Item current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    // ---- opening and closing ----

    void open(List<Media.Item> list, int at, RectF fromScreen, float radius) {
        items = new ArrayList<>(list);
        index = Math.max(0, Math.min(items.size() - 1, at));
        scale = 1f;
        tx = ty = pageX = dragX = dragY = 0;
        axis = 0;
        closing = false;
        chromeOn = false;
        chrome = 0f;
        open = true;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        if (fromScreen != null) {
            flightFrom.set(fromScreen);
            flightFrom.offset(-loc[0], -loc[1]);
        } else {
            flightFrom.setEmpty();
        }
        flightRadius = radius;
        setVisibility(VISIBLE);
        request();
        animateFlight(0f, 1f, null);
        if (listener != null) listener.onViewerIndex(current());
    }

    void close() {
        if (!open || closing) return;
        closing = true;
        Media.Item it = current();
        // fly from where the photo is drawn now to where it sits in the feed
        RectF now = new RectF();
        photoRect(index, now);
        RectF target = it != null && listener != null ? listener.thumbRect(it) : null;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        if (target != null) {
            target.offset(-loc[0], -loc[1]);
            flightFrom.set(target);
        } else {
            flightFrom.setEmpty();
        }
        // restart the flight from the photo's current place, the dark as it is now
        closeBg = 1f - 0.85f * dismissProgress();
        scale = 1f;
        tx = ty = pageX = 0;
        closeFrom.set(now);
        dragX = dragY = 0;
        animateFlight(1f, 0f, new Runnable() {
            @Override
            public void run() {
                finishClose();
            }
        });
    }

    private final RectF closeFrom = new RectF();
    private float closeBg = 1f;

    private void finishClose() {
        open = false;
        closing = false;
        setVisibility(INVISIBLE);
        for (Bitmap b : full.values()) {
            if (b != null && b.getConfig() != Bitmap.Config.HARDWARE) b.recycle();
        }
        full.clear();
        if (listener != null) listener.onViewerClosed();
    }

    /** Take a photo out (hidden, trashed): move on to the next one, or close when none are left. */
    void remove(long key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key() == key) {
                items.remove(i);
                if (items.isEmpty()) {
                    open = false;
                    setVisibility(INVISIBLE);
                    if (listener != null) listener.onViewerClosed();
                    return;
                }
                if (index >= items.size()) index = items.size() - 1;
                scale = 1f;
                tx = ty = 0;
                request();
                if (listener != null) listener.onViewerIndex(current());
                invalidate();
                return;
            }
        }
    }

    private void animateFlight(final float a, final float b, final Runnable end) {
        if (anim != null) anim.cancel();
        long d = Motion.ms(closing ? 300 : 340);
        if (d <= 0) {
            flight = b;
            invalidate();
            if (end != null) end.run();
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(d);
        anim.setInterpolator(new android.animation.TimeInterpolator() {
            @Override
            public float getInterpolation(float x) {
                return x;
            }
        });
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                float u = (float) v.getAnimatedValue();
                float e = b > a ? Motion.ease(u) : Spring.out(u);
                flight = a + (b - a) * e;
                invalidate();
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator x) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                if (!cancelled && end != null) end.run();
            }
        });
        anim.start();
    }

    // ---- pictures ----

    private void request() {
        int[] order = {index, index + 1, index - 1};
        Set<Long> keep = new HashSet<>();
        for (int i : order) if (i >= 0 && i < items.size()) keep.add(items.get(i).key());
        for (Long k : new ArrayList<>(full.keySet())) {
            if (!keep.contains(k)) {
                Bitmap b = full.remove(k);
                if (b != null && b.getConfig() != Bitmap.Config.HARDWARE) b.recycle();
            }
        }
        for (int i : order) {
            if (i < 0 || i >= items.size()) continue;
            final Media.Item it = items.get(i);
            if (it.video || full.containsKey(it.key()) || loading.contains(it.key())) continue;
            loading.add(it.key());
            final Integer started = gen.get(it.key());
            decoder.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap b = null;
                    try {
                        b = ImageDecoder.decodeBitmap(
                                ImageDecoder.createSource(getContext().getContentResolver(), it.uri()),
                                new ImageDecoder.OnHeaderDecodedListener() {
                                    @Override
                                    public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info,
                                                                ImageDecoder.Source s) {
                                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                                        int max = 2400;
                                        if (Math.max(w, h) > max) {
                                            float f = max / (float) Math.max(w, h);
                                            d.setTargetSize(Math.max(1, Math.round(w * f)),
                                                    Math.max(1, Math.round(h * f)));
                                        }
                                    }
                                });
                    } catch (Throwable e) {
                        b = null;
                    }
                    final Bitmap got = b;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            loading.remove(it.key());
                            Integer now = gen.get(it.key());
                            boolean same = now == null ? started == null : now.equals(started);
                            if (got != null && open && same) {
                                full.put(it.key(), got);
                                invalidate();
                            }
                        }
                    });
                }
            });
        }
    }

    /** The photo was changed (saved over): show the new picture right away. */
    void replace(Media.Item it, Bitmap b) {
        Integer g = gen.get(it.key());
        gen.put(it.key(), g == null ? 1 : g + 1);
        loading.remove(it.key());
        Bitmap old = full.put(it.key(), b);
        if (old != null && old != b && old.getConfig() != Bitmap.Config.HARDWARE) old.recycle();
        // the same photo object may sit in this list: its shape may have changed with a crop
        for (Media.Item x : items) {
            if (x.key() == it.key()) {
                x.w = it.w;
                x.h = it.h;
            }
        }
        invalidate();
    }

    private Bitmap bitmapFor(Media.Item it) {
        Bitmap b = full.get(it.key());
        if (b != null) return b;
        return listener != null ? listener.thumbFor(it) : null;
    }

    // ---- geometry ----

    private float gapX() {
        return 16 * dp;
    }

    /** the photo fitted into the screen */
    private void fitted(Media.Item it, RectF out) {
        float W = getWidth(), H = getHeight();
        float a = it.w > 0 && it.h > 0 ? (float) it.w / it.h : 1f;
        if (a > W / H) {
            float h = W / a;
            out.set(0, (H - h) / 2f, W, (H + h) / 2f);
        } else {
            float w = H * a;
            out.set((W - w) / 2f, 0, (W + w) / 2f, H);
        }
    }

    /** where page i is drawn now, everything included */
    private void photoRect(int i, RectF out) {
        Media.Item it = items.get(i);
        fitted(it, out);
        float off = (i - index) * (getWidth() + gapX()) + pageX;
        if (i == index) {
            float cx = out.centerX(), cy = out.centerY();
            float w = out.width() * scale, h = out.height() * scale;
            out.set(cx - w / 2 + tx, cy - h / 2 + ty, cx + w / 2 + tx, cy + h / 2 + ty);
            if (dragY != 0 || dragX != 0) {
                float p = dismissProgress();
                float s = 1f - 0.3f * p;
                float dcx = out.centerX() + dragX, dcy = out.centerY() + dragY;
                float hw = out.width() * s / 2, hh = out.height() * s / 2;
                out.set(dcx - hw, dcy - hh, dcx + hw, dcy + hh);
            }
        }
        out.offset(off, 0);
    }

    private float dismissProgress() {
        return Math.max(0f, Math.min(1f, dragY / (getHeight() * 0.5f)));
    }

    private void clampPan() {
        Media.Item it = current();
        if (it == null) return;
        fitted(it, fit);
        float dw = fit.width() * scale, dh = fit.height() * scale;
        float W = getWidth(), H = getHeight();
        float mx = Math.max(0, (dw - W) / 2f), my = Math.max(0, (dh - H) / 2f);
        // a photo narrower (or shorter) than the screen stays centred on that axis
        tx = dw <= W ? 0 : Math.max(-mx, Math.min(mx, tx));
        ty = dh <= H ? 0 : Math.max(-my, Math.min(my, ty));
    }

    private void zoomBy(float f, float fx, float fy) {
        Media.Item it = current();
        if (it == null) return;
        float ns = Math.max(0.85f, Math.min(6f, scale * f));
        fitted(it, fit);
        float cx = fit.centerX(), cy = fit.centerY();
        // keep the point under the fingers where it is
        tx = fx - cx - (fx - cx - tx) * (ns / scale);
        ty = fy - cy - (fy - cy - ty) * (ns / scale);
        scale = ns;
        if (scale >= 1f) clampPan();
        invalidate();
    }

    private void zoomTo(final float target, final float fx, final float fy) {
        if (anim != null && anim.isRunning() && !closing) anim.cancel();
        long d = Motion.ms(280);
        if (d <= 0) {
            zoomBy(target / scale, fx, fy);
            if (target == 1f) {
                tx = ty = 0;
            }
            return;
        }
        final float from = scale;
        final float ftx = tx, fty = ty;
        Media.Item it = current();
        if (it == null) return;
        fitted(it, fit);
        final float cx = fit.centerX(), cy = fit.centerY();
        ValueAnimator z = ValueAnimator.ofFloat(0f, 1f);
        z.setDuration(d);
        z.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                float e = Motion.ease((float) v.getAnimatedFraction());
                float s = from + (target - from) * e;
                tx = fx - cx - (fx - cx - ftx) * (s / from);
                ty = fy - cy - (fy - cy - fty) * (s / from);
                scale = s;
                if (target == 1f) {
                    tx *= 1f - e;
                    ty *= 1f - e;
                }
                clampPan();
                invalidate();
            }
        });
        z.start();
    }

    // ---- touch ----

    private void drag(float dx, float dy) {
        if (axis == 0) {
            if (scale > 1.02f) axis = 1;
            else if (Math.abs(dy) > Math.abs(dx) && dy < 0) axis = 2;   // finger moving down
            else axis = 1;
        }
        if (axis == 2) {
            dragX -= dx;
            dragY -= dy;
            invalidate();
            return;
        }
        if (scale > 1.02f && pageX == 0) {
            float before = tx;
            tx -= dx;
            ty -= dy;
            clampPan();
            float overflow = (before - dx) - tx;
            if (Math.abs(overflow) > 0.5f && Math.abs(dx) > Math.abs(dy)) pageX += overflow;
        } else {
            pageX -= dx;
            // a little give at the ends
            if ((index == 0 && pageX > 0) || (index == items.size() - 1 && pageX < 0)) pageX += dx * 0.65f;
        }
        invalidate();
    }

    private void release() {
        float vx = flingV[0], vy = flingV[1];
        flingV[0] = flingV[1] = 0;
        if (axis == 2) {
            if (dragY > getHeight() * 0.15f || vy > 1400) {
                close();
            } else {
                settleBack();
            }
        } else if (pageX != 0) {
            float W = getWidth() + gapX();
            int dir = 0;
            if ((pageX < -W * 0.25f || vx < -900) && index < items.size() - 1) dir = 1;
            else if ((pageX > W * 0.25f || vx > 900) && index > 0) dir = -1;
            settlePage(dir);
        }
        axis = 0;
    }

    private void settleBack() {
        final float fx = dragX, fy = dragY;
        long d = Motion.ms(260);
        if (d <= 0) {
            dragX = dragY = 0;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(d);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                float e = Motion.ease(v.getAnimatedFraction());
                dragX = fx * (1f - e);
                dragY = fy * (1f - e);
                invalidate();
            }
        });
        a.start();
    }

    private void settlePage(final int dir) {
        final float from = pageX;
        final float W = getWidth() + gapX();
        final float to = -dir * W;
        long d = Motion.ms(300);
        Runnable done = new Runnable() {
            @Override
            public void run() {
                if (dir != 0) {
                    index += dir;
                    scale = 1f;
                    tx = ty = 0;
                    request();
                    if (listener != null) listener.onViewerIndex(current());
                }
                pageX = 0;
                invalidate();
            }
        };
        if (d <= 0) {
            done.run();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(d);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                pageX = from + (to - from) * Motion.ease(v.getAnimatedFraction());
                invalidate();
            }
        });
        final Runnable fin = done;
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                fin.run();
            }
        });
        a.start();
    }

    private void tap(float x, float y) {
        if (closing) return;
        float barH = 46 * dp * Math.min(1.25f, textScale);
        if (chrome > 0.5f && y < barH && noteShowing() && noteUndo != null) {
            Runnable u = noteUndo;
            clearNote();
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            u.run();
            return;
        }
        if (chrome > 0.5f) {
            for (int i = 0; i < actions.length; i++) {
                if (actionRects[i].contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null && current() != null) listener.onViewerAction(actions[i], current());
                    return;
                }
            }
        }
        Media.Item it = current();
        if (it != null && it.video) {
            photoRect(index, r);
            if (r.contains(x, y)) {
                if (listener != null) listener.playVideo(it);
                return;
            }
        }
        setChrome(!chromeOn);
    }

    void setChrome(boolean on) {
        chromeOn = on;
        if (chromeAnim != null) chromeAnim.cancel();
        final float from = chrome, to = on ? 1f : 0f;
        long d = Motion.ms(200);
        if (d <= 0) {
            chrome = to;
            invalidate();
            return;
        }
        chromeAnim = ValueAnimator.ofFloat(0f, 1f);
        chromeAnim.setDuration(d);
        chromeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                chrome = from + (to - from) * v.getAnimatedFraction();
                invalidate();
            }
        });
        chromeAnim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!open) return false;
        if (closing || flight < 1f) return true;
        int act = e.getActionMasked();
        if (holding) {
            if (act == MotionEvent.ACTION_MOVE) {
                listener.onHoldMove(e.getRawX(), e.getRawY());
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                holding = false;
                listener.onHoldEnd(e.getRawX(), e.getRawY(), act == MotionEvent.ACTION_CANCEL);
            }
            return true;
        }
        if (act == MotionEvent.ACTION_DOWN) {
            axis = 0;
            flingV[0] = flingV[1] = 0;
        }
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) release();
        return true;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        if (!open || t == null || items.isEmpty()) return;
        float p = dismissProgress();
        float bgA = closing ? closeBg * flight : flight * (1f - 0.85f * p);
        fill.setColor(t.bg);
        fill.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, bgA))));
        c.drawRect(0, 0, getWidth(), getHeight(), fill);

        if (flight < 1f) {
            // the flight between the thumbnail and its place
            Media.Item it = current();
            RectF end = new RectF();
            if (closing && !closeFrom.isEmpty()) end.set(closeFrom);
            else fitted(it, end);
            RectF a = flightFrom.isEmpty() ? shrunk(end) : flightFrom;
            float e = flight;
            r.set(a.left + (end.left - a.left) * e, a.top + (end.top - a.top) * e,
                    a.right + (end.right - a.right) * e, a.bottom + (end.bottom - a.bottom) * e);
            float rad = flightRadius * (1f - e);
            drawPicture(c, it, r, rad, flightFrom.isEmpty() ? e : 1f);
            return;
        }
        for (int i = Math.max(0, index - 1); i <= Math.min(items.size() - 1, index + 1); i++) {
            photoRect(i, r);
            if (r.right < 0 || r.left > getWidth()) continue;
            float rad = i == index && (dragX != 0 || dragY != 0) ? (rounded ? 10 * dp : 0) * p : 0;
            drawPicture(c, items.get(i), r, rad, 1f);
        }
        if (chrome > 0f) drawChrome(c);
    }

    private RectF shrunk(RectF end) {
        RectF s = new RectF(end);
        s.inset(end.width() * 0.08f, end.height() * 0.08f);
        return s;
    }

    private void drawPicture(Canvas c, Media.Item it, RectF dst, float rad, float alpha) {
        Bitmap b = bitmapFor(it);
        if (b == null) {
            fill.setColor(Theme.mix(t.bg, t.plate, 0.7f));
            fill.setAlpha(Math.round(255 * alpha));
            c.drawRoundRect(dst, rad, rad, fill);
            return;
        }
        float bw = b.getWidth(), bh = b.getHeight();
        float da = dst.width() / Math.max(1f, dst.height()), ba = bw / Math.max(1f, bh);
        if (ba > da) {
            float sw = bh * da;
            src.set(Math.round((bw - sw) / 2), 0, Math.round((bw + sw) / 2), (int) bh);
        } else {
            float sh = bw / da;
            src.set(0, Math.round((bh - sh) / 2), (int) bw, Math.round((bh + sh) / 2));
        }
        paint.setAlpha(Math.round(255 * alpha));
        if (rad > 0.5f) {
            c.save();
            clip.reset();
            clip.addRoundRect(dst, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.drawBitmap(b, src, dst, paint);
            c.restore();
        } else {
            c.drawBitmap(b, src, dst, paint);
        }
        if (it.video) {
            // a still frame; a tap hands it to VLC
            float s = 44 * dp;
            float cx = dst.centerX(), cy = dst.centerY();
            fill.setColor(t.plate);
            fill.setAlpha(Math.round(230 * alpha));
            float cr = rounded ? 12 * dp : 0;
            c.drawRoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, cr, cr, fill);
            fill.setColor(t.text);
            fill.setAlpha(Math.round(255 * alpha));
            clip.reset();
            clip.moveTo(cx - 6 * dp, cy - 9 * dp);
            clip.lineTo(cx + 10 * dp, cy);
            clip.lineTo(cx - 6 * dp, cy + 9 * dp);
            clip.close();
            c.drawPath(clip, fill);
        }
    }

    private void drawChrome(Canvas c) {
        Media.Item it = current();
        if (it == null) return;
        int a = Math.round(255 * chrome);
        float h = 46 * dp * Math.min(1.25f, textScale);
        float base = h / 2f + text.getTextSize() * 0.36f;
        // top: when, and where in the feed
        fill.setColor(t.bg);
        fill.setAlpha(Math.round(220 * chrome));
        c.drawRect(0, 0, getWidth(), h, fill);
        String pos = (index + 1) + " / " + items.size();
        text.setColor(t.s1);
        text.setAlpha(a);
        float pw = text.measureText(pos);
        c.drawText(pos, getWidth() - 14 * dp - pw, base, text);
        boolean n = noteShowing();
        text.setColor(n ? t.s1 : t.text);
        text.setAlpha(a);
        String when = TextUtils.ellipsize(n ? note : when(it), new android.text.TextPaint(text),
                getWidth() - 40 * dp - pw, TextUtils.TruncateAt.END).toString();
        c.drawText(when, 14 * dp, base, text);
        // bottom: what can be done
        float top = getHeight() - h;
        fill.setColor(t.plate);
        fill.setAlpha(Math.round(240 * chrome));
        c.drawRect(0, top, getWidth(), getHeight(), fill);
        float each = getWidth() / (float) actions.length;
        for (int i = 0; i < actions.length; i++) {
            actionRects[i].set(i * each, top, (i + 1) * each, getHeight());
            String label = actions[i];
            int col = t.text;
            if (label.equals("choose") && listener != null && listener.chosen(it)) {
                label = "chosen";
                col = t.accent;
            } else if (label.equals("use")) {
                col = t.accent;
            }
            text.setColor(col);
            text.setAlpha(a);
            float tw = text.measureText(label);
            c.drawText(label, i * each + (each - tw) / 2f, top + base, text);
        }
    }

    private static final String[] DAYS = {"", "sun", "mon", "tue", "wed", "thu", "fri", "sat"};
    private static final String[] MON = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug",
            "sep", "oct", "nov", "dec"};

    private String when(Media.Item it) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(it.taken);
        int d = cal.get(Calendar.DAY_OF_MONTH), m = cal.get(Calendar.MONTH), y = cal.get(Calendar.YEAR);
        String time = String.format(Locale.US, "%d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        String date = dateNumbers ? d + "." + (m + 1) + "." + y : d + " " + MON[m] + " " + y;
        return DAYS[cal.get(Calendar.DAY_OF_WEEK)] + " " + date + " · " + time;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        clampPan();
    }

    @SuppressWarnings("unused")
    private long now() {
        return SystemClock.uptimeMillis();
    }
}
