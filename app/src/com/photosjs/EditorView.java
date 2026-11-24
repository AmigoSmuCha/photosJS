package com.photosjs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The editor: two tools, both done well.
 *  crop - a rectangle with small visible corners and much bigger touch zones;
 *         free or square, one tap apart (remembered).
 *  draw - freehand marks in the theme's 16 colours, any brush size. One finger
 *         draws; two fingers zoom and move without leaving marks; the brush
 *         keeps its size on screen whatever the zoom.
 * Every step can be undone. Nothing is written until "save".
 */
public class EditorView extends View {
    interface Listener {
        void onEditorSave(Media.Item original);

        void onEditorClosed();

        void onEditorMessage(String text);
    }

    static final int CROP = 0, DRAW = 1;

    private static final class Stroke {
        int color;
        float width;                        // in work-image pixels / work width (normalised)
        final List<float[]> pts = new ArrayList<>();   // normalised 0..1 of the whole image
    }

    private final float dp;
    private Theme t;
    private Listener listener;
    private boolean rounded = true;
    private float textScale = 1f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF r = new RectF(), area = new RectF(), shown = new RectF();
    private final Path path = new Path();

    private Media.Item item;
    private Bitmap work;
    private boolean open, loading;
    private int mode = DRAW;
    private boolean square;
    private final RectF crop = new RectF(0, 0, 1, 1);   // normalised
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Object> undo = new ArrayList<>(); // Stroke, or a RectF (the crop before)
    private int[] colors = new int[16];
    private int color = 0;
    private float brushDp = 6f;
    private boolean dirty;
    private long backAt;
    private String note;
    private long noteUntil;

    // zoom while drawing
    private float zoom = 1f, panX, panY;

    // touch
    private Stroke live;
    private long liveAt;
    private float liveTravel, lastX, lastY;
    private boolean twoFinger;
    private float pinchDist, pinchCx, pinchCy;
    private int grab = -1;                 // crop: which handle (0..8)
    private final RectF grabStart = new RectF();
    private float grabX, grabY;
    private boolean sliding;               // brush size slider

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public EditorView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        text.setTypeface(L.font(c));
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        setVisibility(INVISIBLE);
    }

    void setTheme(Theme th) {
        t = th;
        colors = th.ink();
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLook(boolean rounded, float textScale) {
        this.rounded = rounded;
        this.textScale = textScale;
        text.setTextSize(10.5f * dp * textScale);
        invalidate();
    }

    boolean isOpen() {
        return open;
    }

    boolean dirty() {
        return dirty;
    }

    /** a message in the editor's own top line */
    void note(String s, long ms) {
        note = s;
        noteUntil = SystemClock.uptimeMillis() + ms;
        invalidate();
        postInvalidateDelayed(ms + 20);
    }

    void open(final Media.Item it) {
        item = it;
        open = true;
        loading = true;
        work = null;
        strokes.clear();
        undo.clear();
        crop.set(0, 0, 1, 1);
        zoom = 1f;
        panX = panY = 0;
        dirty = false;
        mode = DRAW;
        square = L.getInt(getContext(), "cropsq", 0) == 1;
        brushDp = L.getInt(getContext(), "brush", 6);
        color = Math.max(0, Math.min(15, L.getInt(getContext(), "inkcolor", 0)));
        setVisibility(VISIBLE);
        invalidate();
        io.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    b = ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(getContext().getContentResolver(), it.uri()),
                            new ImageDecoder.OnHeaderDecodedListener() {
                                @Override
                                public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info,
                                                            ImageDecoder.Source s) {
                                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                                    int max = 2048;
                                    if (Math.max(w, h) > max) {
                                        float f = max / (float) Math.max(w, h);
                                        d.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
                                    }
                                }
                            });
                } catch (Throwable e) {
                    b = null;
                }
                final Bitmap got = b;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (!open || item != it) return;
                        if (got == null) {
                            if (listener != null) listener.onEditorMessage("this picture can't be opened for editing");
                            close();
                            return;
                        }
                        work = got;
                        invalidate();
                    }
                });
            }
        });
    }

    void close() {
        open = false;
        setVisibility(INVISIBLE);
        work = null;
        strokes.clear();
        undo.clear();
        if (listener != null) listener.onEditorClosed();
    }

    /** back: the first press with unsaved marks only says so; the second leaves */
    boolean back() {
        long now = SystemClock.uptimeMillis();
        if (dirty && now - backAt > 3000) {
            backAt = now;
            if (listener != null) listener.onEditorMessage("back again to leave without saving");
            return true;
        }
        close();
        return true;
    }

    // ---- layout ----

    private float topH() {
        return Math.round(27 * dp * textScale);
    }

    private float barH() {
        return Math.round(46 * dp * Math.min(1.25f, textScale));
    }

    private float sliderH() {
        return Math.round(40 * dp);
    }

    private float swatch() {
        return (getWidth() - 7 * gap()) / 8f;
    }

    private float gap() {
        return Math.max(1, Math.round(2 * dp));
    }

    /** the tool area, the same height in both modes so nothing moves */
    private float toolsH() {
        return sliderH() + 2 * swatch() + 2 * gap();
    }

    private void imageArea(RectF out) {
        out.set(0, topH(), getWidth(), getHeight() - barH() - toolsH());
    }

    /** where the work image (all of it in crop mode, the crop in draw mode) is on screen */
    private void layoutShown() {
        if (work == null) return;
        imageArea(area);
        float W = work.getWidth(), H = work.getHeight();
        RectF a = new RectF(area);
        float pad = mode == CROP ? 18 * dp : 6 * dp;
        a.inset(pad, pad);
        float rw, rh;
        if (mode == CROP) {
            rw = W;
            rh = H;
        } else {
            rw = W * crop.width();
            rh = H * crop.height();
        }
        float s = Math.min(a.width() / rw, a.height() / rh);
        float w = rw * s, h = rh * s;
        shown.set(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2);
        if (mode == DRAW && zoom != 1f) {
            float cx = area.centerX(), cy = area.centerY();
            shown.set(cx + (shown.left - cx) * zoom + panX, cy + (shown.top - cy) * zoom + panY,
                    cx + (shown.right - cx) * zoom + panX, cy + (shown.bottom - cy) * zoom + panY);
        }
    }

    /** normalised image point -> screen */
    private float sx(float u) {
        if (mode == CROP) return shown.left + u * shown.width();
        return shown.left + (u - crop.left) / crop.width() * shown.width();
    }

    private float sy(float v) {
        if (mode == CROP) return shown.top + v * shown.height();
        return shown.top + (v - crop.top) / crop.height() * shown.height();
    }

    private float ux(float x) {
        if (mode == CROP) return (x - shown.left) / shown.width();
        return crop.left + (x - shown.left) / shown.width() * crop.width();
    }

    private float vy(float y) {
        if (mode == CROP) return (y - shown.top) / shown.height();
        return crop.top + (y - shown.top) / shown.height() * crop.height();
    }

    /** screen pixels per work-image pixel */
    private float pxScale() {
        if (work == null) return 1f;
        return mode == CROP ? shown.width() / work.getWidth() : shown.width() / (work.getWidth() * crop.width());
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        if (!open || t == null) return;
        c.drawColor(t.bg);
        layoutShown();
        imageArea(area);
        text.setTextSize(10.5f * dp * textScale);
        float base = topH() / 2f + text.getTextSize() * 0.36f;

        // the reading line
        boolean showNote = note != null && SystemClock.uptimeMillis() < noteUntil;
        text.setColor(showNote ? t.s1 : t.text);
        c.drawText(showNote ? note : mode == CROP ? "crop" : "draw", 10 * dp, base, text);
        String right = loading ? "opening" : mode == DRAW && zoom != 1f
                ? String.format(Locale.US, "%.1f×", zoom) : (square && mode == CROP ? "square" : "");
        text.setColor(t.s1);
        c.drawText(right, getWidth() - 10 * dp - text.measureText(right), base, text);

        if (work != null) {
            c.save();
            c.clipRect(area);
            float W = work.getWidth(), H = work.getHeight();
            if (mode == CROP) {
                src.set(0, 0, (int) W, (int) H);
            } else {
                src.set(Math.round(crop.left * W), Math.round(crop.top * H),
                        Math.round(crop.right * W), Math.round(crop.bottom * H));
            }
            c.drawBitmap(work, src, shown, paint);
            if (mode == DRAW) c.clipRect(shown);
            for (Stroke s : strokes) drawStroke(c, s);
            if (live != null) drawStroke(c, live);
            if (mode == CROP) drawCrop(c);
            c.restore();
        }
        drawTools(c);
        drawBar(c);
    }

    private void drawStroke(Canvas c, Stroke s) {
        if (s.pts.isEmpty()) return;
        ink.setColor(s.color);
        ink.setStrokeWidth(Math.max(1f, s.width * work.getWidth() * pxScale()));
        path.reset();
        float[] p0 = s.pts.get(0);
        path.moveTo(sx(p0[0]), sy(p0[1]));
        if (s.pts.size() == 1) {
            path.lineTo(sx(p0[0]) + 0.1f, sy(p0[1]));
        }
        for (int i = 1; i < s.pts.size(); i++) {
            float[] a = s.pts.get(i - 1), b = s.pts.get(i);
            float mx = (sx(a[0]) + sx(b[0])) / 2, my = (sy(a[1]) + sy(b[1])) / 2;
            path.quadTo(sx(a[0]), sy(a[1]), mx, my);
        }
        float[] last = s.pts.get(s.pts.size() - 1);
        path.lineTo(sx(last[0]), sy(last[1]));
        c.drawPath(path, ink);
    }

    private void cropScreen(RectF out) {
        out.set(sx(crop.left), sy(crop.top), sx(crop.right), sy(crop.bottom));
    }

    private void drawCrop(Canvas c) {
        cropScreen(r);
        // outside the crop sinks
        fill.setColor(t.bg);
        fill.setAlpha(170);
        c.drawRect(shown.left, shown.top, shown.right, r.top, fill);
        c.drawRect(shown.left, r.bottom, shown.right, shown.bottom, fill);
        c.drawRect(shown.left, r.top, r.left, r.bottom, fill);
        c.drawRect(r.right, r.top, shown.right, r.bottom, fill);
        // a hairline frame, and small corners in the lamp colour
        fill.setAlpha(255);
        fill.setColor(t.text);
        float h = Math.max(1, dp);
        c.drawRect(r.left, r.top, r.right, r.top + h, fill);
        c.drawRect(r.left, r.bottom - h, r.right, r.bottom, fill);
        c.drawRect(r.left, r.top, r.left + h, r.bottom, fill);
        c.drawRect(r.right - h, r.top, r.right, r.bottom, fill);
        fill.setColor(grab >= 0 ? t.accent : t.text);
        float L = 16 * dp, w = 3 * dp;
        float[][] corners = {{r.left, r.top, 1, 1}, {r.right, r.top, -1, 1}, {r.left, r.bottom, 1, -1}, {r.right, r.bottom, -1, -1}};
        for (float[] k : corners) {
            float x = k[0], y = k[1], dx = k[2], dy = k[3];
            c.drawRect(Math.min(x, x + dx * L), Math.min(y, y + dy * w), Math.max(x, x + dx * L), Math.max(y, y + dy * w), fill);
            c.drawRect(Math.min(x, x + dx * w), Math.min(y, y + dy * L), Math.max(x, x + dx * w), Math.max(y, y + dy * L), fill);
        }
    }

    private float toolsTop() {
        return getHeight() - barH() - toolsH();
    }

    private void drawTools(Canvas c) {
        float top = toolsTop();
        float sw = swatch(), g = gap();
        float rr = rounded ? 6 * dp : 0;
        if (mode == DRAW) {
            // brush size: a track, a knob, and the brush itself on the right
            float y = top + sliderH() / 2f;
            float left = 14 * dp, right = getWidth() - 64 * dp;
            fill.setColor(t.s3);
            c.drawRect(left, y - dp, right, y + dp, fill);
            float f = (brushDp - 2f) / 34f;
            float kx = left + (right - left) * f;
            fill.setColor(t.text);
            c.drawRect(left, y - dp, kx, y + dp, fill);
            float k = 7 * dp;
            c.drawRoundRect(kx - k, y - k, kx + k, y + k, rounded ? 3 * dp : 0, rounded ? 3 * dp : 0, fill);
            fill.setColor(colors[color]);
            float bd = Math.min(brushDp * dp, sliderH() * 0.9f);
            c.drawCircle(getWidth() - 32 * dp, y, bd / 2, fill);
            // the 16 colours
            for (int i = 0; i < 16; i++) {
                float x0 = (i % 8) * (sw + g), y0 = top + sliderH() + (i / 8) * (sw + g);
                fill.setColor(colors[i]);
                c.drawRoundRect(x0, y0, x0 + sw, y0 + sw, rr, rr, fill);
                if (i == color) {
                    // the chosen one: a frame in the ground colour inside it
                    fill.setColor(t.bg);
                    float in = 5 * dp, fw = 2.5f * dp;
                    r.set(x0 + in, y0 + in, x0 + sw - in, y0 + sw - in);
                    c.drawRect(r.left, r.top, r.right, r.top + fw, fill);
                    c.drawRect(r.left, r.bottom - fw, r.right, r.bottom, fill);
                    c.drawRect(r.left, r.top, r.left + fw, r.bottom, fill);
                    c.drawRect(r.right - fw, r.top, r.right, r.bottom, fill);
                }
            }
        } else {
            // crop: the shape, one tap apart, and "whole photo"
            float rowH = (toolsH() - g) / 2f;
            String[][] rows = {{"free", "square"}, {"whole photo", ""}};
            for (int row = 0; row < 2; row++) {
                float y0 = top + row * (rowH + g);
                for (int col = 0; col < 2; col++) {
                    String label = rows[row][col];
                    if (label.isEmpty()) continue;
                    float x0 = col * (getWidth() / 2f + g / 2f);
                    float x1 = row == 1 ? getWidth() : x0 + getWidth() / 2f - g / 2f;
                    boolean on = row == 0 && (col == 1) == square;
                    fill.setColor(on ? Theme.mix(t.plate, t.text, 0.08f) : t.plate);
                    c.drawRoundRect(x0, y0, x1, y0 + rowH, rr, rr, fill);
                    text.setColor(on ? t.accent : t.text);
                    float tw = text.measureText(label);
                    c.drawText(label, (x0 + x1) / 2f - tw / 2f, y0 + rowH / 2f + text.getTextSize() * 0.36f, text);
                }
            }
        }
    }

    private final RectF[] barRects = {new RectF(), new RectF(), new RectF(), new RectF()};

    private void drawBar(Canvas c) {
        float top = getHeight() - barH();
        fill.setColor(t.plate);
        c.drawRect(0, top, getWidth(), getHeight(), fill);
        String[] labels = {"crop", "draw", "undo", "save"};
        int[] cols = {mode == CROP ? t.text : t.s1, mode == DRAW ? t.text : t.s1,
                undo.isEmpty() ? t.s2 : t.text, dirty ? t.accent : t.s2};
        float base = top + barH() / 2f + text.getTextSize() * 0.36f;
        float x = 4 * dp;
        for (int i = 0; i < 2; i++) {
            float w = text.measureText(labels[i]) + 20 * dp;
            barRects[i].set(x, top, x + w, getHeight());
            text.setColor(cols[i]);
            c.drawText(labels[i], x + 10 * dp, base, text);
            x += w;
        }
        float xr = getWidth() - 4 * dp;
        for (int i = 3; i >= 2; i--) {
            float w = text.measureText(labels[i]) + 20 * dp;
            barRects[i].set(xr - w, top, xr, getHeight());
            text.setColor(cols[i]);
            c.drawText(labels[i], xr - w + 10 * dp, base, text);
            xr -= w;
        }
    }

    // ---- touch ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!open) return false;
        int act = e.getActionMasked();
        float x = e.getX(), y = e.getY();
        if (act == MotionEvent.ACTION_DOWN) {
            twoFinger = false;
            sliding = false;
            grab = -1;
            if (y >= getHeight() - barH()) {
                barTap = barHit(x, y);
                return true;
            }
            barTap = -1;
            if (y >= toolsTop()) {
                toolsDown(x, y);
                return true;
            }
            if (work == null) return true;
            layoutShown();
            if (mode == CROP) {
                cropDown(x, y);
            } else {
                startStroke(x, y);
            }
            return true;
        }
        if (barTap >= 0) {
            if (act == MotionEvent.ACTION_UP && barHit(x, y) == barTap) barAction(barTap);
            return true;
        }
        if (sliding) {
            if (act == MotionEvent.ACTION_MOVE || act == MotionEvent.ACTION_UP) slideTo(x);
            if (act == MotionEvent.ACTION_UP) L.putInt(getContext(), "brush", Math.round(brushDp));
            return true;
        }
        if (work == null) return true;
        layoutShown();
        if (act == MotionEvent.ACTION_POINTER_DOWN && mode == DRAW) {
            // a second finger: zoom and move; a just-begun mark is not a mark
            if (live != null) {
                boolean tiny = liveTravel < 14 * dp || SystemClock.uptimeMillis() - liveAt < 140;
                if (!tiny) commitStroke();
                live = null;
            }
            twoFinger = true;
            pinchDist = dist(e);
            pinchCx = (e.getX(0) + e.getX(1)) / 2f;
            pinchCy = (e.getY(0) + e.getY(1)) / 2f;
            invalidate();
            return true;
        }
        if (mode == DRAW) {
            if (twoFinger) {
                if (act == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) pinch(e);
                return true;
            }
            if (act == MotionEvent.ACTION_MOVE && live != null) {
                for (int h = 0; h < e.getHistorySize(); h++) addPoint(e.getHistoricalX(h), e.getHistoricalY(h));
                addPoint(x, y);
                invalidate();
            } else if (act == MotionEvent.ACTION_UP && live != null) {
                addPoint(x, y);
                commitStroke();
            } else if (act == MotionEvent.ACTION_CANCEL) {
                live = null;
                invalidate();
            }
            return true;
        }
        // crop
        if (grab >= 0) {
            if (act == MotionEvent.ACTION_MOVE) cropMove(x, y);
            else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) cropUp();
        }
        return true;
    }

    private int barTap = -1;

    private int barHit(float x, float y) {
        for (int i = 0; i < barRects.length; i++) if (barRects[i].contains(x, y)) return i;
        return -1;
    }

    private void barAction(int i) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        switch (i) {
            case 0:
                mode = CROP;
                break;
            case 1:
                mode = DRAW;
                zoom = 1f;
                panX = panY = 0;
                break;
            case 2:
                undoLast();
                break;
            case 3:
                if (dirty && listener != null) listener.onEditorSave(item);
                break;
        }
        invalidate();
    }

    private void toolsDown(float x, float y) {
        float top = toolsTop();
        if (mode == DRAW) {
            if (y < top + sliderH()) {
                if (x < getWidth() - 50 * dp) {
                    sliding = true;
                    slideTo(x);
                }
                return;
            }
            float sw = swatch(), g = gap();
            int col = (int) (x / (sw + g)), row = (int) ((y - top - sliderH()) / (sw + g));
            int i = row * 8 + col;
            if (i >= 0 && i < 16) {
                color = i;
                L.putInt(getContext(), "inkcolor", i);
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            }
        } else {
            float rowH = (toolsH() - gap()) / 2f;
            if (y < top + rowH) {
                boolean sq = x >= getWidth() / 2f;
                if (sq != square) {
                    square = sq;
                    L.putInt(getContext(), "cropsq", sq ? 1 : 0);
                    if (sq) makeSquare();
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            } else {
                // the whole photo again
                if (crop.left != 0 || crop.top != 0 || crop.right != 1 || crop.bottom != 1) {
                    undo.add(new RectF(crop));
                    crop.set(0, 0, 1, 1);
                    if (square) makeSquare();
                    dirty = true;
                }
            }
            invalidate();
        }
    }

    private void slideTo(float x) {
        float left = 14 * dp, right = getWidth() - 64 * dp;
        float f = Math.max(0f, Math.min(1f, (x - left) / (right - left)));
        brushDp = 2f + 34f * f;
        invalidate();
    }

    // drawing

    private void startStroke(float x, float y) {
        live = new Stroke();
        live.color = colors[color];
        // the brush keeps its size on screen: in the image it gets finer as you zoom in
        live.width = brushDp * dp / pxScale() / work.getWidth();
        liveAt = SystemClock.uptimeMillis();
        liveTravel = 0;
        lastX = x;
        lastY = y;
        live.pts.add(new float[] {ux(x), vy(y)});
        invalidate();
    }

    private void addPoint(float x, float y) {
        if (live == null) return;
        float d = (float) Math.hypot(x - lastX, y - lastY);
        if (d < 1.5f * dp) return;
        liveTravel += d;
        lastX = x;
        lastY = y;
        live.pts.add(new float[] {ux(x), vy(y)});
    }

    private void commitStroke() {
        if (live == null) return;
        strokes.add(live);
        undo.add(live);
        live = null;
        dirty = true;
        invalidate();
    }

    private static float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private void pinch(MotionEvent e) {
        float d = dist(e);
        float cx = (e.getX(0) + e.getX(1)) / 2f, cy = (e.getY(0) + e.getY(1)) / 2f;
        float f = pinchDist > 0 ? d / pinchDist : 1f;
        float nz = Math.max(1f, Math.min(8f, zoom * f));
        f = nz / zoom;
        // zoom about the fingers' middle, then follow it as it moves
        float ax = area.centerX(), ay = area.centerY();
        panX = (cx - ax) - ((pinchCx - ax) - panX) * f;
        panY = (cy - ay) - ((pinchCy - ay) - panY) * f;
        zoom = nz;
        if (zoom == 1f) {
            panX = 0;
            panY = 0;
        }
        pinchDist = d;
        pinchCx = cx;
        pinchCy = cy;
        invalidate();
    }

    private void undoLast() {
        if (undo.isEmpty()) return;
        Object last = undo.remove(undo.size() - 1);
        if (last instanceof Stroke) strokes.remove(last);
        else if (last instanceof RectF) crop.set((RectF) last);
        dirty = !undo.isEmpty();
        invalidate();
    }

    // cropping

    /** 0..3 corners, 4..7 edges, 8 inside */
    private void cropDown(float x, float y) {
        cropScreen(r);
        float cz = 26 * dp, ez = 20 * dp;   // touch zones, much bigger than what is drawn
        float[][] corners = {{r.left, r.top}, {r.right, r.top}, {r.left, r.bottom}, {r.right, r.bottom}};
        grab = -1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(x - corners[i][0]) < cz && Math.abs(y - corners[i][1]) < cz) grab = i;
        }
        if (grab < 0) {
            boolean inY = y > r.top - ez && y < r.bottom + ez, inX = x > r.left - ez && x < r.right + ez;
            if (Math.abs(y - r.top) < ez && inX) grab = 4;
            else if (Math.abs(y - r.bottom) < ez && inX) grab = 5;
            else if (Math.abs(x - r.left) < ez && inY) grab = 6;
            else if (Math.abs(x - r.right) < ez && inY) grab = 7;
            else if (r.contains(x, y)) grab = 8;
        }
        if (grab >= 0) {
            grabStart.set(crop);
            grabX = x;
            grabY = y;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
        }
    }

    private void cropMove(float x, float y) {
        float du = (x - grabX) / shown.width(), dv = (y - grabY) / shown.height();
        RectF c = new RectF(grabStart);
        float minU = 40 * dp / shown.width(), minV = 40 * dp / shown.height();
        switch (grab) {
            case 0: c.left += du; c.top += dv; break;
            case 1: c.right += du; c.top += dv; break;
            case 2: c.left += du; c.bottom += dv; break;
            case 3: c.right += du; c.bottom += dv; break;
            case 4: c.top += dv; break;
            case 5: c.bottom += dv; break;
            case 6: c.left += du; break;
            case 7: c.right += du; break;
            default: c.offset(du, dv);
        }
        if (grab == 8) {
            float w = c.width(), h = c.height();
            c.left = Math.max(0, Math.min(1 - w, c.left));
            c.top = Math.max(0, Math.min(1 - h, c.top));
            c.right = c.left + w;
            c.bottom = c.top + h;
        } else {
            c.left = Math.max(0, Math.min(c.right - minU, c.left));
            c.top = Math.max(0, Math.min(c.bottom - minV, c.top));
            c.right = Math.min(1, Math.max(c.left + minU, c.right));
            c.bottom = Math.min(1, Math.max(c.top + minV, c.bottom));
            if (square) squareFrom(c, grab);
        }
        crop.set(c);
        invalidate();
    }

    private void cropUp() {
        if (!grabStart.equals(crop)) {
            undo.add(new RectF(grabStart));
            dirty = true;
        }
        grab = -1;
        invalidate();
    }

    /** keep the crop square in image pixels, anchored at the corner or edge opposite the grab */
    private void squareFrom(RectF c, int g) {
        float W = work.getWidth(), H = work.getHeight();
        float side = Math.min(c.width() * W, c.height() * H);
        float su = side / W, sv = side / H;
        switch (g) {
            case 0: c.left = c.right - su; c.top = c.bottom - sv; break;
            case 1: c.right = c.left + su; c.top = c.bottom - sv; break;
            case 2: c.left = c.right - su; c.bottom = c.top + sv; break;
            case 4:
            case 5: {
                float cx = c.centerX();
                side = c.height() * H;
                su = Math.min(1f, side / W);
                sv = su * W / H;
                c.left = cx - su / 2;
                c.right = cx + su / 2;
                if (g == 4) c.top = c.bottom - sv;
                else c.bottom = c.top + sv;
                break;
            }
            case 6:
            case 7: {
                float cy = c.centerY();
                side = c.width() * W;
                sv = Math.min(1f, side / H);
                su = sv * H / W;
                c.top = cy - sv / 2;
                c.bottom = cy + sv / 2;
                if (g == 6) c.left = c.right - su;
                else c.right = c.left + su;
                break;
            }
            default: c.right = c.left + su; c.bottom = c.top + sv;
        }
        // keep it inside the photo
        if (c.left < 0) c.offset(-c.left, 0);
        if (c.top < 0) c.offset(0, -c.top);
        if (c.right > 1) c.offset(1 - c.right, 0);
        if (c.bottom > 1) c.offset(0, 1 - c.bottom);
    }

    private void makeSquare() {
        if (work == null) return;
        float W = work.getWidth(), H = work.getHeight();
        float side = Math.min(crop.width() * W, crop.height() * H);
        float cx = crop.centerX(), cy = crop.centerY();
        float su = side / W / 2, sv = side / H / 2;
        RectF before = new RectF(crop);
        crop.set(cx - su, cy - sv, cx + su, cy + sv);
        if (!before.equals(crop)) {
            undo.add(before);
            dirty = true;
        }
    }

    // ---- saving ----

    /**
     * The edit at full size: the crop of the original, with the marks drawn
     * over it in the same places. Runs off the main thread.
     */
    Bitmap render(Bitmap original) {
        int W = original.getWidth(), H = original.getHeight();
        int left = Math.round(crop.left * W), top = Math.round(crop.top * H);
        int w = Math.max(1, Math.round(crop.width() * W)), h = Math.max(1, Math.round(crop.height() * H));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(original, new Rect(left, top, left + w, top + h), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        List<Stroke> copy = new ArrayList<>(strokes);
        for (Stroke s : copy) {
            if (s.pts.isEmpty()) continue;
            p.setColor(s.color);
            p.setStrokeWidth(Math.max(1f, s.width * W));
            path.reset();
            float[] p0 = s.pts.get(0);
            path.moveTo(p0[0] * W - left, p0[1] * H - top);
            if (s.pts.size() == 1) path.lineTo(p0[0] * W - left + 0.1f, p0[1] * H - top);
            for (int i = 1; i < s.pts.size(); i++) {
                float[] a = s.pts.get(i - 1), b = s.pts.get(i);
                float ax = a[0] * W - left, ay = a[1] * H - top, bx = b[0] * W - left, by = b[1] * H - top;
                path.quadTo(ax, ay, (ax + bx) / 2, (ay + by) / 2);
            }
            float[] last = s.pts.get(s.pts.size() - 1);
            path.lineTo(last[0] * W - left, last[1] * H - top);
            c.drawPath(path, p);
        }
        return out;
    }

    void saved() {
        dirty = false;
        undo.clear();
        invalidate();
    }
}
