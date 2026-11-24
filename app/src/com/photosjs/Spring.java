package com.photosjs;

/** Soft spring easing: settles with a ~3% overshoot, never bouncy. */
final class Spring {
    private Spring() {}

    /** t in [0, 1] (time / duration) -> eased progress, 1 at t = 1. */
    static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) (1.0 - Math.exp(-8.0 * t) * Math.cos(7.0 * t));
    }

    /** Plain ease-out for fades. */
    static float out(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float u = 1f - t;
        return 1f - u * u * u;
    }
}
