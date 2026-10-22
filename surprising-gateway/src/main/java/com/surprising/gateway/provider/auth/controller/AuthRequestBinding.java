package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.utils.AuthCodeFormats;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** HTTP身份及权限输入绑定；账号开关、认证和权限决策留在Service。 */
public final class AuthRequestBinding {
    private AuthRequestBinding() { }
    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{3,32}$";

    public static RegisterRequest register(RegisterRequest request) {
        validatePassword(request.password());
        return new RegisterRequest(request.username() == null || request.username().isBlank()
                ? null : normalizeUsername(request.username()), request.password(),
                normalizeEmail(request.email()), normalizePhone(request.phone()));
    }

    public static LoginRequest login(LoginRequest request) {
        return new LoginRequest(normalizeLoginIdentifier(request.identifier()), request.password());
    }

    public static List<String> roles(List<String> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("roles are required");
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        values.forEach(value -> codes.add(AuthCodeFormats.role(value)));
        return List.copyOf(codes);
    }

    public static AdminRolePermissionsRequest permissions(AdminRolePermissionsRequest request) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (request != null && request.permissions() != null)
            request.permissions().forEach(value -> codes.add(AuthCodeFormats.permission(value)));
        return new AdminRolePermissionsRequest(List.copyOf(codes));
    }

    private static String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("username must be 3-32 letters, digits or underscore");
        }
        return normalized;
    }

    private static String normalizeLoginIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("email, phone or username is required");
        }
        String normalized = identifier.trim();
        if (normalized.contains("@")) {
            return normalizeEmail(normalized);
        }
        if (normalized.startsWith("+")) {
            return normalizePhone(normalized);
        }
        return normalizeUsername(normalized);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password length must be 8-128");
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String normalized = phone.trim().replace(" ", "").replace("-", "");
        if (!normalized.matches("\\+[1-9]\\d{7,14}")) {
            throw new IllegalArgumentException("phone must use E.164 format");
        }
        return normalized;
    }
}
