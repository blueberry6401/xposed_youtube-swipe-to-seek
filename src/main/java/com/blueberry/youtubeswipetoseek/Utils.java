package com.blueberry.youtubeswipetoseek;

import android.content.Context;
import android.content.pm.PackageManager;

import de.robv.android.xposed.XposedBridge;

/**
 * Created by hieptran on 06/05/2016.
 */
public class Utils {
    private static final String PACKAGE_NAME = Utils.class.getPackage().getName();
    private static Context sModuleContext = null;

    public static synchronized Context getModuleContext(Context c) {
        if (sModuleContext == null) {
            try {
                sModuleContext = c.createPackageContext(PACKAGE_NAME,
                        Context.CONTEXT_IGNORE_SECURITY);
            } catch (PackageManager.NameNotFoundException e) {
                XposedBridge.log(e.getMessage());
            }
        }
        return sModuleContext;
    }
}
