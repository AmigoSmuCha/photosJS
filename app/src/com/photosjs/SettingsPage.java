package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings, as a whole page at the end of the swipe. Every row shows its
 * current choice and a short line on what it does. The same stepper
 * everywhere: tap the right side of the value to step forward, the left side
 * to step back; tapping the name steps forward. Changes are live.
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
    private float contentH;
    private int pressedRow = -1, pressedSide = 0;
    private float textScale = 1f, scroll, downX, downY, downScroll;
    private boolean moved;
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
    }

    void setRows(List<Row> r) {
        rows = r;
        measureRows();
        invalidate();
    }

    private void measureRows() {
        rowTop = new float[rows.size()];
        rowBottom = new float[rows.size()];
        float y = 4 * dp;
        for (int i = 0; i < rows.size(); i++) {
            rowTop[i] = y;
            y += rows.get(i).value() == null ? headH : rowH;
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
            if (i == pressedRow && !moved && !info) {
                fill.setColor(t.plate);
                float mid = (vl + getWidth()) / 2f;
                if (pressedSide < 0) c.drawRect(vl, y, mid, yb, fill);
                else if (pressedSide > 0) c.drawRect(mid, y, getWidth(), yb, fill);
                else c.drawRect(0, y, vl, yb, fill);
            }
            float h = yb - y;
            float l1 = y + h * 0.44f, l2 = y + h * 0.80f;
            label.setColor(t.text);
            c.drawText(TextUtils.ellipsize(r.label(), label, vl - pad - 6 * dp,
                    TextUtils.TruncateAt.END).toString(), pad, l1, label);
            desc.setColor(t.s1);
            c.drawText(TextUtils.ellipsize(r.desc(), desc, getWidth() - 2 * pad,
                    TextUtils.TruncateAt.END).toString(), pad, l2, desc);
            if (info) continue;
            value.setColor(t.s2);
            float aw = value.measureText("›");
            c.drawText("‹", vl, l1, value);
            c.drawText("›", vr - aw, l1, value);
            String vv = TextUtils.ellipsize(v, value, vr - vl - 2 * aw - 8 * dp,
                    TextUtils.TruncateAt.END).toString();
            value.setColor(t.text);
            c.drawText(vv, (vl + vr) / 2f - value.measureText(vv) / 2f, l1, value);
        }
        if (again) postInvalidateOnAnimation();
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
                if (pressedRow >= 0) {
                    String pv = rows.get(pressedRow).value();
                    if (pv == null || pv.isEmpty()) pressedRow = -1;
                }
                pressedSide = side;
                downX = e.getX();
                downY = e.getY();
                downScroll = scroll;
                moved = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && (Math.abs(e.getY() - downY) > slop || Math.abs(e.getX() - downX) > slop)) {
                    moved = true;
                }
                if (moved) {
                    scroll = Math.max(0, Math.min(maxScroll(), downScroll - (e.getY() - downY)));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    int row = rowAt(e.getY());
                    if (row >= 0 && row == pressedRow) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        rows.get(row).step(side == -1 ? -1 : 1);
                        measureRows();
                    }
                } else {
                    vt.computeCurrentVelocity(1000);
                    scroller.fling(0, Math.round(scroll), 0, Math.round(-vt.getYVelocity()), 0, 0, 0,
                            Math.round(maxScroll()));
                }
                pressedRow = -1;
                vt.recycle();
                vt = null;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedRow = -1;
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
