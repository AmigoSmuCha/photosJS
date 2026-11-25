package com.photosjs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The face for headings (date labels, the reading line, section heads):
 *   plain       - Ubuntu Sans Mono, as everything else
 *   blocks      - lockJS's block face
 *   alagard     - Hewett Tsoi's gothic pixel font (ASCII only: accents dropped)
 *   spaced caps - bold capitals with air between the letters
 * Callers give a paint already sized for plain text; this adjusts it.
 */
final class Heading {
    static final int PLAIN = 0, BLOCKS = 1, ALAGARD = 2, CAPS = 3;
    static final String[] NAMES = {"plain", "blocks", "alagard", "spaced caps"};

    private static Typeface alagard;

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

    /** the words as this face writes them */
    static String text(String s, int style) {
        if (style == CAPS) return s.toUpperCase(Locale.ROOT);
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
                p.setTextSize(Math.max(16f, Math.round(plainSize * 1.1f / 16f) * 16f));
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

    static float width(String s, int style, Paint p) {
        String t = text(s, style);
        if (style == BLOCKS) return BlockFont.width(t, BlockFont.block(p.getTextSize()));
        return p.measureText(t);
    }

    /** Draws with the text's middle on cy. */
    static void draw(Canvas c, String s, int style, float x, float cy, Paint p) {
        String t = text(s, style);
        if (style == BLOCKS) {
            BlockFont.draw(c, t, x, cy, BlockFont.block(p.getTextSize()), p);
        } else {
            float base = cy + p.getTextSize() * (style == ALAGARD ? 0.3f : 0.36f);
            c.drawText(t, x, Math.round(base), p);
        }
    }

    /** Shortened to fit the room. */
    static String fit(String s, int style, Paint p, float room) {
        String t = text(s, style);
        if (style == BLOCKS) return BlockFont.fit(t, BlockFont.block(p.getTextSize()), Math.max(0, room));
        TextPaint tp = p instanceof TextPaint ? (TextPaint) p : new TextPaint(p);
        return TextUtils.ellipsize(t, tp, Math.max(0, room), TextUtils.TruncateAt.END).toString();
    }
}
