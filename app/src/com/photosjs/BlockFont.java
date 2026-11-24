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

    private static void g(char c, String... rows) {
        G.put(c, rows);
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
        g('a', "#####", "#   #", "#####", "#   #", "#   #");
        g('b', "#### ", "#   #", "#### ", "#   #", "#### ");
        g('c', "#####", "#    ", "#    ", "#    ", "#####");
        g('d', "#### ", "#   #", "#   #", "#   #", "#### ");
        g('e', "#####", "#    ", "#####", "#    ", "#####");
        g('f', "#####", "#    ", "#####", "#    ", "#    ");
        g('g', "#####", "#    ", "# ###", "#   #", "#####");
        g('h', "#   #", "#   #", "#####", "#   #", "#   #");
        g('i', "###", " # ", " # ", " # ", "###");
        g('j', "    #", "    #", "    #", "#   #", "#####");
        g('k', "#   #", "#  # ", "###  ", "#  # ", "#   #");
        g('l', "#    ", "#    ", "#    ", "#    ", "#####");
        g('m', "#####", "# # #", "# # #", "# # #", "# # #");
        g('n', "#   #", "##  #", "# # #", "#  ##", "#   #");
        g('o', "#####", "#   #", "#   #", "#   #", "#####");
        g('p', "#####", "#   #", "#####", "#    ", "#    ");
        g('q', "#####", "#   #", "#   #", "#  # ", "### #");
        g('r', "#####", "#   #", "#####", "#  # ", "#   #");
        g('s', "#####", "#    ", "#####", "    #", "#####");
        g('t', "#####", "  #  ", "  #  ", "  #  ", "  #  ");
        g('u', "#   #", "#   #", "#   #", "#   #", "#####");
        g('v', "#   #", "#   #", "#   #", " # # ", "  #  ");
        g('w', "# # #", "# # #", "# # #", "# # #", "#####");
        g('x', "#   #", " # # ", "  #  ", " # # ", "#   #");
        g('y', "#   #", "#   #", "#####", "  #  ", "  #  ");
        g('z', "#####", "   # ", "  #  ", " #   ", "#####");
        g('.', " ", " ", " ", " ", "#");
        g(',', " ", " ", " ", "#", "#");
        g(':', " ", "#", " ", "#", " ");
        g('/', "    #", "   # ", "  #  ", " #   ", "#    ");
        g('-', "   ", "   ", "###", "   ", "   ");
        g('·', " ", " ", "#", " ", " ");
        g('\'', "#", "#", " ", " ", " ");
        g('(', " #", "# ", "# ", "# ", " #");
        g(')', "# ", " #", " #", " #", "# ");
        g('!', "#", "#", "#", " ", "#");
        g('_', "     ", "     ", "     ", "     ", "#####");
        g('+', "     ", "  #  ", " ### ", "  #  ", "     ");
        g('&', " ##  ", "#  # ", " ## #", "#  # ", " ## #");
        g('?', "#####", "    #", "  ###", "     ", "  #  ");
        g(' ', "  ", "  ", "  ", "  ", "  ");
    }

    private static String[] glyph(char ch) {
        String[] r = G.get(Character.toLowerCase(ch));
        if (r != null) return r;
        String base = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        if (!base.isEmpty()) {
            r = G.get(Character.toLowerCase(base.charAt(0)));
            if (r != null) return r;
        }
        return G.get('?');
    }

    /** The block size that makes this face sit with text of the given size. */
    static float block(float textSize) {
        return Math.max(1f, Math.round(textSize / 6.2f));
    }

    static float width(String s, float block) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) w += block;
            w += glyph(s.charAt(i))[0].length() * block;
        }
        return w;
    }

    /** Draws with the glyphs' vertical middle on cy. */
    static void draw(Canvas c, String s, float x, float cy, float block, Paint p) {
        float top = Math.round(cy - 2.5f * block);
        float cx = Math.round(x);
        for (int i = 0; i < s.length(); i++) {
            String[] g = glyph(s.charAt(i));
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
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n).trim() + "..";
            if (width(t, block) <= room) return t;
        }
        return "";
    }
}
