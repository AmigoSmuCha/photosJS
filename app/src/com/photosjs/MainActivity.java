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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
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
    private float dp;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Media.Item> all = new ArrayList<>();
    private final Map<Long, Media.Item> byKey = new HashMap<>();
    private Set<Long> hiddenKeys = new HashSet<>();
    private List<Store.Album> albums = new ArrayList<>();
    private boolean loadedOnce, asked, hasAlbums, hiddenOpen;
    private int tab = TAB_PHOTOS;
    private Media.Folder openFolder, openAlbum;
    private int photoCount, folderCount, folderItemCount, albumItemCount;

    // what the fan / sheet is about
    private List<Media.Item> pending = new ArrayList<>();
    private Media.Folder holdBoard;
    private GridView holdGrid;
    private boolean renaming;
    private List<Media.Item> trashing = new ArrayList<>();
    // photos photosJS just saved over: their new size, until Android has re-read them
    private final Map<Long, long[]> fresh = new HashMap<>();

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

        setContentView(root);
        loadAlbums();
        hasAlbums = !albums.isEmpty();
        setPages();
        applyTheme();
        applyLook(false);
        int p = pinterest();
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
        boolean rounded = L.rounded(this), airy = L.airy(this);
        int heading = L.heading(this);
        boolean nums = L.dateNumbers(this);
        float s = L.textScale(this);
        int dates = L.dates(this);
        for (GridView g : grids()) g.setLook(rounded, airy, dates, nums, heading, s);
        folderView.setLook(rounded, airy, s);
        albumBoards.setLook(rounded, airy, s);
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
                t = next;
                applyTheme();
                if (result != PixelCopy.SUCCESS) return;
                final ImageView over = new ImageView(MainActivity.this);
                over.setImageBitmap(snap);
                root.addView(over, new FrameLayout.LayoutParams(-1, -1));
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean first = !loadedOnce;
                        loadedOnce = true;
                        all = list;
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
                        thumbs.clearFailures();
                        if (first) top.clearMessage();
                        refreshAll(first ? GridView.TOP : GridView.KEEP);
                        if (all.isEmpty()) top.message("no photos here yet", 600000, 0);
                        else if (first) firstHint();
                    }
                });
            }
        });
    }

    /** One dim line about the gestures that matter, until they have been used. */
    private void firstHint() {
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
        Media.sort(list, by, descOf("all"));
        photos.setItems(list, dated(by), by == Media.BY_MODIFIED, how);
        photoCount = list.size();
    }

    private void refreshFolders(boolean toTop) {
        List<Media.Item> list = Media.filter(all, videos(), 0, hiddenKeys);
        Media.sort(list, Media.BY_TAKEN, true);   // collages: each folder's newest photos
        List<Media.Folder> fs = Media.folders(list);
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
        Media.sort(list, by, descOf(key));
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
        Media.sort(list, by, descOf(key));
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
        if (hasAlbums) pager.setPages(photos, boards, albumsBox, settings);
        else pager.setPages(photos, boards, settings);
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
        if (g == null) return;
        g.setSelecting(true);
        pager.setLocked(true);
        bar.setSelecting(true, 0, actionsFor(g));
        if (first != null) g.selectKey(first.key());
        int shown = L.getInt(this, "hint_select", 0);
        if (shown < 3 && g != hiddenGrid) {
            L.putInt(this, "hint_select", shown + 1);
            top.message("tap photos, or drag sideways across them", 4000, 0);
        }
    }

    @Override
    public void onTwoFingerTap(GridView from, Media.Item it) {
        if (from == hiddenGrid) return;
        if (from.selecting()) exitSelect();
        else startSelect(from, it);
    }

    @Override
    public void onSelectDone() {
        if (hiddenOpen) closeHidden();
        else exitSelect();
    }

    private void exitSelect() {
        for (GridView g : grids()) g.setSelecting(false);
        if (!hiddenOpen) pager.setLocked(false);
        bar.setSelecting(false, 0, null);
    }

    @Override
    public void onSelectionChanged(GridView from, int n) {
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

    private int[] fanActions() {
        int[] def = {FanView.ALBUM, FanView.SELECT, FanView.WALLPAPER, FanView.EDIT};
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) out[i] = L.getInt(this, "fan" + i, def[i]);
        return out;
    }

    @Override
    public void onHoldStart(GridView from, Media.Item it, RectF screen, Bitmap bm, float radius,
                            float x, float y) {
        L.putInt(this, "used_hold", 1);
        holdBoard = null;
        holdGrid = from;
        pending = new ArrayList<>();
        pending.add(it);
        int[] acts = from == hiddenGrid ? new int[] {FanView.HIDE, FanView.DELETE, FanView.INFO} : fanActions();
        fan.open(screen, bm, radius, x, y, acts, false);
    }

    @Override
    public void onBoardHold(FolderView from, Media.Folder f, RectF screen, float radius, float x, float y) {
        holdBoard = f;
        pending = new ArrayList<>();
        Bitmap bm = f.cover != null ? thumbs.best(f.cover, 1) : null;
        fan.open(screen, bm, radius, x, y, new int[] {FanView.RENAME, FanView.DROP_ALBUM}, false);
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
        if (action == FanView.NONE) unsay();
        else say(action == FanView.HIDE && hiddenOpen ? "bring back" : FanView.name(action), 60000, t.text);
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
        switch (action) {
            case FanView.ALBUM: chooseAlbum(items); break;
            case FanView.SELECT: if (!items.isEmpty()) startSelect(holdGrid, items.get(0)); break;
            case FanView.EDIT: if (!items.isEmpty()) edit(items.get(0)); break;
            case FanView.SHARE: share(items); break;
            case FanView.HIDE:
                if (hiddenOpen) unhide(items);
                else hide(items);
                break;
            case FanView.DELETE: trash(items); break;
            case FanView.INFO: if (!items.isEmpty()) info(items.get(0)); break;
            case FanView.W_HOME: if (!items.isEmpty()) homeWallpaper(items.get(0)); break;
            case FanView.W_LOCKJS: if (!items.isEmpty()) toLockJS(items.get(0)); break;
            case FanView.RENAME:
                if (holdBoard != null) {
                    renaming = true;
                    sheet.showName("rename the album", holdBoard.name);
                }
                break;
            case FanView.DROP_ALBUM: if (holdBoard != null) dropAlbum(holdBoard.bucket); break;
        }
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
        if (!it.video) {
            viewerFrom = from;
            from.setGhost(it.key());
            viewer.open(from.items(), from.indexOf(it.key()), from.screenRectOf(it.key()), from.radiusNow());
            return;
        }
        playVideo(it);
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
                        saveCopy(it, out);
                        out.recycle();
                        out = null;
                    } else {
                        keepOriginal(it);
                    }
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName();
                }
                final Bitmap result = out;
                final String error = err;
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
                            say("saved as a copy next to the original");
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
    private void saveCopy(Media.Item it, Bitmap out) throws Exception {
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
                        say("saved over the original; the original is kept in photosJS");
                        load();
                    }
                });
            }
        });
    }

    @Override
    public void onViewerHold(Media.Item it, RectF screen, Bitmap bm, float x, float y) {
        L.putInt(this, "used_hold", 1);
        holdBoard = null;
        holdGrid = null;
        pending = new ArrayList<>();
        pending.add(it);
        int[] acts = fanActions();
        for (int i = 0; i < acts.length; i++) if (acts[i] == FanView.SELECT) acts[i] = FanView.NONE;
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
        if (fan.isOpen()) {
            fan.close();
            unsay();
        } else if (sheet.isOpen()) {
            sheet.close();
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
        r.add(new Row("headings", "dates and titles: plain, blocks, alagard or spaced caps") {
            @Override
            public String value() {
                return Heading.NAMES[L.heading(MainActivity.this)];
            }

            @Override
            public void step(int d) {
                L.putInt(MainActivity.this, "heading", L.heading(MainActivity.this));
                cycle("heading", 0, Heading.NAMES.length, d);
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
                for (GridView g : grids()) g.setPinterestUpTo(p);
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
        final int[] fanDef = {FanView.ALBUM, FanView.SELECT, FanView.WALLPAPER, FanView.EDIT};
        final int[] choices = new int[FanView.PHOTO_ACTIONS.length + 1];
        choices[0] = FanView.NONE;
        System.arraycopy(FanView.PHOTO_ACTIONS, 0, choices, 1, FanView.PHOTO_ACTIONS.length);
        for (int i = 0; i < 4; i++) {
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
