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
                    p(c).edit().putString("crash", s.length() > 4000 ? s.substring(0, 4000) : s)
                            .commit();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}
