package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The bottom bar, where the thumb is: photos / folders / albums on the left,
 * the select square and the settings mark on the right. Its height never
 * changes; other modes will only swap what is drawn inside it.
 */
public class BarView extends View {
    interface Listener {
        void onTab(int tab);

        void onSelectToggle();

        void onSettings();
    }

    static final String[] TABS = {"photos", "folders", "albums"};

    private final float dp;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] tabRects = {new RectF(), new RectF(), new RectF()};
    private final RectF selRect = new RectF(), setRect = new RectF();
    private Theme t;
    private Listener listener;
    private int tab = 0, pressed = -1;
    private boolean settingsOpen;

    public BarView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        text.setTypeface(L.font(c));
        text.setTextSize(10.5f * dp);
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTab(int i) {
        tab = i;
        invalidate();
    }

    void setSettingsOpen(boolean open) {
        settingsOpen = open;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        c.drawColor(t.plate);
        float h = getHeight();
        float x = 4 * dp;
        float base = h / 2f + text.getTextSize() * 0.36f;
        for (int i = 0; i < TABS.length; i++) {
            float w = text.measureText(TABS[i]) + 14 * dp;
            tabRects[i].set(x, 0, x + w, h);
            int col = i == tab ? t.text : t.s1;
            if (i == pressed) col = Theme.mix(col, t.plate, 0.35f);
            text.setColor(col);
            c.drawText(TABS[i], x + 7 * dp, base, text);
            x += w;
        }
        float g = 36 * dp;
        setRect.set(getWidth() - g - 2 * dp, 0, getWidth() - 2 * dp, h);
        selRect.set(setRect.left - g, 0, setRect.left, h);

        // select: a small open square
        float s = 11 * dp, cx = selRect.centerX(), cy = h / 2f;
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(1.1f * dp);
        fill.setColor(pressed == 10 ? t.s2 : t.s1);
        c.drawRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, fill);

        // settings: three bars, the last one short; lit while the panel is open
        fill.setStyle(Paint.Style.FILL);
        int sc = settingsOpen ? t.accent : (pressed == 11 ? t.s2 : t.s1);
        fill.setColor(sc);
        cx = setRect.centerX();
        float bw = 11 * dp, bh = 1.4f * dp;
        for (int i = 0; i < 3; i++) {
            float y = cy - 4 * dp + i * 4 * dp;
            float w = i == 2 ? bw * 0.62f : bw;
            c.drawRect(cx - bw / 2, y - bh / 2, cx - bw / 2 + w, y + bh / 2, fill);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int hit = hit(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (hit != pressed) {
                    pressed = -1;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (hit == pressed && hit >= 0 && listener != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (hit < 10) listener.onTab(hit);
                    else if (hit == 10) listener.onSelectToggle();
                    else listener.onSettings();
                }
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return true;
    }

    private int hit(float x, float y) {
        for (int i = 0; i < tabRects.length; i++) if (tabRects[i].contains(x, y)) return i;
        if (selRect.contains(x, y)) return 10;
        if (setRect.contains(x, y)) return 11;
        return -1;
    }
}
