package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;

/**
 * One line of words in the app's face (settings › font), for the few places
 * that are laid out as views rather than drawn (the album sheet). It stands in
 * for a single-line TextView: shortened with an ellipsis when it doesn't fit,
 * vertically centred, aligned to the start, the middle or the end.
 */
public class Words extends View {
    static final int START = 0, CENTRE = 1, END = 2;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private int align = START;

    public Words(Context c) {
        super(c);
        paint.setTypeface(L.font(c));
    }

    void setText(String s) {
        text = s == null ? "" : s;
        requestLayout();
        invalidate();
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /** size in pixels, as for plain text */
    void setSize(float px) {
        paint.setTextSize(px);
        requestLayout();
        invalidate();
    }

    void setAlign(int a) {
        align = a;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = Math.round(Heading.measure(text, paint)) + getPaddingLeft() + getPaddingRight();
        int h = Math.round(paint.getTextSize() * 1.7f) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        String s = Heading.fitText(text, paint, right - left);
        float w = Heading.measure(s, paint);
        float x = align == CENTRE ? (left + right - w) / 2f : align == END ? right - w : left;
        float mid = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        Heading.text(c, s, x, mid + paint.getTextSize() * 0.36f, paint);
    }
}
