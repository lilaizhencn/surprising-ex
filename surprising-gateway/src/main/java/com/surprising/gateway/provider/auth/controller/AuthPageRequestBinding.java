package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.AuthPageQuery.Order;
import com.surprising.gateway.provider.auth.model.UserQueryFilter;
import com.surprising.gateway.provider.auth.utils.AdminCursorPage;
import java.time.DateTimeException;
import java.util.List;
import java.util.Locale;

/** 认证、会话、审批和合规列表的 HTTP 分页与筛选绑定。 */
public final class AuthPageRequestBinding {
    private AuthPageRequestBinding() {
    }

    public static AuthPageQuery created(int limit, String cursor, String sort) {
        return bind(limit, cursor, sort, Order.CREATED_DESC, List.of(Order.CREATED_DESC, Order.CREATED_ASC));
    }

    public static AuthPageQuery requested(int limit, String cursor, String sort) {
        return bind(limit, cursor, sort, Order.REQUESTED_DESC, List.of(Order.REQUESTED_DESC, Order.REQUESTED_ASC));
    }

    public static AuthPageQuery complianceUsers(int limit, String cursor, String sort) {
        return bind(limit, cursor, sort, Order.UPDATED_DESC, List.of(Order.UPDATED_DESC, Order.UPDATED_ASC));
    }

    public static AuthPageQuery riskTags(int limit, String cursor, String sort) {
        return bind(limit, cursor, sort, Order.CREATED_DESC,
                List.of(Order.CREATED_DESC, Order.CREATED_ASC, Order.UPDATED_DESC, Order.UPDATED_ASC));
    }

    public static AuthPageQuery amlCases(int limit, String cursor, String sort) {
        return bind(limit, cursor, sort, Order.UPDATED_DESC,
                List.of(Order.UPDATED_DESC, Order.UPDATED_ASC, Order.CREATED_DESC, Order.CREATED_ASC));
    }

    private static AuthPageQuery bind(int limit, String cursor, String sort, Order defaultOrder, List<Order> allowed) {
        String token = sort == null || sort.isBlank() ? defaultOrder.token() : sort.trim();
        Order order = allowed.stream().filter(value -> value.token().equals(token)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + sort));
        try {
            return new AuthPageQuery(AdminCursorPage.limit(limit, 500), AdminCursorPage.decodeCursor(cursor), order);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("invalid cursor", invalid);
        }
    }

    public static void sessionUser(Long userId) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }

    public static UserQueryFilter userFilter(String query, String status) {
        String term = query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT);
        if (term != null && term.length() > 64) {
            term = term.substring(0, 64);
        }
        String exact = query != null && query.trim().matches("\\d{1,19}") ? query.trim() : null;
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !List.of("NORMAL", "FROZEN", "TRADE_DISABLED", "WITHDRAW_DISABLED").contains(normalized)) {
            throw new IllegalArgumentException("invalid user status");
        }
        return new UserQueryFilter(term, exact, normalized);
    }

    public static String approvalStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "APPROVED", "REJECTED", "CONSUMED").contains(normalized)) {
            throw new IllegalArgumentException("invalid approval status");
        }
        return normalized;
    }

    public static String approvalService(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid approval service");
        }
        return normalized;
    }
}
