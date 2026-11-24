package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * One quiet panel. Every row shows its current choice and a short line on what
 * it does. The same stepper everywhere: tap the right side of the value to
 * step forward, the left side to step back; tapping the name steps forward.
 * A change takes effect the moment it is made.
 */
public class SettingsPanel extends View {
    interface Row {
        String label();

        String desc();

        String value();

        void step(int dir);
    }

    private final float dp;
    private final int rowH;
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint desc = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private Theme t;
    private List<Row> rows = new ArrayList<>();
    private int pressedRow = -1, pressedSide = 0;

    public SettingsPanel(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        rowH = Math.round(44 * dp);
        label.setTypeface(L.font(c));
        label.setTextSize(10 * dp);
        desc.setTypeface(L.font(c));
        desc.setTextSize(8.5f * dp);
        value.setTypeface(L.font(c));
        value.setTextSize(10 * dp);
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setRows(List<Row> r) {
        rows = r;
        requestLayout();
        invalidate();
    }

    int wantedHeight() {
        return rows.size() * rowH + Math.round(10 * dp);
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
        c.drawColor(t.plate);
        float pad = 12 * dp;
        float top = 5 * dp;
        float vl = valueLeft(), vr = getWidth() - pad;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float y = top + i * rowH;
            if (i == pressedRow) {
                fill.setColor(Theme.mix(t.plate, t.text, 0.06f));
                float mid = (vl + getWidth()) / 2f;
                if (pressedSide < 0) c.drawRect(vl, y, mid, y + rowH, fill);
                else if (pressedSide > 0) c.drawRect(mid, y, getWidth(), y + rowH, fill);
                else c.drawRect(0, y, vl, y + rowH, fill);
            }
            float l1 = y + rowH * 0.42f, l2 = y + rowH * 0.78f;
            label.setColor(t.text);
            c.drawText(TextUtils.ellipsize(r.label(), label, vl - pad - 6 * dp,
                    TextUtils.TruncateAt.END).toString(), pad, l1, label);
            desc.setColor(t.s1);
            c.drawText(TextUtils.ellipsize(r.desc(), desc, getWidth() - 2 * pad,
                    TextUtils.TruncateAt.END).toString(), pad, l2, desc);
            // ‹ value ›
            value.setColor(t.s2);
            float aw = value.measureText("›");
            c.drawText("‹", vl, l1, value);
            c.drawText("›", vr - aw, l1, value);
            String v = TextUtils.ellipsize(r.value(), value, vr - vl - 2 * aw - 8 * dp,
                    TextUtils.TruncateAt.END).toString();
            value.setColor(t.text);
            float vw = value.measureText(v);
            c.drawText(v, (vl + aw + vr - aw) / 2f - vw / 2f, l1, value);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float top = 5 * dp;
        int row = (int) ((e.getY() - top) / rowH);
        if (e.getY() < top || row >= rows.size()) row = -1;
        float vl = valueLeft();
        int side = e.getX() < vl ? 2 : e.getX() < (vl + getWidth()) / 2f ? -1 : 1;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedRow = row;
                pressedSide = side == 2 ? 0 : side;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (row >= 0 && row == pressedRow) {
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
