package com.rafiullah.voicetrigger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service: listens continuously (offline, Vosk), starts recording on the
 * start word, stops on the stop word, saves a WAV file to Music/VoiceTriggerRecorder.
 */
public class RecorderService extends Service {

    static volatile boolean RUNNING = false;
    static volatile String STATUS = "Stopped";

    private static final String CHANNEL = "vtr_channel";
    private static final int NOTIF_ID = 77;
    private static final int RATE = 16000;
    private static final long MAX_RECORD_MS = 2L * 60 * 60 * 1000; // safety: 2 hours per file
    private static final String FOLDER = "VoiceTriggerRecorder";

    private volatile boolean stopFlag = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (RUNNING) return START_STICKY;
        if (!Lic.allowed(this)) { STATUS = "Trial expired"; stopSelf(); return START_NOT_STICKY; }

        try {
            Notification n = buildNotification("Starting…");
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            STATUS = "Could not start: open the app and press START";
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vtr:listen");
        wakeLock.acquire();

        RUNNING = true;
        stopFlag = false;
        worker = new Thread(this::loop, "vtr-worker");
        worker.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopFlag = true;
        if (worker != null) {
            try { worker.join(4000); } catch (InterruptedException ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        RUNNING = false;
        if (!STATUS.startsWith("Error")) STATUS = "Stopped";
        super.onDestroy();
    }

    // ------------------------------------------------------------------ main loop
    private void loop() {
        String startWord = ModelUtil.norm(Lic.p(this).getString("start_word", "record"));
        String stopWord = ModelUtil.norm(Lic.p(this).getString("stop_word", "stop"));
        String[] startToks = startWord.split(" ");
        String[] stopToks = stopWord.split(" ");
        String fileLabel = Lic.p(this).getString("start_label", startWord)
                .replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
        if (fileLabel.isEmpty()) fileLabel = "rec";

        AudioRecord ar = null;
        Model model = null;
        Recognizer rec = null;
        RandomAccessFile raf = null;
        File tmp = null;
        boolean recording = false;
        long recStart = 0;
        long dataBytes = 0;
        String recName = null;

        try {
            STATUS = "Loading speech model (first time takes a while)…";
            File modelDir = ModelUtil.prepare(this);
            java.util.Set<String> vocab = ModelUtil.vocab(modelDir);
            if (vocab != null) {
                for (String w : (startWord + " " + stopWord).split(" ")) {
                    if (!vocab.contains(w)) {
                        throw new IOException("The word \"" + w + "\" is not in the speech vocabulary. "
                                + "Tap SPEAK and say the word so the app learns how it hears it.");
                    }
                }
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir.getAbsolutePath());
            String grammar = "[\"" + startWord + "\", \"" + stopWord + "\", \"[unk]\"]";
            rec = new Recognizer(model, (float) RATE, grammar);

            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            ar.startRecording();

            setStatus("Listening for \"" + startWord + "\"");
            short[] buf = new short[2048];
            byte[] bytes = new byte[buf.length * 2];

            while (!stopFlag) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) continue;

                // write audio while recording
                if (recording) {
                    for (int i = 0; i < n; i++) {
                        bytes[2 * i] = (byte) (buf[i] & 0xFF);
                        bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xFF);
                    }
                    raf.write(bytes, 0, n * 2);
                    dataBytes += n * 2L;
                }

                // speech recognition (partial results for fast reaction)
                String heard;
                if (rec.acceptWaveForm(buf, n)) heard = new JSONObject(rec.getResult()).optString("text", "");
                else heard = new JSONObject(rec.getPartialResult()).optString("partial", "");
                if (heard.isEmpty()) continue;

                String[] words = heard.split("\\s+");
                if (!recording && has(words, startToks)) {
                    tmp = new File(getCacheDir(), "current.wav");
                    raf = new RandomAccessFile(tmp, "rw");
                    raf.setLength(0);
                    raf.write(new byte[44]);               // header placeholder
                    dataBytes = 0;
                    recStart = System.currentTimeMillis();
                    recName = fileLabel + "_"
                            + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(recStart))
                            + ".wav";
                    recording = true;
                    rec.reset();
                    setStatus("RECORDING… say \"" + stopWord + "\" to stop");
                } else if (recording && has(words, stopToks)) {
                    recording = false;
                    finish(raf, tmp, dataBytes, recName);
                    raf = null;
                    rec.reset();
                    setStatus("Saved " + recName + ". Listening for \"" + startWord + "\"");
                }

                if (recording && System.currentTimeMillis() - recStart > MAX_RECORD_MS) {
                    recording = false;
                    finish(raf, tmp, dataBytes, recName);
                    raf = null;
                    setStatus("Saved " + recName + " (max length). Listening…");
                }
            }
        } catch (Throwable t) {
            STATUS = "Error: " + t.getMessage();
        } finally {
            try { if (raf != null) finish(raf, tmp, dataBytes, recName); } catch (Throwable ignored) {}
            try { if (ar != null) { ar.stop(); ar.release(); } } catch (Throwable ignored) {}
            try { if (rec != null) rec.close(); } catch (Throwable ignored) {}
            try { if (model != null) model.close(); } catch (Throwable ignored) {}
            RUNNING = false;
            stopForeground(true);
            stopSelf();
        }
    }

    /** True if the phrase (one or more words) appears contiguously in the heard words. */
    private static boolean has(String[] words, String[] phrase) {
        for (int i = 0; i + phrase.length <= words.length; i++) {
            boolean ok = true;
            for (int j = 0; j < phrase.length; j++) {
                if (!words[i + j].equals(phrase[j])) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ saving
    private void finish(RandomAccessFile raf, File tmp, long dataBytes, String name) throws IOException {
        writeHeader(raf, dataBytes);
        raf.close();
        if (dataBytes > 0) saveToMusic(tmp, name);
        tmp.delete();
    }

    private void writeHeader(RandomAccessFile f, long dataLen) throws IOException {
        long total = dataLen + 36;
        long byteRate = RATE * 2L;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, total); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); h[20] = 1; h[22] = 1; le32(h, 24, RATE); le32(h, 28, byteRate);
        h[32] = 2; h[34] = 16; put(h, 36, "data"); le32(h, 40, dataLen);
        f.seek(0);
        f.write(h);
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, long v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    private void saveToMusic(File src, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav");
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("Cannot create file");
            try (OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(src)) {
                copy(in, os);
            }
            cv.clear();
            cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER);
            dir.mkdirs();
            File dst = new File(dir, name);
            try (OutputStream os = new FileOutputStream(dst); InputStream in = new FileInputStream(src)) {
                copy(in, os);
            }
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, null, null);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.flush();
    }

    // ------------------------------------------------------------------ notification
    private void setStatus(String s) {
        STATUS = s;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(s));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Voice Trigger Recorder",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Voice Trigger Recorder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
