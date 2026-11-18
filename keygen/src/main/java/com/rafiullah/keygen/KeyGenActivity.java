package com.rafiullah.keygen;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rafiullah.common.LicenseCore;

public class KeyGenActivity extends Activity {

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(24), dp(18), dp(12));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView title = new TextView(this);
        title.setText("Key Generator");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Paste the customer's Mobile ID (from Voice Trigger Recorder) and generate the key.");
        hint.setPadding(0, dp(8), 0, dp(8));
        col.addView(hint);

        RadioGroup rg = new RadioGroup(this);
        RadioButton r1 = new RadioButton(this);
        r1.setText("Voice Trigger Recorder");
        r1.setId(1);
        RadioButton r2 = new RadioButton(this);
        r2.setText("Voice Trigger Camera");
        r2.setId(2);
        rg.addView(r1);
        rg.addView(r2);
        rg.check(1);
        col.addView(rg);

        EditText idEt = new EditText(this);
        idEt.setHint("Mobile ID");
        idEt.setSingleLine(true);
        col.addView(idEt);

        Button paste = new Button(this);
        paste.setText("Paste from clipboard");
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                if (t != null) idEt.setText(t.toString().trim());
            }
        });
        col.addView(paste);

        Button gen = new Button(this);
        gen.setText("Generate key");
        col.addView(gen);

        TextView out = new TextView(this);
        out.setTextSize(24);
        out.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        out.setTextIsSelectable(true);
        out.setPadding(0, dp(16), 0, dp(8));
        col.addView(out);

        Button copy = new Button(this);
        copy.setText("Copy key");
        col.addView(copy);

        Button share = new Button(this);
        share.setText("Share key (WhatsApp etc.)");
        col.addView(share);

        gen.setOnClickListener(v -> {
            String id = idEt.getText().toString().trim();
            if (id.length() < 8) {
                Toast.makeText(this, "Enter a valid Mobile ID", Toast.LENGTH_SHORT).show();
                return;
            }
            out.setText(LicenseCore.keyFor(id, rg.getCheckedRadioButtonId() == 2 ? "cam" : "rec"));
        });
        copy.setOnClickListener(v -> {
            if (out.getText().length() == 0) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Key", out.getText()));
            Toast.makeText(this, "Key copied", Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> {
            if (out.getText().length() == 0) return;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, "Your " + (rg.getCheckedRadioButtonId() == 2 ? "Voice Trigger Camera" : "Voice Trigger Recorder") + " license key: " + out.getText());
            startActivity(Intent.createChooser(i, "Send key"));
        });

        TextView footer = new TextView(this);
        footer.setText("Developed by Rafiullah");
        footer.setGravity(Gravity.CENTER);
        footer.setTypeface(Typeface.DEFAULT_BOLD);
        footer.setPadding(0, dp(10), 0, dp(10));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }
}
