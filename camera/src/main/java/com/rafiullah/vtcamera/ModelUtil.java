package com.rafiullah.vtcamera;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/** Copies the bundled speech model to internal storage (once) and reads its vocabulary. */
final class ModelUtil {
    private ModelUtil() {}

    static synchronized File prepare(Context c) throws IOException {
        File dir = new File(c.getFilesDir(), "model");
        File ready = new File(dir, ".ready");
        if (!ready.exists()) {
            deleteRec(dir);
            copyAssets(c.getAssets(), "model", dir);
            new FileOutputStream(ready).close();
        }
        return dir;
    }

    /** Words the model knows, or null if the list file is not found. */
    static Set<String> vocab(File modelDir) {
        File f = new File(modelDir, "graph/words.txt");
        if (!f.exists()) return null;
        Set<String> s = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                int sp = line.indexOf(' ');
                s.add(sp > 0 ? line.substring(0, sp) : line);
            }
        } catch (IOException e) {
            return null;
        }
        return s;
    }

    /** Lower-case letters/apostrophes/spaces only. */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}']+", " ").trim();
    }

    private static void copyAssets(AssetManager am, String path, File dst) throws IOException {
        String[] list = am.list(path);
        if (list != null && list.length > 0) {
            dst.mkdirs();
            for (String s : list) copyAssets(am, path + "/" + s, new File(dst, s));
        } else {
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dst)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } catch (IOException e) {
                dst.mkdirs();
            }
        }
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteRec(c); }
        f.delete();
    }
}
