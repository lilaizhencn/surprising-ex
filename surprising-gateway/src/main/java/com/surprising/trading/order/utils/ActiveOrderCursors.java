package com.surprising.trading.order.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 活动订单游标的纯协议编解码；不读取交易状态。 */
public final class ActiveOrderCursors {
    private ActiveOrderCursors() { }
    private static final String ACTIVE_ORDER_CURSOR_PREFIX = "core-open:v1:";

    public static String encode(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid active order cursor");
        String value = ACTIVE_ORDER_CURSOR_PREFIX + orderId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!value.startsWith(ACTIVE_ORDER_CURSOR_PREFIX)) {
                throw new IllegalArgumentException("invalid active order cursor");
            }
            long orderId = Long.parseLong(value.substring(ACTIVE_ORDER_CURSOR_PREFIX.length()));
            if (orderId <= 0) throw new IllegalArgumentException("invalid active order cursor");
            return orderId;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid active order cursor", exception);
        }
    }
}
