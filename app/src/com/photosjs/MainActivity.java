package com.photosjs;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements GridView.Listener, FolderView.Listener,
        BarView.Listener, Thumbs.Listener {

    private static final int REQ_MEDIA = 1;
    private static final int TAB_PHOTOS = 0, TAB_FOLDERS = 1;

    private Theme t;
    private Thumbs thumbs;
    private FrameLayout root, content;
    private TopLine top;
    private BarView bar;
    private GridView photos, folderGrid;
    private FolderView folderView;
    private SettingsPanel panel;
    private View catcher;
    private boolean panelOpen;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Media.Item> all = new ArrayList<>();
    private boolean loadedOnce, asked;
    private int tab = TAB_PHOTOS;
    private Media.Folder openFolder;
    private int photoCount, folderCount, folderItemCount;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        L.catchCrashes(this);
        t = Theme.get(this);
        thumbs = new Thumbs(getContentResolver());
        thumbs.addListener(this);
        float dp = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        top = new TopLine(this);
        column.addView(top, new LinearLayout.LayoutParams(-1, Math.round(27 * dp)));

        content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        bar = new BarView(this);
        bar.setListener(this);
        column.addView(bar, new LinearLayout.LayoutParams(-1, Math.round(40 * dp)));

        photos = new GridView(this);
        photos.setThumbs(thumbs);
        photos.setListener(this);
        photos.setColumns(colsOf("all"));
        photos.setPinterestUpTo(pinterest());
        content.addView(photos, new FrameLayout.LayoutParams(-1, -1));

        folderView = new FolderView(this);
        folderView.setThumbs(thumbs);
        folderView.setListener(this);
        folderView.setVisibility(View.GONE);
        content.addView(folderView, new FrameLayout.LayoutParams(-1, -1));

        folderGrid = new GridView(this);
        folderGrid.setThumbs(thumbs);
        folderGrid.setListener(this);
        folderGrid.setPinterestUpTo(pinterest());
        folderGrid.setVisibility(View.GONE);
        content.addView(folderGrid, new FrameLayout.LayoutParams(-1, -1));

        // taps above the open settings panel close it; nothing is dimmed
        catcher = new View(this);
        catcher.setVisibility(View.GONE);
        catcher.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) closePanel();
                return true;
            }
        });
        content.addView(catcher, new FrameLayout.LayoutParams(-1, -1));

        panel = new SettingsPanel(this);
        panel.setVisibility(View.GONE);
        content.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        applyTheme();
        top.set("photos", "");

        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true, observer);
        getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true, observer);

        if (hasMedia()) {
            load();
        } else {
            askMedia();
        }
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        super.onDestroy();
    }

    // ---- theme ----

    private void applyTheme() {
        Window w = getWindow();
        w.setStatusBarColor(t.bg);
        w.setNavigationBarColor(t.plate);
        root.setBackgroundColor(t.bg);
        top.setTheme(t);
        bar.setTheme(t);
        photos.setTheme(t);
        folderGrid.setTheme(t);
        folderView.setTheme(t);
        panel.setTheme(t);
    }

    /** The whole app cross-fades at once, not colour by colour. */
    private void switchTheme(int index) {
        Theme next = Theme.ALL[(index + Theme.ALL.length) % Theme.ALL.length];
        L.putString(this, "theme", next.key);
        if (root.getWidth() > 0) {
            try {
                Bitmap snap = Bitmap.createBitmap(root.getWidth(), root.getHeight(),
                        Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(snap));
                final ImageView over = new ImageView(this);
                over.setImageBitmap(snap);
                root.addView(over, new FrameLayout.LayoutParams(-1, -1));
                t = next;
                applyTheme();
                over.animate().alpha(0f).setDuration(450).setInterpolator(new DecelerateInterpolator())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                root.removeView(over);
                            }
                        });
                return;
            } catch (Throwable ignored) {
            }
        }
        t = next;
        applyTheme();
    }

    // ---- permission and loading ----

    private boolean hasMedia() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askMedia() {
        if (asked && Build.VERSION.SDK_INT >= 23
                && !shouldShowRequestPermissionRationale(Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE)) {
            // Android no longer asks: the switch is in the app's settings
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
            }
            return;
        }
        asked = true;
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO}, REQ_MEDIA);
        } else {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MEDIA) return;
        if (hasMedia()) {
            load();
        } else {
            top.message("photosJS needs your photos · tap anywhere to allow", 600000, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loadedOnce && hasMedia()) load();
    }

    private final Runnable reload = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };

    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean self) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, 700);
        }
    };

    private void load() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<Media.Item> list = Media.load(getContentResolver());
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean first = !loadedOnce;
                        loadedOnce = true;
                        all = list;
                        thumbs.clearFailures();
                        top.clearMessage();
                        refreshAll(first ? GridView.TOP : GridView.KEEP);
                        if (all.isEmpty()) top.message("no photos here yet", 600000, 0);
                    }
                });
            }
        });
    }

    // ---- views ----

    private String viewKey() {
        if (tab == TAB_FOLDERS) return openFolder == null ? "folders" : "f:" + openFolder.bucket;
        return "all";
    }

    private int sortOf(String view) {
        return L.getInt(this, "sort:" + view, L.getInt(this, "sort:all", Media.BY_TAKEN));
    }

    private boolean descOf(String view) {
        return L.getInt(this, "desc:" + view, L.getInt(this, "desc:all", 1)) == 1;
    }

    private int colsOf(String view) {
        return L.getInt(this, "cols:" + view, L.getInt(this, "defcols", 4));
    }

    private boolean videos() {
        return L.getInt(this, "videos", 1) == 1;
    }

    private boolean sizes() {
        return L.getInt(this, "sizes", 0) == 1;
    }

    private int pinterest() {
        return L.getInt(this, "pinterest", 2);
    }

    private void refreshAll(int how) {
        refreshPhotos(how);
        refreshFolders(false);
        if (openFolder != null) refreshFolderGrid(how);
        updateTop();
    }

    private void refreshPhotos(int how) {
        List<Media.Item> list = Media.filter(all, videos(), 0);
        int by = sortOf("all");
        Media.sort(list, by, descOf("all"));
        photos.setItems(list, by == Media.BY_TAKEN || by == Media.BY_MODIFIED, by == Media.BY_MODIFIED, how);
        photoCount = list.size();
    }

    private void refreshFolders(boolean toTop) {
        List<Media.Item> list = Media.filter(all, videos(), 0);
        Media.sort(list, Media.BY_TAKEN, true);   // covers: each folder's newest photo
        List<Media.Folder> fs = Media.folders(list);
        Media.sortFolders(fs, sortOf("folders"), descOf("folders"));
        folderView.setFolders(fs, sizes(), toTop);
        folderCount = fs.size();
        if (openFolder != null) {
            Media.Folder still = null;
            for (Media.Folder f : fs) if (f.bucket == openFolder.bucket) still = f;
            if (still != null) openFolder = still;
        }
    }

    private void refreshFolderGrid(int how) {
        if (openFolder == null) return;
        String key = "f:" + openFolder.bucket;
        List<Media.Item> list = Media.filter(all, videos(), openFolder.bucket);
        int by = sortOf(key);
        Media.sort(list, by, descOf(key));
        folderGrid.setItems(list, by == Media.BY_TAKEN || by == Media.BY_MODIFIED, by == Media.BY_MODIFIED, how);
        folderItemCount = list.size();
    }

    private static String count(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    private void updateTop() {
        if (tab == TAB_PHOTOS) {
            top.set("photos", loadedOnce ? count(photoCount) : "");
        } else if (openFolder == null) {
            top.set("folders", count(folderCount));
        } else {
            top.set(openFolder.name, count(folderItemCount));
        }
    }

    private View currentView() {
        if (tab == TAB_PHOTOS) return photos;
        return openFolder == null ? folderView : folderGrid;
    }

    /** Swap the visible page with a short cross-fade; the bars never move. */
    private void show(View next) {
        View[] pages = {photos, folderView, folderGrid};
        for (final View v : pages) {
            if (v == next) continue;
            if (v.getVisibility() == View.VISIBLE) {
                v.animate().cancel();
                v.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                    }
                });
            }
        }
        if (next.getVisibility() != View.VISIBLE) {
            next.animate().cancel();
            next.setAlpha(0f);
            next.setVisibility(View.VISIBLE);
            next.animate().alpha(1f).setDuration(220);
        }
        catcher.bringToFront();
        panel.bringToFront();
    }

    // ---- bar ----

    @Override
    public void onTab(int i) {
        if (i == 2) {
            top.message("albums come in a later build");
            return;
        }
        closePanel();
        if (i == tab) {
            if (i == TAB_FOLDERS && openFolder != null) closeFolder();
            return;
        }
        tab = i;
        bar.setTab(i);
        show(currentView());
        updateTop();
    }

    @Override
    public void onSelectToggle() {
        top.message("selecting comes in the next build");
    }

    @Override
    public void onSettings() {
        if (panelOpen) closePanel();
        else openPanel();
    }

    // ---- grid and folders ----

    @Override
    public void onOpen(Media.Item it) {
        if (it.video) {
            Intent v = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(it.uri(), it.mime.isEmpty() ? "video/*" : it.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(new Intent(v).setPackage("org.videolan.vlc"));
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(v);
                } catch (Throwable e2) {
                    top.message("nothing here can play this video");
                }
            }
            return;
        }
        top.message("the viewer comes in a later build");
    }

    @Override
    public void onColumnsChanged(int cols) {
        L.putInt(this, "cols:" + viewKey(), cols);
    }

    @Override
    public void onOpenFolder(Media.Folder f) {
        openFolder = f;
        folderGrid.setColumns(colsOf("f:" + f.bucket));
        refreshFolderGrid(GridView.TOP);
        show(folderGrid);
        updateTop();
    }

    private void closeFolder() {
        openFolder = null;
        show(folderView);
        updateTop();
    }

    @Override
    public void onThumbsArrived() {
        currentView().invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!hasMedia() && e.getActionMasked() == MotionEvent.ACTION_UP) askMedia();
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) {
            closePanel();
        } else if (tab == TAB_FOLDERS && openFolder != null) {
            closeFolder();
        } else if (tab != TAB_PHOTOS) {
            onTab(TAB_PHOTOS);
        } else {
            super.onBackPressed();
        }
    }

    // ---- settings ----

    private void openPanel() {
        panel.setRows(rows());
        panelOpen = true;
        bar.setSettingsOpen(true);
        panel.setVisibility(View.VISIBLE);
        catcher.setVisibility(View.VISIBLE);
        catcher.bringToFront();
        panel.bringToFront();
        panel.setTranslationY(panel.wantedHeight());
        panel.animate().cancel();
        panel.animate().translationY(0).setDuration(280).setInterpolator(new DecelerateInterpolator(2f));
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        bar.setSettingsOpen(false);
        catcher.setVisibility(View.GONE);
        panel.animate().cancel();
        panel.animate().translationY(panel.getHeight()).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!panelOpen) panel.setVisibility(View.GONE);
                    }
                });
    }

    private abstract static class Row implements SettingsPanel.Row {
        final String label, desc;

        Row(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String desc() {
            return desc;
        }
    }

    private String viewName() {
        if (tab == TAB_PHOTOS) return "photos";
        return openFolder == null ? "the folder list" : "this folder";
    }

    private List<SettingsPanel.Row> rows() {
        List<SettingsPanel.Row> r = new ArrayList<>();
        r.add(new Row("theme", "the colours of the room") {
            @Override
            public String value() {
                return t.name;
            }

            @Override
            public void step(int d) {
                switchTheme(Theme.index(t.key) + d);
            }
        });
        final String view = viewKey();
        r.add(new Row("sort by", "for " + viewName() + ", remembered") {
            @Override
            public String value() {
                return Media.SORT_NAMES[sortOf(view)];
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "sort:" + view, (sortOf(view) + d + 4) % 4);
                resortCurrent();
            }
        });
        r.add(new Row("order", "for " + viewName() + ", remembered") {
            @Override
            public String value() {
                int by = sortOf(view);
                boolean desc = descOf(view);
                if (by == Media.BY_NAME) return desc ? "z to a" : "a to z";
                if (by == Media.BY_SIZE) return desc ? "largest first" : "smallest first";
                return desc ? "newest first" : "oldest first";
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "desc:" + view, descOf(view) ? 0 : 1);
                resortCurrent();
            }
        });
        r.add(new Row("videos", "shown with the photos, with their length") {
            @Override
            public String value() {
                return videos() ? "shown" : "hidden";
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "videos", videos() ? 0 : 1);
                refreshPhotos(GridView.MORPH);
                refreshFolders(false);
                refreshFolderGrid(GridView.MORPH);
                updateTop();
            }
        });
        r.add(new Row("folder sizes", "how much space each folder takes") {
            @Override
            public String value() {
                return sizes() ? "shown" : "hidden";
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "sizes", sizes() ? 0 : 1);
                refreshFolders(false);
            }
        });
        r.add(new Row("new views", "columns a folder starts with") {
            @Override
            public String value() {
                int c = L.getInt(MainActivity.this, "defcols", 4);
                return c + (c == 1 ? " column" : " columns");
            }

            @Override
            public void step(int d) {
                int c = L.getInt(MainActivity.this, "defcols", 4);
                int i = 0;
                for (int k = 0; k < GridView.LEVELS.length; k++) if (GridView.LEVELS[k] == c) i = k;
                i = (i + d + GridView.LEVELS.length) % GridView.LEVELS.length;
                L.putInt(MainActivity.this, "defcols", GridView.LEVELS[i]);
            }
        });
        r.add(new Row("pinterest view", "whole photos at this many columns or fewer") {
            @Override
            public String value() {
                int p = pinterest();
                return p == 0 ? "off" : "up to " + p + (p == 1 ? " column" : " columns");
            }

            @Override
            public void step(int d) {
                int p = (pinterest() + d + 5) % 5;
                L.putInt(MainActivity.this, "pinterest", p);
                photos.setPinterestUpTo(p);
                folderGrid.setPinterestUpTo(p);
            }
        });
        final String crash = L.getString(this, "crash", "");
        if (!crash.isEmpty()) {
            String first = crash.split("\n")[0];
            r.add(new Row("last crash", first) {
                @Override
                public String value() {
                    return "copy";
                }

                @Override
                public void step(int d) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("photosJS crash", crash));
                    L.putString(MainActivity.this, "crash", "");
                    top.message("crash report copied");
                    panel.setRows(rows());
                }
            });
        }
        return r;
    }

    private void resortCurrent() {
        if (tab == TAB_PHOTOS) refreshPhotos(GridView.TOP);
        else if (openFolder == null) refreshFolders(true);
        else refreshFolderGrid(GridView.TOP);
        updateTop();
    }
}
