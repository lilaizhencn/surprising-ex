package com.surprising.gateway.provider.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 统一 SHA-256 / HMAC / 常量时间比对辅助，消除各模块重复的密码学样板代码。
 */
public final class Sha256Digests {

    private Sha256Digests() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static byte[] bytes(byte[] input) {
        return newDigest().digest(input);
    }

    public static byte[] bytes(String input) {
        return bytes(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] input) {
        return HexFormat.of().formatHex(bytes(input));
    }

    public static String hex(String input) {
        return HexFormat.of().formatHex(bytes(input));
    }

    public static String base64Url(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(input));
    }

    public static byte[] hmac(String algorithm, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(algorithm + " is unavailable", ex);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        return hmac("HmacSHA256", key, data);
    }

    public static byte[] hmacSha256(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }

    public static String hmacSha256Hex(byte[] key, byte[] data) {
        return HexFormat.of().formatHex(hmacSha256(key, data));
    }

    public static String hmacSha256Hex(String secret, String data) {
        return hmacSha256Hex(secret.getBytes(StandardCharsets.UTF_8), data.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Base64Url(byte[] key, byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(key, data));
    }

    public static String hmacSha256Base64Url(String secret, String data) {
        return hmacSha256Base64Url(secret.getBytes(StandardCharsets.UTF_8), data.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }
}
