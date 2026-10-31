package com.deshipay.gateway.db;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

public class PreferencesManager {
    private static final String PREF_NAME = "deshipay_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_DEVICE_TOKEN = "device_token";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_SERVICE_ACTIVE = "service_active";
    private static final String KEY_LAST_SYNC_TIME = "last_sync_time";
    private static final String KEY_LAST_SYNC_STATUS = "last_sync_status";

    public static final String DEFAULT_SERVER_URL = "https://api.yourgateway.com";
    public static final String DEFAULT_DEVICE_TOKEN = "";

    private final SharedPreferences prefs;

    public PreferencesManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public void setServerUrl(String url) {
        if (url != null) {
            url = url.trim();
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getDeviceToken() {
        return prefs.getString(KEY_DEVICE_TOKEN, DEFAULT_DEVICE_TOKEN);
    }

    public void setDeviceToken(String token) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token != null ? token.trim() : "").apply();
    }

    public boolean isPaired() {
        String token = getDeviceToken();
        return token != null && !token.trim().isEmpty();
    }

    public String getDeviceName() {
        return prefs.getString(KEY_DEVICE_NAME, Build.MANUFACTURER + " " + Build.MODEL);
    }

    public void setDeviceName(String name) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
    }

    public boolean isServiceActive() {
        return prefs.getBoolean(KEY_SERVICE_ACTIVE, true);
    }

    public void setServiceActive(boolean active) {
        prefs.edit().putBoolean(KEY_SERVICE_ACTIVE, active).apply();
    }

    public long getLastSyncTime() {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0);
    }

    public void setLastSyncTime(long time) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply();
    }

    public String getLastSyncStatus() {
        return prefs.getString(KEY_LAST_SYNC_STATUS, "Ready");
    }

    public void setLastSyncStatus(String status) {
        prefs.edit().putString(KEY_LAST_SYNC_STATUS, status).apply();
    }
}
