package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;

/**
 * The one reading line at the top: a title on the left, a count on the right.
 * It is also the only place messages appear: a message cross-fades in over the
 * title, stays a moment and fades back on its own.
 */
public class TopLine extends View {
    private final float dp;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint count = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Theme t;
    private String titleText = "", countText = "", message = null, oldTitle = null;
    private int messageColor;
    private int heading;
    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private float scale = 1f;
    private Runnable undo;
    private long messageAt = -1, messageMs = 2600, titleAt = -1;
    private static final long FADE_FULL = 260;

    public TopLine(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        title.setTypeface(L.font(c));
        count.setTypeface(L.font(c));
        setTextScale(1f);
    }

    /** title and count in the heading face; messages stay in the plain one */
    void setHeading(int h) {
        heading = h;
        invalidate();
    }

    void setTextScale(float s) {
        scale = s;
        title.setTextSize(11 * dp * s);
        count.setTextSize(11 * dp * s);
        invalidate();
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void set(String newTitle, String newCount) {
        if (!newTitle.equals(titleText)) {
            oldTitle = titleText;
            titleAt = SystemClock.uptimeMillis();
        }
        titleText = newTitle;
        countText = newCount;
        invalidate();
    }

    void message(String text) {
        message(text, 2600, 0);
    }

    /** color 0 = the hint shade */
    void message(String text, long ms, int color) {
        message(text, ms, color, null);
    }

    /** A message that can be tapped to undo what it reports. */
    void message(String text, long ms, int color, Runnable undo) {
        this.undo = undo;
        message = text;
        messageMs = ms;
        messageColor = color;
        messageAt = SystemClock.uptimeMillis();
        invalidate();
    }

    void clearMessage() {
        undo = null;
        if (message == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - messageAt < messageMs) messageAt = now - messageMs; // fade out from now
        invalidate();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (undo == null || message == null) return false;
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            Runnable u = undo;
            undo = null;
            clearMessage();
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            u.run();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        long FADE = Math.max(1, Motion.ms(FADE_FULL));
        float pad = 10 * dp;
        float base = getHeight() / 2f + title.getTextSize() * 0.36f;

        // message strength: fade in, hold, fade out
        float m = 0f;
        if (message != null) {
            long age = now - messageAt;
            if (age < FADE) m = age / (float) FADE;
            else if (age < messageMs) m = 1f;
            else if (age < messageMs + FADE) m = 1f - (age - messageMs) / (float) FADE;
            else {
                message = null;
                undo = null;
            }
            if (message != null) again = true;
        }
        float bk = 0;
        float cy = getHeight() / 2f;
        Heading.style(head, getContext(), heading, 11 * dp * scale);
        float countW = countText.isEmpty() ? 0 : Heading.width(countText, heading, head);
        float room = getWidth() - 2 * pad - countW - 12 * dp;

        // title (cross-fading from the old one when it changed)
        float ta = 1f;
        if (titleAt >= 0) {
            float u = (now - titleAt) / (float) FADE;
            if (u >= 1f) {
                titleAt = -1;
                oldTitle = null;
            } else {
                ta = u;
                again = true;
                if (oldTitle != null) {
                    head.setColor(t.text);
                    head.setAlpha(Math.round(255 * (1f - u) * (1f - m)));
                    heading(c, oldTitle, pad, base, cy, bk, room);
                }
            }
        }
        head.setColor(t.text);
        head.setAlpha(Math.round(255 * ta * (1f - m)));
        heading(c, titleText, pad, base, cy, bk, room);
        if (m > 0f && message != null) {
            title.setColor(messageColor != 0 ? messageColor : t.s1);
            title.setAlpha(Math.round(255 * m));
            c.drawText(ellipsize(message, room), pad, base, title);
        }
        if (!countText.isEmpty()) {
            head.setColor(t.s1);
            head.setAlpha(255);
            Heading.draw(c, countText, heading, getWidth() - pad - countW, cy, head);
        }
        if (again) postInvalidateOnAnimation();
    }

    private void heading(Canvas c, String s, float x, float base, float cy, float bk, float room) {
        Heading.draw(c, Heading.fit(s, heading, head, room), heading, x, cy, head);
    }

    private String ellipsize(String s, float room) {
        return TextUtils.ellipsize(s, title, Math.max(0, room), TextUtils.TruncateAt.END).toString();
    }
}
