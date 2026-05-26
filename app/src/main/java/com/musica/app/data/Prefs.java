package com.musica.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the remote-server connection settings (base URL + pre-shared key).
 * Backed by a private SharedPreferences file; this is the single place that
 * reads/writes those two values.
 */
public final class Prefs {

    private static final String FILE = "echoes_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_API_KEY = "api_key";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String serverUrl() {
        return sp.getString(KEY_SERVER_URL, "");
    }

    public String apiKey() {
        return sp.getString(KEY_API_KEY, "");
    }

    public void save(String serverUrl, String apiKey) {
        sp.edit()
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_API_KEY, apiKey)
                .apply();
    }
}
