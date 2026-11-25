package com.surprising.gateway.provider.auth.service;

import java.util.List;

/**
 * 权限码通配符匹配辅助（支持 {@code *} 及前缀通配如 {@code admin.orders.*}）。
 */
final class AuthPermissionMatcher {

    private AuthPermissionMatcher() {
    }

    static boolean matches(List<String> permissions, String required) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        if (permissions.contains("*") || permissions.contains(required)) {
            return true;
        }
        for (String permission : permissions) {
            if (permission != null && permission.contains("*") && wildcardMatches(permission, required)) {
                return true;
            }
        }
        String current = required;
        while (current.contains(".")) {
            current = current.substring(0, current.lastIndexOf('.'));
            if (permissions.contains(current + ".*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean wildcardMatches(String pattern, String required) {
        int pi = 0;
        int ri = 0;
        int starIdx = -1;
        int matchIdx = 0;
        int pLen = pattern.length();
        int rLen = required.length();
        while (ri < rLen) {
            if (pi < pLen && pattern.charAt(pi) == required.charAt(ri) && pattern.charAt(pi) != '*') {
                pi++;
                ri++;
            } else if (pi < pLen && pattern.charAt(pi) == '*') {
                starIdx = pi++;
                matchIdx = ri;
            } else if (starIdx != -1 && isPermissionTokenChar(required.charAt(matchIdx))) {
                pi = starIdx + 1;
                ri = ++matchIdx;
            } else {
                return false;
            }
        }
        while (pi < pLen && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pLen;
    }

    private static boolean isPermissionTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
    }
}
