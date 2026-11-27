package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The settings, as a whole page at the end of the swipe. Every row shows its
 * current choice and a short line on what it does. The same stepper
 * everywhere: tap the right side of the value to step forward, the left side
 * to step back; the name does nothing (the user, 2026-11-28: only the arrows
 * or the gesture change a setting). Or swipe the value sideways:
 * it follows the finger, and the next one slides in after it. What is
 * pressed lights up in the lamp colour; nothing gets a box. Changes are live.
 */
public class SettingsPage extends View {
    interface Row {
        String label();

        String desc();

        /** null for a section heading */
        String value();

        void step(int dir);
    }

    private final float dp;
    private final int slop;
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint desc = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private VelocityTracker vt;
    private Theme t;
    private List<Row> rows = new ArrayList<>();
    private float[] rowTop = new float[0], rowBottom = new float[0];
    // each row's name and description, broken into lines in the app's face
    private List<List<String>> labLines = new ArrayList<>(), descLines = new ArrayList<>(),
            valLines = new ArrayList<>();
    private float labLine, descLine, rowPad;
    private float contentH;
    private int pressedRow = -1, pressedSide = 0;
    private float textScale = 1f, scroll, downX, downY, downScroll;
    private boolean moved;
    // swiping a value: the row, how far the value has followed the finger
    private int swipeRow = -1;
    private float swipeDX;
    private boolean swiping;
    // a value changing: the old one slides out, the new one in (dir -1 = to the left)
    private int slideRow = -1, slideDir;
    private float slideFrom;
    private List<String> slideOld = new ArrayList<>();
    private long slideAt;
    // a swipe let go without stepping springs back from here
    private int backRow = -1;
    private float backFrom;
    private long backAt;
    private int heading;
    private int rowH, headH;

    public SettingsPage(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        scroller = new OverScroller(c);
        label.setTypeface(L.font(c));
        desc.setTypeface(L.font(c));
        value.setTypeface(L.font(c));
        head.setTypeface(L.font(c));
        applyText();
    }

    void setTheme(Theme th) {
        t = th;
        // values are measured once per change: read them again (the theme's name among them)
        measureRows();
        invalidate();
    }

    void setLook(int heading, float textScale) {
        this.heading = heading;
        this.textScale = textScale;
        applyText();
        measureRows();
        invalidate();
    }

    private void applyText() {
        label.setTextSize(10.5f * dp * textScale);
        desc.setTextSize(8.5f * dp * textScale);
        value.setTextSize(10.5f * dp * textScale);
        head.setTextSize(9 * dp * textScale);
        rowH = Math.round(46 * dp * textScale);
        headH = Math.round(34 * dp * textScale);
        labLine = label.getTextSize() * 1.42f;
        descLine = desc.getTextSize() * 1.5f;
        rowPad = 7 * dp * textScale;
    }

    void setRows(List<Row> r) {
        rows = r;
        measureRows();
        invalidate();
    }

    /**
     * Rows grow to fit: a name may take two lines (left of the value), a
     * description up to three, so a wide face (blocks) still says it all.
     */
    private void measureRows() {
        rowTop = new float[rows.size()];
        rowBottom = new float[rows.size()];
        labLines = new ArrayList<>();
        descLines = new ArrayList<>();
        valLines = new ArrayList<>();
        float aw = Heading.measure("›", value);
        float W = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        float pad = 14 * dp;
        float y = 4 * dp;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            rowTop[i] = y;
            String v = r.value();
            if (v == null) {
                labLines.add(Collections.<String>emptyList());
                descLines.add(Collections.<String>emptyList());
                valLines.add(Collections.<String>emptyList());
                y += headH;
            } else {
                float labRoom = v.isEmpty() ? W - 2 * pad : W * 0.5f - pad - 6 * dp;
                List<String> lab = Heading.wrap(r.label(), label, labRoom, 2);
                List<String> des = Heading.wrap(r.desc(), desc, W - 2 * pad, 3);
                List<String> val = v.isEmpty() ? Collections.<String>emptyList()
                        : Heading.wrap(v, value, W * 0.5f - pad - 2 * aw - 8 * dp, 2);
                labLines.add(lab);
                descLines.add(des);
                valLines.add(val);
                int top = Math.max(1, Math.max(lab.size(), val.size()));
                float h = 2 * rowPad + top * labLine + des.size() * descLine;
                y += Math.max(rowH, h);
            }
            rowBottom[i] = y;
        }
        contentH = y + 16 * dp;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private float maxScroll() {
        return Math.max(0, contentH - getHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w != ow) measureRows();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private float valueLeft() {
        return getWidth() * 0.5f;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        c.drawColor(t.bg);
        boolean again = false;
        if (scroller.computeScrollOffset()) {
            scroll = Math.max(0, Math.min(maxScroll(), scroller.getCurrY()));
            again = true;
        }
        float pad = 14 * dp;
        float vl = valueLeft(), vr = getWidth() - pad;
        for (int i = 0; i < rows.size(); i++) {
            float y = rowTop[i] - scroll, yb = rowBottom[i] - scroll;
            if (yb < 0 || y > getHeight()) continue;
            Row r = rows.get(i);
            String v = r.value();
            if (v == null) {
                Heading.style(head, getContext(), heading, 9 * dp * textScale);
                head.setColor(t.s2);
                float hy = yb - (yb - y) * 0.28f;
                Heading.draw(c, r.label(), heading, pad, hy - head.getTextSize() * 0.36f, head);
                fill.setColor(t.hair);
                c.drawRect(pad, yb - 1, getWidth() - pad, yb, fill);
                continue;
            }
            boolean info = v.isEmpty();   // a line to read, nothing to step
            boolean pressed = i == pressedRow && !moved && !info;
            // the lines sit in the middle of the row
            List<String> lab = labLines.get(i), des = descLines.get(i), val = valLines.get(i);
            int topLines = Math.max(1, Math.max(lab.size(), val.size()));
            float block = topLines * labLine + des.size() * descLine;
            float ly = y + ((yb - y) - block) / 2f;
            float l1 = ly + labLine * 0.72f;
            label.setColor(pressed && pressedSide == 0 ? t.accent : t.text);
            for (int k = 0; k < lab.size(); k++) Heading.text(c, lab.get(k), pad, l1 + k * labLine, label);
            desc.setColor(t.s1);
            float dy = ly + topLines * labLine + descLine * 0.70f;
            for (int k = 0; k < des.size(); k++) Heading.text(c, des.get(k), pad, dy + k * descLine, desc);
            if (info) continue;
            float aw = Heading.measure("›", value);
            boolean left = pressed && pressedSide < 0, right = pressed && pressedSide > 0;
            value.setColor(left || (swiping && swipeRow == i && swipeDX > 0) ? t.accent : t.s2);
            Heading.text(c, "‹", vl, l1, value);
            value.setColor(right || (swiping && swipeRow == i && swipeDX < 0) ? t.accent : t.s2);
            Heading.text(c, "›", vr - aw, l1, value);
            // the value: still, following a finger, springing back, or sliding to the next one
            float travel = (vr - vl) / 2f;
            float lo = vl + aw + 4 * dp, hi = vr - aw - 4 * dp;
            c.save();
            c.clipRect(lo, y, hi, yb);
            int col = pressed && pressedSide != 0 ? Theme.mix(t.text, t.accent, 0.55f) : t.text;
            long now = SystemClock.uptimeMillis();
            if (i == slideRow) {
                float u = Math.min(1f, (now - slideAt) / (float) Math.max(1, Motion.ms(240)));
                float e = Motion.level() == Motion.OFF ? 1f : Spring.out(u);
                if (u < 1f) again = true;
                else slideRow = -1;
                drawValue(c, slideOld, slideFrom + (slideDir * travel - slideFrom) * e, l1, vl, vr, t.text, 1f - e);
                drawValue(c, val, -slideDir * travel * (1f - e), l1, vl, vr, col, e);
            } else if (swiping && i == swipeRow) {
                drawValue(c, val, swipeDX, l1, vl, vr, t.accent, 1f - Math.min(0.7f, Math.abs(swipeDX) / travel));
            } else if (i == backRow) {
                float u = Math.min(1f, (now - backAt) / (float) Math.max(1, Motion.ms(260)));
                if (u < 1f) again = true;
                else backRow = -1;
                drawValue(c, val, backFrom * (1f - Motion.ease(u)), l1, vl, vr, col, 1f);
            } else {
                drawValue(c, val, 0, l1, vl, vr, col, 1f);
            }
            c.restore();
        }
        if (again) postInvalidateOnAnimation();
    }

    private void drawValue(Canvas c, List<String> lines, float dx, float l1, float vl, float vr, int color,
                           float alpha) {
        if (alpha <= 0f) return;
        value.setColor(color);
        value.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        for (int k = 0; k < lines.size(); k++) {
            String vv = lines.get(k);
            Heading.text(c, vv, (vl + vr) / 2f - Heading.measure(vv, value) / 2f + dx, l1 + k * labLine, value);
        }
        value.setAlpha(255);
    }

    /** steps row i, the old value sliding out towards dir (-1 left, +1 right) from offset 'from' */
    private void stepRow(int i, int step, int dir, float from) {
        slideOld = new ArrayList<>(valLines.get(i));
        rows.get(i).step(step);
        measureRows();
        slideRow = i;
        slideDir = dir;
        slideFrom = from;
        slideAt = SystemClock.uptimeMillis();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    }

    private boolean steppable(int row) {
        if (row < 0 || row >= rows.size()) return false;
        String v = rows.get(row).value();
        return v != null && !v.isEmpty();
    }

    private int rowAt(float y) {
        float cy = y + scroll;
        for (int i = 0; i < rows.size(); i++) if (cy >= rowTop[i] && cy < rowBottom[i]) return i;
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        float vl = valueLeft();
        int side = e.getX() < vl ? 0 : e.getX() < (vl + getWidth()) / 2f ? -1 : 1;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                pressedRow = rowAt(e.getY());
                // only the value's half answers: the name is for reading
                if (!steppable(pressedRow) || side == 0) pressedRow = -1;
                pressedSide = side;
                downX = e.getX();
                downY = e.getY();
                downScroll = scroll;
                moved = false;
                swiping = false;
                // a value can be swiped: the pages don't take a sideways move that starts there
                swipeRow = pressedRow >= 0 && side != 0 ? pressedRow : -1;
                if (swipeRow >= 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!moved && (Math.abs(dy) > slop || Math.abs(dx) > slop)) {
                    moved = true;
                    swiping = swipeRow >= 0 && Math.abs(dx) > Math.abs(dy);
                    if (swiping) {
                        slideRow = -1;
                        backRow = -1;
                    }
                }
                if (swiping) {
                    float travel = (getWidth() - 14 * dp - vl) / 2f;
                    swipeDX = Math.max(-travel, Math.min(travel, dx));
                    invalidate();
                } else if (moved) {
                    scroll = Math.max(0, Math.min(maxScroll(), downScroll - dy));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (swiping) {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    boolean go = Math.abs(swipeDX) > 36 * dp || (Math.abs(vx) > 900 && Math.abs(swipeDX) > 8 * dp);
                    if (go) {
                        // finger to the left: the next value comes in from the right
                        int dir = swipeDX < 0 ? -1 : 1;
                        stepRow(swipeRow, dir < 0 ? 1 : -1, dir, swipeDX);
                    } else {
                        backRow = swipeRow;
                        backFrom = swipeDX;
                        backAt = SystemClock.uptimeMillis();
                    }
                } else if (!moved) {
                    int row = rowAt(e.getY());
                    if (row >= 0 && row == pressedRow) {
                        int step = side == -1 ? -1 : 1;
                        stepRow(row, step, step > 0 ? -1 : 1, 0);
                    }
                } else {
                    vt.computeCurrentVelocity(1000);
                    scroller.fling(0, Math.round(scroll), 0, Math.round(-vt.getYVelocity()), 0, 0, 0,
                            Math.round(maxScroll()));
                }
                pressedRow = -1;
                swiping = false;
                swipeRow = -1;
                swipeDX = 0;
                vt.recycle();
                vt = null;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedRow = -1;
                if (swiping) {
                    backRow = swipeRow;
                    backFrom = swipeDX;
                    backAt = SystemClock.uptimeMillis();
                }
                swiping = false;
                swipeRow = -1;
                swipeDX = 0;
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                invalidate();
                return true;
        }
        return true;
    }
}
