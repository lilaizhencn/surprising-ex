package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.CreatedAtPageQuery;
import com.surprising.trading.api.utils.AdminCursorPage;
import java.util.Locale;

/** 登录与管理操作审计入口的参数绑定；不访问身份或持久化状态。 */
public final class AuditLogRequestBinding {
    private AuditLogRequestBinding() {}

    public static CreatedAtPageQuery page(int limit, String cursor, String sort) {
        String token = sort == null || sort.isBlank() ? "createdAt.desc" : sort.trim();
        if (!token.equals("createdAt.asc") && !token.equals("createdAt.desc")) {
            throw new IllegalArgumentException("unsupported sort: " + sort);
        }
        try {
            return new CreatedAtPageQuery(AdminCursorPage.limit(limit,500),
                    AdminCursorPage.decodeCursor(cursor),token.equals("createdAt.desc"));
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("invalid cursor",invalid);
        }
    }

    public static String result(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    public static String service(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String method(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
