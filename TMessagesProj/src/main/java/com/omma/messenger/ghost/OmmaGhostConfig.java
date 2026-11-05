package com.omma.messenger.ghost;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** Ghost mode settings, kept in their own SharedPreferences. */
public final class OmmaGhostConfig {

    private static final String PREFS_NAME = "omma_ghost";

    public static boolean hideReadReceipts;
    public static boolean hideOnlineStatus;
    public static boolean hideTyping;
    public static boolean hideStoryViews;
    public static boolean markReadAfterSend;

    static {
        load();
    }

    private OmmaGhostConfig() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void load() {
        SharedPreferences p = prefs();
        hideReadReceipts = p.getBoolean("hideReadReceipts", false);
        hideOnlineStatus = p.getBoolean("hideOnlineStatus", false);
        hideTyping = p.getBoolean("hideTyping", false);
        hideStoryViews = p.getBoolean("hideStoryViews", false);
        markReadAfterSend = p.getBoolean("markReadAfterSend", false);
    }

    public static void save() {
        prefs().edit()
            .putBoolean("hideReadReceipts", hideReadReceipts)
            .putBoolean("hideOnlineStatus", hideOnlineStatus)
            .putBoolean("hideTyping", hideTyping)
            .putBoolean("hideStoryViews", hideStoryViews)
            .putBoolean("markReadAfterSend", markReadAfterSend)
            .apply();
    }

    public static boolean isGhostModeActive() {
        return hideReadReceipts || hideOnlineStatus || hideTyping || hideStoryViews;
    }
}
