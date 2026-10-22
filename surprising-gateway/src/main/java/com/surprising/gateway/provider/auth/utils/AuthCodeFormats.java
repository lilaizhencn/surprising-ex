package com.surprising.gateway.provider.auth.utils;

import java.util.Locale;

/** 无状态代码格式；HTTP绑定和服务端权限常量共用，不执行授权或SQL。 */
public final class AuthCodeFormats {
    private AuthCodeFormats() { }

    public static String role(String roleCode) {
        String normalized = roleCode == null ? "" : roleCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{2,64}")) {
            throw new IllegalArgumentException("invalid role code");
        }
        return normalized;
    }

    public static String permission(String permissionCode) {
        String normalized = permissionCode == null ? "" : permissionCode.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9*][a-z0-9.*_-]{1,127}")) {
            throw new IllegalArgumentException("invalid permission code");
        }
        return normalized;
    }
}
