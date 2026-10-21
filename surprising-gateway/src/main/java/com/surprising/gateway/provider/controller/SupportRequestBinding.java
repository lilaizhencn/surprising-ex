package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.controller.AuthPageRequestBinding;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.AuthPageQuery.Order;
import java.util.List;
import java.util.Locale;

/** 客服 HTTP 参数格式与原生分页绑定；权限、状态与事务由 Service 负责。 */
public final class SupportRequestBinding {
    private static final int MAX_LIMIT = 100;

    private SupportRequestBinding() {
    }

    public static AuthPageQuery tickets(int limit, String cursor, String sort) {
        return AuthPageRequestBinding.bind(boundLimit(limit), cursor, sort, Order.UPDATED_DESC,
                List.of(Order.UPDATED_DESC, Order.UPDATED_ASC, Order.CREATED_DESC, Order.CREATED_ASC));
    }

    public static AuthPageQuery notes(int limit, String cursor, String sort) {
        return AuthPageRequestBinding.bind(limit, cursor, sort, Order.CREATED_ASC,
                List.of(Order.CREATED_ASC, Order.CREATED_DESC));
    }

    public static String optionalStatus(String status) {
        return status == null || status.isBlank() ? null : normalizeTicketStatus(status);
    }

    public static String initialNote(String note) {
        return note == null || note.isBlank() ? null : requireText(note, "initialNote", 2000);
    }

    public static int boundLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    public static String normalizeTicketStatus(String status) {
        String normalized = requireText(status, "status", 32).toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "PENDING_USER", "PENDING_INTERNAL", "RESOLVED", "CLOSED").contains(normalized)) {
            throw new IllegalArgumentException("invalid ticket status: " + status);
        }
        return normalized;
    }

    public static String normalizePriority(String priority) {
        String normalized = priority == null || priority.isBlank()
                ? "MEDIUM"
                : priority.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(normalized)) {
            throw new IllegalArgumentException("invalid ticket priority: " + priority);
        }
        return normalized;
    }

    public static String normalizeNoteType(String noteType) {
        String normalized = noteType == null || noteType.isBlank()
                ? "NOTE"
                : noteType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NOTE", "STATUS_CHANGE", "ESCALATION", "FOLLOW_UP").contains(normalized)) {
            throw new IllegalArgumentException("invalid note type: " + noteType);
        }
        return normalized;
    }

    public static String normalizeVisibility(String visibility) {
        String normalized = visibility == null || visibility.isBlank()
                ? "INTERNAL"
                : visibility.trim().toUpperCase(Locale.ROOT);
        if (!List.of("INTERNAL", "CUSTOMER").contains(normalized)) {
            throw new IllegalArgumentException("invalid note visibility: " + visibility);
        }
        return normalized;
    }

    public static String normalizeCode(String value, String fallback, String field) {
        String normalized = value == null || value.isBlank()
                ? fallback
                : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,64}")) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return normalized;
    }

    public static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }
}
