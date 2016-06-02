package com.blueberry.youtubeswipetoseek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import static com.blueberry.youtubeswipetoseek.SettingsActivity.*;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Created by hieptran on 24/05/2016.
 */

public class YoutubeHooker extends BroadcastReceiver implements IXposedHookLoadPackage {
    private final boolean DEBUG = BuildConfig.DEBUG;
    private final String TAG = getClass().getSimpleName();
    private final String PACKAGE_NAME = YoutubeHooker.class.getPackage().getName();

    private MediaController mYoutubeMediaController;
    private SwipeDetector mYoutubeVideoSwipeDetector;
    private AudioManager mAudioManager;
    private Toast mInfoToast;
    private boolean mIsTouchEventDispatched;
    private Resources mModuleResources;
    private XSharedPreferences mPrefs;
    private View mYoutubePlayerView;
    // Settings boolean
    boolean mIsSeekingEnabled, mIsChangingVolumeEnabled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals("com.google.android.youtube")) {
            hookYoutubeSwipeToSeek(loadPackageParam);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action.equals(SettingsActivity.ACTION_SETTINGS_CHANGED)) {
            if (DEBUG) XposedBridge.log(TAG + ": reload settings");

            if (intent.hasExtra(PREF_SWIPE_TO_SEEK)) {
                mIsSeekingEnabled = intent.getBooleanExtra(PREF_SWIPE_TO_SEEK, false);
            } else if (intent.hasExtra(PREF_SWIPE_TO_CHANGE_VOLUME)) {
                mIsChangingVolumeEnabled = intent.getBooleanExtra(PREF_SWIPE_TO_CHANGE_VOLUME, false);
            }
            if (DEBUG) XposedBridge.log(String.format("%s: allow seek %s, allow change vol %s", TAG, String.valueOf(mIsSeekingEnabled), String.valueOf(mIsChangingVolumeEnabled)));
        }
    }

    private void hookYoutubeSwipeToSeek(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // Preferences
        initPrefs();

        // Hook YouTubeApplication to register settings broadcast listener, create SwipeDetector
        XposedHelpers.findAndHookMethod("com.google.android.apps.youtube.app.YouTubeApplication", loadPackageParam.classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context c = (Context) XposedHelpers.callMethod(param.thisObject, "getApplicationContext");
                        c.registerReceiver(YoutubeHooker.this, new IntentFilter(SettingsActivity.ACTION_SETTINGS_CHANGED));

                        // Create module resources to access resources of module
                        if (mModuleResources == null) {
                            mModuleResources = Utils.getModuleContext(c).getResources();
                        }

                        //
                        mInfoToast = Toast.makeText(c, "", Toast.LENGTH_LONG);
                        mAudioManager = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);

                        // Make SwipeDetector to listen touch event
                        SwipeDetector.OnSwipe onYoutubeVideoSwipe = new SwipeDetector.OnSwipe() {
                            PlaybackState currentVideoPlaybackState;
                            SwipeDetector.Direction swipeDirection;
                            boolean isFullscreen;
                            long currentVideoDuration;
                            // Position of video playback when touch
                            long onStartPosition;
                            // Keep the current position of playback when swiping
                            long currentPos;
                            // Cache video's duration in string to show on toast while swiping
                            String currentVideoDurationString;
                            // Volume
                            int maxMusicVolume, currentMusicVolume;

                            @Override
                            public void swipeX(int mm) {
                                if (swipeDirection == null) {
                                    swipeDirection = SwipeDetector.Direction.HORIZONTAL;
                                    mInfoToast.setDuration(Toast.LENGTH_LONG);
                                }
                                if (!mIsSeekingEnabled) return;
                                // mIsTouchEventDispatched will be true if youtube's view has handled
                                // the touch event
                                if (mIsTouchEventDispatched) return;
                                if (swipeDirection == SwipeDetector.Direction.VERTICAL) return;

                                int secsToSeek = (int) (mm / 1.5);
                                if (DEBUG) {
                                    XposedBridge.log(TAG + ": swipe " + mm + ", seek " + secsToSeek);
                                }

                                long newPos = onStartPosition + secsToSeek * 1000;
                                newPos = Math.max(0, newPos);
                                newPos = Math.min(newPos, currentVideoDuration - 500);
                                if (newPos != currentPos) {
                                    mYoutubeMediaController.getTransportControls().seekTo(newPos);
                                    currentPos = newPos;
                                }
                                // Recalculate after modify newPost
                                secsToSeek = (int) ((newPos - onStartPosition) / 1000);

                                // Show toast
                                String timeinfo = String.format("[%c%ds] (%d%%)\n(%s / %s)",
                                        secsToSeek < 0 ? '–' : '+',
                                        Math.abs(secsToSeek),
                                        (int) ((double) newPos / currentVideoDuration * 100),
                                        millisToTimeString(newPos),
                                        currentVideoDurationString);
                                mInfoToast.setText(timeinfo);
                                mInfoToast.show();
                            }

                            @Override
                            public void swipeY(int mm) {
                                if (swipeDirection == null) {
                                    swipeDirection = SwipeDetector.Direction.VERTICAL;
                                    mInfoToast.setDuration(Toast.LENGTH_SHORT);
                                }
                                if (!mIsChangingVolumeEnabled) return;
                                if (!isFullscreen) return;
                                if (swipeDirection == SwipeDetector.Direction.HORIZONTAL) return;

                                int volumeDelta = -mm / 3; // Invert: up to increase volume, and otherwise
                                if (DEBUG)
                                    XposedBridge.log(TAG + ": swipe " + mm + ", volume " + volumeDelta);
                                int newVolume = currentMusicVolume + volumeDelta;
                                newVolume = Math.max(0, newVolume);
                                newVolume = Math.min(newVolume, maxMusicVolume);
                                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
                                // Show toast
                                mInfoToast.setText(
                                        mModuleResources.getString(R.string.toast_volume, newVolume));
                                mInfoToast.show();
                            }

                            @Override
                            public void onSwipeStop() {
                                mInfoToast.cancel();
                                currentPos = -1;
                                if (DEBUG) XposedBridge.log(TAG + "swipe stopped");
                            }

                            @Override
                            public boolean onSwipeStart() {
                                if (mYoutubeMediaController != null) {
                                    MediaMetadata metadata = mYoutubeMediaController.getMetadata();
                                    if (metadata == null) {
                                        currentVideoDuration = Long.MAX_VALUE;
                                    } else {
                                        currentVideoDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                                    }
                                    currentVideoPlaybackState = mYoutubeMediaController.getPlaybackState();
                                    if (currentVideoDuration > 0 && currentVideoPlaybackState != null) {
                                        currentVideoDurationString = millisToTimeString(currentVideoDuration);
                                        onStartPosition = currentVideoPlaybackState.getPosition();

                                        // Init vars
                                        swipeDirection = null;
                                        // Get screen size to know if player is in fullscreen mode
                                        DisplayMetrics displayMetrics = mYoutubePlayerView.getResources().getDisplayMetrics();
                                        int scrHeight = displayMetrics.heightPixels;
                                        isFullscreen = mYoutubePlayerView.getHeight() == scrHeight;
                                        if (DEBUG) XposedBridge.log(TAG + "scrHeight=" + scrHeight + ", player view height=" + mYoutubePlayerView.getHeight());
                                        //
                                        maxMusicVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                        currentMusicVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                                        currentPos = -1;
                                        if (DEBUG) {
                                            XposedBridge.log(TAG + ": swipe started");
                                            XposedBridge.log(String.format("%s: fullScr=%s, maxMusicVol=%d, currentMusicVol=%d",
                                                                            TAG, isFullscreen, maxMusicVolume, currentMusicVolume));
                                        }
                                        return true;
                                    }
                                }
                                return false;
                            }
                        };
                        mYoutubeVideoSwipeDetector = new SwipeDetector(c.getResources(), onYoutubeVideoSwipe);
                    }
                });

        // Get MediaController
        XposedHelpers.findAndHookConstructor("android.media.session.MediaController", loadPackageParam.classLoader,
                Context.class, "android.media.session.MediaSession$Token", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        mYoutubeMediaController = (MediaController) param.thisObject;

                        if (DEBUG) XposedBridge.log(TAG + ": got youtube media controller, null: " + String.valueOf(mYoutubeMediaController == null));
                    }
                });


        Class ytPlayerViewCls = XposedHelpers.findClass("com.google.android.apps.youtube.app.player.YouTubePlayerView", loadPackageParam.classLoader);
        XposedBridge.hookAllConstructors(ytPlayerViewCls, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mYoutubePlayerView = (View) param.thisObject;
            }
        });
        // Receive touch events
        XposedHelpers.findAndHookMethod(ytPlayerViewCls,
                "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        mIsTouchEventDispatched = (boolean) param.getResult();
                        MotionEvent motionEvent = (MotionEvent) param.args[0];
                        mYoutubeVideoSwipeDetector.onEvent(motionEvent);
                    }
                });

        // We handle ACTION_DOWN by hook into onInterceptTouchEvent, because we counldn't receive it
        // in dispatchTouchEvent!
//        XposedHelpers.findAndHookMethod("com.google.android.apps.youtube.app.player.YouTubePlayerView", loadPackageParam.classLoader,
//                "onInterceptTouchEvent", MotionEvent.class,
//                new XC_MethodHook() {
//                    @Override
//                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
//                        if (Boolean.parseBoolean(param.getResult().toString())) {
//                            return;
//                        }
//
//                        MotionEvent motionEvent = (MotionEvent) param.args[0];
//                        if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
//                            mYoutubeVideoSwipeDetector.onEvent(motionEvent);
//                        }
//                    }
//                });
    }

    private void initPrefs() {
        if (mPrefs == null) mPrefs = new XSharedPreferences(PACKAGE_NAME);
        mPrefs.makeWorldReadable();
        mPrefs.reload();

        mIsSeekingEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_SEEK, true);
        mIsChangingVolumeEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_CHANGE_VOLUME, true);
    }

    private String millisToTimeString(long millis) {
        long seconds = millis / 1000;
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        long h = (seconds / (60 * 60)) % 24;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
