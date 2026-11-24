# photosJS

A gallery for the Unihertz Jelly Star: a warm, quiet room where the photos are the only thing that's lit.

This first build is the grid:

- **Pinch** to change the grid: 1, 2, 3, 4, 6 or 8 columns. The photo under your fingers stays under your fingers.
- At few columns the grid becomes the **Pinterest view**: whole photos in their true shape, same width, packed into columns. Settings › pinterest view sets up to how many columns (0 = off).
- Date labels: by day, by month at 4–6 columns, by year at 8.
- **Fast scroll**: drag the thin bar at the right edge.
- **Folders**: every device folder with its cover, count and (optionally) size; open one for the same grid.
- **Settings** (the three bars, bottom right): theme, sort and order (remembered per view), videos, folder sizes, columns for new views, Pinterest view. Every row: tap the right of the value to step forward, the left to step back.
- Videos open in VLC.

Not yet: the viewer, selecting, the long-press fan, albums, the editor.

## Build

GitHub Actions builds every push to `main` and publishes `photosJS.apk` as a release. Locally: `bash build.sh` with the Android SDK (`ANDROID_HOME`) and a JDK 17+.

Font: Ubuntu Sans Mono (Ubuntu Font Licence, `app/assets/fonts/UFL.txt`).
