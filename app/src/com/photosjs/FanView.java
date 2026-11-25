package com.photosjs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The long-press fan. The held photo lifts, everything else sinks into the
 * dark, and a few actions bloom on an arc around the thumb, always on screen
 * (the arc turns away from the edges). Slide onto one and let go to do it;
 * let go anywhere else to cancel. A light tick as the thumb crosses each one.
 * The reading line names what letting go will do (the thumb hides the chips).
 *
 * In tap mode (a second, smaller choice such as where a wallpaper goes) the
 * fan stays open and waits for a tap.
 */
public class FanView extends View {
    interface Listener {
        void onFanHover(int action);

        void onFanPick(int action);

        void onFanCancel();
    }

    // actions
    static final int ALBUM = 0, WALLPAPER = 1, EDIT = 2, SHARE = 3, HIDE = 4, DELETE = 5, INFO = 6, SELECT = 7;
    static final int RENAME = 20, DROP_ALBUM = 21;
    static final int W_HOME = 30, W_LOCKJS = 31;
    static final int NONE = -1;

    static final int[] PHOTO_ACTIONS = {ALBUM, SELECT, WALLPAPER, EDIT, SHARE, HIDE, DELETE, INFO};

    static String name(int a) {
        switch (a) {
            case ALBUM: return "add to album";
            case WALLPAPER: return "set as wallpaper";
            case EDIT: return "edit";
            case SHARE: return "share";
            case HIDE: return "set aside";
            case SELECT: return "select";
            case DELETE: return "move to trash";
            case INFO: return "info";
            case RENAME: return "rename album";
            case DROP_ALBUM: return "delete album (photos stay)";
            case W_HOME: return "home screen";
            case W_LOCKJS: return "lockJS";
            default: return "none";
        }
    }

    /** short names, for settings */
    static String shortName(int a) {
        switch (a) {
            case ALBUM: return "album";
            case WALLPAPER: return "wallpaper";
            case DELETE: return "trash";
            default: return a == NONE ? "none" : name(a);
        }
    }

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final Rect src = new Rect();
    private final RectF r = new RectF(), q = new RectF();
    private final Path clip = new Path();
    private Theme t;
    private Listener listener;
    private boolean rounded = true;

    private boolean open, tapMode;
    private long openedAt;
    private Bitmap bm;
    private final RectF held = new RectF();
    private float heldRadius;
    private float fx, fy;            // the finger, in this view's coordinates
    private int[] acts = new int[0];
    private float[] cx = new float[0], cy = new float[0];
    private int hover = -1;
    private long hoverAt;
    private final int[] loc = new int[2];

    public FanView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        // invisible, not gone: it stays laid out, so it knows the screen when it opens
        setVisibility(INVISIBLE);
    }

    void setTheme(Theme th) {
        t = th;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setRounded(boolean r) {
        rounded = r;
    }

    boolean isOpen() {
        return open;
    }

    /** Open around a finger at screen point (x, y); the held thing's rect is in screen coords. */
    void open(RectF screenRect, Bitmap b, float radius, float x, float y, int[] actions, boolean tap) {
        getLocationOnScreen(loc);
        held.set(screenRect);
        held.offset(-loc[0], -loc[1]);
        heldRadius = radius;
        bm = b;
        fx = x - loc[0];
        fy = y - loc[1];
        List<Integer> a = new ArrayList<>();
        for (int k : actions) if (k != NONE) a.add(k);
        acts = new int[a.size()];
        for (int i = 0; i < acts.length; i++) acts[i] = a.get(i);
        tapMode = tap;
        hover = -1;
        place();
        open = true;
        openedAt = SystemClock.uptimeMillis();
        setVisibility(VISIBLE);
        invalidate();
    }

    /** The same place, new choices (the second step), waiting for a tap. */
    void reopen(int[] actions) {
        List<Integer> a = new ArrayList<>();
        for (int k : actions) if (k != NONE) a.add(k);
        acts = new int[a.size()];
        for (int i = 0; i < acts.length; i++) acts[i] = a.get(i);
        tapMode = true;
        hover = -1;
        place();
        openedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    void close() {
        open = false;
        bm = null;
        setVisibility(INVISIBLE);
    }

    private float chip() {
        return 46 * dp;
    }

    /** Lay the chips on an arc around the finger, turning it until all fit on screen. */
    private void place() {
        int n = acts.length;
        cx = new float[n];
        cy = new float[n];
        if (n == 0) return;
        float R = (n <= 3 ? 80 : 92) * dp;
        float spread = n <= 1 ? 0 : (n <= 3 ? 50 : n == 4 ? 42 : 34);
        float half = chip() / 2 + 6 * dp;
        boolean leftHalf = fx < getWidth() / 2f;
        float[] bases = leftHalf
                ? new float[] {-90, -65, -40, -15, 10, 90, 115, -115, 180}
                : new float[] {-90, -115, -140, -165, 190, 90, 65, -65, 0};
        float chosen = -90;
        for (float base : bases) {
            boolean fits = true;
            for (int i = 0; i < n; i++) {
                double ang = Math.toRadians(base + (i - (n - 1) / 2f) * spread);
                float x = fx + (float) Math.cos(ang) * R, y = fy + (float) Math.sin(ang) * R;
                if (x - half < 0 || x + half > getWidth() || y - half < 0 || y + half > getHeight()) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                chosen = base;
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(chosen + (i - (n - 1) / 2f) * spread);
            cx[i] = clampX(fx + (float) Math.cos(ang) * R, half);
            cy[i] = clampY(fy + (float) Math.sin(ang) * R, half);
        }
    }

    private float clampX(float x, float half) {
        return Math.max(half, Math.min(getWidth() - half, x));
    }

    private float clampY(float y, float half) {
        return Math.max(half, Math.min(getHeight() - half, y));
    }

    private int nearest(float x, float y) {
        int best = -1;
        float bd = chip() * 0.95f;
        bd *= bd;
        for (int i = 0; i < acts.length; i++) {
            float d = (cx[i] - x) * (cx[i] - x) + (cy[i] - y) * (cy[i] - y);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** finger moved (screen coords) */
    void move(float x, float y) {
        if (!open || tapMode) return;
        int h = nearest(x - loc[0], y - loc[1]);
        if (h != hover) {
            hover = h;
            hoverAt = SystemClock.uptimeMillis();
            if (h >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onFanHover(h >= 0 ? acts[h] : NONE);
            invalidate();
        }
    }

    /** finger lifted (screen coords) */
    void release(float x, float y, boolean cancel) {
        if (!open || tapMode) return;
        int h = cancel ? -1 : nearest(x - loc[0], y - loc[1]);
        if (h >= 0) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            if (listener != null) listener.onFanPick(acts[h]);
        } else {
            close();
            if (listener != null) listener.onFanCancel();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!open) return false;
        if (!tapMode) return true;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
            int h = nearest(e.getX(), e.getY());
            if (h != hover) {
                hover = h;
                hoverAt = SystemClock.uptimeMillis();
                if (h >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                if (listener != null) listener.onFanHover(h >= 0 ? acts[h] : NONE);
                invalidate();
            }
        } else if (act == MotionEvent.ACTION_UP) {
            int h = nearest(e.getX(), e.getY());
            if (h >= 0) {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                if (listener != null) listener.onFanPick(acts[h]);
            } else {
                close();
                if (listener != null) listener.onFanCancel();
            }
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!open || t == null) return;
        long now = SystemClock.uptimeMillis();
        long d = Motion.ms(260);
        float u = d <= 0 ? 1f : Math.min(1f, (now - openedAt) / (float) d);
        float e = Motion.ease(u), f = Motion.fade(u);
        boolean again = u < 1f;

        // everything sinks
        fill.setColor(t.bg);
        fill.setAlpha(Math.round(205 * f));
        c.drawRect(0, 0, getWidth(), getHeight(), fill);

        // the held photo lifts
        if (bm != null || heldRadius >= 0) {
            float lift = 1f + 0.05f * e;
            r.set(held);
            float dx = r.width() * (lift - 1f) / 2f, dy = r.height() * (lift - 1f) / 2f;
            r.inset(-dx, -dy);
            if (bm != null) {
                cover(bm, r);
                BitmapShader sh = new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                q.set(src);
                m.setRectToRect(q, r, Matrix.ScaleToFit.FILL);
                sh.setLocalMatrix(m);
                photo.setShader(sh);
                c.drawRoundRect(r, heldRadius, heldRadius, photo);
            } else {
                fill.setColor(t.plate);
                fill.setAlpha(255);
                c.drawRoundRect(r, heldRadius, heldRadius, fill);
            }
        }

        // the chips bloom out from the finger
        float s = chip();
        float cr = rounded ? 12 * dp : 0;
        for (int i = 0; i < acts.length; i++) {
            float x = fx + (cx[i] - fx) * e, y = fy + (cy[i] - fy) * e;
            boolean on = i == hover;
            float grow = 1f;
            if (on) {
                long hd = Motion.ms(160);
                float hu = hd <= 0 ? 1f : Math.min(1f, (now - hoverAt) / (float) hd);
                grow = 1f + 0.14f * Motion.ease(hu);
                if (hu < 1f) again = true;
            }
            float half = s / 2 * grow * (0.4f + 0.6f * e);
            r.set(x - half, y - half, x + half, y + half);
            fill.setColor(on ? t.accent : t.plate);
            fill.setAlpha(Math.round(255 * f));
            c.drawRoundRect(r, cr, cr, fill);
            fill.setColor(on ? t.bg : t.text);
            fill.setAlpha(Math.round(255 * f));
            float gs = half * 0.9f;
            r.set(x - gs / 2, y - gs / 2, x + gs / 2, y + gs / 2);
            glyph(c, acts[i], r);
        }
        if (again) postInvalidateOnAnimation();
    }

    private void cover(Bitmap b, RectF dst) {
        float bw = b.getWidth(), bh = b.getHeight();
        float da = dst.width() / Math.max(1f, dst.height()), ba = bw / Math.max(1f, bh);
        if (ba > da) {
            float sw = bh * da;
            src.set(Math.round((bw - sw) / 2), 0, Math.round((bw + sw) / 2), (int) bh);
        } else {
            float sh = bw / da;
            src.set(0, Math.round((bh - sh) / 2), (int) bw, Math.round((bh + sh) / 2));
        }
    }

    /** Small, plain glyphs: just touching the meaning. */
    private void glyph(Canvas c, int a, RectF b) {
        float u = b.width() / 12f;
        float rr = rounded ? u : 0;
        stroke.setColor(fill.getColor());
        stroke.setStrokeWidth(1.4f * u);
        switch (a) {
            case ALBUM:
                c.drawRoundRect(b.left + 3.5f * u, b.top + 1 * u, b.right - 0.5f * u, b.bottom - 4 * u, rr, rr, stroke);
                c.drawRoundRect(b.left + 0.5f * u, b.top + 4.5f * u, b.right - 3.5f * u, b.bottom - 0.5f * u, rr, rr, fill);
                break;
            case WALLPAPER:
                c.drawRoundRect(b.left + 3 * u, b.top, b.right - 3 * u, b.bottom, rr, rr, stroke);
                c.drawRect(b.left + 3 * u, b.top + 7 * u, b.right - 3 * u, b.bottom, fill);
                break;
            case EDIT:
                c.drawLine(b.left + 1.5f * u, b.bottom - 1.5f * u, b.right - 4 * u, b.top + 4 * u, stroke);
                c.drawRect(b.right - 2.6f * u, b.top + 0.4f * u, b.right - 0.4f * u, b.top + 2.6f * u, fill);
                break;
            case SHARE:
                c.drawRect(b.centerX() - 0.7f * u, b.top + 1.5f * u, b.centerX() + 0.7f * u, b.bottom - 4 * u, fill);
                c.drawRoundRect(b.left + 1 * u, b.top + 6 * u, b.right - 1 * u, b.bottom, rr, rr, stroke);
                break;
            case HIDE:
                // set aside: the picture's place stays, a small one moved off to the side
                c.drawRoundRect(b.left + 0.7f * u, b.top + 2 * u, b.left + 7.5f * u, b.bottom - 2 * u, rr, rr, stroke);
                c.drawRoundRect(b.right - 3.2f * u, b.bottom - 5.5f * u, b.right, b.bottom - 2 * u, rr, rr, fill);
                break;
            case SELECT:
                c.drawRoundRect(b.left + 1 * u, b.top + 1 * u, b.right - 1 * u, b.bottom - 1 * u, rr, rr, stroke);
                c.drawRect(b.centerX() - 2 * u, b.centerY() - 2 * u, b.centerX() + 2 * u, b.centerY() + 2 * u, fill);
                break;
            case DELETE:
            case DROP_ALBUM:
                c.drawRect(b.left + 1 * u, b.top + 2 * u, b.right - 1 * u, b.top + 3.4f * u, fill);
                c.drawRoundRect(b.left + 2.5f * u, b.top + 5 * u, b.right - 2.5f * u, b.bottom, rr, rr, stroke);
                break;
            case INFO:
                c.drawRect(b.centerX() - 0.9f * u, b.top + 1 * u, b.centerX() + 0.9f * u, b.top + 2.8f * u, fill);
                c.drawRect(b.centerX() - 0.9f * u, b.top + 4.5f * u, b.centerX() + 0.9f * u, b.bottom - 1 * u, fill);
                break;
            case RENAME:
                c.drawRect(b.left, b.centerY() - 0.8f * u, b.right - 4 * u, b.centerY() + 0.8f * u, fill);
                c.drawRect(b.right - 2 * u, b.top + 1.5f * u, b.right - 0.6f * u, b.bottom - 1.5f * u, fill);
                break;
            case W_HOME:
                clip.reset();
                clip.moveTo(b.centerX(), b.top);
                clip.lineTo(b.right, b.top + 5 * u);
                clip.lineTo(b.left, b.top + 5 * u);
                clip.close();
                c.drawPath(clip, fill);
                c.drawRect(b.left + 2 * u, b.top + 6.5f * u, b.right - 2 * u, b.bottom, fill);
                break;
            case W_LOCKJS:
                // lockJS's own icon: a bar in the lamp colour over a block
                c.drawRect(b.left + 1 * u, b.top + 1 * u, b.right - 1 * u, b.top + 3.5f * u, fill);
                c.drawRect(b.left + 1 * u, b.top + 5 * u, b.right - 1 * u, b.bottom - 1 * u, fill);
                break;
            default:
                c.drawRect(b.left + 3 * u, b.top + 3 * u, b.right - 3 * u, b.bottom - 3 * u, fill);
        }
    }
}
