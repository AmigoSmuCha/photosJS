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
    static final String[] TEXT_NAMES = {"small", "medium", "large", "larger", "largest"};

    static int textIndex(Context c) {
        return Math.max(0, Math.min(TEXT.length - 1, getInt(c, "text", 1)));
    }

    static float textScale(Context c) {
        return TEXT[textIndex(c)];
    }

    /** Pinterest look by default: rounded cards with air between them. */
    static boolean rounded(Context c) {
        return getInt(c, "rounded", 1) == 1;
    }

    static boolean airy(Context c) {
        return getInt(c, "airy", 1) == 1;
    }

    static final String[] DATE_NAMES = {"auto", "by day", "by month", "by year", "off"};

    /** months as words ("22 nov", "november") or numbers ("22.11.", "11.2026") */
    static boolean dateNumbers(Context c) {
        return getInt(c, "datenum", 0) == 1;
    }

    /** headings (date labels, the reading line) in the blocks face from lockJS */
    static boolean blocks(Context c) {
        return getInt(c, "blocks", 0) == 1;
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
