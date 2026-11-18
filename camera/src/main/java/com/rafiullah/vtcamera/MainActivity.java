package com.rafiullah.vtcamera;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private TextView idTv, licTv, statusTv;
    private EditText keyEt, startEt, stopEt;
    private Button toggleBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startTrialIfPossible();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(12));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        col.addView(label("Voice Trigger Camera", 24, true));
        col.addView(label("Say the START word to take a photo or start a video, and the STOP word to end a video. "
                + "Works in the background and with the screen locked.", 14, false));

        // ---- Words ----
        col.addView(label("What to capture", 14, true));
        modeGroup = radio(10, "Photo", "Video", !"video".equals(Lic.p(this).getString("mode", "photo")));
        col.addView(modeGroup);
        col.addView(label("Camera", 14, true));
        camGroup = radio(20, "Back", "Front", !"front".equals(Lic.p(this).getString("camera", "back")));
        col.addView(camGroup);

        col.addView(label("Start word / phrase (photo: takes a picture; video: starts it)", 14, true));
        startEt = new EditText(this);
        startEt.setSingleLine(true);
        startEt.setText(Lic.p(this).getString("start_label", Lic.p(this).getString("start_word", "record")));
        col.addView(wordRow(startEt));

        col.addView(label("Stop word / phrase (video only)", 14, true));
        stopEt = new EditText(this);
        stopEt.setSingleLine(true);
        stopEt.setText(Lic.p(this).getString("stop_word", "stop"));
        col.addView(wordRow(stopEt));
        col.addView(label("Type a word or short phrase, or tap SPEAK and say it. "
                + "SPEAK stores the word the way the app hears you, which also works for names and "
                + "other languages. Stop listening first before using SPEAK.", 12, false));

        // ---- Start / stop service ----
        toggleBtn = new Button(this);
        toggleBtn.setOnClickListener(v -> toggle());
        col.addView(toggleBtn);

        statusTv = label("", 14, false);
        col.addView(statusTv);

        Button battery = new Button(this);
        battery.setText("Allow unrestricted battery (recommended)");
        battery.setOnClickListener(v -> askBattery());
        col.addView(battery);

        // ---- License ----
        col.addView(label("License", 18, true));
        licTv = label("", 14, false);
        col.addView(licTv);

        TextView contact = label("For the unlimited version, contact on WhatsApp: +923455046533 (tap to open)", 14, true);
        contact.setTextColor(0xFF1B7F3B);
        contact.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/923455046533")));
            } catch (Exception ex) {
                Toast.makeText(this, "WhatsApp: +923455046533", Toast.LENGTH_LONG).show();
            }
        });
        col.addView(contact);

        col.addView(label("Your Mobile ID (send this to get a key):", 13, false));
        idTv = new TextView(this);
        idTv.setText(Lic.deviceId(this));
        idTv.setTextSize(16);
        idTv.setTypeface(Typeface.MONOSPACE);
        idTv.setTextIsSelectable(true);
        col.addView(idTv);

        Button copy = new Button(this);
        copy.setText("Copy Mobile ID");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Mobile ID", Lic.deviceId(this)));
            Toast.makeText(this, "Mobile ID copied", Toast.LENGTH_SHORT).show();
        });
        col.addView(copy);

        keyEt = new EditText(this);
        keyEt.setHint("Enter license key  XXXX-XXXX-XXXX-XXXX");
        keyEt.setSingleLine(true);
        keyEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        col.addView(keyEt);

        Button act = new Button(this);
        act.setText("Activate key");
        act.setOnClickListener(v -> {
            if (Lic.activate(this, keyEt.getText().toString())) {
                Toast.makeText(this, "Activated. Thank you!", Toast.LENGTH_LONG).show();
                keyEt.setText("");
            } else {
                Toast.makeText(this, "Invalid key for this mobile", Toast.LENGTH_LONG).show();
            }
            refresh();
        });
        col.addView(act);

        col.addView(label("Photos: Pictures/VoiceTriggerCamera\nVideos: Movies/VoiceTriggerCamera\n"
                + "File name: <startword>_<date>_<time>.jpg or .mp4 (video has no sound)", 12, false));

        // ---- Footer (below main screen) ----
        TextView footer = new TextView(this);
        footer.setText("Developed by Rafiullah");
        footer.setGravity(Gravity.CENTER);
        footer.setTextSize(14);
        footer.setTypeface(Typeface.DEFAULT_BOLD);
        footer.setPadding(0, dp(10), 0, dp(10));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }

    private void refresh() {
        licTv.setText(Lic.statusText(this));
        toggleBtn.setText(CameraService.RUNNING ? "STOP LISTENING" : "START LISTENING");
        statusTv.setText("Status: " + CameraService.STATUS);
    }

    private void toggle() {
        if (CameraService.RUNNING) {
            stopService(new Intent(this, CameraService.class));
            return;
        }
        if (!Lic.allowed(this)) {
            if (!Lic.initDone(this)) {
                startTrialIfPossible();
            } else {
                Toast.makeText(this, "Trial expired. Enter a license key.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (capturing) { Toast.makeText(this, "Wait, listening for your word…", Toast.LENGTH_SHORT).show(); return; }
        String label = startEt.getText().toString().trim();
        String s = ModelUtil.norm(label);
        String e = ModelUtil.norm(stopEt.getText().toString());
        boolean video = modeGroup.getCheckedRadioButtonId() == 12;
        if (s.isEmpty() || (video && (e.isEmpty() || s.equals(e)))) {
            Toast.makeText(this, video ? "Enter two different words or phrases" : "Enter a start word",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Lic.p(this).edit().putString("start_word", s).putString("stop_word", e.isEmpty() ? "stop" : e)
                .putString("start_label", label)
                .putString("mode", video ? "video" : "photo")
                .putString("camera", camGroup.getCheckedRadioButtonId() == 22 ? "front" : "back").apply();

        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
            return;
        }
        startSvc();
    }

    private boolean hasTrialPerm() {
        boolean read = Build.VERSION.SDK_INT >= 33
                ? checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean write = Build.VERSION.SDK_INT > 28
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return read && write;
    }

    /** The trial start is stored outside the app, so storage/audio permission is needed once. */
    private void startTrialIfPossible() {
        if (hasTrialPerm()) { Lic.init(this); return; }
        if (Lic.initDone(this)) return;
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.READ_MEDIA_AUDIO);
        else need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT <= 28) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        requestPermissions(need.toArray(new String[0]), 3);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 3) {
            if (hasTrialPerm()) Lic.init(this);
            else Toast.makeText(this, "Permission is needed to start the trial", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (code == 2) {
                if (pendingTarget != null) captureWord(pendingTarget, pendingBtn);
                pendingTarget = null; pendingBtn = null;
            } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startSvc();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show();
        }
    }

    private android.widget.RadioGroup modeGroup, camGroup;

    private android.widget.RadioGroup radio(int base, String a, String b, boolean firstSelected) {
        android.widget.RadioGroup g = new android.widget.RadioGroup(this);
        g.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton r1 = new android.widget.RadioButton(this);
        r1.setText(a);
        r1.setId(base + 1);
        android.widget.RadioButton r2 = new android.widget.RadioButton(this);
        r2.setText(b);
        r2.setId(base + 2);
        g.addView(r1);
        g.addView(r2);
        g.check(firstSelected ? base + 1 : base + 2);
        return g;
    }

    private EditText pendingTarget;
    private Button pendingBtn;
    private volatile boolean capturing = false;

    private LinearLayout wordRow(EditText et) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        Button sp = new Button(this);
        sp.setText("SPEAK");
        sp.setOnClickListener(v -> captureWord(et, sp));
        row.addView(sp, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** Listens once and puts what the speech model heard into the field. */
    private void captureWord(EditText target, Button btn) {
        if (CameraService.RUNNING) {
            Toast.makeText(this, "Press STOP LISTENING first, then set the word", Toast.LENGTH_LONG).show();
            return;
        }
        if (capturing) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingTarget = target; pendingBtn = btn;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        capturing = true;
        final String old = btn.getText().toString();
        btn.setText("Loading…");
        new Thread(() -> {
            String text = "", err = null;
            Model m = null; Recognizer r = null; AudioRecord ar = null;
            try {
                File md = ModelUtil.prepare(this);
                m = new Model(md.getAbsolutePath());
                r = new Recognizer(m, 16000f);
                int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2);
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
                ar.startRecording();
                runOnUiThread(() -> btn.setText("Speak now…"));
                short[] buf = new short[2048];
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < 8000) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    if (r.acceptWaveForm(buf, n)) {
                        text = new JSONObject(r.getResult()).optString("text", "").trim();
                        if (!text.isEmpty()) break;
                    }
                }
                if (text.isEmpty()) text = new JSONObject(r.getFinalResult()).optString("text", "").trim();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            } finally {
                try { if (ar != null) { ar.stop(); ar.release(); } } catch (Throwable ignored) {}
                try { if (r != null) r.close(); } catch (Throwable ignored) {}
                try { if (m != null) m.close(); } catch (Throwable ignored) {}
            }
            final String ft = text, fe = err;
            runOnUiThread(() -> {
                capturing = false;
                btn.setText(old);
                if (fe != null) Toast.makeText(this, "Error: " + fe, Toast.LENGTH_LONG).show();
                else if (ft.isEmpty()) Toast.makeText(this, "Did not hear anything. Try again.", Toast.LENGTH_LONG).show();
                else { target.setText(ft); Toast.makeText(this, "Set to: " + ft, Toast.LENGTH_SHORT).show(); }
            });
        }).start();
    }

    private void startSvc() {
        Intent i = new Intent(this, CameraService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void askBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ex) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
