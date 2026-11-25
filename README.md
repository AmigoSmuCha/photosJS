# photosJS

A gallery for the Unihertz Jelly Star: a warm, quiet room where the photos are the only thing that's lit.

Pinterest first:

- **The feed**: whole photos in their true shape, packed into equal-width columns, rounded, with air between them. Pinch for more or fewer columns (1, 2, 3, 4, 5, 6, 8); the photo under your fingers stays under your fingers. At many columns it becomes an overview of squares (settings › pinterest view sets where).
- **Boards**: every device folder as a board, a collage of its newest photos with its name and count. Pinch for bigger or smaller boards; tap one for its feed.
- **Swipe sideways** between photos, boards, albums (once there is one) and settings; the pages follow the finger. Or tap the glyphs in the bar.
- **Hold a photo**: a fan of actions blooms around your thumb; slide onto one and let go (the top line says which). Set what's in the fan in settings › long press: album, select, wallpaper (home screen or lockJS), edit, share, set aside, trash, info.
- **Select**: "select" in the fan, or tap the feed with two fingers. Tap photos, or drag sideways across them to select a run; drag near the top or bottom edge to keep going. Then: album, share, aside, trash; "done" (or two fingers again) to finish.
- **Albums**: collections that move or copy nothing; one photo can be in many. Hold an album's board to rename or delete it (the photos stay).
- **Set aside** photos are kept out of photosJS only (other apps still see them); settings › set aside to bring them back. **Trash** is Android's own (restorable 30 days, gone from every app); settings › trash without asking skips Android's confirm.
- Undo: messages that say "tap to undo" in the top line can be tapped.
- **Fast scroll**: drag the thin bar at the right edge.
- **Settings** (the last page): 16 themes, text size, headings (plain, lockJS blocks, Alagard or spaced caps), animations, corners, spacing, Pinterest view, dates (and months as numbers), videos, sorting for every kind of view, board sizes. Every row: tap the right of the value to step forward, the left to step back.
- **The viewer**: tap a photo and it grows out of its place. Swipe sideways for the next one, down to put it back, pinch or double-tap to zoom, tap for the controls (album, edit, share, hide, trash, info). Hold for the fan.
- **The editor** (edit in the fan or the viewer): *crop* — drag the corners or edges (the touch zones are much bigger than the marks), free or square; *draw* — one finger draws in the theme's 16 colours, two fingers zoom and move without marking, the brush keeps its size on screen. Undo every step. Saves as a copy next to the original (settings › edits save as: over the original, which is then kept inside photosJS).
- Videos open in VLC (in the viewer, a still frame: tap it).

## Build

GitHub Actions builds every push to `main` and publishes `photosJS.apk` as a release. Locally: `bash build.sh` with the Android SDK (`ANDROID_HOME`) and a JDK 17+.

Fonts: Ubuntu Sans Mono (Ubuntu Font Licence, `app/assets/fonts/UFL.txt`); Alagard by Hewett Tsoi / Pix3M ("give credit if used", `app/assets/fonts/Alagard-credit.txt`).
