package com.photosjs;

/**
 * One photo or video for another app (ACTION_VIEW, named with setPackage):
 * cameraJS's last shot. The viewer grows it from the sender's thumbnail
 * (Intent.getSourceBounds) over a see-through window, so the camera stays
 * live behind it; closing it (or the back key) flies it back and finishes,
 * which lands in the camera again. All of it lives in MainActivity.
 */
public class ViewActivity extends MainActivity {
}
