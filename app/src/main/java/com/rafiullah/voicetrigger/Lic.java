package com.rafiullah.voicetrigger;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.rafiullah.common.LicenseCore;

final class Lic {
    static final long TRIAL_MS = 3L * 24 * 60 * 60 * 1000; // 3 days

    private Lic() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("vtr", Context.MODE_PRIVATE);
    }

    static String deviceId(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "unknown" : id;
    }

    /** Current time, never going backwards (blocks simple clock rollback). */
    static long now(Context c) {
        long last = p(c).getLong("last_seen", 0);
        long t = Math.max(System.currentTimeMillis(), last);
        p(c).edit().putLong("last_seen", t).apply();
        return t;
    }

    static boolean initDone(Context c) {
        return p(c).contains("first_run");
    }

    /** Starts the one-time trial (or restores the original start after a reinstall). */
    static void init(Context c) {
        if (!initDone(c)) {
            long found = TrialGuard.find(c);
            long first = found > 0 ? found : now(c);
            p(c).edit().putLong("first_run", first).apply();
        }
        TrialGuard.ensure(c, p(c).getLong("first_run", now(c)));
    }

    static boolean licensed(Context c) {
        return LicenseCore.verify(deviceId(c), p(c).getString("key", ""));
    }

    static boolean activate(Context c, String key) {
        if (LicenseCore.verify(deviceId(c), key)) {
            p(c).edit().putString("key", key.trim()).apply();
            return true;
        }
        return false;
    }

    static long trialLeftMs(Context c) {
        if (!initDone(c)) return 0;
        long first = p(c).getLong("first_run", 0);
        return Math.max(0, first + TRIAL_MS - now(c));
    }

    static boolean allowed(Context c) {
        return licensed(c) || trialLeftMs(c) > 0;
    }

    static String statusText(Context c) {
        if (licensed(c)) return "Licensed: full version (unlimited)";
        if (!initDone(c)) return "Allow the permission so your one-time trial can start.";
        long left = trialLeftMs(c);
        if (left <= 0) return "Trial expired. Enter a license key to continue.";
        long h = left / 3600000L;
        return "Trial: " + (h / 24) + " day(s) " + (h % 24) + " hour(s) left";
    }
}
