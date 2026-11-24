package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** The albums page, waiting for its build: one calm line in the middle. */
public class AlbumsPage extends View {
    private final float dp;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Theme t;

    public AlbumsPage(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        text.setTypeface(L.font(c));
        setTextScale(1f);
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setTextScale(float s) {
        text.setTextSize(9.5f * dp * s);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        c.drawColor(t.bg);
        String s = "albums arrive in a later build";
        text.setColor(t.s2);
        c.drawText(s, (getWidth() - text.measureText(s)) / 2f, getHeight() / 2f, text);
    }
}
