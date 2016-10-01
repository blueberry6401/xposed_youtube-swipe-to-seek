package com.blueberry.youtubeswipetoseek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.XResources;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;

import static com.blueberry.youtubeswipetoseek.SettingsActivity.*;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LayoutInflated;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedBridge.*;
import static de.robv.android.xposed.XposedHelpers.*;

/**
 * Created by hieptran on 24/05/2016.
 */

public class YoutubeHooker implements IXposedHookLoadPackage, IXposedHookInitPackageResources {
    private static final String[] SUPPORT_YOUTUBE_PACKAGE = new String[] {
            "com.google.android.youtube"
//            "com.google.android.apps.youtube.gaming",
//            "com.google.android.ogyoutube"
    };
    private static final String[] CLASS_APPLICATION = new String[]{
            "com.google.android.apps.youtube.app.YouTubeApplication",
//            "com.google.android.apps.youtube.gaming.application.GamingApplication",
            "com.google.android.apps.ogyoutube.app.YouTubeApplication"
    };
    private static final String[] CLASS_MEDIA_CONTROLLER = new String[] {
            "android.media.session.MediaController",
//            "android.media.session.MediaController",
            "android.media.session.MediaController"
    };
    private static final String[] CLASS_PLAYER_VIEW = new String[]{
            "com.google.android.apps.youtube.app.player.YouTubePlayerView",
//            "com.google.android.apps.youtube.gaming.player.GamingPlayerView",
            "com.google.android.apps.ogyoutube.app.player.YouTubePlayerView"
    };
    private static final String[] CLASS_WATCH_WHILE_LAYOUT = new String[]{
            "com.google.android.apps.youtube.app.ui.WatchWhileLayout",
//            "com.google.android.apps.youtube.gaming.player.GamingPlayerView",
            null
    };
    private static HookDataHolder[] mHookDataHolder = new HookDataHolder[SUPPORT_YOUTUBE_PACKAGE.length];

    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String TAG = "YoutubeHooker";
    private static final String PACKAGE_NAME = YoutubeHooker.class.getPackage().getName();
    private static final int MSG_SEEK = 1;
    private static int EDGE_SIZE;

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

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam initPackageResourcesParam) throws Throwable {
        int pgIndex = findPackageIndex(initPackageResourcesParam.packageName);
        if (pgIndex != -1) {
            hookYoutubeSwipeToSeek(initPackageResourcesParam, pgIndex);
        }
    }

    private static void hookYoutubeSwipeToSeek(XC_LoadPackage.LoadPackageParam loadPackageParam, final int pgIndex) {
        // Holds objects share through classes
        final HookDataHolder hookDataHolder;
        if (mHookDataHolder[pgIndex] == null) {
            hookDataHolder = new HookDataHolder();
            mHookDataHolder[pgIndex] = hookDataHolder;
        } else {
            hookDataHolder = mHookDataHolder[pgIndex];
        }

        hookDataHolder.loadPackageParam = loadPackageParam;
        // hookDataHolder.xResources != null means setTimes method hasn't been hooked
        if (hookDataHolder.xResources != null) hookSetTimesMethod(hookDataHolder.xResources, pgIndex, hookDataHolder);

        // Preferences
        initPrefs(pgIndex);

        // Find PlayerViewMode obfuscated class name
        Class ytPlayerViewCls = findClass(CLASS_PLAYER_VIEW[pgIndex], loadPackageParam.classLoader);
        String viewModeClassName = findPlayerViewModeClassName(ytPlayerViewCls);
        Field viewModeField = null;
        if (viewModeClassName != null && !TextUtils.isEmpty(viewModeClassName)) {
            try {
                final Class viewModeCls = findClass(viewModeClassName, loadPackageParam.classLoader);
                viewModeField = findFirstFieldByExactType(ytPlayerViewCls, viewModeCls);
            } catch (Exception e) {
                log(TAG + ": " + e.getMessage());
            }
        }


        // Hook YouTubeApplication to register settings broadcast listener and to create SwipeDetector
        final Field finalViewModeField = viewModeField;
        findAndHookMethod(CLASS_APPLICATION[pgIndex], loadPackageParam.classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                        Context c = (Context) callMethod(param.thisObject, "getApplicationContext");
                        c.registerReceiver(new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                String action = intent.getAction();
                                if (action.equals(SettingsActivity.ACTION_SETTINGS_CHANGED)) {
                                    if (DEBUG) log(TAG + ": reload settings");

                                    if (intent.hasExtra(PREF_SWIPE_TO_SEEK)) {
                                        hookDataHolder.isSeekingEnabled = intent.getBooleanExtra(PREF_SWIPE_TO_SEEK, false);
                                    } else if (intent.hasExtra(PREF_SWIPE_TO_CHANGE_VOLUME)) {
                                        hookDataHolder.isChangingVolumeEnabled = intent.getBooleanExtra(PREF_SWIPE_TO_CHANGE_VOLUME, false);
                                    }
                                    if (DEBUG) log(String.format("%s: allow seek %s, allow change vol %s", TAG, String.valueOf(hookDataHolder.isSeekingEnabled), String.valueOf(hookDataHolder.isChangingVolumeEnabled)));
                                }
                            }
                        }, new IntentFilter(SettingsActivity.ACTION_SETTINGS_CHANGED));

                        // Create module resources to access resources of module
                        if (mModuleResources == null) {
                            mModuleResources = Utils.getModuleContext(c).getResources();
                            EDGE_SIZE = (int) TypedValue.applyDimension(
                                    TypedValue.COMPLEX_UNIT_DIP, 30f, c.getResources().getDisplayMetrics());
                        }

                        //
                        hookDataHolder.infoToast = Toast.makeText(c, "", Toast.LENGTH_LONG);
                        hookDataHolder.audioManager = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);

                        // Make SwipeDetector to listen touch event
                        SwipeDetector.OnSwipe onYoutubeVideoSwipe = new SwipeDetector.OnSwipe() {
                            PlaybackState currentVideoPlaybackState;
                            SwipeDetector.Direction swipeDirection;
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
                                    hookDataHolder.infoToast.setDuration(Toast.LENGTH_LONG);
                                }
                                if (!hookDataHolder.isSeekingEnabled) return;
                                // isTouchEventDispatched will be true if youtube's view has handled
                                // the touch event
                                // this condition helps us to detect if users are swiping seek bar
                                // UPDATE: no longer works, detect if user swipes from edge instead
//                                if (hookDataHolder.isTouchEventDispatched) return;
                                if (swipeDirection == SwipeDetector.Direction.VERTICAL) return;

                                int secsToSeek = (int) (mm / 1.5);
                                if (DEBUG) {
                                    log(TAG + ": swipe " + mm + ", seek " + secsToSeek);
                                }

                                newPos =  onStartPosition + secsToSeek * 1000;
                                newPos = Math.max(0, newPos);
                                newPos = Math.min(newPos, currentVideoDuration - 500);
                                if (newPos != currentPos) {
                                    Message seekMsg = hookDataHolder.handler.obtainMessage(MSG_SEEK, newPos);
                                    hookDataHolder.handler.sendMessageDelayed(seekMsg, 500);
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
                                hookDataHolder.infoToast.setText(timeinfo);
                                hookDataHolder.infoToast.show();
                            }

                            @Override
                            public void swipeY(int mm) {
                                if (swipeDirection == null) {
                                    swipeDirection = SwipeDetector.Direction.VERTICAL;
                                    hookDataHolder.infoToast.setDuration(Toast.LENGTH_SHORT);
                                }
                                if (!hookDataHolder.isChangingVolumeEnabled) return;
                                if (!hookDataHolder.isFullscreen) return;
                                if (swipeDirection == SwipeDetector.Direction.HORIZONTAL) return;

                                int volumeDelta = -mm / 3; // Invert: up to increase volume, and otherwise
                                if (DEBUG)
                                    log(TAG + ": swipe " + mm + ", volume " + volumeDelta);
                                int newVolume = currentMusicVolume + volumeDelta;
                                newVolume = Math.max(0, newVolume);
                                newVolume = Math.min(newVolume, maxMusicVolume);
                                hookDataHolder.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
                                // Show toast
                                hookDataHolder.infoToast.setText(
                                        mModuleResources.getString(R.string.toast_volume, newVolume));
                                hookDataHolder.infoToast.show();
                            }

                            @Override
                            public boolean onTouchedDown(int x, int y) {
                                // Check if we are playing 360 video
                                // Actually we check if vrButton is visible or not
                                // There are too many ways but this way doesn't touch any obfuscated codes
                                // I'm not sure this method has correct result for all devices
                                if (hookDataHolder.vrButton != null
                                        && hookDataHolder.vrButton.getVisibility() == View.VISIBLE)
                                {
                                    if (DEBUG) log(TAG + ": vr button visible, maybe this is 360 video");
                                    return false;
                                }


                                // Check if player is fullscreen. In fullscreen mode, vertical swiping is disabled
                                try {
                                    // Use PlayerViewMode to detect if player is fullscreen
                                    Object viewModeData = finalViewModeField.get(hookDataHolder.youtubePlayerView);
                                    hookDataHolder.isFullscreen = viewModeData.toString().equals("WATCH_WHILE_FULLSCREEN");
                                    if (DEBUG) log(TAG + ": viewMode field data: " + viewModeData.toString());
                                } catch (Exception e) {
                                    if (DEBUG) log(TAG + ": cannot use PlayerViewMode, detect fullscreen by get PlayerView size");
                                    // Get screen size to know if player is in fullscreen mode
                                    DisplayMetrics displayMetrics = hookDataHolder.youtubePlayerView.getResources().getDisplayMetrics();
                                    int scrHeight = displayMetrics.heightPixels;
                                    hookDataHolder.isFullscreen = hookDataHolder.youtubePlayerView.getHeight() == scrHeight;
                                    if (DEBUG) log(TAG + ": scrHeight=" + scrHeight + ", player view height=" + hookDataHolder.youtubePlayerView.getHeight());
                                }



                                // Bypass if user swipes from edge
                                if (DEBUG) log(TAG + ": touched down, x=" + x + ", y=" + y);
                                return !(
                                        x < EDGE_SIZE
                                        || x > hookDataHolder.youtubePlayerView.getWidth() - EDGE_SIZE
                                        || y < EDGE_SIZE
                                        || y > hookDataHolder.youtubePlayerView.getHeight() - hookDataHolder.seekBarHeight
                                );
                            }

                            @Override
                            public void onSwipeStop() {
                                // Seek immediately
                                if (swipeDirection != null && swipeDirection == SwipeDetector.Direction.HORIZONTAL && hookDataHolder.isSeekingEnabled) {
                                    Message seekMsg = hookDataHolder.handler.obtainMessage(MSG_SEEK, newPos);
                                    hookDataHolder.handler.sendMessage(seekMsg);
                                }

                                hookDataHolder.infoToast.cancel();
                                currentPos = -1;
                                if (DEBUG) log(TAG + "swipe stopped");
                            }

                            @Override
                            public boolean onSwipeStart() {
                                if (hookDataHolder.youtubeMediaController != null) {
                                    MediaMetadata metadata = hookDataHolder.youtubeMediaController.getMetadata();
                                    if (metadata == null) {
                                        currentVideoDuration = Long.MAX_VALUE;
                                    } else {
                                        currentVideoDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                                    }
                                    currentVideoPlaybackState = hookDataHolder.youtubeMediaController.getPlaybackState();
                                    if (currentVideoDuration > 0 && currentVideoPlaybackState != null) {
                                        currentVideoDurationString = millisToTimeString(currentVideoDuration);
                                        // By default we use getPosition() to get more accurate value
                                        onStartPosition = currentVideoPlaybackState.getPosition();
                                        if (hookDataHolder.settimeCurrentPosition >= 0
                                                &&Math.abs(onStartPosition - hookDataHolder.settimeCurrentPosition) > 2000) {
                                            onStartPosition = hookDataHolder.settimeCurrentPosition;
                                            if (DEBUG) log(TAG + ": getPosition not equal setTimesPosition");
                                        }

                                        // Init vars
                                        swipeDirection = null;
                                        //
                                        maxMusicVolume = hookDataHolder.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                        currentMusicVolume = hookDataHolder.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                                        currentPos = -1;
                                        if (DEBUG) {
                                            log(TAG + ": swipe started");
                                            log(String.format("%s: current pos=%d, video duration=%d",
                                                                            TAG, onStartPosition, currentVideoDuration));
                                            log(String.format("%s: fullScr=%s, maxMusicVol=%d, currentMusicVol=%d",
                                                                            TAG, hookDataHolder.isFullscreen, maxMusicVolume, currentMusicVolume));
                                        }
                                        return true;
                                    }
                                }
                                return false;
                            }
                        };
                        hookDataHolder.youtubeVideoSwipeDetector = new SwipeDetector(c.getResources(), onYoutubeVideoSwipe);
                    }
                });

        // Get MediaController
        findAndHookConstructor(CLASS_MEDIA_CONTROLLER[pgIndex], loadPackageParam.classLoader,
                Context.class, "android.media.session.MediaSession$Token", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        hookDataHolder.youtubeMediaController = (MediaController) param.thisObject;

                        if (DEBUG) log(TAG + ": got youtube media controller, null: " + String.valueOf(hookDataHolder.youtubeMediaController == null));
                    }
                });


        hookAllConstructors(ytPlayerViewCls, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                hookDataHolder.youtubePlayerView = (View) param.thisObject;

                hookDataHolder.handler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case MSG_SEEK:
                                if (hasMessages(MSG_SEEK)) removeMessages(MSG_SEEK);
                                hookDataHolder.youtubeMediaController.getTransportControls().seekTo((Long) msg.obj);
                                break;
                        }
                    }
                };
            }
        });
        // Receive touch events
        findAndHookMethod("android.view.ViewGroup", loadPackageParam.classLoader,
                "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!param.thisObject.getClass().getName().equals(CLASS_WATCH_WHILE_LAYOUT[pgIndex])) {
                            if (DEBUG) log(TAG + ": not WatchWhileLayout, " + param.thisObject.getClass().getSimpleName());
                            return;
                        }

                        hookDataHolder.isTouchEventDispatched = (boolean) param.getResult();
                        MotionEvent motionEvent = (MotionEvent) param.args[0];

                        hookDataHolder.youtubeVideoSwipeDetector.onEvent(motionEvent);
                    }
                });
        // Hook to get some needed values
        hookAllConstructors(findClass(CLASS_WATCH_WHILE_LAYOUT[pgIndex], loadPackageParam.classLoader), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Resources r = (Resources) callMethod(param.thisObject, "getResources");
                int seekBarHeightId = r.getIdentifier("controls_overlay_bottom_ui_size", "dimen", SUPPORT_YOUTUBE_PACKAGE[pgIndex]);

                hookDataHolder.seekBarHeight = seekBarHeightId > 0
                            ? r.getDimensionPixelSize(seekBarHeightId)
                            : (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 42f, r.getDisplayMetrics());
                if (DEBUG) log(TAG + ": found seekBarHeight=" + hookDataHolder.seekBarHeight);
            }
        });
    }

    private static void hookYoutubeSwipeToSeek(XC_InitPackageResources.InitPackageResourcesParam resParam, int pgIndex) {
        // Holds objects share through classes
        final HookDataHolder hookDataHolder;
        if (mHookDataHolder[pgIndex] == null) {
            hookDataHolder = new HookDataHolder();
            mHookDataHolder[pgIndex] = hookDataHolder;
        } else {
            hookDataHolder = mHookDataHolder[pgIndex];
        }

        // Hook vr button, detect its visibility to know if we're in 360 video
        try {
            XResources res = resParam.res;
            res.hookLayout(SUPPORT_YOUTUBE_PACKAGE[pgIndex], "layout", "vr_button", new XC_LayoutInflated() {
                @Override
                public void handleLayoutInflated(LayoutInflatedParam layoutInflatedParam) throws Throwable {
                    View v = layoutInflatedParam.view;
                    if (v.getClass().getName().equals("com.google.android.libraries.youtube.common.ui.TouchImageView")) {
                        hookDataHolder.vrButton = v;
                        log(TAG + ": vr button found: vr_button");
                    } else {
                        log(TAG + ": vr button not found");
                    }
                }
            });

        } catch (Exception e) {
            if (DEBUG) log(TAG + ": " + e.getMessage());

            // In old versions, they named modoro_button instead of vr_button
            if (DEBUG) log(TAG + ": vr_button does not exist, continue to found modoro_button");
            try {
                XResources res = resParam.res;
                res.hookLayout(SUPPORT_YOUTUBE_PACKAGE[pgIndex], "layout", "modoro_button", new XC_LayoutInflated() {
                    @Override
                    public void handleLayoutInflated(LayoutInflatedParam layoutInflatedParam) throws Throwable {
                        View v = layoutInflatedParam.view;
                        if (v.getClass().getName().equals("com.google.android.libraries.youtube.common.ui.TouchImageView")) {
                            hookDataHolder.vrButton = v;
                            log(TAG + ": vr button found: modoro_button");
                        } else {
                            log(TAG + ": vr button not found");
                        }
                    }
                });
            } catch (Exception e2) {
                if (DEBUG) log(TAG + ": " + e2.getMessage());
            }
        }


        //
        if (hookDataHolder.loadPackageParam != null) {
            hookSetTimesMethod(resParam.res, pgIndex, hookDataHolder);
        } else {
            // Assign xResources to request hooking after we have loadPackageParam
            hookDataHolder.xResources = resParam.res;
        }
    }

    private static void initPrefs(int pgIndex) {
        if (mPrefs == null) mPrefs = new XSharedPreferences(PACKAGE_NAME);
        mPrefs.makeWorldReadable();
        mPrefs.reload();

        mHookDataHolder[pgIndex].isSeekingEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_SEEK, true);
        mHookDataHolder[pgIndex].isChangingVolumeEnabled = mPrefs.getBoolean(PREF_SWIPE_TO_CHANGE_VOLUME, true);
    }

    private static String millisToTimeString(long millis) {
        long seconds = millis / 1000;
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        long h = seconds / (60 * 60);
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private static String findPlayerViewModeClassName(Class playerViewClass) {
        // In class YouTubePlayerView there is 2 method which has only one parameter with the same type
        // These parameters are PlayerViewMode
        Method[] allMethods = playerViewClass.getDeclaredMethods();

        String name = null;
        for (int i = 0; i < allMethods.length - 1; i++) {
            if (name != null) break;
            Method m = allMethods[i];
            if (m.getParameterTypes().length != 1 || m.getName().toLowerCase().contains("touch")) continue;

            for (int j = i + 1; j < allMethods.length; j++) {
                Method m2 = allMethods[j];
                if (m2.getParameterTypes().length != 1 || m2.getName().toLowerCase().contains("touch")) continue;

                if (m2.getParameterTypes()[0].toString().equals(m.getParameterTypes()[0].toString())) {
                    name = m2.getParameterTypes()[0].getName();
                    if (DEBUG) log(TAG + ": found PlayerViewModeObfuscatedClassName: " + name);
                    break;
                }
            }
        }
        return name;
    }

    /**
     * By default we use playbackState.getPosition(), but there is sometimes it returns wrong value
     * (especially if you have Youtube background playback module installed)
     * I don't have time to dig any further, so this is a little workaround solution
     */
    private static void hookSetTimesMethod(XResources res, int pgIndex, final HookDataHolder dataHolder) {
        dataHolder.settimeCurrentPosition = -1;
        if (dataHolder.loadPackageParam == null) return;

        // Hook method setTimes() in InlineControlsOverlay
        try {
            res.hookLayout(SUPPORT_YOUTUBE_PACKAGE[pgIndex], "layout", "inline_controls_overlay", new XC_LayoutInflated() {
                @Override
                public void handleLayoutInflated(LayoutInflatedParam layoutInflatedParam) throws Throwable {
                    StackTraceElement[] traces = Thread.currentThread().getStackTrace();
                    if (DEBUG) log(TAG + ": " + Arrays.toString(traces));
                    String obfuscatedClsName = "";

                    obfuscatedClsName = findControlOverlayClassName(traces);
                    if (!TextUtils.isEmpty(obfuscatedClsName)) {
                        findAndHookSetTimesMethod(dataHolder, obfuscatedClsName);
                    }
                }
            });
        } catch (Exception e) {
            if (DEBUG) log(e.getMessage());
        }

        // Hook method setTimes() in DefaultControlsOverlay
        try {
            res.hookLayout(SUPPORT_YOUTUBE_PACKAGE[pgIndex], "layout", "default_controls_overlay", new XC_LayoutInflated() {
                @Override
                public void handleLayoutInflated(LayoutInflatedParam layoutInflatedParam) throws Throwable {
                    StackTraceElement[] traces = Thread.currentThread().getStackTrace();
                    if (DEBUG) log(TAG + ": " + Arrays.toString(traces));
                    String obfuscatedClsName = "";

                    obfuscatedClsName = findControlOverlayClassName(traces);
                    if (!TextUtils.isEmpty(obfuscatedClsName)) {
                        findAndHookSetTimesMethod(dataHolder, obfuscatedClsName);
                    }
                }
            });
        } catch (Exception e) {
            if (DEBUG) log(e.getMessage());
        }

        // Hook method setTimes() in YouTubeControlsOverlay
        try {
            res.hookLayout(SUPPORT_YOUTUBE_PACKAGE[pgIndex], "layout", "youtube_controls_overlay", new XC_LayoutInflated() {
                @Override
                public void handleLayoutInflated(LayoutInflatedParam layoutInflatedParam) throws Throwable {
                    StackTraceElement[] traces = Thread.currentThread().getStackTrace();
                    if (DEBUG) log(TAG + ": " + Arrays.toString(traces));
                    String obfuscatedClsName = "";

                    obfuscatedClsName = findControlOverlayClassName(traces);
                    if (!TextUtils.isEmpty(obfuscatedClsName)) {
                        findAndHookSetTimesMethod(dataHolder, obfuscatedClsName);
                    }
                }
            });
        } catch (Exception e) {
            if (DEBUG) log(e.getMessage());
        }
    }

    /**
     * Find class call inflate() in constructor
     */
    private static String findControlOverlayClassName(StackTraceElement[] traces) {
        String obfuscatedClsName = "";

        for (int k = 0; k < traces.length; k++) {
            StackTraceElement trace = traces[k];

            String s = trace.toString();
            if (!s.contains("blueberry") && !s.contains("de.robv")) {
                int i = s.indexOf(".<init>");
                if (i < 0) continue;
                if (k > 0 && traces[k - 1].toString().contains(".inflate")) {
                    obfuscatedClsName = s.substring(0, i);
                }

                // If we reach a constructor but it doesn't invoke inflate(), jump out
                break;
            }
        }
        if (DEBUG && !TextUtils.isEmpty(obfuscatedClsName)) log(TAG + ": found control overlay class name: " + obfuscatedClsName);
        return obfuscatedClsName;
    }

    private static void findAndHookSetTimesMethod(final HookDataHolder dataHolder, String obfuscatedClsName) {
        Class cls = findClass(obfuscatedClsName, dataHolder.loadPackageParam.classLoader);
        // Find setTimes method, this method has 4 long parameters, pretty unique
        Method[] allMethods = cls.getDeclaredMethods();
        Method method = null;
        for (Method m : allMethods) {
            Class[] paramsCls = m.getParameterTypes();
            if (paramsCls.length == 4
                    && paramsCls[0].equals(long.class) && paramsCls[1].equals(long.class)
                    && paramsCls[2].equals(long.class) && paramsCls[3].equals(long.class)) {
                method = m;
                break;
            }
        }

        if (method != null) {
            if (DEBUG) log(TAG + ": found setTimes method: " + method.toString());

            findAndHookMethod(cls, method.getName(), long.class, long.class, long.class, long.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    dataHolder.settimeCurrentPosition = (long) param.args[0];
                }
            });
        }
    }
}
