package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The bottom bar, where the thumb is, Pinterest-style: one small glyph per
 * page, spread evenly. Feed, boards, albums (only once there is one) and
 * settings. A short mark in the lamp colour sits under the page you are on
 * and slides along while you swipe.
 *
 * While selecting, the same bar (same height, nothing moves) shows the count,
 * what can be done with the selection, and "done".
 *
 * Picking for another app, there is no settings page, and the right end
 * holds "cancel" (one photo) or the count and "use" (several).
 */
public class BarView extends View {
    interface Listener {
        void onPage(int logical);

        void onSelectDone();

        void onSelectAction(int action);

        /** picking several for another app: send them */
        void onPickUse();

        /** picking for another app: send nothing */
        void onPickCancel();
    }

    static final int P_PHOTOS = 0, P_BOARDS = 1, P_ALBUMS = 2, P_SETTINGS = 3;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF a = new RectF();
    private Theme t;
    private Listener listener;
    private float pos = 0f;
    private int pressed = -1;
    private boolean rounded = true;
    private int heading;
    private float scale = 1f;

    // the pages, left to right
    private int[] slots = {P_PHOTOS, P_BOARDS, P_SETTINGS};

    // selecting
    private boolean selecting;
    private int count;
    private String[] actions = {};
    private final RectF[] actionRects = new RectF[8];
    private final RectF doneRect = new RectF();

    // picking for another app
    private boolean picking, pickMany, hasAlbums;
    private int pickCount;
    private final RectF useRect = new RectF();

    public BarView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        text.setTypeface(L.font(c));
        for (int i = 0; i < actionRects.length; i++) actionRects[i] = new RectF();
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLook(boolean rounded, int heading, float textScale) {
        this.rounded = rounded;
        this.heading = heading;
        scale = Math.min(1.25f, textScale);
        text.setTextSize(10.5f * dp * scale);
        invalidate();
    }

    void setHasAlbums(boolean has) {
        hasAlbums = has;
        if (picking) {
            slots = has ? new int[] {P_PHOTOS, P_BOARDS, P_ALBUMS} : new int[] {P_PHOTOS, P_BOARDS};
        } else {
            slots = has ? new int[] {P_PHOTOS, P_BOARDS, P_ALBUMS, P_SETTINGS}
                    : new int[] {P_PHOTOS, P_BOARDS, P_SETTINGS};
        }
        invalidate();
    }

    /** Picking for another app: no settings page; "cancel" or "use" at the right. */
    void setPicking(boolean many) {
        picking = true;
        pickMany = many;
        setHasAlbums(hasAlbums);
    }

    void setPickCount(int n) {
        pickCount = n;
        invalidate();
    }

    /** how much of the width the page glyphs share */
    private float glyphsW() {
        return picking ? getWidth() - endW() : getWidth();
    }

    /** the right end while picking */
    private float endW() {
        return pickMany ? text.measureText("use") + 64 * dp * scale : text.measureText("cancel") + 32 * dp;
    }

    /** The pager position, fractional while swiping (pages are in slot order). */
    void setPosition(float p) {
        pos = p;
        invalidate();
    }

    void setSelecting(boolean on, int count, String[] actions) {
        selecting = on;
        this.count = count;
        this.actions = actions == null ? new String[0] : actions;
        invalidate();
    }

    private float slotX(int i) {
        float w = glyphsW() / (float) slots.length;
        return w * i + w / 2f;
    }

    private float markX() {
        float p = Math.max(0, Math.min(slots.length - 1, pos));
        int lo = (int) Math.floor(p), hi = Math.min(slots.length - 1, lo + 1);
        return slotX(lo) + (slotX(hi) - slotX(lo)) * (p - lo);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t == null) return;
        c.drawColor(t.plate);
        if (selecting) {
            drawSelecting(c);
            return;
        }
        float cy = getHeight() / 2f - 1.5f * dp;
        float s = 15 * dp * scale;
        for (int i = 0; i < slots.length; i++) {
            float cx = slotX(i);
            float near = 1f - Math.min(1f, Math.abs(pos - i));
            int col = Theme.mix(t.s1, t.text, near);
            if (i == pressed) col = Theme.mix(col, t.plate, 0.4f);
            fill.setColor(col);
            a.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            drawGlyph(c, slots[i], a);
        }
        float x = markX();
        float mw = 12 * dp, mh = 2 * dp, my = getHeight() / 2f + s / 2f + 5 * dp;
        fill.setColor(t.accent);
        float r = rounded ? mh / 2 : 0;
        c.drawRoundRect(x - mw / 2, my, x + mw / 2, my + mh, r, r, fill);
        if (picking) drawEnd(c);
    }

    /** picking: "cancel", or the count in the lamp colour, then "use" */
    private void drawEnd(Canvas c) {
        float h = getHeight();
        useRect.set(getWidth() - endW(), 0, getWidth(), h);
        float base = h / 2f + text.getTextSize() * 0.36f;
        if (!pickMany) {
            int col = pressed == 98 ? Theme.mix(t.s1, t.plate, 0.4f) : t.s1;
            text.setColor(col);
            c.drawText("cancel", useRect.right - 16 * dp - text.measureText("cancel"), base, text);
            return;
        }
        boolean any = pickCount > 0;
        String n = String.valueOf(pickCount);
        Heading.style(head, getContext(), heading, 10.5f * dp * scale);
        head.setColor(any ? t.accent : t.s2);
        float nw = Heading.width(n, heading, head);
        float uw = text.measureText("use");
        float x = useRect.right - 16 * dp - uw - 8 * dp - nw;
        Heading.draw(c, n, heading, x, h / 2f, head);
        int col = any ? t.text : t.s2;
        if (pressed == 98) col = Theme.mix(col, t.plate, 0.4f);
        text.setColor(col);
        c.drawText("use", useRect.right - 16 * dp - uw, base, text);
    }

    private void drawSelecting(Canvas c) {
        float h = getHeight();
        float base = h / 2f + text.getTextSize() * 0.36f;
        // the count, in the lamp colour, in the heading face
        String n = String.valueOf(count);
        Heading.style(head, getContext(), heading, 10.5f * dp * scale);
        head.setColor(t.accent);
        float x = 14 * dp;
        Heading.draw(c, n, heading, x, h / 2f, head);
        x += Heading.width(n, heading, head) + 10 * dp;
        // "done" at the right end
        String done = "done";
        float dw = text.measureText(done) + 24 * dp;
        doneRect.set(getWidth() - dw, 0, getWidth(), h);
        text.setColor(pressed == 99 ? Theme.mix(t.text, t.plate, 0.4f) : t.text);
        c.drawText(done, doneRect.left + 12 * dp, base, text);
        // the actions share the room between
        float right = doneRect.left - 4 * dp;
        float each = actions.length == 0 ? 0 : (right - x) / actions.length;
        for (int i = 0; i < actions.length && i < actionRects.length; i++) {
            float l = x + i * each;
            actionRects[i].set(l, 0, l + each, h);
            int col = count == 0 ? t.s2 : t.text;
            if (pressed == 100 + i) col = Theme.mix(col, t.plate, 0.4f);
            text.setColor(col);
            float tw = text.measureText(actions[i]);
            c.drawText(actions[i], l + (each - tw) / 2f, base, text);
        }
    }

    private void drawGlyph(Canvas c, int kind, RectF b) {
        float u = b.width() / 15f;
        float r = rounded ? 1.2f * u : 0;
        float g = 1.5f * u;
        switch (kind) {
            case P_PHOTOS: { // feed: two columns of uneven cards
                float colW = (b.width() - g) / 2f;
                float x2 = b.left + colW + g;
                c.drawRoundRect(b.left, b.top, b.left + colW, b.top + 8.5f * u, r, r, fill);
                c.drawRoundRect(b.left, b.top + 8.5f * u + g, b.left + colW, b.bottom, r, r, fill);
                c.drawRoundRect(x2, b.top, b.right, b.top + 5 * u, r, r, fill);
                c.drawRoundRect(x2, b.top + 5 * u + g, b.right, b.bottom, r, r, fill);
                break;
            }
            case P_BOARDS: { // board: one large, two small
                float big = b.left + 9 * u;
                float top = b.top + 2 * u, bot = b.bottom - 2 * u;
                float mid = top + (bot - top - g) / 2f;
                c.drawRoundRect(b.left, top, big, bot, r, r, fill);
                c.drawRoundRect(big + g, top, b.right, mid, r, r, fill);
                c.drawRoundRect(big + g, mid + g, b.right, bot, r, r, fill);
                break;
            }
            case P_ALBUMS: { // album: a stack of prints
                Paint.Style st = fill.getStyle();
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(1.3f * u);
                c.drawRoundRect(b.left + 4 * u, b.top + 1.5f * u, b.right - 0.5f * u, b.bottom - 4.5f * u, r, r, fill);
                fill.setStyle(st);
                c.drawRoundRect(b.left + 0.5f * u, b.top + 5 * u, b.right - 4 * u, b.bottom - 0.5f * u, r, r, fill);
                break;
            }
            default: { // settings: three bars, the last one short
                float bh = 1.55f * u;
                float w = b.width() - 3 * u, x = b.left + 1.5f * u;
                for (int k = 0; k < 3; k++) {
                    float y = b.centerY() - 4 * u + k * 4 * u;
                    float ww = k == 2 ? w * 0.62f : w;
                    c.drawRect(x, y - bh / 2, x + ww, y + bh / 2, fill);
                }
            }
        }
    }

    private int hit(float x, float y) {
        if (selecting) {
            if (doneRect.contains(x, y)) return 99;
            for (int i = 0; i < actions.length && i < actionRects.length; i++) {
                if (actionRects[i].contains(x, y)) return 100 + i;
            }
            return -1;
        }
        if (picking && x >= getWidth() - endW()) return 98;
        int i = (int) (x / (glyphsW() / (float) slots.length));
        return Math.max(0, Math.min(slots.length - 1, i));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int h = hit(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = h;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (h != pressed && pressed != -1) {
                    pressed = -1;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (h == pressed && h != -1 && listener != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (selecting) {
                        if (h == 99) listener.onSelectDone();
                        else if (count > 0) listener.onSelectAction(h - 100);
                    } else if (h == 98) {
                        if (!pickMany) listener.onPickCancel();
                        else if (pickCount > 0) listener.onPickUse();
                    } else {
                        listener.onPage(slots[h]);
                    }
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
