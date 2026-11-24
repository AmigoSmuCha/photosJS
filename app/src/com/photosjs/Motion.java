package com.photosjs;

import android.content.Context;

/**
 * The animations setting, in one place: full (springs and morphs), calm
 * (shorter, plain fades, no overshoot) or off (everything instant).
 */
final class Motion {
    static final int OFF = 0, CALM = 1, FULL = 2;
    static final String[] NAMES = {"off", "calm", "full"};
    private static int level = FULL;

    private Motion() {}

    static void load(Context c) {
        level = Math.max(0, Math.min(2, L.getInt(c, "motion", FULL)));
    }

    static int level() {
        return level;
    }

    static long ms(long full) {
        if (level == OFF) return 0;
        if (level == CALM) return Math.round(full * 0.6);
        return full;
    }

    /** Progress t (0..1 of the duration) eased: a soft spring when full, a plain ease when calm. */
    static float ease(float t) {
        if (level == OFF) return 1f;
        return level == FULL ? Spring.ease(t) : Spring.out(t);
    }

    static float fade(float t) {
        if (level == OFF) return 1f;
        return Spring.out(t);
    }
}
