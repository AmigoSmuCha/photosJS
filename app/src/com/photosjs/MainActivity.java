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
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
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
        BarView.Listener, Thumbs.Listener, Pager.Listener {

    private static final int REQ_MEDIA = 1;
    private static final int TAB_PHOTOS = BarView.P_PHOTOS, TAB_FOLDERS = BarView.P_BOARDS,
            TAB_ALBUMS = BarView.P_ALBUMS, TAB_SETTINGS = BarView.P_SETTINGS;

    private Theme t;
    private Thumbs thumbs;
    private FrameLayout root, content, boards;
    private TopLine top;
    private BarView bar;
    private Pager pager;
    private GridView photos, folderGrid;
    private FolderView folderView;
    private AlbumsPage albums;
    private SettingsPage settings;
    private boolean hasAlbums;
    private float dp;

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
        Motion.load(this);
        t = Theme.get(this);
        thumbs = new Thumbs(getContentResolver());
        thumbs.addListener(this);
        dp = getResources().getDisplayMetrics().density;

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
        column.addView(bar, new LinearLayout.LayoutParams(-1, Math.round(46 * dp)));

        pager = new Pager(this);
        pager.setListener(this);
        content.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        photos = new GridView(this);
        photos.setThumbs(thumbs);
        photos.setListener(this);
        pager.addView(photos);

        boards = new FrameLayout(this);
        folderView = new FolderView(this);
        folderView.setThumbs(thumbs);
        folderView.setListener(this);
        folderView.setColumns(L.getInt(this, "fcols", 2));
        boards.addView(folderView, new FrameLayout.LayoutParams(-1, -1));
        folderGrid = new GridView(this);
        folderGrid.setThumbs(thumbs);
        folderGrid.setListener(this);
        folderGrid.setVisibility(View.GONE);
        boards.addView(folderGrid, new FrameLayout.LayoutParams(-1, -1));
        pager.addView(boards);

        albums = new AlbumsPage(this);
        settings = new SettingsPage(this);
        setPages();

        setContentView(root);
        applyTheme();
        applyLook(false);
        settings.setRows(rows());
        photos.setPinterestUpTo(pinterest());
        folderGrid.setPinterestUpTo(pinterest());
        photos.setColumns(colsOf("all"));
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

    // ---- theme and look ----

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
        albums.setTheme(t);
        settings.setTheme(t);
    }

    private void applyLook(boolean relayoutBars) {
        boolean rounded = L.rounded(this), airy = L.airy(this), blocks = L.blocks(this);
        boolean nums = L.dateNumbers(this);
        float s = L.textScale(this);
        int dates = L.dates(this);
        photos.setLook(rounded, airy, dates, nums, blocks, s);
        folderGrid.setLook(rounded, airy, dates, nums, blocks, s);
        folderView.setLook(rounded, airy, s);
        bar.setLook(rounded, blocks, s);
        settings.setLook(blocks, s);
        top.setTextScale(s);
        top.setBlocks(blocks);
        albums.setTextScale(s);
        LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) top.getLayoutParams();
        tl.height = Math.round(27 * dp * s);
        LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) bar.getLayoutParams();
        bl.height = Math.round(46 * dp * Math.min(1.25f, s));
        if (relayoutBars) {
            top.requestLayout();
            bar.requestLayout();
        }
    }

    /** The whole app cross-fades at once, not colour by colour. */
    private void switchTheme(int index) {
        final Theme next = Theme.ALL[(index + Theme.ALL.length) % Theme.ALL.length];
        L.putString(this, "theme", next.key);
        if (root.getWidth() == 0 || Motion.level() == Motion.OFF) {
            t = next;
            applyTheme();
            return;
        }
        final Bitmap snap;
        try {
            snap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            t = next;
            applyTheme();
            return;
        }
        int[] loc = new int[2];
        root.getLocationInWindow(loc);
        Rect area = new Rect(loc[0], loc[1], loc[0] + root.getWidth(), loc[1] + root.getHeight());
        PixelCopy.request(getWindow(), area, snap, new PixelCopy.OnPixelCopyFinishedListener() {
            @Override
            public void onPixelCopyFinished(int result) {
                if (result != PixelCopy.SUCCESS) {
                    t = next;
                    applyTheme();
                    return;
                }
                final ImageView over = new ImageView(MainActivity.this);
                over.setImageBitmap(snap);
                root.addView(over, new FrameLayout.LayoutParams(-1, -1));
                t = next;
                applyTheme();
                over.animate().alpha(0f).setDuration(Motion.ms(450))
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                root.removeView(over);
                            }
                        });
            }
        }, main);
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
        if (asked && !shouldShowRequestPermissionRationale(Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE)) {
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
        if (hasMedia()) load();
        else top.message("photosJS needs your photos · tap anywhere to allow", 600000, 0);
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

    /** sorting is set for three kinds of view: the feed, the boards, and every folder's feed */
    private static String sortKey(String view) {
        return view.startsWith("f:") ? "folderfeeds" : view;
    }

    private int sortOf(String view) {
        return L.getInt(this, "sort:" + sortKey(view), L.getInt(this, "sort:all", Media.BY_TAKEN));
    }

    private boolean descOf(String view) {
        return L.getInt(this, "desc:" + sortKey(view), L.getInt(this, "desc:all", 1)) == 1;
    }

    private int defCols() {
        return L.getInt(this, "defcols", 2);
    }

    private int colsOf(String view) {
        return L.getInt(this, "cols:" + view, defCols());
    }

    private boolean videos() {
        return L.getInt(this, "videos", 1) == 1;
    }

    private boolean sizes() {
        return L.getInt(this, "sizes", 0) == 1;
    }

    private int pinterest() {
        return Math.max(0, Math.min(5, L.getInt(this, "pinterest", 3)));
    }

    private void refreshAll(int how) {
        refreshPhotos(how);
        refreshFolders(false);
        if (openFolder != null) refreshFolderGrid(how);
        updateTop();
    }

    private static boolean dated(int by) {
        return by == Media.BY_TAKEN || by == Media.BY_MODIFIED;
    }

    private void refreshPhotos(int how) {
        List<Media.Item> list = Media.filter(all, videos(), 0);
        int by = sortOf("all");
        Media.sort(list, by, descOf("all"));
        photos.setItems(list, dated(by), by == Media.BY_MODIFIED, how);
        photoCount = list.size();
    }

    private void refreshFolders(boolean toTop) {
        List<Media.Item> list = Media.filter(all, videos(), 0);
        Media.sort(list, Media.BY_TAKEN, true);   // collages: each folder's newest photos
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
        folderGrid.setItems(list, dated(by), by == Media.BY_MODIFIED, how);
        folderItemCount = list.size();
    }

    private static String count(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    private void updateTop() {
        if (tab == TAB_PHOTOS) {
            top.set("photos", loadedOnce ? count(photoCount) : "");
        } else if (tab == TAB_ALBUMS) {
            top.set("albums", "");
        } else if (tab == TAB_SETTINGS) {
            top.set("settings", "");
        } else if (openFolder == null) {
            top.set("folders", count(folderCount));
        } else {
            top.set(openFolder.name, count(folderItemCount));
        }
    }

    private View currentView() {
        if (tab == TAB_PHOTOS) return photos;
        if (tab == TAB_ALBUMS) return albums;
        if (tab == TAB_SETTINGS) return settings;
        return openFolder == null ? folderView : folderGrid;
    }

    /** photos, boards, albums (only once there is one), settings */
    private void setPages() {
        bar.setHasAlbums(hasAlbums);
        if (hasAlbums) pager.setPages(photos, boards, albums, settings);
        else pager.setPages(photos, boards, settings);
    }

    private int logical(int pagerIndex) {
        View v = pagerIndex < pager.getChildCount() ? pager.getChildAt(pagerIndex) : null;
        if (v == boards) return TAB_FOLDERS;
        if (v == albums) return TAB_ALBUMS;
        if (v == settings) return TAB_SETTINGS;
        return TAB_PHOTOS;
    }

    private View pageView(int logical) {
        switch (logical) {
            case TAB_FOLDERS: return boards;
            case TAB_ALBUMS: return albums;
            case TAB_SETTINGS: return settings;
            default: return photos;
        }
    }

    private void crossfade(final View out, View in) {
        long d = Motion.ms(200);
        out.animate().cancel();
        in.animate().cancel();
        if (d <= 0) {
            out.setVisibility(View.GONE);
            in.setVisibility(View.VISIBLE);
            in.setAlpha(1f);
            return;
        }
        out.animate().alpha(0f).setDuration(d).withEndAction(new Runnable() {
            @Override
            public void run() {
                out.setVisibility(View.GONE);
                out.setAlpha(1f);
            }
        });
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).setDuration(d + 60);
    }

    // ---- pager and bar ----

    @Override
    public void onPageScrolled(float pos) {
        bar.setPosition(pos);
    }

    @Override
    public void onPageSelected(int page) {
        int lg = logical(page);
        if (lg == TAB_SETTINGS && settings != null) settings.setRows(rows());
        if (lg == tab) return;
        tab = lg;
        updateTop();
    }

    @Override
    public void onPage(int lg) {
        if (lg == tab) {
            if (lg == TAB_FOLDERS && openFolder != null) closeFolder();
            return;
        }
        int i = pager.indexOf(pageView(lg));
        if (i >= 0) pager.setPage(i, true);
    }

    @Override
    public void onSelectToggle() {
        top.message("selecting comes in the next build");
    }

    @Override
    public void onSelectAction(int action) {
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
    public void onBoardColumnsChanged(int cols) {
        L.putInt(this, "fcols", cols);
    }

    @Override
    public void onOpenFolder(Media.Folder f) {
        openFolder = f;
        folderGrid.setColumns(colsOf("f:" + f.bucket));
        refreshFolderGrid(GridView.TOP);
        crossfade(folderView, folderGrid);
        updateTop();
    }

    private void closeFolder() {
        openFolder = null;
        crossfade(folderGrid, folderView);
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
        if (tab == TAB_FOLDERS && openFolder != null) {
            closeFolder();
        } else if (tab != TAB_PHOTOS) {
            pager.setPage(0, true);
        } else {
            super.onBackPressed();
        }
    }

    // ---- settings ----

    private abstract static class Row implements SettingsPage.Row {
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

    private static final class Head extends Row {
        Head(String name) {
            super(name, "");
        }

        @Override
        public String value() {
            return null;
        }

        @Override
        public void step(int d) {
        }
    }

    private int cycle(String key, int def, int count, int d) {
        int v = (L.getInt(this, key, def) + d + count) % count;
        L.putInt(this, key, v);
        return v;
    }

    private String sortValue(String view) {
        return Media.SORT_NAMES[sortOf(view)];
    }

    private String orderValue(String view) {
        int by = sortOf(view);
        boolean desc = descOf(view);
        if (by == Media.BY_NAME) return desc ? "z to a" : "a to z";
        if (by == Media.BY_SIZE) return desc ? "largest first" : "smallest first";
        return desc ? "newest first" : "oldest first";
    }

    private void sortRows(List<SettingsPage.Row> r, final String view, String what) {
        r.add(new Row(what + " sorted by", "remembered") {
            @Override
            public String value() {
                return sortValue(view);
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "sort:" + view, (sortOf(view) + d + 4) % 4);
                resort(view);
            }
        });
        r.add(new Row(what + " order", "remembered") {
            @Override
            public String value() {
                return orderValue(view);
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "desc:" + view, descOf(view) ? 0 : 1);
                resort(view);
            }
        });
    }

    private List<SettingsPage.Row> rows() {
        List<SettingsPage.Row> r = new ArrayList<>();
        r.add(new Head("look"));
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
        r.add(new Row("text size", "every word in the app") {
            @Override
            public String value() {
                return L.TEXT_NAMES[L.textIndex(MainActivity.this)];
            }

            @Override
            public void step(int d) {
                cycle("text", 1, L.TEXT.length, d);
                applyLook(true);
            }
        });
        r.add(new Row("headings", "dates and titles: plain letters or lockJS blocks") {
            @Override
            public String value() {
                return L.blocks(MainActivity.this) ? "blocks" : "plain";
            }

            @Override
            public void step(int d) {
                cycle("blocks", 0, 2, d);
                applyLook(false);
            }
        });
        r.add(new Row("animations", "full springs, calm fades, or none") {
            @Override
            public String value() {
                return Motion.NAMES[Motion.level()];
            }

            @Override
            public void step(int d) {
                cycle("motion", Motion.FULL, 3, d);
                Motion.load(MainActivity.this);
            }
        });
        r.add(new Row("corners", "of photos and boards") {
            @Override
            public String value() {
                return L.rounded(MainActivity.this) ? "rounded" : "square";
            }

            @Override
            public void step(int d) {
                cycle("rounded", 1, 2, d);
                applyLook(false);
            }
        });
        r.add(new Row("spacing", "air between photos, or a hairline") {
            @Override
            public String value() {
                return L.airy(MainActivity.this) ? "airy" : "tight";
            }

            @Override
            public void step(int d) {
                cycle("airy", 1, 2, d);
                applyLook(false);
            }
        });

        r.add(new Head("feed"));
        r.add(new Row("pinterest view", "whole photos at this many columns or fewer") {
            @Override
            public String value() {
                int p = pinterest();
                return p == 0 ? "off" : "up to " + p + (p == 1 ? " column" : " columns");
            }

            @Override
            public void step(int d) {
                int p = (pinterest() + d + 6) % 6;
                L.putInt(MainActivity.this, "pinterest", p);
                photos.setPinterestUpTo(p);
                folderGrid.setPinterestUpTo(p);
            }
        });
        r.add(new Row("dates", "labels between photos when sorted by date") {
            @Override
            public String value() {
                return L.DATE_NAMES[L.dates(MainActivity.this)];
            }

            @Override
            public void step(int d) {
                cycle("dates", 2, L.DATE_NAMES.length, d);
                applyLook(false);
            }
        });
        r.add(new Row("months", "as names (22 nov) or numbers (22.11.)") {
            @Override
            public String value() {
                return L.dateNumbers(MainActivity.this) ? "numbers" : "names";
            }

            @Override
            public void step(int d) {
                cycle("datenum", 0, 2, d);
                applyLook(false);
            }
        });
        r.add(new Row("videos", "hidden: only photos and pictures are shown") {
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
            }
        });
        r.add(new Row("folders open with", "columns, until you pinch them") {
            @Override
            public String value() {
                int c = defCols();
                return c + (c == 1 ? " column" : " columns");
            }

            @Override
            public void step(int d) {
                int c = defCols();
                int i = 0;
                for (int k = 0; k < GridView.LEVELS.length; k++) if (GridView.LEVELS[k] == c) i = k;
                i = (i + d + GridView.LEVELS.length) % GridView.LEVELS.length;
                L.putInt(MainActivity.this, "defcols", GridView.LEVELS[i]);
            }
        });

        r.add(new Head("sorting"));
        sortRows(r, "all", "photos");
        sortRows(r, "folders", "boards");
        sortRows(r, "folderfeeds", "folders");

        r.add(new Head("boards"));
        r.add(new Row("folder sizes", "how much space each board takes") {
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

        final String crash = L.getString(this, "crash", "");
        if (!crash.isEmpty()) {
            r.add(new Head("trouble"));
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
                    settings.setRows(rows());
                }
            });
        }
        return r;
    }

    private void resort(String view) {
        if (view.equals("all")) refreshPhotos(GridView.TOP);
        else if (view.equals("folders")) refreshFolders(true);
        else refreshFolderGrid(GridView.TOP);
        updateTop();
    }
}
