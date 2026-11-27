package com.photosjs;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements GridView.Listener, FolderView.Listener,
        BarView.Listener, Thumbs.Listener, Pager.Listener, FanView.Listener, AlbumSheet.Listener,
        ViewerView.Listener, EditorView.Listener {

    private static final int REQ_MEDIA = 1, REQ_TRASH = 2, REQ_UNTRASH = 3, REQ_WRITE = 4;
    private static final int TAB_PHOTOS = BarView.P_PHOTOS, TAB_FOLDERS = BarView.P_BOARDS,
            TAB_ALBUMS = BarView.P_ALBUMS, TAB_SETTINGS = BarView.P_SETTINGS;

    private Theme t;
    private Thumbs thumbs;
    private Store store;
    private FrameLayout root, content, boards, albumsBox;
    private TopLine top;
    private BarView bar;
    private Pager pager;
    private GridView photos, folderGrid, albumGrid, hiddenGrid;
    private FolderView folderView, albumBoards;
    private SettingsPage settings;
    private FanView fan;
    private AlbumSheet sheet;
    private ViewerView viewer;
    private GridView viewerFrom;
    private EditorView editor;
    private Bitmap overwriteWith;
    private Media.Item overwriteItem;
    // the original of the photo being saved over, as a copy in Android's trash
    private Uri originalInTrash;
    private float dp;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Media.Item> all = new ArrayList<>();
    private final Map<Long, Media.Item> byKey = new HashMap<>();
    private Set<Long> hiddenKeys = new HashSet<>();
    private List<Store.Album> albums = new ArrayList<>();
    private boolean loadedOnce, asked, hasAlbums, hiddenOpen;
    // arranging by hand: the boards (albums or folders) or the feed being arranged
    private FolderView arrangingBoards;
    private GridView arrangingFeed;
    // every view's order by hand: view ("all", "f:<bucket>", "a:<album>", "folders") -> key -> place
    private Map<String, Map<Long, Integer>> orders = new HashMap<>();
    private int tab = TAB_PHOTOS;
    private Media.Folder openFolder, openAlbum;
    private int photoCount, folderCount, folderItemCount, albumItemCount;

    // what the fan / sheet is about
    private List<Media.Item> pending = new ArrayList<>();
    private Media.Folder holdBoard;
    private GridView holdGrid;
    // the photo under the finger (edit, wallpaper, info act on it), and whether
    // the fan acts on the whole selection it belongs to
    private Media.Item holdItem;
    private boolean holdOnSelection;
    private boolean renaming;
    private List<Media.Item> trashing = new ArrayList<>();
    // photos photosJS just saved over: their new size, until Android has re-read them
    private final Map<Long, long[]> fresh = new HashMap<>();

    // picking for another app (PickActivity): one photo with a tap, or several
    private boolean picking, pickMany, pickImages = true, pickVideos = true;
    private final List<String> pickTypes = new ArrayList<>();
    private final Set<Long> chosen = new LinkedHashSet<>();     // in the order they were chosen
    private String pickFor = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        L.catchCrashes(this);
        Motion.load(this);
        t = Theme.get(this);
        store = new Store(this);
        thumbs = new Thumbs(getContentResolver());
        thumbs.addListener(this);
        dp = getResources().getDisplayMetrics().density;
        if (this instanceof PickActivity) readPick();

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

        photos = grid();

        boards = new FrameLayout(this);
        folderView = new FolderView(this);
        folderView.setThumbs(thumbs);
        folderView.setListener(this);
        folderView.setHoldable(true);
        folderView.setColumns(L.getInt(this, "fcols", 2));
        boards.addView(folderView, new FrameLayout.LayoutParams(-1, -1));
        folderGrid = grid();
        folderGrid.setVisibility(View.GONE);
        boards.addView(folderGrid, new FrameLayout.LayoutParams(-1, -1));

        albumsBox = new FrameLayout(this);
        albumBoards = new FolderView(this);
        albumBoards.setThumbs(thumbs);
        albumBoards.setListener(this);
        albumBoards.setHoldable(true);
        albumBoards.setColumns(L.getInt(this, "acols", 2));
        albumBoards.setEmptyText("no albums yet");
        albumsBox.addView(albumBoards, new FrameLayout.LayoutParams(-1, -1));
        albumGrid = grid();
        albumGrid.setVisibility(View.GONE);
        albumsBox.addView(albumGrid, new FrameLayout.LayoutParams(-1, -1));

        settings = new SettingsPage(this);

        hiddenGrid = grid();
        hiddenGrid.setVisibility(View.GONE);
        content.addView(hiddenGrid, new FrameLayout.LayoutParams(-1, -1));

        viewer = new ViewerView(this);
        viewer.setListener(this);
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));

        editor = new EditorView(this);
        editor.setListener(this);
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));

        sheet = new AlbumSheet(this);
        sheet.setListener(this);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));

        fan = new FanView(this);
        fan.setListener(this);
        root.addView(fan, new FrameLayout.LayoutParams(-1, -1));

        if (picking) setUpPicking();
        setContentView(root);
        loadAlbums();
        hasAlbums = !albums.isEmpty();
        setPages();
        applyTheme();
        applyLook(false);
        int p = pinterestUpTo();
        for (GridView g : grids()) g.setPinterestUpTo(p);
        photos.setColumns(colsOf("all"));
        settings.setRows(rows());
        top.set("photos", "");

        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true, observer);
        getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true, observer);

        if (hasMedia()) load();
        else askMedia();
    }

    private GridView grid() {
        GridView g = new GridView(this);
        g.setThumbs(thumbs);
        g.setListener(this);
        return g;
    }

    private GridView[] grids() {
        return new GridView[] {photos, folderGrid, albumGrid, hiddenGrid};
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
        for (GridView g : grids()) g.setTheme(t);
        folderView.setTheme(t);
        albumBoards.setTheme(t);
        settings.setTheme(t);
        fan.setTheme(t);
        viewer.setTheme(t);
        editor.setTheme(t);
        sheet.setLook(t, L.rounded(this), L.textScale(this));
    }

    private void applyLook(boolean relayoutBars) {
        // the font first: every view measures its words in it
        Heading.setFace(this, L.heading(this));
        boolean rounded = L.rounded(this);
        int space = L.spacing(this);
        int heading = L.heading(this);
        boolean nums = L.dateNumbers(this);
        float s = L.textScale(this);
        int dates = L.dates(this);
        for (GridView g : grids()) g.setLook(rounded, space, dates, nums, heading, s);
        boolean ripple = L.getInt(this, "resize", 1) == 1;
        for (GridView g : grids()) g.setRipple(ripple);
        folderView.setRipple(ripple);
        albumBoards.setRipple(ripple);
        folderView.setLook(rounded, space, s);
        albumBoards.setLook(rounded, space, s);
        bar.setLook(rounded, heading, s);
        settings.setLook(heading, s);
        fan.setRounded(rounded);
        viewer.setLook(rounded, nums, s);
        editor.setLook(rounded, s);
        sheet.setLook(t, rounded, s);
        top.setTextScale(s);
        top.setHeading(heading);
        LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) top.getLayoutParams();
        tl.height = Math.round(27 * dp * s);
        LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) bar.getLayoutParams();
        bl.height = Math.round(46 * dp * L.barScale(s));
        if (relayoutBars) {
            top.requestLayout();
            bar.requestLayout();
        }
    }

    private android.animation.ValueAnimator textGrow;

    /**
     * A new text size grows (or shrinks) into place: the settings, the reading
     * line and the bar follow it frame by frame; the feeds and boards take it
     * at the end, once.
     */
    private void animateTextSize(final float from, final float to) {
        if (textGrow != null) textGrow.cancel();
        long d = Motion.ms(320);
        if (d <= 0 || from == to) {
            applyLook(true);
            return;
        }
        Heading.setFace(this, L.heading(this));
        final boolean rounded = L.rounded(this);
        final int heading = L.heading(this);
        textGrow = android.animation.ValueAnimator.ofFloat(0f, 1f);
        textGrow.setDuration(d);
        textGrow.setInterpolator(new DecelerateInterpolator());
        textGrow.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float s = from + (to - from) * (Float) a.getAnimatedValue();
                settings.setLook(heading, s);
                top.setTextScale(s);
                bar.setLook(rounded, heading, s);
                LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) top.getLayoutParams();
                tl.height = Math.round(27 * dp * s);
                LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) bar.getLayoutParams();
                bl.height = Math.round(46 * dp * L.barScale(s));
                top.requestLayout();
                bar.requestLayout();
            }
        });
        textGrow.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled) applyLook(true);
            }
        });
        textGrow.start();
    }

    // the theme being switched to: t changes only once the old look is captured
    private Theme themeTarget;
    private boolean themeSnapPending;
    private android.animation.ValueAnimator barFade;

    /** the theme the settings show: where a switch is going, else the current one */
    private Theme shownTheme() {
        return themeTarget != null ? themeTarget : t;
    }

    /**
     * The whole app cross-fades at once, not colour by colour: a snapshot of the
     * old look lies over the new one and fades away, and the system bars fade
     * along. Steps taken while a snapshot is on its way only move the target,
     * so quick taps never stack up or end on a theme other than the one named.
     */
    private void switchTheme(int index) {
        final Theme next = Theme.ALL[(index + Theme.ALL.length) % Theme.ALL.length];
        themeTarget = next;
        L.putString(this, "theme", next.key);
        if (themeSnapPending) return;
        if (root.getWidth() == 0 || Motion.level() == Motion.OFF) {
            t = themeTarget;
            themeTarget = null;
            applyTheme();
            return;
        }
        final Bitmap snap;
        try {
            snap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            t = themeTarget;
            themeTarget = null;
            applyTheme();
            return;
        }
        int[] loc = new int[2];
        root.getLocationInWindow(loc);
        Rect area = new Rect(loc[0], loc[1], loc[0] + root.getWidth(), loc[1] + root.getHeight());
        themeSnapPending = true;
        PixelCopy.request(getWindow(), area, snap, new PixelCopy.OnPixelCopyFinishedListener() {
            @Override
            public void onPixelCopyFinished(int result) {
                themeSnapPending = false;
                final int oldBar = t.bg, oldNav = t.plate;
                t = themeTarget != null ? themeTarget : t;
                themeTarget = null;
                applyTheme();
                if (result != PixelCopy.SUCCESS) {
                    snap.recycle();
                    return;
                }
                long d = Motion.ms(450);
                final ImageView over = new ImageView(MainActivity.this);
                over.setScaleType(ImageView.ScaleType.FIT_XY);
                over.setImageBitmap(snap);
                root.addView(over, new FrameLayout.LayoutParams(-1, -1));
                over.animate().alpha(0f).setDuration(d)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                root.removeView(over);
                                snap.recycle();
                            }
                        });
                // the status and navigation bars fade with it instead of jumping
                if (barFade != null) barFade.cancel();
                final int newBar = t.bg, newNav = t.plate;
                final Window w = getWindow();
                w.setStatusBarColor(oldBar);
                w.setNavigationBarColor(oldNav);
                barFade = android.animation.ValueAnimator.ofFloat(0f, 1f);
                barFade.setDuration(d);
                barFade.setInterpolator(new DecelerateInterpolator());
                barFade.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        float f = (Float) a.getAnimatedValue();
                        w.setStatusBarColor(Theme.mix(oldBar, newBar, f));
                        w.setNavigationBarColor(Theme.mix(oldNav, newNav, f));
                    }
                });
                barFade.start();
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
        if (tab == TAB_SETTINGS) settings.setRows(rows());
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
                final Set<Long> hid = store.hidden();
                final List<Store.Album> al = store.albums();
                final Map<String, Map<Long, Integer>> ord = store.orders();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean first = !loadedOnce;
                        loadedOnce = true;
                        all = picking ? pickable(list) : list;
                        byKey.clear();
                        long now = System.currentTimeMillis();
                        for (Media.Item it : all) {
                            byKey.put(it.key(), it);
                            long[] f = fresh.get(it.key());
                            if (f != null && now - f[2] < 30000) {
                                it.w = (int) f[0];
                                it.h = (int) f[1];
                            }
                        }
                        hiddenKeys = hid;
                        albums = al;
                        orders = ord;
                        thumbs.clearFailures();
                        if (first) top.clearMessage();
                        refreshAll(first ? GridView.TOP : GridView.KEEP);
                        if (all.isEmpty()) top.message(picking ? "nothing here fits what was asked for"
                                : "no photos here yet", 600000, 0);
                        else if (first) firstHint();
                        if (first) moveKeptOriginals();
                    }
                });
            }
        });
    }

    /** One dim line about the gestures that matter, until they have been used. */
    private void firstHint() {
        if (picking) {
            String to = pickFor.isEmpty() ? "" : " for " + pickFor;
            top.message(pickMany ? "choose" + to + ": tap, or drag sideways · hold to look closer or edit"
                    : "tap a photo to use it" + to + " · hold to look closer or edit", 9000, 0);
            return;
        }
        if (L.getInt(this, "used_pinch", 0) == 1 && L.getInt(this, "used_hold", 0) == 1) return;
        top.message("pinch to resize · hold a photo for more · tap with two fingers to select", 7000, 0);
    }

    // ---- views and data ----

    private void loadAlbums() {
        albums = store.albums();
    }

    private String viewKey() {
        if (hiddenOpen) return "hidden";
        if (tab == TAB_FOLDERS) return openFolder == null ? "folders" : "f:" + openFolder.bucket;
        if (tab == TAB_ALBUMS) return openAlbum == null ? "albums" : "a:" + openAlbum.bucket;
        return "all";
    }

    /** sorting is set per kind of view: feed, boards, folder feeds, albums, album feeds */
    private static String sortKey(String view) {
        if (view.startsWith("f:")) return "folderfeeds";
        if (view.startsWith("a:")) return "albumfeeds";
        return view;
    }

    private int sortOf(String view) {
        int by = L.getInt(this, "sort:" + sortKey(view), L.getInt(this, "sort:all", Media.BY_TAKEN));
        return Math.max(0, Math.min(Media.BY_HAND, by));
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
        // picking: the request has already decided which photos and videos there are
        return picking || L.getInt(this, "videos", 1) == 1;
    }

    private boolean sizes() {
        return L.getInt(this, "sizes", 0) == 1;
    }

    /** Pinterest view: 0 off (squares), 1 on (whole photos at any size), 2 up to a threshold */
    private static final String[] PINTEREST_NAMES = {"off", "on", "threshold"};

    private int pinterestMode() {
        if (!L.has(this, "pmode")) return L.getInt(this, "pinterest", 3) == 0 ? 0 : 1;
        return Math.max(0, Math.min(2, L.getInt(this, "pmode", 1)));
    }

    /** the threshold: whole photos at this many columns or fewer */
    private int pinterestThreshold() {
        return Math.max(1, Math.min(8, L.getInt(this, "pinterest", 3)));
    }

    /** what the feeds are told: whole photos up to this many columns */
    private int pinterestUpTo() {
        int m = pinterestMode();
        return m == 0 ? 0 : m == 1 ? 99 : pinterestThreshold();
    }

    private static boolean dated(int by) {
        return by == Media.BY_TAKEN || by == Media.BY_MODIFIED;
    }

    private void refreshAll(int how) {
        refreshPhotos(how);
        refreshFolders(false);
        if (openFolder != null) refreshFolderGrid(how);
        refreshAlbums(false);
        if (openAlbum != null) refreshAlbumGrid(how);
        if (hiddenOpen) refreshHidden(how);
        updateTop();
    }

    private void refreshPhotos(int how) {
        List<Media.Item> list = Media.filter(all, videos(), 0, hiddenKeys);
        int by = sortOf("all");
        sortFeed(list, "all");
        photos.setItems(list, dated(by), by == Media.BY_MODIFIED, how);
        photoCount = list.size();
    }

    private void refreshFolders(boolean toTop) {
        List<Media.Item> list = Media.filter(all, videos(), 0, hiddenKeys);
        Media.sort(list, Media.BY_TAKEN, true);   // collages: each folder's newest photos
        List<Media.Folder> fs = Media.folders(list);
        if (sortOf("folders") == Media.BY_HAND) {
            // folders never arranged (new ones) first
            Map<Long, Integer> pos = orderOf("folders");
            for (Media.Folder f : fs) {
                Integer p = pos.get(f.bucket);
                f.order = p != null ? p : -1;
            }
        }
        Media.sortFolders(fs, sortOf("folders"), descOf("folders"));
        folderView.setFolders(fs, sizes(), toTop);
        folderCount = fs.size();
        if (openFolder != null) {
            for (Media.Folder f : fs) if (f.bucket == openFolder.bucket) openFolder = f;
        }
    }

    private void refreshFolderGrid(int how) {
        if (openFolder == null) return;
        String key = "f:" + openFolder.bucket;
        List<Media.Item> list = Media.filter(all, videos(), openFolder.bucket, hiddenKeys);
        int by = sortOf(key);
        sortFeed(list, key);
        folderGrid.setItems(list, dated(by), by == Media.BY_MODIFIED, how);
        folderItemCount = list.size();
    }

    /** the photos of an album that still exist and aren't hidden, newest added first */
    private List<Media.Item> albumItems(Store.Album a) {
        List<Media.Item> out = new ArrayList<>();
        for (long k : a.keys) {
            Media.Item it = byKey.get(k);
            if (it == null || hiddenKeys.contains(k)) continue;
            if (!videos() && it.video) continue;
            out.add(it);
        }
        return out;
    }

    private Store.Album album(long id) {
        for (Store.Album a : albums) if (a.id == id) return a;
        return null;
    }

    private void refreshAlbums(boolean toTop) {
        List<Media.Folder> boardsList = new ArrayList<>();
        for (Store.Album a : albums) {
            Media.Folder f = new Media.Folder();
            f.bucket = a.id;
            f.name = a.name;
            f.newestTaken = a.created;
            f.newestModified = a.created;
            f.order = a.pos;
            for (Media.Item it : albumItems(a)) {
                f.count++;
                f.size += it.size;
                if (f.covers.size() < 3) f.covers.add(it);
                if (f.cover == null) f.cover = it;
            }
            boardsList.add(f);
        }
        Media.sortFolders(boardsList, sortOf("albums"), descOf("albums"));
        albumBoards.setFolders(boardsList, sizes(), toTop);
        if (openAlbum != null) {
            Media.Folder still = null;
            for (Media.Folder f : boardsList) if (f.bucket == openAlbum.bucket) still = f;
            if (still == null) closeAlbum();
            else openAlbum = still;
        }
        boolean has = !albums.isEmpty();
        if (has != hasAlbums) {
            hasAlbums = has;
            setPages();
        }
    }

    private void refreshAlbumGrid(int how) {
        if (openAlbum == null) return;
        Store.Album a = album(openAlbum.bucket);
        if (a == null) return;
        String key = "a:" + a.id;
        List<Media.Item> list = albumItems(a);
        int by = sortOf(key);
        sortFeed(list, key);
        albumGrid.setItems(list, dated(by), by == Media.BY_MODIFIED, how);
        albumItemCount = list.size();
    }

    private void refreshHidden(int how) {
        List<Media.Item> list = new ArrayList<>();
        for (Media.Item it : all) if (hiddenKeys.contains(it.key())) list.add(it);
        Media.sort(list, Media.BY_TAKEN, true);
        hiddenGrid.setItems(list, true, false, how);
    }

    private static String count(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    private void updateTop() {
        if (hiddenOpen) {
            top.set("set aside", count(hiddenGrid.items().size()));
        } else if (tab == TAB_PHOTOS) {
            top.set("photos", loadedOnce ? count(photoCount) : "");
        } else if (tab == TAB_SETTINGS) {
            top.set("settings", "");
        } else if (tab == TAB_ALBUMS) {
            if (openAlbum == null) top.set("albums", count(albums.size()));
            else top.set(openAlbum.name, count(albumItemCount));
        } else if (openFolder == null) {
            top.set("boards", count(folderCount));
        } else {
            top.set(openFolder.name, count(folderItemCount));
        }
    }

    /** photos, boards, albums (only once there is one), settings */
    private void setPages() {
        bar.setHasAlbums(hasAlbums);
        if (picking) {
            if (hasAlbums) pager.setPages(photos, boards, albumsBox);
            else pager.setPages(photos, boards);
        } else if (hasAlbums) {
            pager.setPages(photos, boards, albumsBox, settings);
        } else {
            pager.setPages(photos, boards, settings);
        }
    }

    private int logical(int pagerIndex) {
        View v = pagerIndex < pager.getChildCount() ? pager.getChildAt(pagerIndex) : null;
        if (v == boards) return TAB_FOLDERS;
        if (v == albumsBox) return TAB_ALBUMS;
        if (v == settings) return TAB_SETTINGS;
        return TAB_PHOTOS;
    }

    private View pageView(int lg) {
        switch (lg) {
            case TAB_FOLDERS: return boards;
            case TAB_ALBUMS: return albumsBox;
            case TAB_SETTINGS: return settings;
            default: return photos;
        }
    }

    private View currentView() {
        if (hiddenOpen) return hiddenGrid;
        switch (tab) {
            case TAB_FOLDERS: return openFolder == null ? folderView : folderGrid;
            case TAB_ALBUMS: return openAlbum == null ? albumBoards : albumGrid;
            case TAB_SETTINGS: return settings;
            default: return photos;
        }
    }

    /** the feed on screen, if there is one */
    private GridView activeGrid() {
        View v = currentView();
        return v instanceof GridView ? (GridView) v : null;
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
        if (hiddenOpen) closeHidden();
        if (lg == tab) {
            if (lg == TAB_FOLDERS && openFolder != null) closeFolder();
            if (lg == TAB_ALBUMS && openAlbum != null) closeAlbum();
            return;
        }
        int i = pager.indexOf(pageView(lg));
        if (i >= 0) pager.setPage(i, true);
    }

    // ---- selecting ----

    private String[] actionsFor(GridView g) {
        if (g == hiddenGrid) return new String[] {"bring back", "trash"};
        if (g == albumGrid) return new String[] {"album", "remove", "share", "aside", "trash"};
        return new String[] {"album", "share", "aside", "trash"};
    }

    /** Selecting, in this feed, starting with this photo (if any). */
    private void startSelect(GridView g, Media.Item first) {
        if (g == null || picking) return;
        g.setSelecting(true);
        pager.setLocked(true);
        bar.setSelecting(true, 0, actionsFor(g));
        if (first != null) g.selectKey(first.key());
        int shown = L.getInt(this, "hint_select", 0);
        if (shown < 3 && g != hiddenGrid) {
            L.putInt(this, "hint_select", shown + 1);
            top.message("tap photos, or drag sideways · hold one for the fan", 4500, 0);
        }
    }

    @Override
    public void onTwoFingerTap(GridView from, Media.Item it) {
        if (from == hiddenGrid || picking || arranging()) return;
        if (from.selecting()) exitSelect();
        else startSelect(from, it);
    }

    // ---- arranging by hand: albums, folder boards, photos ----

    private boolean arranging() {
        return arrangingBoards != null || arrangingFeed != null;
    }

    /** albums or folder boards: hold one and move it */
    private void startArrangeBoards(FolderView fv) {
        boolean albumsToo = fv == albumBoards;
        int n = albumsToo ? albums.size() : folderCount;
        if (n < 2) {
            say(albumsToo ? "one album has nothing to be arranged with" : "one board has nothing to be arranged with");
            return;
        }
        endArrange();
        arrangingBoards = fv;
        fv.setArranging(true);
        pager.setLocked(true);
        bar.setArranging(albumsToo ? "arrange albums" : "arrange boards");
        say("hold " + (albumsToo ? "an album" : "a board") + " and move it · done when they're in place", 7000, 0);
    }

    /**
     * Photos by hand in this feed (all photos, a folder or an album). The order
     * on screen becomes the order by hand first, so nothing jumps; then hold a
     * photo and move it.
     */
    private void startArrangeFeed(GridView g) {
        String key = feedKey(g);
        if (key == null || picking) return;
        exitSelect();
        endArrange();
        if (sortOf(key) != Media.BY_HAND) {
            List<Long> keys = new ArrayList<>();
            for (Media.Item it : g.items()) keys.add(it.key());
            saveOrder(key, keys);
            L.putInt(this, "sort:" + sortKey(key), Media.BY_HAND);
            if (g == photos) refreshPhotos(GridView.KEEP);
            else if (g == folderGrid) refreshFolderGrid(GridView.KEEP);
            else refreshAlbumGrid(GridView.KEEP);
        }
        arrangingFeed = g;
        g.setArranging(true);
        pager.setLocked(true);
        bar.setArranging("arrange photos");
        say("hold a photo and move it · done when they're in place", 7000, 0);
    }

    private void endArrange() {
        if (arrangingBoards != null) arrangingBoards.setArranging(false);
        if (arrangingFeed != null) arrangingFeed.setArranging(false);
        if (!arranging()) return;
        arrangingBoards = null;
        arrangingFeed = null;
        if (!hiddenOpen) pager.setLocked(false);
        bar.setArranging(null);
        unsay();
    }

    /** the view a feed shows, for orders by hand */
    private String feedKey(GridView g) {
        if (g == photos) return "all";
        if (g == folderGrid && openFolder != null) return "f:" + openFolder.bucket;
        if (g == albumGrid && openAlbum != null) return "a:" + openAlbum.bucket;
        return null;
    }

    private Map<Long, Integer> orderOf(String view) {
        Map<Long, Integer> m = orders.get(view);
        return m != null ? m : new HashMap<Long, Integer>();
    }

    /** remembered now (in memory) and written in the background */
    private void saveOrder(final String view, final List<Long> keys) {
        Map<Long, Integer> m = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) m.put(keys.get(i), i);
        orders.put(view, m);
        io.execute(new Runnable() {
            @Override
            public void run() {
                store.setOrder(view, keys);
            }
        });
    }

    /** a feed in its order: by hand from the stored places, else by the sort setting */
    private void sortFeed(List<Media.Item> list, String view) {
        int by = sortOf(view);
        if (by == Media.BY_HAND) Media.sortByHand(list, orderOf(view));
        else Media.sort(list, by, descOf(view));
    }

    @Override
    public void onFeedArranged(GridView from, List<Media.Item> order) {
        String key = feedKey(from);
        if (key == null) return;
        List<Long> keys = new ArrayList<>();
        for (Media.Item it : order) keys.add(it.key());
        saveOrder(key, keys);
    }

    @Override
    public void onArranged(FolderView from, List<Media.Folder> order) {
        List<Long> ids = new ArrayList<>();
        for (Media.Folder f : order) ids.add(f.bucket);
        if (from == albumBoards) {
            store.setOrder(ids);
            // arranging means the albums are now sorted by hand
            L.putInt(this, "sort:albums", Media.BY_HAND);
            albums = store.albums();
            for (Media.Folder f : order) {
                Store.Album a = album(f.bucket);
                if (a != null) f.order = a.pos;
            }
        } else {
            saveOrder("folders", ids);
            L.putInt(this, "sort:folders", Media.BY_HAND);
            for (int i = 0; i < order.size(); i++) order.get(i).order = i;
        }
    }

    @Override
    public void onSelectDone() {
        if (arranging()) {
            endArrange();
            return;
        }
        if (hiddenOpen) closeHidden();
        else exitSelect();
    }

    private void exitSelect() {
        if (picking) return;     // picking several: every feed stays in choosing
        for (GridView g : grids()) g.setSelecting(false);
        if (!hiddenOpen) pager.setLocked(false);
        bar.setSelecting(false, 0, null);
    }

    @Override
    public void onSelectionChanged(GridView from, int n) {
        if (picking) {
            bar.setPickCount(chosen.size());
            return;
        }
        if (from.selecting()) bar.setSelecting(true, n, actionsFor(from));
    }

    @Override
    public void onSelectAction(int action) {
        GridView g = activeGrid();
        if (g == null) return;
        List<Media.Item> sel = g.selectedItems();
        if (sel.isEmpty()) return;
        String a = actionsFor(g)[action];
        switch (a) {
            case "album": chooseAlbum(sel); break;
            case "remove": removeFromAlbum(sel); break;
            case "share": share(sel); break;
            case "aside": hide(sel); break;
            case "bring back": unhide(sel); break;
            case "trash": trash(sel); break;
        }
        if (a.equals("album")) return;
        if (g == hiddenGrid) {
            // stay with the photos set aside, ready for more
            g.setSelecting(false);
            g.setSelecting(true);
            bar.setSelecting(true, 0, actionsFor(g));
        } else {
            exitSelect();
        }
    }

    // ---- the long-press fan ----

    /** what blooms around the thumb, unless settings › long press says otherwise */
    private static final int[] FAN_DEFAULT = {FanView.ALBUM, FanView.SELECT, FanView.WALLPAPER, FanView.EDIT,
            FanView.ORDER};

    private int[] fanActions() {
        int[] def = FAN_DEFAULT;
        int[] out = new int[def.length];
        for (int i = 0; i < def.length; i++) out[i] = L.getInt(this, "fan" + i, def[i]);
        return out;
    }

    @Override
    public void onHoldStart(GridView from, Media.Item it, RectF screen, Bitmap bm, float radius,
                            float x, float y) {
        L.putInt(this, "used_hold", 1);
        holdBoard = null;
        holdGrid = from;
        holdItem = it;
        // while selecting, holding a selected photo acts on all of them (picking: just that one)
        holdOnSelection = !picking && from.selecting() && from.isSelected(it.key());
        pending = holdOnSelection ? from.selectedItems() : one(it);
        if (pending.isEmpty()) pending = one(it);
        int[] acts = picking ? new int[] {FanView.VIEW, FanView.EDIT, FanView.INFO, FanView.ALBUM}
                : from == hiddenGrid ? new int[] {FanView.HIDE, FanView.DELETE, FanView.INFO} : fanActions();
        if (from.selecting()) {
            for (int i = 0; i < acts.length; i++) if (acts[i] == FanView.SELECT) acts[i] = FanView.NONE;
        }
        fan.open(screen, bm, radius, x, y, acts, false);
        if (pending.size() > 1) say(count(pending.size()) + " photos", 60000, 0);
    }

    /** fan actions that take a whole selection; the rest take the photo under the finger */
    private static boolean forMany(int action) {
        return action == FanView.ALBUM || action == FanView.SHARE || action == FanView.HIDE
                || action == FanView.DELETE;
    }

    @Override
    public void onBoardHold(FolderView from, Media.Folder f, RectF screen, float radius, float x, float y) {
        holdBoard = f;
        holdItem = null;
        holdOnSelection = false;
        pending = new ArrayList<>();
        Bitmap bm = f.cover != null ? thumbs.best(f.cover, 1) : null;
        // a device folder can only be arranged here: no renaming, no deleting
        int[] acts = from == folderView ? new int[] {FanView.ARRANGE_BOARDS}
                : new int[] {FanView.RENAME, FanView.ARRANGE, FanView.DROP_ALBUM};
        fan.open(screen, bm, radius, x, y, acts, false);
    }

    @Override
    public void onHoldMove(float x, float y) {
        fan.move(x, y);
    }

    @Override
    public void onHoldEnd(float x, float y, boolean cancel) {
        fan.release(x, y, cancel);
    }

    @Override
    public void onFanHover(int action) {
        if (action == FanView.NONE) {
            if (pending.size() > 1) say(count(pending.size()) + " photos", 60000, 0);
            else unsay();
            return;
        }
        String n = action == FanView.HIDE && hiddenOpen ? "bring back" : FanView.name(action);
        if (pending.size() > 1 && forMany(action)) n += " · " + count(pending.size()) + " photos";
        say(n, 60000, t.text);
    }

    @Override
    public void onFanCancel() {
        unsay();
    }

    @Override
    public void onFanPick(int action) {
        unsay();
        if (action == FanView.WALLPAPER) {
            fan.reopen(new int[] {FanView.W_HOME, FanView.W_LOCKJS});
            say("where should it go?", 60000, 0);
            return;
        }
        fan.close();
        List<Media.Item> items = pending;
        Media.Item one = holdItem != null ? holdItem : items.isEmpty() ? null : items.get(0);
        switch (action) {
            case FanView.ALBUM: chooseAlbum(items); break;
            case FanView.SELECT: if (one != null) startSelect(holdGrid, one); break;
            case FanView.EDIT: if (one != null) edit(one); break;
            case FanView.SHARE: share(items); break;
            case FanView.HIDE:
                if (hiddenOpen) unhide(items);
                else hide(items);
                break;
            case FanView.DELETE: trash(items); break;
            case FanView.INFO: if (one != null) info(one); break;
            case FanView.VIEW: if (one != null && holdGrid != null) onPeek(holdGrid, one); break;
            case FanView.W_HOME: if (one != null) homeWallpaper(one); break;
            case FanView.W_LOCKJS: if (one != null) toLockJS(one); break;
            case FanView.RENAME:
                if (holdBoard != null) {
                    renaming = true;
                    sheet.showName("rename the album", holdBoard.name);
                }
                break;
            case FanView.DROP_ALBUM: if (holdBoard != null) dropAlbum(holdBoard.bucket); break;
            case FanView.ARRANGE: startArrangeBoards(albumBoards); break;
            case FanView.ARRANGE_BOARDS: startArrangeBoards(folderView); break;
            case FanView.ORDER: if (holdGrid != null) startArrangeFeed(holdGrid); break;
        }
        // done with the selection, as the same action in the bar would be
        if (holdOnSelection && holdGrid != null && holdGrid.selecting()
                && (action == FanView.SHARE || action == FanView.HIDE || action == FanView.DELETE)) {
            if (holdGrid == hiddenGrid) {
                holdGrid.setSelecting(false);
                holdGrid.setSelecting(true);
                bar.setSelecting(true, 0, actionsFor(holdGrid));
            } else {
                exitSelect();
            }
        }
        holdOnSelection = false;
    }

    // ---- messages: the reading line, or the viewer's own while it is open ----

    private void say(String text) {
        say(text, 2600, 0, null);
    }

    private void say(String text, long ms, int color, Runnable undo) {
        if (editor.isOpen()) editor.note(text, ms);
        else if (viewer.isOpen()) viewer.note(text, ms, undo);
        else top.message(text, ms, color, undo);
    }

    private void say(String text, long ms, int color) {
        say(text, ms, color, null);
    }

    private void unsay() {
        if (viewer.isOpen()) viewer.clearNote();
        else top.clearMessage();
    }

    // ---- actions ----

    private static List<Long> keys(List<Media.Item> items) {
        List<Long> out = new ArrayList<>();
        for (Media.Item it : items) out.add(it.key());
        return out;
    }

    private static String photosWord(int n) {
        return n == 1 ? "1 photo" : n + " photos";
    }

    private void chooseAlbum(List<Media.Item> items) {
        pending = new ArrayList<>(items);
        renaming = false;
        List<Integer> counts = new ArrayList<>();
        for (Store.Album a : albums) counts.add(albumItems(a).size());
        sheet.showChooser("add " + photosWord(items.size()) + " to", albums, counts);
    }

    @Override
    public void onAlbumChosen(long id) {
        final List<Long> ks = keys(pending);
        int added = store.addToAlbum(id, ks);
        Store.Album a = album(id);
        afterAlbumsChanged();
        exitSelect();
        say(added == 0 ? "already there" : photosWord(added) + " added to " + (a != null ? a.name : "the album"));
    }

    @Override
    public void onNameGiven(String name) {
        if (renaming && holdBoard != null) {
            store.renameAlbum(holdBoard.bucket, name);
            renaming = false;
            afterAlbumsChanged();
            return;
        }
        long id = store.createAlbum(name);
        if (!pending.isEmpty()) store.addToAlbum(id, keys(pending));
        afterAlbumsChanged();
        exitSelect();
        say(pending.isEmpty() ? "album made" : photosWord(pending.size()) + " in the new album " + name);
    }

    @Override
    public void onSheetClosed() {
        renaming = false;
    }

    private void afterAlbumsChanged() {
        albums = store.albums();
        refreshAlbums(false);
        if (openAlbum != null) refreshAlbumGrid(GridView.MORPH);
        updateTop();
    }

    private void removeFromAlbum(List<Media.Item> items) {
        if (openAlbum == null) return;
        final long id = openAlbum.bucket;
        final List<Long> ks = keys(items);
        store.removeFromAlbum(id, ks);
        afterAlbumsChanged();
        say(photosWord(ks.size()) + " out of the album · tap to undo", 5000, 0, new Runnable() {
            @Override
            public void run() {
                store.addToAlbum(id, ks);
                afterAlbumsChanged();
            }
        });
    }

    private void dropAlbum(long id) {
        final Store.Album gone = store.deleteAlbum(id);
        if (openAlbum != null && openAlbum.bucket == id) closeAlbum();
        afterAlbumsChanged();
        if (gone == null) return;
        say("album " + gone.name + " deleted, photos kept · tap to undo", 6000, 0, new Runnable() {
            @Override
            public void run() {
                store.restoreAlbum(gone);
                afterAlbumsChanged();
            }
        });
    }

    private void hide(List<Media.Item> items) {
        final List<Long> ks = keys(items);
        if (viewer.isOpen()) for (long k : ks) viewer.remove(k);
        store.hide(ks);
        hiddenKeys.addAll(ks);
        refreshAfterHiding();
        say(photosWord(ks.size()) + " set aside · tap to undo", 5000, 0, new Runnable() {
            @Override
            public void run() {
                store.unhide(ks);
                hiddenKeys.removeAll(ks);
                refreshAfterHiding();
            }
        });
    }

    private void unhide(List<Media.Item> items) {
        final List<Long> ks = keys(items);
        store.unhide(ks);
        hiddenKeys.removeAll(ks);
        refreshAfterHiding();
        say(photosWord(ks.size()) + " back in the feed · tap to undo", 5000, 0, new Runnable() {
            @Override
            public void run() {
                store.hide(ks);
                hiddenKeys.addAll(ks);
                refreshAfterHiding();
            }
        });
    }

    private void refreshAfterHiding() {
        refreshPhotos(GridView.MORPH);
        refreshFolders(false);
        refreshFolderGrid(GridView.MORPH);
        refreshAlbums(false);
        refreshAlbumGrid(GridView.MORPH);
        if (hiddenOpen) refreshHidden(GridView.MORPH);
        updateTop();
    }

    private void share(List<Media.Item> items) {
        if (items.isEmpty()) return;
        Intent send;
        boolean allImages = true;
        for (Media.Item it : items) if (it.video) allImages = false;
        if (items.size() == 1) {
            Media.Item it = items.get(0);
            send = new Intent(Intent.ACTION_SEND).setType(it.mime.isEmpty() ? (it.video ? "video/*" : "image/*") : it.mime)
                    .putExtra(Intent.EXTRA_STREAM, it.uri());
            send.setClipData(ClipData.newRawUri("", it.uri()));
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (Media.Item it : items) uris.add(it.uri());
            send = new Intent(Intent.ACTION_SEND_MULTIPLE).setType(allImages ? "image/*" : "*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            send.setClipData(clip);
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, null));
        } catch (Throwable e) {
            say("nothing here can take these");
        }
    }

    /** Into Android's trash: gone from every app, back on undo, erased by Android after 30 days. */
    private void trash(List<Media.Item> items) {
        if (items.isEmpty()) return;
        trashing = new ArrayList<>(items);
        ArrayList<Uri> uris = new ArrayList<>();
        for (Media.Item it : items) uris.add(it.uri());
        try {
            PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
            startIntentSenderForResult(pi.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
        } catch (Throwable e) {
            say("Android didn't let photosJS move these to the trash");
        }
    }

    private void untrash(List<Media.Item> items) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Media.Item it : items) uris.add(it.uri());
        try {
            PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, false);
            startIntentSenderForResult(pi.getIntentSender(), REQ_UNTRASH, null, 0, 0, 0);
        } catch (Throwable e) {
            say("couldn't bring them back; they are in Android's trash");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_TRASH && result == RESULT_OK) {
            final List<Media.Item> gone = trashing;
            if (viewer.isOpen()) for (Media.Item it : gone) viewer.remove(it.key());
            say(photosWord(gone.size()) + " moved to the trash · tap to undo", 6000, 0, new Runnable() {
                @Override
                public void run() {
                    untrash(gone);
                }
            });
            load();
        } else if (code == REQ_WRITE) {
            if (result == RESULT_OK) {
                writeOver();
            } else {
                overwriteWith = null;
                editor.note("not saved", 3000);
                dropTrashedOriginal();
            }
        } else if (code == REQ_UNTRASH && result == RESULT_OK) {
            say("back from the trash");
            load();
        }
    }

    private void info(Media.Item it) {
        String when = new SimpleDateFormat(L.dateNumbers(this) ? "d.M.yyyy HH:mm" : "d MMM yyyy HH:mm",
                Locale.ENGLISH).format(new Date(it.taken)).toLowerCase(Locale.ENGLISH);
        String dims = it.w > 0 ? it.w + "×" + it.h + " · " : "";
        String extra = it.video ? " · " + Media.duration(it.duration) : "";
        say(dims + Media.size(it.size) + " · " + when + extra + " · " + it.name, 7000, t.text);
    }

    private void homeWallpaper(final Media.Item it) {
        try {
            Intent crop = WallpaperManager.getInstance(this).getCropAndSetWallpaperIntent(it.uri());
            startActivity(crop);
            return;
        } catch (Throwable ignored) {
            // no crop screen here: set it as it is
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try (InputStream in = getContentResolver().openInputStream(it.uri())) {
                    WallpaperManager.getInstance(MainActivity.this).setStream(in, null, true,
                            WallpaperManager.FLAG_SYSTEM);
                    msg = "home screen wallpaper set";
                } catch (Throwable e) {
                    msg = "couldn't set the wallpaper";
                }
                final String m = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        say(m);
                    }
                });
            }
        });
    }

    private void toLockJS(Media.Item it) {
        Intent send = new Intent(Intent.ACTION_SEND).setPackage("com.lockjs")
                .setType(it.mime.isEmpty() ? "image/*" : it.mime)
                .putExtra(Intent.EXTRA_STREAM, it.uri())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("", it.uri()));
        try {
            startActivity(send);
        } catch (ActivityNotFoundException e) {
            say("lockJS can't take a picture yet; it needs its next update", 5000, 0);
        }
    }

    // ---- grid and boards ----

    @Override
    public void onOpen(GridView from, Media.Item it) {
        if (picking) {
            if (pickMany) from.toggleKey(it.key());
            else finishPick(one(it));
            return;
        }
        if (!it.video) {
            viewerFrom = from;
            from.setGhost(it.key());
            viewer.open(from.items(), from.indexOf(it.key()), from.screenRectOf(it.key()), from.radiusNow());
            return;
        }
        playVideo(it);
    }

    // ---- picking for another app ----

    /** What the other app asked for: one or several, photos and/or videos, which types. */
    private void readPick() {
        Intent in = getIntent();
        picking = true;
        pickMany = in.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        List<String> asked = new ArrayList<>();
        String[] extra = null;
        try {
            extra = in.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } catch (Throwable ignored) {
        }
        if (extra != null) for (String x : extra) if (x != null && !x.trim().isEmpty()) asked.add(x);
        if (asked.isEmpty()) {
            String ty = in.resolveType(this);
            if (ty != null) asked.add(ty);
        }
        boolean any = asked.isEmpty(), img = false, vid = false;
        pickTypes.clear();
        for (String ty : asked) {
            String x = ty.trim().toLowerCase(Locale.US);
            if (x.equals("vnd.android.cursor.dir/image")) x = "image/*";
            else if (x.equals("vnd.android.cursor.dir/video")) x = "video/*";
            if (x.equals("*/*") || x.equals("*")) {
                any = true;
            } else if (x.startsWith("image/")) {
                img = true;
                pickTypes.add(x);
            } else if (x.startsWith("video/")) {
                vid = true;
                pickTypes.add(x);
            }
        }
        if (any) {
            pickTypes.clear();
            img = true;
            vid = true;
        }
        pickImages = img;
        // videos: when they were asked for, and either the feed shows them or only videos were asked for
        pickVideos = vid && (L.getInt(this, "videos", 1) == 1 || !img);
        pickFor = callerName();
    }

    private String callerName() {
        String pkg = getCallingPackage();
        if (pkg == null || pkg.equals(getPackageName())) return "";
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private void setUpPicking() {
        bar.setPicking(pickMany);
        for (GridView g : grids()) {
            // a hold opens the fan (look closer, edit, info, album), so a photo can be edited first
            if (pickMany) {
                g.shareSelection(chosen);
                g.setSelecting(true);
            }
        }
        albumBoards.setHoldable(false);
        folderView.setHoldable(false);
        viewer.setActions(pickMany ? new String[] {"edit", "choose", "use"} : new String[] {"edit", "use"});
        // picking several, a sideways drag chooses; the bar moves between pages
        if (pickMany) pager.setLocked(true);
        setResult(RESULT_CANCELED);
    }

    private boolean pickable(Media.Item it) {
        if (it.video ? !pickVideos : !pickImages) return false;
        if (pickTypes.isEmpty()) return true;
        String m = it.mime == null || it.mime.isEmpty() ? (it.video ? "video/*" : "image/*")
                : it.mime.toLowerCase(Locale.US);
        for (String p : pickTypes) {
            if (p.endsWith("/*") ? m.startsWith(p.substring(0, p.length() - 1)) : p.equals(m)) return true;
        }
        return false;
    }

    private List<Media.Item> pickable(List<Media.Item> list) {
        List<Media.Item> out = new ArrayList<>();
        for (Media.Item it : list) if (pickable(it)) out.add(it);
        return out;
    }

    private static List<Media.Item> one(Media.Item it) {
        List<Media.Item> l = new ArrayList<>();
        l.add(it);
        return l;
    }

    /** the chosen photos that still exist, in the order they were chosen */
    private List<Media.Item> chosenItems() {
        List<Media.Item> out = new ArrayList<>();
        for (long k : chosen) {
            Media.Item it = byKey.get(k);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** a long press while picking: the photo, big, with "use" (and "choose") under it */
    @Override
    public void onPeek(GridView from, Media.Item it) {
        if (viewer.isOpen()) return;
        viewerFrom = from;
        from.setGhost(it.key());
        viewer.open(from.items(), from.indexOf(it.key()), from.screenRectOf(it.key()), from.radiusNow());
    }

    @Override
    public boolean chosen(Media.Item it) {
        return it != null && chosen.contains(it.key());
    }

    @Override
    public void onPickUse() {
        finishPick(chosenItems());
    }

    @Override
    public void onPickCancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    /** Hands the photos to the app that asked: the first as the data, all of them as ClipData. */
    private void finishPick(List<Media.Item> items) {
        if (items.isEmpty()) return;
        List<String> mimes = new ArrayList<>();
        for (Media.Item it : items) {
            String m = it.mime == null || it.mime.isEmpty() ? (it.video ? "video/*" : "image/*") : it.mime;
            if (!mimes.contains(m)) mimes.add(m);
        }
        ClipData clip = new ClipData("photosJS", mimes.toArray(new String[0]),
                new ClipData.Item(items.get(0).uri()));
        for (int i = 1; i < items.size(); i++) clip.addItem(new ClipData.Item(items.get(i).uri()));
        Intent out = new Intent();
        out.setData(items.get(0).uri());
        out.setClipData(clip);
        out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, out);
        finish();
    }

    // ---- the viewer ----

    @Override
    public RectF thumbRect(Media.Item it) {
        if (viewerFrom == null || !viewerFrom.reveal(it.key())) return null;
        return viewerFrom.screenRectOf(it.key());
    }

    @Override
    public float thumbRadius() {
        return viewerFrom != null ? viewerFrom.radiusNow() : 0;
    }

    @Override
    public Bitmap thumbFor(Media.Item it) {
        Bitmap b = thumbs.best(it, 2);
        return b != null ? b : (viewerFrom != null ? viewerFrom.thumbOf(it) : null);
    }

    @Override
    public void onViewerIndex(Media.Item it) {
        if (viewerFrom == null || it == null) return;
        viewerFrom.setGhost(it.key());
        viewerFrom.reveal(it.key());
    }

    @Override
    public void onViewerClosed() {
        if (viewerFrom != null) viewerFrom.setGhost(Long.MIN_VALUE);
        viewerFrom = null;
    }

    @Override
    public void onViewerAction(String action, Media.Item it) {
        if (picking) {
            if (action.equals("edit")) {
                edit(it);
            } else if (action.equals("choose")) {
                (viewerFrom != null ? viewerFrom : photos).toggleKey(it.key());
                viewer.invalidate();
            } else if (action.equals("use")) {
                finishPick(pickMany && !chosen.isEmpty() ? chosenItems() : one(it));
            }
            return;
        }
        List<Media.Item> one = new ArrayList<>();
        one.add(it);
        pending = one;
        switch (action) {
            case "album": chooseAlbum(one); break;
            case "edit": edit(it); break;
            case "share": share(one); break;
            case "aside": hide(one); break;
            case "trash": trash(one); break;
            case "info": info(it); break;
        }
    }

    // ---- the editor ----

    private boolean overwrite() {
        return L.getInt(this, "overwrite", 0) == 1;
    }

    private void edit(Media.Item it) {
        if (it.video) {
            say("videos can't be edited here");
            return;
        }
        editor.open(it);
    }

    @Override
    public void onEditorMessage(String text) {
        say(text);
    }

    @Override
    public void onEditorClosed() {
    }

    @Override
    public void onEditorSave(final Media.Item it) {
        final boolean over = overwrite() && "image/jpeg".equals(it.mime);
        editor.note("saving", 60000);
        io.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null;
                Uri made = null;
                String err = null;
                try {
                    Bitmap full = android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(getContentResolver(), it.uri()),
                            new android.graphics.ImageDecoder.OnHeaderDecodedListener() {
                                @Override
                                public void onHeaderDecoded(android.graphics.ImageDecoder d,
                                                            android.graphics.ImageDecoder.ImageInfo info,
                                                            android.graphics.ImageDecoder.Source s) {
                                    d.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                                    int max = 4096;
                                    if (Math.max(w, h) > max) {
                                        float f = max / (float) Math.max(w, h);
                                        d.setTargetSize(Math.round(w * f), Math.round(h * f));
                                    }
                                }
                            });
                    out = editor.render(full);
                    full.recycle();
                    if (!over) {
                        made = saveCopy(it, out);
                        out.recycle();
                        out = null;
                    } else {
                        // the original goes to Android's trash (restorable there for 30 days);
                        // only if Android won't take it there, photosJS keeps it itself
                        originalInTrash = trashOriginal(it);
                        if (originalInTrash == null) keepOriginal(it);
                    }
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName();
                }
                final Bitmap result = out;
                final String error = err;
                final long madeKey = made != null ? android.content.ContentUris.parseId(made) : 0;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            editor.note("couldn't save (" + error + ")", 5000);
                            return;
                        }
                        if (!over) {
                            editor.saved();
                            editor.close();
                            afterSave(madeKey, "saved as a copy next to the original", null);
                            return;
                        }
                        overwriteWith = result;
                        overwriteItem = it;
                        try {
                            List<Uri> one = new ArrayList<>();
                            one.add(it.uri());
                            PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), one);
                            startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
                        } catch (Throwable e) {
                            editor.note("Android didn't let photosJS change the original", 5000);
                        }
                    }
                });
            }
        });
    }

    /** a new picture next to the original, dated like it so it sits beside it */
    private Uri saveCopy(Media.Item it, Bitmap out) throws Exception {
        String rel = relativePath(it);
        String base = it.name.contains(".") ? it.name.substring(0, it.name.lastIndexOf('.')) : it.name;
        Uri u = null;
        for (String path : new String[] {rel, "Pictures/photosJS/"}) {
            if (path == null) continue;
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, base + "_edit.jpg");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (it.taken > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, it.taken + 1);
            try {
                u = getContentResolver().insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            } catch (Throwable e) {
                u = null;
            }
            if (u != null) break;
        }
        if (u == null) throw new IllegalStateException("no place to save");
        try (OutputStream os = getContentResolver().openOutputStream(u)) {
            out.compress(Bitmap.CompressFormat.JPEG, 95, os);
        }
        android.content.ContentValues done = new android.content.ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(u, done, null, null);
        return u;
    }

    /**
     * What a save leaves on screen. Picking for another app, the saved photo is
     * the one to send: chosen at once when picking several, or one tap on the
     * message away when picking one.
     */
    private void afterSave(final long key, String text, Runnable undo) {
        if (picking && key != 0) {
            if (pickMany) {
                chosen.add(key);
                bar.setPickCount(chosen.size());
                currentView().invalidate();
                say(text + " · chosen", 5000, 0);
            } else {
                say(text + " · tap here to use it", 12000, 0, new Runnable() {
                    @Override
                    public void run() {
                        Media.Item x = byKey.get(key);
                        if (x != null) finishPick(one(x));
                    }
                });
            }
            load();
            return;
        }
        say(text, undo != null ? 8000 : 3200, 0, undo);
        load();
    }

    /**
     * Before writing over a photo: a copy of the original goes into Android's
     * trash next to it ("name_original"), hidden like everything trashed and
     * restorable from the phone's trash for 30 days. Null if Android refuses.
     */
    private Uri trashOriginal(Media.Item it) {
        return copyToTrash(it.uri(), null, it.name, it.mime, relativePath(it), it.taken);
    }

    private Uri copyToTrash(Uri fromUri, File fromFile, String name, String mime, String rel, long taken) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".jpg";
        Uri u = null;
        for (String path : new String[] {rel, "Pictures/photosJS/"}) {
            if (path == null) continue;
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, base + "_original" + ext);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "image/jpeg" : mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (taken > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, taken);
            try {
                u = getContentResolver().insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            } catch (Throwable e) {
                u = null;
            }
            if (u != null) break;
        }
        if (u == null) return null;
        try {
            try (InputStream in = fromFile != null ? new FileInputStream(fromFile)
                    : getContentResolver().openInputStream(fromUri);
                 OutputStream os = getContentResolver().openOutputStream(u)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            android.content.ContentValues ready = new android.content.ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(u, ready, null, null);
            android.content.ContentValues trashed = new android.content.ContentValues();
            trashed.put(MediaStore.MediaColumns.IS_TRASHED, 1);
            if (getContentResolver().update(u, trashed, null, null) < 1) throw new IllegalStateException("not trashed");
            return u;
        } catch (Throwable e) {
            try {
                getContentResolver().delete(u, null, null);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** "tap to undo" after saving over: the original comes back out of the trash, over the edit */
    private void restoreOriginal(final Media.Item it, final Uri orig) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                Bitmap shown = null;
                final int[] size = new int[2];
                try {
                    android.content.ContentValues back = new android.content.ContentValues();
                    back.put(MediaStore.MediaColumns.IS_TRASHED, 0);
                    try {
                        getContentResolver().update(orig, back, null, null);
                    } catch (Throwable ignored) {
                    }
                    try (InputStream in = getContentResolver().openInputStream(orig);
                         OutputStream os = getContentResolver().openOutputStream(it.uri(), "wt")) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    getContentResolver().delete(orig, null, null);
                    shown = android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(getContentResolver(), it.uri()),
                            new android.graphics.ImageDecoder.OnHeaderDecodedListener() {
                                @Override
                                public void onHeaderDecoded(android.graphics.ImageDecoder d,
                                                            android.graphics.ImageDecoder.ImageInfo info,
                                                            android.graphics.ImageDecoder.Source s) {
                                    d.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                                    size[0] = w;
                                    size[1] = h;
                                    float f = Math.min(1f, 2400f / Math.max(w, h));
                                    if (f < 1f) d.setTargetSize(Math.round(w * f), Math.round(h * f));
                                }
                            });
                    thumbs.seed(it, shown);
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName();
                }
                final Bitmap pic = shown;
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            say("couldn't bring the original back (" + error + "); it is in the phone's trash");
                            return;
                        }
                        if (size[0] > 0) {
                            fresh.put(it.key(), new long[] {size[0], size[1], System.currentTimeMillis()});
                            Media.Item same = byKey.get(it.key());
                            for (Media.Item x : new Media.Item[] {it, same}) {
                                if (x == null) continue;
                                x.w = size[0];
                                x.h = size[1];
                            }
                        }
                        if (pic != null && viewer.isOpen()) viewer.replace(it, pic);
                        refreshAll(GridView.KEEP);
                        say("the original is back");
                        load();
                    }
                });
            }
        });
    }

    /**
     * Once: originals that earlier versions kept inside photosJS move to
     * Android's trash, next to their photos, like every new one does.
     */
    private void moveKeptOriginals() {
        if (L.getInt(this, "orig_trash", 0) == 1) return;
        final File dir = new File(getFilesDir(), "originals");
        final File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            L.putInt(this, "orig_trash", 1);
            return;
        }
        final Map<Long, Media.Item> items = new HashMap<>(byKey);
        io.execute(new Runnable() {
            @Override
            public void run() {
                int moved = 0, left = 0;
                for (File f : files) {
                    String n = f.getName();
                    int us = n.indexOf('_');
                    Media.Item src = null;
                    String name = n;
                    if (us > 0) {
                        name = n.substring(us + 1);
                        try {
                            src = items.get(Long.parseLong(n.substring(0, us)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String rel = src != null ? relativePath(src) : null;
                    String mime = src != null ? src.mime : (name.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg");
                    Uri u = copyToTrash(null, f, name, mime, rel, src != null ? src.taken : 0);
                    if (u != null && f.delete()) moved++;
                    else left++;
                }
                final int m = moved, l = left;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (l == 0) {
                            L.putInt(MainActivity.this, "orig_trash", 1);
                            //noinspection ResultOfMethodCallIgnored
                            dir.delete();
                        }
                        if (m > 0) {
                            say(m + (m == 1 ? " original" : " originals")
                                    + " kept in photosJS moved to the phone's trash", 6000, 0);
                        }
                    }
                });
            }
        });
    }

    private String relativePath(Media.Item it) {
        String[] proj = {MediaStore.MediaColumns.RELATIVE_PATH};
        try (android.database.Cursor c = getContentResolver().query(it.uri(), proj, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** before writing over a photo, its original goes into photosJS's own room */
    private void keepOriginal(Media.Item it) throws Exception {
        File dir = new File(getFilesDir(), "originals");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no room for the original");
        File f = new File(dir, it.key() + "_" + it.name);
        if (f.exists()) return;
        try (InputStream in = getContentResolver().openInputStream(it.uri());
             OutputStream os = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /** the write was refused or failed: the original's copy in the trash isn't needed */
    private void dropTrashedOriginal() {
        final Uri u = originalInTrash;
        originalInTrash = null;
        if (u == null) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    getContentResolver().delete(u, null, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void writeOver() {
        final Bitmap out = overwriteWith;
        final Media.Item it = overwriteItem;
        overwriteWith = null;
        overwriteItem = null;
        if (out == null || it == null) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try (OutputStream os = getContentResolver().openOutputStream(it.uri(), "wt")) {
                    out.compress(Bitmap.CompressFormat.JPEG, 95, os);
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName();
                }
                // what the viewer and the thumbnails show from now on: the new picture
                Bitmap forViewer = null;
                final int newW = out.getWidth(), newH = out.getHeight();
                if (err == null) {
                    try {
                        thumbs.seed(it, out);
                        float f = Math.min(1f, 2400f / Math.max(newW, newH));
                        forViewer = f < 1f
                                ? Bitmap.createScaledBitmap(out, Math.round(newW * f), Math.round(newH * f), true)
                                : out.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (Throwable ignored) {
                        forViewer = null;
                    }
                }
                out.recycle();
                final Bitmap shown = forViewer;
                final String error = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            editor.note("couldn't save (" + error + ")", 5000);
                            dropTrashedOriginal();
                            return;
                        }
                        // a crop changes the shape: the feed and the viewer take the new one now
                        fresh.put(it.key(), new long[] {newW, newH, System.currentTimeMillis()});
                        Media.Item same = byKey.get(it.key());
                        for (Media.Item x : new Media.Item[] {it, same}) {
                            if (x == null) continue;
                            x.w = newW;
                            x.h = newH;
                            x.modified = System.currentTimeMillis();
                        }
                        if (shown != null && viewer.isOpen()) viewer.replace(it, shown);
                        refreshAll(GridView.KEEP);
                        editor.saved();
                        editor.close();
                        final Uri orig = originalInTrash;
                        originalInTrash = null;
                        if (picking) {
                            afterSave(it.key(), "saved over the original", null);
                        } else if (orig != null) {
                            afterSave(0, "saved over · the original is in the trash · tap to undo", new Runnable() {
                                @Override
                                public void run() {
                                    restoreOriginal(it, orig);
                                }
                            });
                        } else {
                            afterSave(0, "saved over · the original is kept in photosJS", null);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onViewerHold(Media.Item it, RectF screen, Bitmap bm, float x, float y) {
        if (picking) return;
        L.putInt(this, "used_hold", 1);
        holdBoard = null;
        holdGrid = null;
        holdItem = it;
        holdOnSelection = false;
        pending = one(it);
        int[] acts = fanActions();
        for (int i = 0; i < acts.length; i++) {
            if (acts[i] == FanView.SELECT || acts[i] == FanView.VIEW || acts[i] == FanView.ORDER) acts[i] = FanView.NONE;
        }
        fan.open(screen, bm, 0, x, y, acts, false);
    }

    @Override
    public void playVideo(Media.Item it) {
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
        }
    }

    @Override
    public void onColumnsChanged(int cols) {
        L.putInt(this, "used_pinch", 1);
        L.putInt(this, "cols:" + viewKey(), cols);
    }

    @Override
    public void onBoardColumnsChanged(int cols) {
        L.putInt(this, "used_pinch", 1);
        L.putInt(this, tab == TAB_ALBUMS ? "acols" : "fcols", cols);
    }

    @Override
    public void onOpenFolder(Media.Folder f) {
        if (tab == TAB_ALBUMS) {
            openAlbum = f;
            albumGrid.setColumns(colsOf("a:" + f.bucket));
            refreshAlbumGrid(GridView.TOP);
            crossfade(albumBoards, albumGrid);
        } else {
            openFolder = f;
            folderGrid.setColumns(colsOf("f:" + f.bucket));
            refreshFolderGrid(GridView.TOP);
            crossfade(folderView, folderGrid);
        }
        updateTop();
    }

    private void closeFolder() {
        exitSelect();
        openFolder = null;
        crossfade(folderGrid, folderView);
        updateTop();
    }

    private void closeAlbum() {
        exitSelect();
        openAlbum = null;
        if (albumGrid.getVisibility() == View.VISIBLE) crossfade(albumGrid, albumBoards);
        updateTop();
    }

    private void openHidden() {
        hiddenOpen = true;
        refreshHidden(GridView.TOP);
        hiddenGrid.setColumns(colsOf("hidden"));
        hiddenGrid.setAlpha(0f);
        hiddenGrid.setVisibility(View.VISIBLE);
        hiddenGrid.animate().alpha(1f).setDuration(Motion.ms(220));
        pager.setLocked(true);
        updateTop();
        startSelect(hiddenGrid, null);
    }

    private void closeHidden() {
        hiddenOpen = false;
        exitSelect();
        hiddenGrid.animate().cancel();
        hiddenGrid.setVisibility(View.GONE);
        pager.setLocked(false);
        settings.setRows(rows());
        updateTop();
    }

    @Override
    public void onThumbsArrived() {
        currentView().invalidate();
        if (fan.isOpen()) fan.invalidate();
        if (viewer.isOpen()) viewer.invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!hasMedia() && e.getActionMasked() == MotionEvent.ACTION_UP) askMedia();
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onBackPressed() {
        GridView g = activeGrid();
        if (picking) {
            if (fan.isOpen()) {
                fan.close();
                unsay();
            } else if (sheet.isOpen()) sheet.close();
            else if (editor.isOpen()) editor.back();
            else if (viewer.isOpen()) viewer.close();
            else if (tab == TAB_FOLDERS && openFolder != null) closeFolder();
            else if (tab == TAB_ALBUMS && openAlbum != null) closeAlbum();
            else onPickCancel();
            return;
        }
        if (fan.isOpen()) {
            fan.close();
            unsay();
        } else if (sheet.isOpen()) {
            sheet.close();
        } else if (arranging()) {
            endArrange();
        } else if (editor.isOpen()) {
            editor.back();
        } else if (viewer.isOpen()) {
            viewer.close();
        } else if (hiddenOpen) {
            closeHidden();
        } else if (g != null && g.selecting()) {
            exitSelect();
        } else if (tab == TAB_FOLDERS && openFolder != null) {
            closeFolder();
        } else if (tab == TAB_ALBUMS && openAlbum != null) {
            closeAlbum();
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

    private String orderValue(String view) {
        int by = sortOf(view);
        boolean desc = descOf(view);
        if (by == Media.BY_HAND) return "as arranged";
        if (by == Media.BY_NAME) return desc ? "z to a" : "a to z";
        if (by == Media.BY_SIZE) return desc ? "largest first" : "smallest first";
        return desc ? "newest first" : "oldest first";
    }

    private void sortRows(List<SettingsPage.Row> r, final String view, String what) {
        r.add(new Row(what + " sorted by", "remembered") {
            @Override
            public String value() {
                return Media.SORT_NAMES[sortOf(view)];
            }

            @Override
            public void step(int d) {
                // by hand: the order things were arranged in (hold one › arrange)
                int n = 5;
                L.putInt(MainActivity.this, "sort:" + view, (sortOf(view) + d + n) % n);
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
                if (sortOf(view) == Media.BY_HAND) {
                    say("arranged by hand: hold one and choose arrange");
                    return;
                }
                L.putInt(MainActivity.this, "desc:" + view, descOf(view) ? 0 : 1);
                resort(view);
            }
        });
    }

    private void resort(String view) {
        switch (view) {
            case "all": refreshPhotos(GridView.TOP); break;
            case "folders": refreshFolders(true); break;
            case "albums": refreshAlbums(true); break;
            case "albumfeeds": refreshAlbumGrid(GridView.TOP); break;
            default: refreshFolderGrid(GridView.TOP);
        }
        updateTop();
    }

    private List<SettingsPage.Row> rows() {
        List<SettingsPage.Row> r = new ArrayList<>();
        r.add(new Head("look"));
        r.add(new Row("theme", "the colours of the room") {
            @Override
            public String value() {
                return shownTheme().name;
            }

            @Override
            public void step(int d) {
                switchTheme(Theme.index(shownTheme().key) + d);
            }
        });
        r.add(new Row("text size", "every word in the app; alagard's letters run a size larger") {
            @Override
            public String value() {
                return L.TEXT_NAMES[L.textIndex(MainActivity.this)];
            }

            @Override
            public void step(int d) {
                float from = L.textScale(MainActivity.this);
                cycle("text", 1, L.TEXT.length, d);
                animateTextSize(from, L.textScale(MainActivity.this));
            }
        });
        r.add(new Row("font", "every word in the app: plain, blocks, alagard, spaced caps or thin blocks") {
            @Override
            public String value() {
                return Heading.NAMES[L.heading(MainActivity.this)];
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "heading", L.heading(MainActivity.this));
                cycle("heading", 0, Heading.NAMES.length, d);
                applyLook(true);
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
        r.add(new Row("resize motion", "when a pinch changes the columns: a ripple from your fingers, or a plain glide") {
            @Override
            public String value() {
                return L.getInt(MainActivity.this, "resize", 1) == 1 ? "ripple" : "glide";
            }

            @Override
            public void step(int d) {
                cycle("resize", 1, 2, d);
                applyLook(false);
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
        r.add(new Row("spacing", "how much air between photos and boards") {
            @Override
            public String value() {
                return L.SPACE_NAMES[L.spacing(MainActivity.this)];
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "space", L.spacing(MainActivity.this));
                cycle("space", 1, L.SPACE_NAMES.length, d);
                applyLook(false);
            }
        });

        r.add(new Head("feed"));
        r.add(new Row("whole photos", "photos uncropped, in their own shape: always, never (squares), or up to a number of columns") {
            @Override
            public String value() {
                return PINTEREST_NAMES[pinterestMode()];
            }

            @Override
            public void step(int d) {
                // on, off, threshold
                int[] cycle = {1, 0, 2};
                int at = 0;
                for (int i = 0; i < cycle.length; i++) if (cycle[i] == pinterestMode()) at = i;
                L.putInt(MainActivity.this, "pmode", cycle[(at + d + cycle.length) % cycle.length]);
                for (GridView g : grids()) g.setPinterestUpTo(pinterestUpTo());
                // the threshold row comes and goes with it
                settings.setRows(rows());
            }
        });
        if (pinterestMode() == 2) {
            r.add(new Row("whole up to", "whole photos at this many columns or fewer, squares beyond") {
                @Override
                public String value() {
                    int p = pinterestThreshold();
                    return "up to " + p + (p == 1 ? " column" : " columns");
                }

                @Override
                public void step(int d) {
                    int[] steps = GridView.LEVELS;
                    int at = 0;
                    for (int i = 0; i < steps.length; i++) if (steps[i] <= pinterestThreshold()) at = i;
                    int next = steps[(at + d + steps.length) % steps.length];
                    L.putInt(MainActivity.this, "pinterest", next);
                    for (GridView g : grids()) g.setPinterestUpTo(pinterestUpTo());
                }
            });
        }
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
        r.add(new Row("videos", "off: only photos and pictures are shown") {
            @Override
            public String value() {
                return videos() ? "on" : "off";
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "videos", videos() ? 0 : 1);
                refreshAfterHiding();
            }
        });
        r.add(new Row("new feeds open with", "columns, until you pinch them") {
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

        r.add(new Head("editor"));
        r.add(new Row("edits save as", "the original is always kept") {
            @Override
            public String value() {
                return overwrite() ? "over the original" : "a copy";
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "overwrite", overwrite() ? 0 : 1);
            }
        });

        r.add(new Head("long press"));
        final int[] fanDef = FAN_DEFAULT;
        final int[] choices = new int[FanView.PHOTO_ACTIONS.length + 1];
        choices[0] = FanView.NONE;
        System.arraycopy(FanView.PHOTO_ACTIONS, 0, choices, 1, FanView.PHOTO_ACTIONS.length);
        for (int i = 0; i < fanDef.length; i++) {
            final int slot = i;
            r.add(new Row("fan " + (i + 1), i == 0 ? "what blooms around your thumb" : "") {
                @Override
                public String value() {
                    return FanView.shortName(L.getInt(MainActivity.this, "fan" + slot, fanDef[slot]));
                }

                @Override
                public void step(int d) {
                    int cur = L.getInt(MainActivity.this, "fan" + slot, fanDef[slot]);
                    int idx = 0;
                    for (int k = 0; k < choices.length; k++) if (choices[k] == cur) idx = k;
                    idx = (idx + d + choices.length) % choices.length;
                    L.putInt(MainActivity.this, "fan" + slot, choices[idx]);
                }
            });
        }

        r.add(new Head("sorting"));
        sortRows(r, "all", "photos");
        sortRows(r, "folders", "boards");
        sortRows(r, "folderfeeds", "folders");
        if (hasAlbums) {
            sortRows(r, "albums", "albums");
            sortRows(r, "albumfeeds", "album photos");
        }

        r.add(new Head("boards"));
        r.add(new Row("sizes", "how much space each board takes") {
            @Override
            public String value() {
                return sizes() ? "on" : "off";
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "sizes", sizes() ? 0 : 1);
                refreshFolders(false);
                refreshAlbums(false);
            }
        });

        r.add(new Head("set aside and trash"));
        r.add(new Row("set aside", "kept out of photosJS only; other apps still see them") {
            @Override
            public String value() {
                int n = 0;
                for (Media.Item it : all) if (hiddenKeys.contains(it.key())) n++;
                return n == 0 ? "none" : n + " · show";
            }

            @Override
            public void step(int d) {
                if (!hiddenKeys.isEmpty()) openHidden();
            }
        });
        if (Build.VERSION.SDK_INT >= 31) {
            r.add(new Row("trash without asking", "lets photosJS skip Android's confirm") {
                @Override
                public String value() {
                    return MediaStore.canManageMedia(MainActivity.this) ? "allowed" : "Android asks";
                }

                @Override
                public void step(int d) {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable e) {
                        top.message("this Android has no such switch");
                    }
                }
            });
        }

        r.add(new Head("about"));
        r.add(new Row("fonts", "Ubuntu Sans Mono · Alagard by Hewett Tsoi (Pix3M)") {
            @Override
            public String value() {
                return "";
            }

            @Override
            public void step(int d) {
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
}
