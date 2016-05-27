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
import android.util.Log;
import android.view.MotionEvent;
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals("com.google.android.youtube")) {
            hookYoutubeSwipeToSeek(loadPackageParam);
        }
    }

    private void initPrefs(Context c) {
        mPrefs = new XSharedPreferences(PACKAGE_NAME);
        mPrefs.reload();

        c.registerReceiver(this, new IntentFilter(SettingsActivity.ACTION_SETTINGS_CHANGED));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action.equals(SettingsActivity.ACTION_SETTINGS_CHANGED)) {
            if (DEBUG) XposedBridge.log(TAG + ": reload settings");
            if (mPrefs != null) mPrefs.reload();
        }
    }

    private void hookYoutubeSwipeToSeek(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // Get MediaController
        XposedHelpers.findAndHookConstructor("android.media.session.MediaController", loadPackageParam.classLoader,
                Context.class, "android.media.session.MediaSession$Token", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        mYoutubeMediaController = (MediaController) param.thisObject;
                        Context context = (Context) param.args[0];
                        if (mModuleResources == null) {
                            mModuleResources = Utils.getModuleContext(context).getResources();
                        }
                        initPrefs(context);

                        if (DEBUG) XposedBridge.log(TAG + ": got youtube media controller, null: " + String.valueOf(mYoutubeMediaController == null));
                    }
                });

        XposedBridge.hookAllConstructors(XposedHelpers.findClass("com.google.android.apps.youtube.app.player.YouTubePlayerView", loadPackageParam.classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Context c = (Context) param.args[0];
                        mInfoToast = Toast.makeText(c, "", Toast.LENGTH_LONG);
                        mAudioManager = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);

                        mYoutubeVideoSwipeDetector = new SwipeDetector(c.getResources(), new SwipeDetector.OnSwipe() {
                            PlaybackState currentVideoPlaybackState;
                            SwipeDetector.Direction swipeDirection;
                            long currentVideoDuration;
                            // Position of video playback when touch
                            long onStartPosition;
                            // Keep the current position of playback when swiping
                            long currentPos;
                            // Cache video's duration in string to show on toast while swiping
                            String currentVideoDurationString;
                            // Volume
                            int maxMusicVolume, currentMusicVolume;
                            // Settings boolean
                            boolean isSeekingEnabled, isChangingVolumeEnabled;

                            @Override
                            public void swipeX(int mm) {
                                if (swipeDirection == null) {
                                    swipeDirection = SwipeDetector.Direction.HORIZONTAL;
                                    mInfoToast.setDuration(Toast.LENGTH_LONG);
                                }
                                if (swipeDirection == SwipeDetector.Direction.VERTICAL) return;
                                if (!isSeekingEnabled) return;
                                // mIsTouchEventDispatched will be true if youtube's view has handled
                                // the touch event
                                if (mIsTouchEventDispatched) return;

                                int secsToSeek = (int) (mm / 1.5);
                                if (DEBUG) XposedBridge.log(TAG + ": swipe " + mm + ", seek " + secsToSeek);

                                long newPos = onStartPosition + secsToSeek * 1000;
                                newPos = Math.max(0, newPos); newPos = Math.min(newPos, currentVideoDuration - 500);
                                if (newPos != currentPos) {
                                    mYoutubeMediaController.getTransportControls().seekTo(newPos);
                                    currentPos = newPos;
                                }

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
                                if (!isChangingVolumeEnabled) return;
                                if (swipeDirection == SwipeDetector.Direction.HORIZONTAL) return;

                                int volumeDelta = -mm / 3; // Invert: up to increase volume, and otherwise
                                if (DEBUG) XposedBridge.log(TAG + ": swipe " + mm + ", volume " + volumeDelta);
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
                                Log.d(TAG, "swipe stopped");
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
                                        isSeekingEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_SEEK, true);
                                        isChangingVolumeEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_CHANGE_VOLUME, true);
                                        maxMusicVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                        currentMusicVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                                        currentPos = -1;
                                        Log.d(TAG, "swipe started");
                                        return true;
                                    }
                                }
                                return false;
                            }
                        });
                    }
                });
        // Receive touch events
        XposedHelpers.findAndHookMethod("com.google.android.apps.youtube.app.player.YouTubePlayerView", loadPackageParam.classLoader,
                "dispatchTouchEvent", MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        mIsTouchEventDispatched = (boolean) param.getResult();

                        MotionEvent motionEvent = (MotionEvent) param.args[0];
//                        if (motionEvent.getAction() != MotionEvent.ACTION_DOWN) {
                            mYoutubeVideoSwipeDetector.onEvent(motionEvent);
//                        }
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

    private String millisToTimeString(long millis) {
        long seconds = millis / 1000;
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        long h = (seconds / (60 * 60)) % 24;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
