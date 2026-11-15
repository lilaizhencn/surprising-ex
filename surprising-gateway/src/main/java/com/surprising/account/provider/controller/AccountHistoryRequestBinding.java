package com.surprising.account.provider.controller;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.account.provider.model.AccountHistoryPageQuery;

/** 只绑定历史HTTP查询参数，不访问资金、产品状态或持久化。 */
public final class AccountHistoryRequestBinding {
    private AccountHistoryRequestBinding() {}
    public static void userId(Long value) {
        if (value == null || value <= 0L) throw new IllegalArgumentException("userId must be positive");
    }
    public static AccountHistoryPageQuery page(int limit, String cursor, String sort) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        String token = sort == null || sort.isBlank() ? "createdAt.desc" : sort.trim();
        if (!token.equals("createdAt.asc") && !token.equals("createdAt.desc")) {
            throw new IllegalArgumentException("unsupported sort: " + sort);
        }
        try {
            return new AccountHistoryPageQuery(limit,
                    AdminCursorPage.decodeCursor(cursor),token.equals("createdAt.desc"));
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("invalid cursor",invalid);
        }
    }

    public static String optionalAsset(String asset) {
        return asset == null || asset.isBlank() ? null : normalizeAsset(asset);
    }

    private static final java.util.regex.Pattern REF_PATTERN = java.util.regex.Pattern.compile("[A-Z0-9_:-]{2,80}");

    public static String optionalReferenceType(String value) {
        return optionalIdentifier(value, "referenceType");
    }

    public static String optionalAdjustmentKind(String value) {
        return optionalIdentifier(value, "adjustmentKind");
    }

    private static String optionalIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!REF_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid " + fieldName + ": " + value);
        }
        return normalized;
    }

    public static String optionalReferenceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("referenceId is too long");
        }
        return normalized;
    }

    private static String normalizeAsset(String asset) {
        return AccountInputBinding.asset(asset);
    }
}
