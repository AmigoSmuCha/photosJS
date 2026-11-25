# photosJS

A gallery for the Unihertz Jelly Star: a warm, quiet room where the photos are the only thing that's lit.

Pinterest first:

- **The feed**: whole photos in their true shape, packed into equal-width columns, rounded, with air between them. Pinch for more or fewer columns (1, 2, 3, 4, 5, 6, 8); the photo under your fingers stays under your fingers. At many columns it becomes an overview of squares (settings › pinterest view sets where).
- **Boards**: every device folder as a board, a collage of its newest photos with its name and count. Pinch for bigger or smaller boards; tap one for its feed.
- **Swipe sideways** between photos, boards, albums (once there is one) and settings; the pages follow the finger. Or tap the glyphs in the bar.
- **Hold a photo**: a fan of actions blooms around your thumb; slide onto one and let go (the top line says which). Set what's in the fan in settings › long press: album, select, wallpaper (home screen or lockJS), edit, share, set aside, trash, info.
- **Select**: "select" in the fan, or tap the feed with two fingers. Tap photos, or drag sideways across them to select a run; drag near the top or bottom edge to keep going. Then: album, share, aside, trash; "done" (or two fingers again) to finish. Holding a photo while selecting opens the fan too: on a selected photo, album, share, set aside and trash take the whole selection (the top line says how many); edit, wallpaper and info take the photo under your finger.
- **Albums**: collections that move or copy nothing; one photo can be in many. Hold an album's board to rename or delete it (the photos stay).
- **Set aside** photos are kept out of photosJS only (other apps still see them); settings › set aside to bring them back. **Trash** is Android's own (restorable 30 days, gone from every app); settings › trash without asking skips Android's confirm.
- Undo: messages that say "tap to undo" in the top line can be tapped.
- **Fast scroll**: drag the thin bar at the right edge.
- **Settings** (the last page): 16 themes, text size, font (plain, lockJS blocks, Alagard or spaced caps — every word in the app: titles, messages, settings, the bar, the viewer, the editor), animations, corners, spacing (tight, airy, breezy, adrift), Pinterest view, dates (and months as numbers), videos, sorting for every kind of view, board sizes. Every row: tap the right of the value to step forward, the left to step back.
- **The viewer**: tap a photo and it grows out of its place. Swipe sideways for the next one, down to put it back, pinch or double-tap to zoom, tap for the controls (album, edit, share, hide, trash, info). Hold for the fan.
- **The editor** (edit in the fan or the viewer): *crop* — drag the corners or edges (the touch zones are much bigger than the marks), free or square; *draw* — one finger draws in the theme's 16 colours, two fingers zoom and move without marking, the brush keeps its size on screen. Undo every step. Saves as a copy next to the original (settings › edits save as: over the original, which is then kept inside photosJS).
- Videos open in VLC (in the viewer, a still frame: tap it).

## As a photo picker for other apps

photosJS answers `ACTION_GET_CONTENT` and `ACTION_PICK` for images and videos. It opens as itself, without settings and with only what was asked for (`image/*`, `video/*`, exact types, `EXTRA_MIME_TYPES`):

- **One photo**: tap it and it goes back to the app that asked. Hold a photo to look closer; "use" under it sends it. "cancel" at the right of the bar, or back, sends nothing.
- **Several** (`EXTRA_ALLOW_MULTIPLE`): every feed is in choosing: tap photos or drag sideways across them, in the feed, a folder or an album; the bar moves between pages and ends with the count and "use". Hold a photo to look closer; "choose" or "use" under it.

The answer is the usual one: the first photo as `getData()`, all of them in `getClipData()` in the order they were chosen, with read permission granted.

Android's own photo picker outranks every app for a plain `GET_CONTENT` (and apps using the new picker, `PickVisualMedia`, can't be redirected at all), so an app that wants photosJS names it:

```java
Intent i = new Intent(Intent.ACTION_GET_CONTENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("image/*")                                // or "video/*", "*/*"
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)        // leave out for one photo
        .setPackage("com.photosjs");
try {
    startActivityForResult(i, PICK);
} catch (ActivityNotFoundException e) {
    // photosJS isn't installed: the phone's own picker (ACTION_OPEN_DOCUMENT)
}
```

If the app checks first whether photosJS is there, it needs `<queries><package android:name="com.photosjs" /></queries>` in its manifest. lockJS's "choose picture" works this way (a long press opens the phone's file picker instead).

## Build

GitHub Actions builds every push to `main` and publishes `photosJS.apk` as a release. Locally: `bash build.sh` with the Android SDK (`ANDROID_HOME`) and a JDK 17+.

The blocks face is lockJS's 5×5 clock face extended to letters, with straight strokes only: where a letter would need a slant, a gap in a joint tells it apart (k is an h whose bar stops short of the right leg; x has both joints open; z is a 2 whose middle bar floats; v is a u with open corners).

Fonts: Ubuntu Sans Mono (Ubuntu Font Licence, `app/assets/fonts/UFL.txt`); Alagard by Hewett Tsoi / Pix3M ("give credit if used", `app/assets/fonts/Alagard-credit.txt`).
