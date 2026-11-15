package com.surprising.gateway.provider.auth.utils;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/** 无状态代码格式与共享密码学随机数；HTTP绑定和服务端权限常量共用，不执行授权或SQL。 */
public final class AuthCodeFormats {
    public static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern ROLE_PATTERN = Pattern.compile("[A-Z0-9_]{2,64}");
    private static final Pattern PERMISSION_PATTERN = Pattern.compile("[a-z0-9*][a-z0-9.*_-]{1,127}");

    private AuthCodeFormats() { }

    public static String sixDigitCode(boolean simulated) {
        return simulated ? "123456" : String.format(Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    public static String role(String roleCode) {
        String normalized = roleCode == null ? "" : roleCode.trim().toUpperCase(Locale.ROOT);
        if (!ROLE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid role code");
        }
        return normalized;
    }

    public static String permission(String permissionCode) {
        String normalized = permissionCode == null ? "" : permissionCode.trim().toLowerCase(Locale.ROOT);
        if (!PERMISSION_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid permission code");
        }
        return normalized;
    }
}
