package com.blueberry.youtubeswipetoseek;

import android.media.AudioManager;
import android.media.session.MediaController;
import android.os.Handler;
import android.view.View;
import android.widget.Toast;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Created by hieptran on 05/06/2016.
 */
public class HookDataHolder {
    MediaController mYoutubeMediaController;
    SwipeDetector mYoutubeVideoSwipeDetector;
    AudioManager mAudioManager;
    Toast mInfoToast;
    boolean mIsTouchEventDispatched;
    View mYoutubePlayerView;
    // Settings boolean
    boolean mIsSeekingEnabled, mIsChangingVolumeEnabled;
    Handler mHandler;
}
