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

    // bg, plate, text, accent (the lamp), highlight (the calm second), warning
    static final Theme[] ALL = {
        new Theme("clay", "clay", 0x1c1c1c, 0x262626, 0xebdbb2, 0xfd4a00, 0x83a598, 0xfabd2f),
        new Theme("parchment", "parchment", 0x1e1e1e, 0x282828, 0xcbb890, 0xfe8019, 0x458588, 0xcc241d),
        new Theme("miasma", "miasma", 0x171412, 0x221d19, 0xd7c483, 0x83a598, 0xeca824, 0xc8553d),
        new Theme("graphite", "graphite", 0x1b1b1b, 0x272727, 0xd8d8d8, 0xf5f5f5, 0xffe5cf, 0xd9826b),
        // from lockJS's second set, grounds darkened so the photos stay the only light
        new Theme("harvest", "harvest", 0x1d2021, 0x282828, 0xfbf1c7, 0xfb4934, 0xb8bb26, 0xfabd2f),
        new Theme("fjord", "fjord", 0x1d1e20, 0x28292b, 0xd9cfc2, 0x5ab2c9, 0xc28e7e, 0xbf616a),
        new Theme("oxblood", "oxblood", 0x1e110e, 0x2b1814, 0xd9d0c0, 0xeb3837, 0x7d97a5, 0xc3904b),
        new Theme("patina", "patina", 0x1c1715, 0x27211e, 0xfbf1c7, 0xc8601a, 0x2a8f91, 0xf7768e),
        new Theme("espresso", "espresso", 0x241c18, 0x332822, 0xdccab9, 0x5acdea, 0xcba6f7, 0xb87f65),
        new Theme("lagoon", "lagoon", 0x102122, 0x182f31, 0xd8ccb1, 0xf0bd19, 0x2aa198, 0xd75b95),
        new Theme("camellia", "camellia", 0x131b19, 0x1b2724, 0xe4c6be, 0xff6e8f, 0x85af80, 0xbf8e4b),
        new Theme("phosphor", "phosphor", 0x191b1a, 0x242725, 0xd1d0b1, 0x3dff85, 0x33b8a2, 0xffb000),
        new Theme("syro", "syro", 0x0f1112, 0x1c2124, 0xe9e7dc, 0xc6ef1f, 0x7fb3c4, 0xff4f9a),
        new Theme("cornish acid", "cornish acid", 0x161818, 0x1f2121, 0xe2dbca, 0xef6f97, 0x65aca8, 0xf2c84b),
        new Theme("kiln", "kiln", 0x1c1a19, 0x282524, 0xd9ccb2, 0xff6a3d, 0x4f88ff, 0xe0a23a),
        new Theme("bindery", "bindery", 0x1c1715, 0x27211e, 0xc2b09a, 0xc78e28, 0x688ab5, 0xe35b52),
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
