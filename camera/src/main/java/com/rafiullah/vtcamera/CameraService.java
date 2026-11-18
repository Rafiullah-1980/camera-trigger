package com.rafiullah.vtcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Foreground service: listens offline for the start word, then takes a photo or starts a
 * video (stop word ends the video). Uses CameraX; works with the screen locked.
 */
public class CameraService extends LifecycleService {

    static volatile boolean RUNNING = false;
    static volatile String STATUS = "Stopped";

    private static final String CHANNEL = "vtc_channel";
    private static final int NOTIF_ID = 78;
    private static final int RATE = 16000;
    private static final long MAX_VIDEO_MS = 2L * 60 * 60 * 1000; // safety: 2 hours per video
    private static final String FOLDER = "VoiceTriggerCamera";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Executor mainExec;

    private volatile boolean stopFlag = false;
    private volatile boolean cameraReady = false;
    private volatile boolean videoActive = false;
    private boolean videoMode, frontCamera;

    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    private ProcessCameraProvider provider;
    private ImageCapture imageCapture;
    private Recorder recorder;
    private Recording activeRec;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (RUNNING) return START_STICKY;
        if (!Lic.allowed(this)) { STATUS = "Trial expired"; stopSelf(); return START_NOT_STICKY; }

        try {
            Notification n = buildNotification("Starting…");
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            STATUS = "Error: could not start. Open the app and press START.";
            stopSelf();
            return START_NOT_STICKY;
        }

        mainExec = ContextCompat.getMainExecutor(this);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vtc:listen");
        wakeLock.acquire();

        videoMode = "video".equals(Lic.p(this).getString("mode", "photo"));
        frontCamera = "front".equals(Lic.p(this).getString("camera", "back"));

        RUNNING = true;
        stopFlag = false;
        cameraReady = false;
        bindCamera();
        worker = new Thread(this::loop, "vtc-worker");
        worker.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopFlag = true;
        try { if (activeRec != null) activeRec.stop(); } catch (Throwable ignored) {}
        try { if (provider != null) provider.unbindAll(); } catch (Throwable ignored) {}
        if (worker != null) {
            try { worker.join(4000); } catch (InterruptedException ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        RUNNING = false;
        if (!STATUS.startsWith("Error")) STATUS = "Stopped";
        super.onDestroy();
    }

    // ------------------------------------------------------------------ camera
    private void bindCamera() {
        final ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                provider.unbindAll();
                CameraSelector sel = frontCamera
                        ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                if (videoMode) {
                    recorder = new Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD,
                                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                            .build();
                    VideoCapture<Recorder> vc = VideoCapture.withOutput(recorder);
                    provider.bindToLifecycle(this, sel, vc);
                } else {
                    imageCapture = new ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                    provider.bindToLifecycle(this, sel, imageCapture);
                }
                cameraReady = true;
            } catch (Throwable t) {
                fail("Camera problem: " + t.getMessage());
            }
        }, mainExec);
    }

    private void fail(String msg) {
        STATUS = "Error: " + msg;
        stopFlag = true;
        main.post(this::stopSelf);
    }

    private String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
    }

    private void takePhoto(String label) {
        if (imageCapture == null) return;
        final String base = label + "_" + stamp();
        ImageCapture.OutputFileOptions opts;
        File legacy = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, base);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            opts = new ImageCapture.OutputFileOptions.Builder(getContentResolver(),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build();
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
            d.mkdirs();
            legacy = new File(d, base + ".jpg");
            opts = new ImageCapture.OutputFileOptions.Builder(legacy).build();
        }
        final File fl = legacy;
        imageCapture.takePicture(opts, mainExec, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults r) {
                if (fl != null) MediaScannerConnection.scanFile(CameraService.this,
                        new String[]{fl.getAbsolutePath()}, null, null);
                setStatus("Photo saved: " + base + ".jpg");
            }
            @Override public void onError(ImageCaptureException e) {
                setStatus("Photo failed: " + e.getMessage());
            }
        });
    }

    private void startVideo(String label, String stopWord) {
        if (recorder == null || activeRec != null) return;
        final String base = label + "_" + stamp();
        PendingRecording pr;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, base);
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            MediaStoreOutputOptions o = new MediaStoreOutputOptions.Builder(getContentResolver(),
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(cv).setDurationLimitMillis(MAX_VIDEO_MS).build();
            pr = recorder.prepareRecording(this, o);
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER);
            d.mkdirs();
            File f = new File(d, base + ".mp4");
            FileOutputOptions o = new FileOutputOptions.Builder(f).setDurationLimitMillis(MAX_VIDEO_MS).build();
            pr = recorder.prepareRecording(this, o);
        }
        activeRec = pr.start(mainExec, ev -> {
            if (ev instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) ev;
                activeRec = null;
                videoActive = false;
                if (fin.hasError() && fin.getError() != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED)
                    setStatus("Video problem (code " + fin.getError() + ")");
                else
                    setStatus("Video saved: " + base + ".mp4. Listening…");
            }
        });
        videoActive = true;
        setStatus("RECORDING VIDEO… say \"" + stopWord + "\" to stop");
    }

    private void stopVideo() {
        try { if (activeRec != null) activeRec.stop(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ voice loop
    private void loop() {
        String startWord = ModelUtil.norm(Lic.p(this).getString("start_word", "record"));
        String stopWord = ModelUtil.norm(Lic.p(this).getString("stop_word", "stop"));
        final String[] startToks = startWord.split(" ");
        final String[] stopToks = stopWord.split(" ");
        String lab = Lic.p(this).getString("start_label", startWord)
                .replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
        final String label = lab.isEmpty() ? "cam" : lab;
        final String stopShown = stopWord;

        AudioRecord ar = null;
        Model model = null;
        Recognizer rec = null;
        try {
            STATUS = "Loading speech model (first time takes a while)…";
            File modelDir = ModelUtil.prepare(this);
            Set<String> vocab = ModelUtil.vocab(modelDir);
            if (vocab != null) {
                String check = videoMode ? startWord + " " + stopWord : startWord;
                for (String w : check.split(" ")) {
                    if (!vocab.contains(w)) {
                        throw new IOException("The word \"" + w + "\" is not in the speech vocabulary. "
                                + "Tap SPEAK and say the word so the app learns how it hears it.");
                    }
                }
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir.getAbsolutePath());
            String grammar = videoMode
                    ? "[\"" + startWord + "\", \"" + stopWord + "\", \"[unk]\"]"
                    : "[\"" + startWord + "\", \"[unk]\"]";
            rec = new Recognizer(model, (float) RATE, grammar);

            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            ar.startRecording();

            setStatus("Listening for \"" + startWord + "\" (" + (videoMode ? "video" : "photo") + ", "
                    + (frontCamera ? "front" : "back") + " camera)");
            short[] buf = new short[2048];
            long lastShot = 0;

            while (!stopFlag) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) continue;

                String heard;
                if (rec.acceptWaveForm(buf, n)) heard = new JSONObject(rec.getResult()).optString("text", "");
                else heard = new JSONObject(rec.getPartialResult()).optString("partial", "");
                if (heard.isEmpty() || !cameraReady) continue;

                String[] words = heard.split("\\s+");
                if (videoMode) {
                    if (!videoActive && has(words, startToks)) {
                        rec.reset();
                        main.post(() -> startVideo(label, stopShown));
                    } else if (videoActive && has(words, stopToks)) {
                        rec.reset();
                        main.post(this::stopVideo);
                    }
                } else if (has(words, startToks) && System.currentTimeMillis() - lastShot > 3000) {
                    lastShot = System.currentTimeMillis();
                    rec.reset();
                    main.post(() -> takePhoto(label));
                }
            }
        } catch (Throwable t) {
            STATUS = "Error: " + t.getMessage();
        } finally {
            try { if (ar != null) { ar.stop(); ar.release(); } } catch (Throwable ignored) {}
            try { if (rec != null) rec.close(); } catch (Throwable ignored) {}
            try { if (model != null) model.close(); } catch (Throwable ignored) {}
            RUNNING = false;
            main.post(() -> { stopForeground(true); stopSelf(); });
        }
    }

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

    // ------------------------------------------------------------------ notification
    private void setStatus(String s) {
        STATUS = s;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(s));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Voice Trigger Camera",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Voice Trigger Camera")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
