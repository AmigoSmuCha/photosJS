package com.photosjs;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Settings in SharedPreferences, the app font, and a crash report. */
final class L {
    private static final String PREFS = "photosjs";
    private static Typeface regular, semibold;

    private L() {}

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int getInt(Context c, String k, int def) {
        return p(c).getInt(k, def);
    }

    static void putInt(Context c, String k, int v) {
        p(c).edit().putInt(k, v).apply();
    }

    static String getString(Context c, String k, String def) {
        return p(c).getString(k, def);
    }

    static void putString(Context c, String k, String v) {
        p(c).edit().putString(k, v).apply();
    }

    static boolean has(Context c, String k) {
        return p(c).contains(k);
    }

    static final float[] TEXT = {0.9f, 1f, 1.15f, 1.3f, 1.5f};
    // Alagard reads small at the same size: its letters take these steps, which start
    // where the others' large is (the user, 2026-11-26), while rows and bars keep TEXT
    // (2026-11-27: the grid stays the same whatever the font)
    static final float[] TEXT_ALAGARD = {1.15f, 1.3f, 1.5f, 1.75f, 2f};
    static final String[] TEXT_NAMES = {"small", "medium", "large", "larger", "largest"};

    static int textIndex(Context c) {
        return Math.max(0, Math.min(TEXT.length - 1, getInt(c, "text", 1)));
    }

    /** bars grow with the text, a little less than it (the words grow fully) */
    static float barScale(float textScale) {
        return 0.6f + 0.4f * textScale;
    }

    /** every size in the app follows this: words, rows, bars (the same in every font) */
    static float textScale(Context c) {
        return TEXT[textIndex(c)];
    }

    /** how much larger Alagard's letters are drawn than the others' at this text size */
    static float alagardBoost(Context c) {
        int i = textIndex(c);
        return TEXT_ALAGARD[i] / TEXT[i];
    }

    /** Pinterest look by default: rounded cards with air between them. */
    static boolean rounded(Context c) {
        return getInt(c, "rounded", 1) == 1;
    }

    /** air between photos and boards: tight, airy, breezy, adrift */
    static final String[] SPACE_NAMES = {"tight", "airy", "breezy", "adrift"};

    /** 0 tight (a hairline) .. 3 adrift; carries over the old on/off "airy" */
    static int spacing(Context c) {
        if (!has(c, "space")) return getInt(c, "airy", 1) == 1 ? 1 : 0;
        return Math.max(0, Math.min(SPACE_NAMES.length - 1, getInt(c, "space", 1)));
    }

    /** the air at one spacing step, for photos this many columns across */
    static float gapDp(int space, int cols) {
        switch (space) {
            case 0: return 0;
            case 2: return cols <= 3 ? 11f : cols <= 5 ? 7f : 4f;
            case 3: return cols <= 3 ? 18f : cols <= 5 ? 12f : 6f;
            default: return cols <= 3 ? 6f : cols <= 5 ? 4f : 2f;
        }
    }

    static final String[] DATE_NAMES = {"auto", "by day", "by month", "by year", "off"};

    /** months as words ("22 nov", "november") or numbers ("22.11.", "11.2026") */
    static boolean dateNumbers(Context c) {
        return getInt(c, "datenum", 0) == 1;
    }

    /** the app's font: Heading.PLAIN, BLOCKS, ALAGARD, CAPS or THIN */
    static int heading(Context c) {
        if (!has(c, "heading")) return getInt(c, "blocks", 0) == 1 ? Heading.BLOCKS : Heading.PLAIN;
        return Math.max(0, Math.min(Heading.NAMES.length - 1, getInt(c, "heading", 0)));
    }

    /** 0 auto (day / month / year by grid size), 1 day, 2 month, 3 year, 4 off */
    static int dates(Context c) {
        return Math.max(0, Math.min(4, getInt(c, "dates", 2)));
    }

    static Typeface font(Context c) {
        if (regular == null) {
            try {
                regular = Typeface.createFromAsset(c.getAssets(), "fonts/UbuntuSansMono-Regular.ttf");
            } catch (Throwable e) {
                regular = Typeface.MONOSPACE;
            }
        }
        return regular;
    }

    static Typeface fontBold(Context c) {
        if (semibold == null) {
            try {
                semibold = Typeface.createFromAsset(c.getAssets(), "fonts/UbuntuSansMono-SemiBold.ttf");
            } catch (Throwable e) {
                semibold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            }
        }
        return semibold;
    }

    /** Keeps the last crash so the settings panel can show it. */
    static void catchCrashes(final Context c) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter w = new StringWriter();
                    e.printStackTrace(new PrintWriter(w));
                    String s = w.toString();
                    if (s.length() > 3000) s = s.substring(0, 3000);
                    // keep the last three, newest first
                    String old = p(c).getString("crash", "");
                    String all = s + (old.isEmpty() ? "" : "\n----\n" + old);
                    String[] parts = all.split("\n----\n");
                    StringBuilder keep = new StringBuilder();
                    for (int i = 0; i < Math.min(3, parts.length); i++) {
                        if (i > 0) keep.append("\n----\n");
                        keep.append(parts[i]);
                    }
                    p(c).edit().putString("crash", keep.toString()).commit();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}
