package com.example.utils;

import java.security.MessageDigest;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import android.util.Base64;

public class EncryptionUtils {

    private static final String DEFAULT_KEY = "AirSignalSecretKey2026"; // 16 or 32 char key

    public static String encrypt(String data) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(DEFAULT_KEY.getBytes("UTF-8"));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(data.getBytes("UTF-8"));
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            e.printStackTrace();
            return data;
        }
    }

    public static String decrypt(String encryptedData) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(DEFAULT_KEY.getBytes("UTF-8"));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] original = cipher.doFinal(Base64.decode(encryptedData, Base64.NO_WRAP));
            return new String(original, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            return encryptedData;
        }
    }

    public static long calculateCRC32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
