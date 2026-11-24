package com.photosjs;

import android.content.Context;

/**
 * A theme is six roles; every other tone is derived from them, so no foreign
 * colour can sneak in. Shades are the text sunk progressively into the
 * background: s1 (secondary text), s2 (hints), s3 (barely-there marks), hair.
 */
final class Theme {
    final String key, name;
    final int bg, plate, text, accent, highlight, warning;
    final int s1, s2, s3, hair;

    private Theme(String key, String name, int bg, int plate, int text, int accent,
                  int highlight, int warning) {
        this.key = key;
        this.name = name;
        this.bg = 0xFF000000 | bg;
        this.plate = 0xFF000000 | plate;
        this.text = 0xFF000000 | text;
        this.accent = 0xFF000000 | accent;
        this.highlight = 0xFF000000 | highlight;
        this.warning = 0xFF000000 | warning;
        this.s1 = mix(this.text, this.bg, 0.35f);
        this.s2 = mix(this.text, this.bg, 0.55f);
        this.s3 = mix(this.text, this.bg, 0.72f);
        this.hair = mix(this.text, this.bg, 0.86f);
    }

    static final Theme[] ALL = {
        new Theme("clay", "clay", 0x1c1c1c, 0x262626, 0xd5c4a1, 0xfd4a00, 0x83a598, 0xfabd2f),
        new Theme("parchment", "parchment", 0x1e1e1e, 0x282828, 0xcbb890, 0xfe8019, 0x458588, 0xcc241d),
        new Theme("miasma", "miasma", 0x171412, 0x221d19, 0xd7c483, 0x83a598, 0xeca824, 0xc8553d),
        new Theme("graphite", "graphite", 0x1b1b1b, 0x272727, 0xd8d8d8, 0xf5f5f5, 0xffe5cf, 0xd9826b),
    };

    static int index(String key) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].key.equals(key)) return i;
        return 0;
    }

    static Theme get(Context c) {
        return ALL[index(L.getString(c, "theme", "clay"))];
    }

    /** a + (b - a) * t per channel, opaque. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * t));
        int g = Math.round(((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * t));
        int bl = Math.round((a & 255) + (((b & 255) - (a & 255)) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(255 * a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }
}
