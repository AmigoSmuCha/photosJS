package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app's face, for every word (settings › font):
 *   plain       - Ubuntu Sans Mono
 *   blocks      - lockJS's block face
 *   alagard     - Hewett Tsoi's gothic pixel font (ASCII only: accents dropped)
 *   spaced caps - bold capitals with air between the letters
 *   thin blocks - the block face three blocks wide (the user's ITHACA)
 *
 * In the blocks face a line that doesn't fit its room is written in the
 * thin blocks before anything is cut (BlockFont.fitAuto).
 *
 * Views keep their paints set up for plain text (typeface, size, colour) and
 * write through text() / measure() / fitText() / wrap(), which take the same
 * arguments as Canvas.drawText and Paint.measureText; the face is applied on
 * the way. style() / draw() / width() / fit() are the older calls for paints
 * a view has already styled itself (headings).
 */
final class Heading {
    static final int PLAIN = 0, BLOCKS = 1, ALAGARD = 2, CAPS = 3, THIN = 4;
    static final String[] NAMES = {"plain", "blocks", "alagard", "spaced caps", "thin blocks"};

    private static Typeface alagard;
    private static Context app;
    private static int face = PLAIN;
    // a copy of the caller's paint in the face (drawing happens on the main thread only)
    private static final TextPaint scratch = new TextPaint();

    private Heading() {}

    static Typeface alagard(Context c) {
        if (alagard == null) {
            try {
                alagard = Typeface.createFromAsset(c.getAssets(), "fonts/Alagard.ttf");
            } catch (Throwable e) {
                alagard = L.font(c);
            }
        }
        return alagard;
    }

    /** the face every word is written in from now on */
    static void setFace(Context c, int f) {
        app = c.getApplicationContext();
        face = Math.max(0, Math.min(NAMES.length - 1, f));
    }

    static int face() {
        return face;
    }

    /** drawn square by square (BlockFont), not with a typeface */
    static boolean blocks(int style) {
        return style == BLOCKS || style == THIN;
    }

    /** the words as this face writes them */
    static String text(String s, int style) {
        if (style == CAPS) return s.toUpperCase(Locale.ROOT);
        if (style == THIN) return BlockFont.thin(s);
        if (style == ALAGARD) {
            return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        }
        return s;
    }

    /**
     * Sets the paint up for this face from a plain size. Alagard is a 16 px
     * pixel font: it is drawn at whole multiples of 16 px, without smoothing,
     * so its pixels stay square.
     */
    static void style(Paint p, Context c, int style, float plainSize) {
        switch (style) {
            case ALAGARD:
                p.setTypeface(alagard(c));
                p.setTextSize(alagardSize(plainSize));
                p.setLetterSpacing(0f);
                p.setAntiAlias(false);
                break;
            case CAPS:
                p.setTypeface(L.fontBold(c));
                p.setTextSize(plainSize * 0.9f);
                p.setLetterSpacing(0.14f);
                p.setAntiAlias(true);
                break;
            default:
                p.setTypeface(L.font(c));
                p.setTextSize(plainSize);
                p.setLetterSpacing(0f);
                p.setAntiAlias(true);
        }
    }

    static float alagardSize(float plainSize) {
        return Math.max(16f, Math.round(plainSize * 1.1f / 16f) * 16f);
    }

    static float width(String s, int style, Paint p) {
        String t = text(s, style);
        if (blocks(style)) return BlockFont.width(t, BlockFont.block(p.getTextSize()));
        return p.measureText(t);
    }

    /** Draws with the text's middle on cy. */
    static void draw(Canvas c, String s, int style, float x, float cy, Paint p) {
        String t = text(s, style);
        if (blocks(style)) {
            BlockFont.draw(c, t, x, cy, BlockFont.block(p.getTextSize()), p);
        } else {
            float base = cy + p.getTextSize() * (style == ALAGARD ? 0.3f : 0.36f);
            c.drawText(t, x, Math.round(base), p);
        }
    }

    /** Shortened to fit the room. */
    static String fit(String s, int style, Paint p, float room) {
        String t = text(s, style);
        if (style == BLOCKS) return BlockFont.fitAuto(t, BlockFont.block(p.getTextSize()), Math.max(0, room));
        if (style == THIN) return BlockFont.fit(t, BlockFont.block(p.getTextSize()), Math.max(0, room));
        TextPaint tp = p instanceof TextPaint ? (TextPaint) p : new TextPaint(p);
        return TextUtils.ellipsize(t, tp, Math.max(0, room), TextUtils.TruncateAt.END).toString();
    }

    // ---- every word: plain paints, written in the app's face ----

    /** p, in the face (alagard and caps only) */
    private static TextPaint faced(Paint p) {
        float size = p.getTextSize();
        scratch.set(p);
        if (face == ALAGARD) {
            scratch.setTypeface(alagard(app));
            scratch.setTextSize(alagardSize(size));
            scratch.setLetterSpacing(0f);
            scratch.setAntiAlias(false);
        } else {
            scratch.setTypeface(L.fontBold(app));
            scratch.setTextSize(size * 0.9f);
            scratch.setLetterSpacing(0.14f);
        }
        return scratch;
    }

    /** Like c.drawText(s, x, baseline, p), in the app's face; p is set up for plain text. */
    static void text(Canvas c, String s, float x, float baseline, Paint p) {
        if (s == null || s.isEmpty()) return;
        if (face == PLAIN || app == null) {
            c.drawText(s, x, baseline, p);
            return;
        }
        float size = p.getTextSize();
        float cy = baseline - size * 0.36f;
        String t = text(s, face);
        if (blocks(face)) {
            BlockFont.draw(c, t, x, cy, BlockFont.block(size), p);
            return;
        }
        TextPaint q = faced(p);
        float b = cy + q.getTextSize() * (face == ALAGARD ? 0.3f : 0.36f);
        c.drawText(t, x, Math.round(b), q);
    }

    /** Like p.measureText(s), in the app's face. */
    static float measure(String s, Paint p) {
        if (s == null || s.isEmpty()) return 0;
        if (face == PLAIN || app == null) return p.measureText(s);
        String t = text(s, face);
        if (blocks(face)) return BlockFont.width(t, BlockFont.block(p.getTextSize()));
        return faced(p).measureText(t);
    }

    /** Shortened (with an ellipsis) to fit the room, in the app's face. */
    static String fitText(String s, Paint p, float room) {
        if (s == null) return "";
        room = Math.max(0, room);
        if (face == PLAIN || app == null) {
            TextPaint tp = p instanceof TextPaint ? (TextPaint) p : new TextPaint(p);
            return TextUtils.ellipsize(s, tp, room, TextUtils.TruncateAt.END).toString();
        }
        String t = text(s, face);
        if (face == BLOCKS) return BlockFont.fitAuto(t, BlockFont.block(p.getTextSize()), room);
        if (face == THIN) return BlockFont.fit(t, BlockFont.block(p.getTextSize()), room);
        return TextUtils.ellipsize(t, faced(p), room, TextUtils.TruncateAt.END).toString();
    }

    /**
     * Broken into lines that fit, at spaces, at most maxLines (the last one
     * shortened). In the blocks face, if that would cut words off, the thin
     * blocks are tried before anything is cut.
     */
    static List<String> wrap(String s, Paint p, float room, int maxLines) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        if (wrapInto(out, s, p, room, maxLines, false) || face != BLOCKS) return out;
        List<String> thin = new ArrayList<>();
        wrapInto(thin, s, p, room, maxLines, true);
        return thin;
    }

    /** true when everything fitted without cutting */
    private static boolean wrapInto(List<String> out, String s, Paint p, float room, int maxLines,
                                    boolean thin) {
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String w = words[i];
            String tryLine = line.length() == 0 ? w : line + " " + w;
            if (measure(mark(tryLine, thin), p) <= room || line.length() == 0) {
                line.setLength(0);
                line.append(tryLine);
                i++;
                continue;
            }
            if (out.size() == maxLines - 1) break;
            out.add(mark(line.toString(), thin));
            line.setLength(0);
        }
        if (i < words.length) {
            // more than fits: the last line takes the rest, shortened
            StringBuilder rest = new StringBuilder(line);
            for (int k = i; k < words.length; k++) rest.append(' ').append(words[k]);
            out.add(cut(mark(rest.toString(), thin), p, room));
            return false;
        }
        String last = mark(line.toString(), thin);
        if (measure(last, p) <= room) {
            out.add(last);
            return true;
        }
        out.add(cut(last, p, room));
        return false;
    }

    private static String mark(String s, boolean thin) {
        return thin ? BlockFont.thin(s) : s;
    }

    /** shortened, without the blocks face's own thinning (wrap has decided that) */
    private static String cut(String s, Paint p, float room) {
        if (face == BLOCKS) return BlockFont.fit(s, BlockFont.block(p.getTextSize()), Math.max(0, room));
        return fitText(s, p, room);
    }
}
