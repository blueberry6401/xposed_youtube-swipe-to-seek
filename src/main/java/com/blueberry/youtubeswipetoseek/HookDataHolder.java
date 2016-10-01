package com.blueberry.youtubeswipetoseek;

import android.content.res.XResources;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.os.Handler;
import android.view.View;
import android.widget.Toast;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Created by hieptran on 05/06/2016.
 */
public class HookDataHolder {
    MediaController youtubeMediaController;
    SwipeDetector youtubeVideoSwipeDetector;
    AudioManager audioManager;
    Toast infoToast;
    boolean isTouchEventDispatched;
    boolean isFullscreen;
    View youtubePlayerView;
    // Settings boolean
    boolean isSeekingEnabled, isChangingVolumeEnabled;
    int seekBarHeight;

    Handler handler;
    View vrButton;
    long settimeCurrentPosition;
    XC_LoadPackage.LoadPackageParam loadPackageParam;
    XResources xResources;
}
