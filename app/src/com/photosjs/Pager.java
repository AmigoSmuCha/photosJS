package com.photosjs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * Photos, folders and albums side by side. A sideways swipe moves them with
 * the finger (swiping right brings in what is on the left) and lets go into
 * the nearest page with a soft spring. Two fingers never page: that is a pinch.
 */
public class Pager extends ViewGroup {
    interface Listener {
        void onPageScrolled(float pos);

        void onPageSelected(int page);
    }

    private final int slop;
    private final float flingV;
    private Listener listener;
    private float pos;
    private int page;
    private boolean locked;
    private boolean dragging, multi;
    private float downX, downY, startPos;
    private VelocityTracker vt;
    private ValueAnimator anim;

    public Pager(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        flingV = 600 * getResources().getDisplayMetrics().density;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** No paging while selecting or while the settings panel is open. */
    void setLocked(boolean l) {
        locked = l;
    }

    int page() {
        return page;
    }

    View current() {
        return page < getChildCount() ? getChildAt(page) : null;
    }

    /**
     * Replace the pages (albums come and go), staying on the page that was
     * showing if it is still there.
     */
    void setPages(View... pages) {
        View cur = current();
        if (anim != null) anim.cancel();
        removeAllViews();
        int keep = 0;
        for (int i = 0; i < pages.length; i++) {
            addView(pages[i]);
            if (pages[i] == cur) keep = i;
        }
        page = keep;
        pos = keep;
        place();
        if (listener != null) listener.onPageSelected(page);
    }

    int indexOf(View v) {
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) == v) return i;
        return -1;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(0, 0, r - l, b - t);
        place();
    }

    private void place() {
        int w = getWidth();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            float x = (i - pos) * w;
            v.setTranslationX(x);
            // pages entirely off screen don't draw
            v.setVisibility(Math.abs(i - pos) < 0.999f ? VISIBLE : INVISIBLE);
        }
        if (listener != null) listener.onPageScrolled(pos);
    }

    void setPage(int p, boolean animate) {
        p = Math.max(0, Math.min(getChildCount() - 1, p));
        page = p;
        if (listener != null) listener.onPageSelected(p);
        settleTo(p, animate);
    }

    private void settleTo(int p, boolean animate) {
        if (anim != null) anim.cancel();
        long d = animate ? Motion.ms(360) : 0;
        if (d <= 0 || getWidth() == 0) {
            pos = p;
            place();
            return;
        }
        final float a = pos, b = p;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(d);
        anim.setInterpolator(new android.animation.TimeInterpolator() {
            @Override
            public float getInterpolation(float x) {
                return Motion.ease(x);
            }
        });
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                pos = a + (b - a) * (float) va.getAnimatedValue();
                place();
            }
        });
        anim.start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                multi = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                if (anim != null && anim.isRunning() && !locked) {
                    // catch a page that is still moving
                    anim.cancel();
                    startDrag(e.getX());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (locked || multi || e.getPointerCount() > 1) return false;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > slop && Math.abs(dx) > 1.6f * Math.abs(dy)) {
                    startDrag(e.getX());
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void startDrag(float x) {
        dragging = true;
        downX = x;
        startPos = pos;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt != null) vt.addMovement(e);
        int w = Math.max(1, getWidth());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float p = startPos - (e.getX() - downX) / w;
                float max = getChildCount() - 1;
                // a little give past the first and last page
                if (p < 0) p = p * 0.3f;
                else if (p > max) p = max + (p - max) * 0.3f;
                pos = p;
                place();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return true;
                dragging = false;
                float v = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    v = vt.getXVelocity();
                }
                int target = Math.round(pos);
                if (v < -flingV) target = (int) Math.floor(pos) + 1;
                else if (v > flingV) target = (int) Math.ceil(pos) - 1;
                target = Math.max(0, Math.min(getChildCount() - 1, target));
                page = target;
                if (listener != null) listener.onPageSelected(target);
                settleTo(target, true);
                return true;
        }
        return true;
    }
}
