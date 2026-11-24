# photosJS

A gallery for the Unihertz Jelly Star: a warm, quiet room where the photos are the only thing that's lit.

Pinterest first:

- **The feed**: whole photos in their true shape, packed into equal-width columns, rounded, with air between them. Pinch for more or fewer columns (1, 2, 3, 4, 6, 8); the photo under your fingers stays under your fingers. At many columns it becomes an overview of squares (settings › pinterest view sets where).
- **Boards**: every device folder as a board, a collage of its newest photos with its name and count. Pinch for bigger or smaller boards; tap one for its feed.
- **Swipe sideways** between photos, boards and albums; the pages follow the finger. Or tap the glyphs in the bar.
- **Fast scroll**: drag the thin bar at the right edge.
- **Settings** (the three bars, bottom right): theme, text size, animations (full / calm / off), corners (rounded / square), spacing (airy / tight), dates (auto / by day / by month / by year / off), sort and order (remembered per view), videos (hidden = only photos), folder sizes, columns for new views, Pinterest view. Every row: tap the right of the value to step forward, the left to step back.
- Videos open in VLC.

Not yet: the viewer, selecting, the long-press fan, albums, the editor.

## Build

GitHub Actions builds every push to `main` and publishes `photosJS.apk` as a release. Locally: `bash build.sh` with the Android SDK (`ANDROID_HOME`) and a JDK 17+.

Font: Ubuntu Sans Mono (Ubuntu Font Licence, `app/assets/fonts/UFL.txt`).
