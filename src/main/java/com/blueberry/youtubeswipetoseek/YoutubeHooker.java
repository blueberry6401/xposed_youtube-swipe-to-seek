package com.blueberry.youtubeswipetoseek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
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

public class YoutubeHooker implements IXposedHookLoadPackage {
    private static final String[] SUPPORT_YOUTUBE_PACKAGE = new String[] {
            "com.google.android.youtube",
            "com.google.android.ogyoutube"
    };
    private static final String[] CLASS_APPLICATION = new String[]{
            "com.google.android.apps.youtube.app.YouTubeApplication",
            "com.google.android.apps.ogyoutube.app.YouTubeApplication"
    };
    private static final String[] CLASS_MEDIA_CONTROLLER = new String[] {
        "android.media.session.MediaController",
                "android.media.session.MediaController"
    };
    private static final String[] CLASS_PLAYER_VIEW = new String[]{
            "com.google.android.apps.youtube.app.player.YouTubePlayerView",
            "com.google.android.apps.ogyoutube.app.player.YouTubePlayerView"
    };
    private static HookDataHolder[] mHookDataHolder = new HookDataHolder[SUPPORT_YOUTUBE_PACKAGE.length];

    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String TAG = "YoutubeHooker";
    private static final String PACKAGE_NAME = YoutubeHooker.class.getPackage().getName();
    private static final int MSG_SEEK = 1;

    private static Resources mModuleResources;
    private static XSharedPreferences mPrefs;

    private static int findPackageIndex(String pgName) {
        for (int i = 0; i < SUPPORT_YOUTUBE_PACKAGE.length; i++) {
            if (SUPPORT_YOUTUBE_PACKAGE[i].equals(pgName)) return i;
        }
        return -1;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        int pgIndex = findPackageIndex(loadPackageParam.packageName);
        if (pgIndex != -1) {
            hookYoutubeSwipeToSeek(loadPackageParam, pgIndex);
        }
    }

    private static void hookYoutubeSwipeToSeek(XC_LoadPackage.LoadPackageParam loadPackageParam, int pgIndex) {
        // Holds objects share through classes
        final HookDataHolder hookDataHolder = new HookDataHolder();
        mHookDataHolder[pgIndex] = hookDataHolder;

        // Preferences
        initPrefs(pgIndex);

        // Hook YouTubeApplication to register settings broadcast listener, create SwipeDetector
        XposedHelpers.findAndHookMethod(CLASS_APPLICATION[pgIndex], loadPackageParam.classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context c = (Context) XposedHelpers.callMethod(param.thisObject, "getApplicationContext");
                        c.registerReceiver(new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                String action = intent.getAction();
                                if (action.equals(SettingsActivity.ACTION_SETTINGS_CHANGED)) {
                                    if (DEBUG) XposedBridge.log(TAG + ": reload settings");

                                    if (intent.hasExtra(PREF_SWIPE_TO_SEEK)) {
                                        hookDataHolder.mIsSeekingEnabled = intent.getBooleanExtra(PREF_SWIPE_TO_SEEK, false);
                                    } else if (intent.hasExtra(PREF_SWIPE_TO_CHANGE_VOLUME)) {
                                        hookDataHolder.mIsChangingVolumeEnabled = intent.getBooleanExtra(PREF_SWIPE_TO_CHANGE_VOLUME, false);
                                    }
                                    if (DEBUG) XposedBridge.log(String.format("%s: allow seek %s, allow change vol %s", TAG, String.valueOf(hookDataHolder.mIsSeekingEnabled), String.valueOf(hookDataHolder.mIsChangingVolumeEnabled)));
                                }
                            }
                        }, new IntentFilter(SettingsActivity.ACTION_SETTINGS_CHANGED));

                        // Create module resources to access resources of module
                        if (mModuleResources == null) {
                            mModuleResources = Utils.getModuleContext(c).getResources();
                        }

                        //
                        hookDataHolder.mInfoToast = Toast.makeText(c, "", Toast.LENGTH_LONG);
                        hookDataHolder.mAudioManager = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);

                        // Make SwipeDetector to listen touch event
                        SwipeDetector.OnSwipe onYoutubeVideoSwipe = new SwipeDetector.OnSwipe() {
                            PlaybackState currentVideoPlaybackState;
                            SwipeDetector.Direction swipeDirection;
                            boolean isFullscreen;
                            long currentVideoDuration;
                            // Position of video playback when touch
                            long onStartPosition;
                            // Keep the current position of playback when swiping
                            long currentPos, newPos;
                            // Cache video's duration in string to show on toast while swiping
                            String currentVideoDurationString;
                            // Volume
                            int maxMusicVolume, currentMusicVolume;

                            @Override
                            public void swipeX(int mm) {
                                if (swipeDirection == null) {
                                    swipeDirection = SwipeDetector.Direction.HORIZONTAL;
                                    hookDataHolder.mInfoToast.setDuration(Toast.LENGTH_LONG);
                                }
                                if (!hookDataHolder.mIsSeekingEnabled) return;
                                // mIsTouchEventDispatched will be true if youtube's view has handled
                                // the touch event
                                if (hookDataHolder.mIsTouchEventDispatched) return;
                                if (swipeDirection == SwipeDetector.Direction.VERTICAL) return;

                                int secsToSeek = (int) (mm / 1.5);
                                if (DEBUG) {
                                    XposedBridge.log(TAG + ": swipe " + mm + ", seek " + secsToSeek);
                                }

                                newPos =  onStartPosition + secsToSeek * 1000;
                                newPos = Math.max(0, newPos);
                                newPos = Math.min(newPos, currentVideoDuration - 500);
                                if (newPos != currentPos) {
                                    Message seekMsg = hookDataHolder.mHandler.obtainMessage(MSG_SEEK, newPos);
                                    hookDataHolder.mHandler.sendMessageDelayed(seekMsg, 500);
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
                                hookDataHolder.mInfoToast.setText(timeinfo);
                                hookDataHolder.mInfoToast.show();
                            }

                            @Override
                            public void swipeY(int mm) {
                                if (swipeDirection == null) {
                                    swipeDirection = SwipeDetector.Direction.VERTICAL;
                                    hookDataHolder.mInfoToast.setDuration(Toast.LENGTH_SHORT);
                                }
                                if (!hookDataHolder.mIsChangingVolumeEnabled) return;
                                if (!isFullscreen) return;
                                if (swipeDirection == SwipeDetector.Direction.HORIZONTAL) return;

                                int volumeDelta = -mm / 3; // Invert: up to increase volume, and otherwise
                                if (DEBUG)
                                    XposedBridge.log(TAG + ": swipe " + mm + ", volume " + volumeDelta);
                                int newVolume = currentMusicVolume + volumeDelta;
                                newVolume = Math.max(0, newVolume);
                                newVolume = Math.min(newVolume, maxMusicVolume);
                                hookDataHolder.mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
                                // Show toast
                                hookDataHolder.mInfoToast.setText(
                                        mModuleResources.getString(R.string.toast_volume, newVolume));
                                hookDataHolder.mInfoToast.show();
                            }

                            @Override
                            public void onSwipeStop() {
                                // Seek immediately
                                if (swipeDirection != null && swipeDirection == SwipeDetector.Direction.HORIZONTAL) {
                                    Message seekMsg = hookDataHolder.mHandler.obtainMessage(MSG_SEEK, newPos);
                                    hookDataHolder.mHandler.sendMessage(seekMsg);
                                }

                                hookDataHolder.mInfoToast.cancel();
                                currentPos = -1;
                                if (DEBUG) XposedBridge.log(TAG + "swipe stopped");
                            }

                            @Override
                            public boolean onSwipeStart() {
                                if (hookDataHolder.mYoutubeMediaController != null) {
                                    MediaMetadata metadata = hookDataHolder.mYoutubeMediaController.getMetadata();
                                    if (metadata == null) {
                                        currentVideoDuration = Long.MAX_VALUE;
                                    } else {
                                        currentVideoDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                                    }
                                    currentVideoPlaybackState = hookDataHolder.mYoutubeMediaController.getPlaybackState();
                                    if (currentVideoDuration > 0 && currentVideoPlaybackState != null) {
                                        currentVideoDurationString = millisToTimeString(currentVideoDuration);
                                        onStartPosition = currentVideoPlaybackState.getPosition();

                                        // Init vars
                                        swipeDirection = null;
                                        // Get screen size to know if player is in fullscreen mode
                                        DisplayMetrics displayMetrics = hookDataHolder.mYoutubePlayerView.getResources().getDisplayMetrics();
                                        int scrHeight = displayMetrics.heightPixels;
                                        isFullscreen = hookDataHolder.mYoutubePlayerView.getHeight() == scrHeight;
                                        if (DEBUG) XposedBridge.log(TAG + "scrHeight=" + scrHeight + ", player view height=" + hookDataHolder.mYoutubePlayerView.getHeight());
                                        //
                                        maxMusicVolume = hookDataHolder.mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                        currentMusicVolume = hookDataHolder.mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
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
                        hookDataHolder.mYoutubeVideoSwipeDetector = new SwipeDetector(c.getResources(), onYoutubeVideoSwipe);
                    }
                });

        // Get MediaController
        XposedHelpers.findAndHookConstructor(CLASS_MEDIA_CONTROLLER[pgIndex], loadPackageParam.classLoader,
                Context.class, "android.media.session.MediaSession$Token", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        hookDataHolder.mYoutubeMediaController = (MediaController) param.thisObject;

                        if (DEBUG) XposedBridge.log(TAG + ": got youtube media controller, null: " + String.valueOf(hookDataHolder.mYoutubeMediaController == null));
                    }
                });


        Class ytPlayerViewCls = XposedHelpers.findClass(CLASS_PLAYER_VIEW[pgIndex], loadPackageParam.classLoader);
        XposedBridge.hookAllConstructors(ytPlayerViewCls, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                hookDataHolder.mYoutubePlayerView = (View) param.thisObject;

                hookDataHolder.mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case MSG_SEEK:
                                if (hasMessages(MSG_SEEK)) removeMessages(MSG_SEEK);
                                hookDataHolder.mYoutubeMediaController.getTransportControls().seekTo((Long) msg.obj);
                                break;
                        }
                    }
                };
            }
        });
        // Receive touch events
        XposedHelpers.findAndHookMethod(ytPlayerViewCls,
                "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        hookDataHolder.mIsTouchEventDispatched = (boolean) param.getResult();
                        MotionEvent motionEvent = (MotionEvent) param.args[0];
                        hookDataHolder.mYoutubeVideoSwipeDetector.onEvent(motionEvent);
                    }
                });
    }

    private static void initPrefs(int pgIndex) {
        if (mPrefs == null) mPrefs = new XSharedPreferences(PACKAGE_NAME);
        mPrefs.makeWorldReadable();
        mPrefs.reload();

        mHookDataHolder[pgIndex].mIsSeekingEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_SEEK, true);
        mHookDataHolder[pgIndex].mIsChangingVolumeEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_CHANGE_VOLUME, true);
    }

    private static String millisToTimeString(long millis) {
        long seconds = millis / 1000;
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        long h = (seconds / (60 * 60)) % 24;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
