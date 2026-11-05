package com.omma.messenger.ghost;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * One-shot flags used by the ghost mode filter in ConnectionsManager.
 * Ported from AyuGram's AyuState: when the user explicitly wants a single
 * read packet to go through (e.g. "mark as read" menu action), the feature
 * that triggered it sets this flag for the next request only.
 */
public final class OmmaGhostState {

    private static final String PREFS_NAME = "omma_ghost_state";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean getAllowReadPacket(int type) {
        return prefs().getBoolean("allowRead_" + type, false);
    }

    public static void setAllowReadPacket(boolean value, int type) {
        prefs().edit().putBoolean("allowRead_" + type, value).apply();
    }
}
