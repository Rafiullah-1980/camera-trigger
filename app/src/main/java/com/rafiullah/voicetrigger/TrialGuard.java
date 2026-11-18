package com.rafiullah.voicetrigger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.rafiullah.common.LicenseCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers the trial start OUTSIDE the app's own data, in a tiny audio file in
 * Music/VoiceTriggerRecorder. Uninstalling, reinstalling or clearing app data does
 * not delete it, so the trial can only be used once per mobile.
 */
final class TrialGuard {
    private static final String PREFIX = "VTR_do_not_delete_";
    private static final String FOLDER = "VoiceTriggerRecorder";
    private static final Pattern TS = Pattern.compile("_(\\d{12,14})");

    private TrialGuard() {}

    private static String tag(Context c) {
        return LicenseCore.keyFor(Lic.deviceId(c) + "|trial").replace("-", "");
    }

    /** Earliest trial start found for this mobile, or -1. */
    static long find(Context c) {
        long best = -1;
        String base = PREFIX + tag(c) + "_";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                try (Cursor cur = c.getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Audio.Media.DISPLAY_NAME},
                        MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?",
                        new String[]{base + "%"}, null)) {
                    while (cur != null && cur.moveToNext()) best = min(best, parse(cur.getString(0), base));
                }
            } else {
                File[] fs = dir().listFiles();
                if (fs != null) for (File f : fs) best = min(best, parse(f.getName(), base));
            }
        } catch (Throwable ignored) {}
        return best;
    }

    static void ensure(Context c, long millis) {
        if (find(c) > 0) return;
        try { write(c, millis); } catch (Throwable ignored) {}
    }

    private static long parse(String name, String base) {
        if (name == null || !name.startsWith(base)) return -1;
        Matcher m = TS.matcher(name.substring(base.length() - 1));
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static long min(long a, long b) {
        if (b <= 0) return a;
        return a <= 0 ? b : Math.min(a, b);
    }

    private static File dir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER);
    }

    private static byte[] silentWav() {
        int data = 3200; // 0.1 s of silence, 16 kHz mono 16-bit
        byte[] h = new byte[44 + data];
        put(h, 0, "RIFF"); le32(h, 4, 36 + data); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); h[20] = 1; h[22] = 1; le32(h, 24, 16000); le32(h, 28, 32000);
        h[32] = 2; h[34] = 16; put(h, 36, "data"); le32(h, 40, data);
        return h;
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, long v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    private static void write(Context c, long millis) throws Exception {
        String name = PREFIX + tag(c) + "_" + millis + ".wav";
        byte[] wav = silentWav();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav");
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return;
            try (OutputStream os = cr.openOutputStream(uri)) { os.write(wav); }
            cv.clear();
            cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
        } else {
            File d = dir();
            d.mkdirs();
            File f = new File(d, name);
            try (OutputStream os = new FileOutputStream(f)) { os.write(wav); }
            MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
        }
    }
}
