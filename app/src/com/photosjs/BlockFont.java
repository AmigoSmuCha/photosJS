package com.photosjs;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * The "blocks" face from lockJS (focus's G5 5x5 matrix: solid squares, one
 * block between glyphs), extended to letters and the few signs dates need.
 * Letters are drawn as capitals; accents are dropped (č -> c).
 */
final class BlockFont {
    private BlockFont() {}

    private static final Map<Character, String[]> G = new HashMap<>();
    // thin blocks: three wide where five would be (the user's ITHACA sample);
    // m, w and the rare signs keep their regular shapes
    private static final Map<Character, String[]> T = new HashMap<>();

    /** A string starting with this is written in the thin blocks. */
    static final char THIN = '\u0001';

    private static void g(char c, String... rows) {
        G.put(c, rows);
    }

    private static void t(char c, String... rows) {
        T.put(c, rows);
    }

    static {
        // digits and A P M exactly as lockJS's clock
        g('0', "#####", "#   #", "#   #", "#   #", "#####");
        g('1', "  #  ", " ##  ", "  #  ", "  #  ", " ### ");
        g('2', "#####", "    #", "#####", "#    ", "#####");
        g('3', "#####", "    #", "#####", "    #", "#####");
        g('4', "#   #", "#   #", "#####", "    #", "    #");
        g('5', "#####", "#    ", "#####", "    #", "#####");
        g('6', "#####", "#    ", "#####", "#   #", "#####");
        g('7', "#####", "    #", "    #", "    #", "    #");
        g('8', "#####", "#   #", "#####", "#   #", "#####");
        g('9', "#####", "#   #", "#####", "    #", "#####");
        // letters: only straight strokes and right angles, no 45 degrees (as the
        // user painted them, 2026-11-25). Where a letter would need a slant, a gap
        // in a joint tells it apart instead: k is an h whose right leg breaks at
        // the bar, r the same under a closed top, x has both joints open, z is a
        // 2 whose middle bar floats, v is a u with open bottom corners.
        g('a', "#####", "#   #", "#####", "#   #", "#   #");
        g('b', "#####", "#   #", "#####", "#   #", "#####");
        g('c', "#####", "#    ", "#    ", "#    ", "#####");
        g('d', "#### ", "#   #", "#   #", "#   #", "#### ");
        g('e', "#####", "#    ", "#####", "#    ", "#####");
        g('f', "#####", "#    ", "#####", "#    ", "#    ");
        g('g', "#####", "#    ", "# ###", "#   #", "#####");
        g('h', "#   #", "#   #", "#####", "#   #", "#   #");
        g('i', "###", " # ", " # ", " # ", "###");
        g('j', "    #", "    #", "    #", "#   #", "#####");
        g('k', "#   #", "#   #", "#### ", "#   #", "#   #");
        g('l', "#    ", "#    ", "#    ", "#    ", "#####");
        g('m', "#####", "# # #", "# # #", "# # #", "# # #");
        g('n', "#####", "#   #", "#   #", "#   #", "#   #");
        g('o', "#####", "#   #", "#   #", "#   #", "#####");
        g('p', "#####", "#   #", "#####", "#    ", "#    ");
        g('q', "#####", "#   #", "#   #", "#####", "    #");
        g('r', "#####", "#   #", "#### ", "#   #", "#   #");
        g('s', "#####", "#    ", "#####", "    #", "#####");
        g('t', "#####", "  #  ", "  #  ", "  #  ", "  #  ");
        g('u', "#   #", "#   #", "#   #", "#   #", "#####");
        g('v', "#   #", "#   #", "#   #", "#   #", " ### ");
        g('w', "# # #", "# # #", "# # #", "# # #", "#####");
        g('x', "#   #", "#   #", " ### ", "#   #", "#   #");
        g('y', "#   #", "#   #", "#####", "  #  ", "  #  ");
        g('z', "#####", "    #", " ### ", "#    ", "#####");
        // signs, the same way
        g('.', " ", " ", " ", " ", "#");
        g(',', " ", " ", " ", "#", "#");
        g(':', " ", "#", " ", "#", " ");
        g(';', " ", "#", " ", "#", "#");
        g('/', "  #", "  #", "###", "#  ", "#  ");
        g('\\', "#  ", "#  ", "###", "  #", "  #");
        g('|', "#", "#", "#", "#", "#");
        g('-', "   ", "   ", "###", "   ", "   ");
        g('\u2013', "   ", "   ", "###", "   ", "   ");
        g('\u2014', "     ", "     ", "#####", "     ", "     ");
        g('=', "   ", "###", "   ", "###", "   ");
        g('\u00b7', " ", " ", "#", " ", " ");
        g('\u2026', "     ", "     ", "     ", "     ", "# # #");
        g('\'', "#", "#", " ", " ", " ");
        g('"', "# #", "# #", "   ", "   ", "   ");
        g('(', " #", "# ", "# ", "# ", " #");
        g(')', "# ", " #", " #", " #", "# ");
        g('[', "##", "# ", "# ", "# ", "##");
        g(']', "##", " #", " #", " #", "##");
        g('<', "  ", " #", "##", " #", "  ");
        g('>', "  ", "# ", "##", "# ", "  ");
        g('\u2039', "  ", " #", "##", " #", "  ");
        g('\u203a', "  ", "# ", "##", "# ", "  ");
        g('!', "#", "#", "#", " ", "#");
        g('_', "     ", "     ", "     ", "     ", "#####");
        g('+', "     ", "  #  ", " ### ", "  #  ", "     ");
        g('*', "  #  ", "# # #", " ### ", "# # #", "  #  ");
        g('\u00d7', "     ", "#   #", " ### ", "#   #", "     ");
        g('#', " # # ", "#####", " # # ", "#####", " # # ");
        g('%', "##  #", "##  #", "  #  ", "#  ##", "#  ##");
        g('&', "### ", "#   ", "####", "#  #", "####");
        g('?', "#####", "    #", "  ###", "     ", "  #  ");
        g(' ', "  ", "  ", "  ", "  ", "  ");

        t('0', "###", "# #", "# #", "# #", "###");
        t('1', " # ", "## ", " # ", " # ", "###");
        t('2', "###", "  #", "###", "#  ", "###");
        t('3', "###", "  #", "###", "  #", "###");
        t('4', "# #", "# #", "###", "  #", "  #");
        t('5', "###", "#  ", "###", "  #", "###");
        t('6', "###", "#  ", "###", "# #", "###");
        t('7', "###", "  #", "  #", "  #", "  #");
        t('8', "###", "# #", "###", "# #", "###");
        t('9', "###", "# #", "###", "  #", "###");
        t('a', "###", "# #", "###", "# #", "# #");
        t('b', "###", "# #", "###", "# #", "###");
        t('c', "###", "#  ", "#  ", "#  ", "###");
        t('d', "## ", "# #", "# #", "# #", "## ");
        t('e', "###", "#  ", "###", "#  ", "###");
        t('f', "###", "#  ", "###", "#  ", "#  ");
        t('g', "###", "#  ", "# #", "# #", "###");
        t('h', "# #", "# #", "###", "# #", "# #");
        t('i', "###", " # ", " # ", " # ", "###");
        t('j', "  #", "  #", "  #", "# #", "###");
        t('k', "# #", "# #", "## ", "# #", "# #");
        t('l', "#  ", "#  ", "#  ", "#  ", "###");
        t('n', "###", "# #", "# #", "# #", "# #");
        t('o', "###", "# #", "# #", "# #", "###");
        t('p', "###", "# #", "###", "#  ", "#  ");
        t('q', "###", "# #", "# #", "###", "  #");
        t('r', "###", "# #", "## ", "# #", "# #");
        t('s', "###", "#  ", "###", "  #", "###");
        t('t', "###", " # ", " # ", " # ", " # ");
        t('u', "# #", "# #", "# #", "# #", "###");
        t('v', "# #", "# #", "# #", "# #", " # ");
        t('x', "# #", "# #", " # ", "# #", "# #");
        t('y', "# #", "# #", "###", " # ", " # ");
        t('z', "###", "  #", " # ", "#  ", "###");
        t('+', "   ", " # ", "###", " # ", "   ");
        t('?', "###", "  #", " ##", "   ", " # ");
        t('_', "   ", "   ", "   ", "   ", "###");
        t('\u00d7', "   ", "# #", " # ", "# #", "   ");
        t('\u2014', "   ", "   ", "###", "   ", "   ");
        t(' ', " ", " ", " ", " ", " ");
    }

    private static String[] glyph(char ch, boolean thin) {
        char c = Character.toLowerCase(ch);
        String[] r = thin ? T.get(c) : null;
        if (r == null) r = G.get(c);
        if (r != null) return r;
        String base = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        if (!base.isEmpty()) {
            c = Character.toLowerCase(base.charAt(0));
            r = thin ? T.get(c) : null;
            if (r == null) r = G.get(c);
            if (r != null) return r;
        }
        return thin ? T.get('?') : G.get('?');
    }

    static boolean isThin(String s) {
        return !s.isEmpty() && s.charAt(0) == THIN;
    }

    /** the same words, marked to be written thin */
    static String thin(String s) {
        return isThin(s) ? s : THIN + s;
    }

    private static String body(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == THIN) i++;
        return i == 0 ? s : s.substring(i);
    }

    /** The block size that makes this face sit with text of the given size. */
    static float block(float textSize) {
        return Math.max(1f, Math.round(textSize / 6.2f));
    }

    static float width(String s, float block) {
        boolean thin = isThin(s);
        s = body(s);
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) w += block;
            w += glyph(s.charAt(i), thin)[0].length() * block;
        }
        return w;
    }

    /** Draws with the glyphs' vertical middle on cy. */
    static void draw(Canvas c, String s, float x, float cy, float block, Paint p) {
        float top = Math.round(cy - 2.5f * block);
        float cx = Math.round(x);
        boolean thin = isThin(s);
        s = body(s);
        for (int i = 0; i < s.length(); i++) {
            String[] g = glyph(s.charAt(i), thin);
            for (int row = 0; row < g.length; row++) {
                String line = g[row];
                for (int col = 0; col < line.length(); col++) {
                    if (line.charAt(col) != '#') continue;
                    float l = cx + col * block, t = top + row * block;
                    c.drawRect(l, t, l + block, t + block, p);
                }
            }
            cx += (g[0].length() + 1) * block;
        }
    }

    /** Cuts the string so it fits, ending in "..". */
    static String fit(String s, float block, float room) {
        if (width(s, block) <= room) return s;
        boolean thin = isThin(s);
        String b = body(s);
        for (int n = b.length() - 1; n > 0; n--) {
            String t = b.substring(0, n).trim() + "..";
            if (thin) t = THIN + t;
            if (width(t, block) <= room) return t;
        }
        return "";
    }

    /** As it is if it fits; else thin if that fits; else thin and cut. */
    static String fitAuto(String s, float block, float room) {
        if (width(s, block) <= room) return s;
        return fit(thin(s), block, room);
    }
}
