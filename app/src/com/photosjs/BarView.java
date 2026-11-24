package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The bottom bar, where the thumb is, Pinterest-style: five small glyphs
 * spread evenly. Photos (a little feed), folders (a board), albums (a stack),
 * then select and settings. A short ember mark sits under the page you are
 * on and slides along while you swipe between pages.
 */
public class BarView extends View {
    interface Listener {
        void onTab(int tab);

        void onSelectToggle();

        void onSettings();
    }

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF a = new RectF();
    private Theme t;
    private Listener listener;
    private float pos = 0f;
    private int pressed = -1;
    private boolean settingsOpen, rounded = true;
    private float scale = 1f;

    public BarView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLook(boolean rounded, float textScale) {
        this.rounded = rounded;
        scale = Math.min(1.2f, textScale);
        invalidate();
    }

    /** The page position from the pager, 0..2, fractional while swiping. */
    void setPosition(float p) {
        pos = p;
        invalidate();
    }

    void setSettingsOpen(boolean open) {
        settingsOpen = open;
        invalidate();
    }

    private float slotX(int i) {
        float w = getWidth() / 5f;
        return w * i + w / 2f;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        c.drawColor(t.plate);
        float cy = getHeight() / 2f - 1.5f * dp;
        float s = 15 * dp * scale;   // glyph box
        for (int i = 0; i < 5; i++) {
            float cx = slotX(i);
            int col;
            if (i < 3) {
                float near = 1f - Math.min(1f, Math.abs(pos - i));
                col = Theme.mix(t.s1, t.text, near);
            } else if (i == 4 && settingsOpen) {
                col = t.accent;
            } else {
                col = t.s1;
            }
            if (i == pressed) col = Theme.mix(col, t.plate, 0.4f);
            fill.setColor(col);
            a.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            drawGlyph(c, i, a);
        }
        // the page mark, sliding with the swipe
        float p = Math.max(0, Math.min(2, pos));
        float x = slotX(0) + (slotX(1) - slotX(0)) * p;
        float mw = 12 * dp, mh = 2 * dp, my = getHeight() / 2f + s / 2f + 5 * dp;
        fill.setColor(t.accent);
        float r = rounded ? mh / 2 : 0;
        c.drawRoundRect(x - mw / 2, my, x + mw / 2, my + mh, r, r, fill);
    }

    private void drawGlyph(Canvas c, int i, RectF b) {
        float u = b.width() / 15f;          // glyph unit
        float r = rounded ? 1.2f * u : 0;
        float g = 1.5f * u;
        switch (i) {
            case 0: { // feed: two columns of uneven cards
                float colW = (b.width() - g) / 2f;
                float x2 = b.left + colW + g;
                c.drawRoundRect(b.left, b.top, b.left + colW, b.top + 8.5f * u, r, r, fill);
                c.drawRoundRect(b.left, b.top + 8.5f * u + g, b.left + colW, b.bottom, r, r, fill);
                c.drawRoundRect(x2, b.top, b.right, b.top + 5 * u, r, r, fill);
                c.drawRoundRect(x2, b.top + 5 * u + g, b.right, b.bottom, r, r, fill);
                break;
            }
            case 1: { // board: one large, two small
                float big = b.left + 9 * u;
                float mid = b.top + (b.height() - g) / 2f;
                float top = b.top + 2 * u, bot = b.bottom - 2 * u;
                mid = top + (bot - top - g) / 2f;
                c.drawRoundRect(b.left, top, big, bot, r, r, fill);
                c.drawRoundRect(big + g, top, b.right, mid, r, r, fill);
                c.drawRoundRect(big + g, mid + g, b.right, bot, r, r, fill);
                break;
            }
            case 2: { // album: a stack of prints
                Paint.Style st = fill.getStyle();
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(1.3f * u);
                c.drawRoundRect(b.left + 4 * u, b.top + 1.5f * u, b.right - 0.5f * u, b.bottom - 4.5f * u, r, r, fill);
                fill.setStyle(st);
                c.drawRoundRect(b.left + 0.5f * u, b.top + 5 * u, b.right - 4 * u, b.bottom - 0.5f * u, r, r, fill);
                break;
            }
            case 3: { // select: an open square
                Paint.Style st = fill.getStyle();
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(1.3f * u);
                float in = 2.5f * u;
                c.drawRoundRect(b.left + in, b.top + in, b.right - in, b.bottom - in, r, r, fill);
                fill.setStyle(st);
                break;
            }
            default: { // settings: three bars, the last one short
                float bh = 1.4f * u * 1.1f;
                float w = b.width() - 3 * u, x = b.left + 1.5f * u;
                for (int k = 0; k < 3; k++) {
                    float y = b.centerY() - 4 * u + k * 4 * u;
                    float ww = k == 2 ? w * 0.62f : w;
                    c.drawRect(x, y - bh / 2, x + ww, y + bh / 2, fill);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int hit = (int) (e.getX() / (getWidth() / 5f));
        hit = Math.max(0, Math.min(4, hit));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (hit != pressed && pressed != -1) {
                    pressed = -1;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (hit == pressed && listener != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (hit < 3) listener.onTab(hit);
                    else if (hit == 3) listener.onSelectToggle();
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
}
