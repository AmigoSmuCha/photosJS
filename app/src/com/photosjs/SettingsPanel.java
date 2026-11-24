package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * One quiet sheet. Every row shows its current choice and a short line on what
 * it does. The same stepper everywhere: tap the right side of the value to
 * step forward, the left side to step back; tapping the name steps forward.
 * A change takes effect the moment it is made. It scrolls when it is taller
 * than the room it is given.
 */
public class SettingsPanel extends View {
    interface Row {
        String label();

        String desc();

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
    private Theme t;
    private List<Row> rows = new ArrayList<>();
    private int pressedRow = -1, pressedSide = 0;
    private int maxH = Integer.MAX_VALUE;
    private float textScale = 1f, scroll, downY, downScroll;
    private boolean moved, rounded = true;
    private int rowH, topPad;

    public SettingsPanel(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
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

    void setLook(boolean rounded, float textScale) {
        this.rounded = rounded;
        this.textScale = textScale;
        applyText();
        requestLayout();
        invalidate();
    }

    private void applyText() {
        label.setTextSize(10 * dp * textScale);
        desc.setTextSize(8.5f * dp * textScale);
        value.setTextSize(10 * dp * textScale);
        head.setTextSize(8.5f * dp * textScale);
        rowH = Math.round(42 * dp * textScale);
        topPad = Math.round(18 * dp);
    }

    void setMaxHeight(int h) {
        maxH = h;
        requestLayout();
    }

    void setRows(List<Row> r) {
        rows = r;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        requestLayout();
        invalidate();
    }

    int contentHeight() {
        return topPad + rows.size() * rowH + Math.round(8 * dp);
    }

    int wantedHeight() {
        return Math.min(maxH, contentHeight());
    }

    private float maxScroll() {
        return Math.max(0, contentHeight() - wantedHeight());
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), wantedHeight());
    }

    private float valueLeft() {
        return getWidth() * 0.52f;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        float rad = rounded ? 14 * dp : 0;
        fill.setColor(t.plate);
        c.drawRoundRect(0, 0, getWidth(), getHeight() + rad, rad, rad, fill);
        // the grabber
        fill.setColor(t.s3);
        float gw = 24 * dp, gh = 3 * dp;
        float gr = rounded ? gh / 2 : 0;
        c.drawRoundRect(getWidth() / 2f - gw / 2, 7 * dp, getWidth() / 2f + gw / 2, 7 * dp + gh, gr, gr, fill);

        c.save();
        c.clipRect(0, topPad - 2 * dp, getWidth(), getHeight());
        float pad = 12 * dp;
        float vl = valueLeft(), vr = getWidth() - pad;
        for (int i = 0; i < rows.size(); i++) {
            float y = topPad + i * rowH - scroll;
            if (y + rowH < 0 || y > getHeight()) continue;
            Row r = rows.get(i);
            if (i == pressedRow && !moved) {
                fill.setColor(Theme.mix(t.plate, t.text, 0.06f));
                float mid = (vl + getWidth()) / 2f;
                if (pressedSide < 0) c.drawRect(vl, y, mid, y + rowH, fill);
                else if (pressedSide > 0) c.drawRect(mid, y, getWidth(), y + rowH, fill);
                else c.drawRect(0, y, vl, y + rowH, fill);
            }
            float l1 = y + rowH * 0.43f, l2 = y + rowH * 0.80f;
            label.setColor(t.text);
            c.drawText(TextUtils.ellipsize(r.label(), label, vl - pad - 6 * dp,
                    TextUtils.TruncateAt.END).toString(), pad, l1, label);
            desc.setColor(t.s1);
            c.drawText(TextUtils.ellipsize(r.desc(), desc, getWidth() - 2 * pad,
                    TextUtils.TruncateAt.END).toString(), pad, l2, desc);
            value.setColor(t.s2);
            float aw = value.measureText("›");
            c.drawText("‹", vl, l1, value);
            c.drawText("›", vr - aw, l1, value);
            String v = TextUtils.ellipsize(r.value(), value, vr - vl - 2 * aw - 8 * dp,
                    TextUtils.TruncateAt.END).toString();
            value.setColor(t.text);
            float vw = value.measureText(v);
            c.drawText(v, (vl + vr) / 2f - vw / 2f, l1, value);
        }
        c.restore();
        // a hint of more below
        if (scroll < maxScroll() - 1) {
            fill.setColor(t.s3);
            c.drawRect(getWidth() / 2f - 6 * dp, getHeight() - 4 * dp, getWidth() / 2f + 6 * dp,
                    getHeight() - 3 * dp, fill);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float vl = valueLeft();
        int row = (int) Math.floor((e.getY() + scroll - topPad) / rowH);
        if (e.getY() < topPad || row < 0 || row >= rows.size()) row = -1;
        int side = e.getX() < vl ? 0 : e.getX() < (vl + getWidth()) / 2f ? -1 : 1;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedRow = row;
                pressedSide = side;
                downY = e.getY();
                downScroll = scroll;
                moved = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.abs(e.getY() - downY) > slop && maxScroll() > 0) moved = true;
                if (moved) {
                    scroll = Math.max(0, Math.min(maxScroll(), downScroll - (e.getY() - downY)));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && row >= 0 && row == pressedRow) {
                    int dir = side == -1 ? -1 : 1;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    rows.get(row).step(dir);
                }
                pressedRow = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedRow = -1;
                invalidate();
                return true;
        }
        return true;
    }
}
