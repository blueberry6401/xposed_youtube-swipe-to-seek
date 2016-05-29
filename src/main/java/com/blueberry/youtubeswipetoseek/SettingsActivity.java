package com.blueberry.youtubeswipetoseek;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;

/**
 * Created by hieptran on 28/05/2016.
 */
public class SettingsActivity extends PreferenceActivity {
    public static final String ACTION_SETTINGS_CHANGED = "ysts.intent.action.SETTINGS_CHANGED";
    public static final String PREF_SWIPE_TO_SEEK = "pref_swipe_to_seek";
    public static final String PREF_SWIPE_TO_CHANGE_VOLUME = "pref_swipe_to_change_volume";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new MainSettingsFragment())
                .commit();
    }

    public static class MainSettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener{
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesMode(MODE_WORLD_READABLE);
            addPreferencesFromResource(R.xml.pref_main);
        }

        @Override
        public void onStart() {
            super.onStart();
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onStop() {
            super.onStop();
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
            // Send a broadcast to reload settings
            // TODO
            // Idk why if I call XSharedPreferences.reload in the runtime, it won't have any entries
            // http://forum.xda-developers.com/xposed/development-xsharedpreferences-issue-t2931396
            // https://github.com/rovo89/XposedBridge/issues/56
            // New settings are put directly in intent
            getActivity().sendBroadcast(makeSettingsUpdateIntent(sharedPreferences, s));
        }

        private Intent makeSettingsUpdateIntent(SharedPreferences sharedPreferences, String key) {
            Intent i = new Intent(ACTION_SETTINGS_CHANGED);

            if (key.equals(PREF_SWIPE_TO_CHANGE_VOLUME) || key.equals(PREF_SWIPE_TO_SEEK)) {
                i.putExtra(key, sharedPreferences.getBoolean(key, false));
            }
            return i;
        }
    }
}
