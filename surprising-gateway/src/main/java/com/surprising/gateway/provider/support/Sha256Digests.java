package com.surprising.gateway.provider.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 统一 SHA-256 摘要辅助，消除各模块重复的 {@code MessageDigest.getInstance("SHA-256")} 样板代码。
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
}
