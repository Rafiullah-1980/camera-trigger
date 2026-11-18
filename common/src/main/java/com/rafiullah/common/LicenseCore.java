package com.rafiullah.common;

import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared by the main app and the key generator. Keep SECRET private. */
public final class LicenseCore {

    // >>> CHANGE THIS to your own long random text BEFORE you build/push. <<<
    private static final String SECRET = "CHANGE-THIS-TO-YOUR-OWN-LONG-RANDOM-SECRET-2026";

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private LicenseCore() {}

    public static String cleanId(String id) {
        return id == null ? "" : id.trim().toLowerCase().replaceAll("\\s+", "");
    }

    /** Key for the Voice Trigger Recorder (product "rec"). */
    public static String keyFor(String deviceId) {
        return keyFor(deviceId, "rec");
    }

    /** Returns key like ABCD-EFGH-JKLM-NPQR for the given device ID and product ("rec" or "cam"). */
    public static String keyFor(String deviceId, String product) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes("UTF-8"), "HmacSHA256"));
            String input = cleanId(deviceId);
            if (product != null && !product.equals("rec")) input += "|" + product;   // "rec" keeps old keys valid
            byte[] h = mac.doFinal(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            long buffer = 0;
            int bits = 0;
            for (int i = 0; i < 10; i++) {          // 80 bits -> 16 chars
                buffer = (buffer << 8) | (h[i] & 0xFF);
                bits += 8;
                while (bits >= 5) {
                    sb.append(ALPHABET.charAt((int) ((buffer >> (bits - 5)) & 31)));
                    bits -= 5;
                }
                buffer &= (1L << bits) - 1;
            }
            String k = sb.toString();
            return k.substring(0, 4) + "-" + k.substring(4, 8) + "-"
                    + k.substring(8, 12) + "-" + k.substring(12, 16);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean verify(String deviceId, String key) {
        return verify(deviceId, key, "rec");
    }

    public static boolean verify(String deviceId, String key, String product) {
        if (key == null || key.trim().isEmpty()) return false;
        String a = keyFor(deviceId, product).replace("-", "");
        String b = key.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
