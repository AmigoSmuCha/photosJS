package com.photosjs;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * A small sheet over the bottom of the screen: which album, or a name for one.
 * Tapping above it closes it. Nothing dims; the photos stay where they are.
 */
public class AlbumSheet extends FrameLayout {
    interface Listener {
        void onAlbumChosen(long album);

        void onNameGiven(String name);

        void onSheetClosed();
    }

    private final float dp;
    private Theme t;
    private Listener listener;
    private float textScale = 1f;
    private boolean rounded = true;
    private final LinearLayout panel;
    private EditText field;

    public AlbumSheet(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        addView(panel, new LayoutParams(-1, -2, Gravity.BOTTOM));
        setVisibility(GONE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLook(Theme th, boolean rounded, float textScale) {
        t = th;
        this.rounded = rounded;
        this.textScale = textScale;
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // a tap above the panel closes the sheet
        if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getY() < panel.getTop()) close();
        return true;
    }

    void close() {
        if (!isOpen()) return;
        hideKeyboard();
        setVisibility(GONE);
        if (listener != null) listener.onSheetClosed();
    }

    private void frame() {
        panel.removeAllViews();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.plate);
        float r = rounded ? 14 * dp : 0;
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        int p = Math.round(14 * dp);
        panel.setPadding(p, Math.round(12 * dp), p, Math.round(8 * dp));
    }

    private TextView text(String s, int color, float size) {
        TextView v = new TextView(getContext());
        v.setText(s);
        v.setTextColor(color);
        v.setTypeface(L.font(getContext()));
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * dp * textScale);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    /** title, then "new album" and every album with its count */
    void showChooser(String title, List<Store.Album> albums, List<Integer> counts) {
        frame();
        TextView head = text(title, t.s1, 9);
        head.setPadding(0, 0, 0, Math.round(6 * dp));
        panel.addView(head);
        ScrollView sv = new ScrollView(getContext());
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        int rowH = Math.round(42 * dp * textScale);
        list.addView(row("new album", "", t.accent, rowH, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showName("name for the new album", "");
            }
        }));
        for (int i = 0; i < albums.size(); i++) {
            final Store.Album a = albums.get(i);
            list.addView(row(a.name, String.valueOf(counts.get(i)), t.text, rowH, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setVisibility(GONE);
                    if (listener != null) listener.onAlbumChosen(a.id);
                }
            }));
        }
        int maxH = Math.round(getHeight() > 0 ? getHeight() * 0.6f : 300 * dp);
        int want = rowH * (albums.size() + 1);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(want, maxH)));
        setVisibility(VISIBLE);
    }

    private View row(String name, String count, int color, int h, OnClickListener click) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(name, color, 10.5f);
        r.addView(n, new LinearLayout.LayoutParams(0, h, 1f));
        n.setGravity(Gravity.CENTER_VERTICAL);
        TextView c = text(count, t.s1, 9.5f);
        c.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(c, new LinearLayout.LayoutParams(-2, h));
        r.setOnClickListener(click);
        return r;
    }

    /** a single line to type a name, then "done" */
    void showName(String title, String initial) {
        frame();
        TextView head = text(title, t.s1, 9);
        panel.addView(head);
        field = new EditText(getContext());
        field.setText(initial);
        field.setSelection(initial.length());
        field.setTextColor(t.text);
        field.setHintTextColor(t.s2);
        field.setTypeface(L.font(getContext()));
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11 * dp * textScale);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        GradientDrawable line = new GradientDrawable();
        line.setColor(Theme.mix(t.plate, t.bg, 0.5f));
        line.setCornerRadius(rounded ? 6 * dp : 0);
        field.setBackground(line);
        int p = Math.round(8 * dp);
        field.setPadding(p, p, p, p);
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                done();
                return true;
            }
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = Math.round(8 * dp);
        panel.addView(field, fl);
        LinearLayout buttons = new LinearLayout(getContext());
        buttons.setGravity(Gravity.END);
        int bh = Math.round(42 * dp * textScale);
        TextView cancel = text("cancel", t.s1, 10.5f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(p * 2, 0, p * 2, 0);
        cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        TextView ok = text("done", t.accent, 10.5f);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(p * 2, 0, p, 0);
        ok.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                done();
            }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(-2, bh));
        buttons.addView(ok, new LinearLayout.LayoutParams(-2, bh));
        panel.addView(buttons);
        setVisibility(VISIBLE);
        field.requestFocus();
        field.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null && field != null) im.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void done() {
        if (field == null) return;
        String name = field.getText().toString().trim();
        if (name.isEmpty()) return;
        hideKeyboard();
        setVisibility(GONE);
        if (listener != null) listener.onNameGiven(name);
    }

    private void hideKeyboard() {
        if (field == null) return;
        InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }
}
