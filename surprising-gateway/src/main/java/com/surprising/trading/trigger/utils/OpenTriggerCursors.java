package com.surprising.trading.trigger.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 活动条件单游标的纯协议编解码；不读取交易状态。 */
public final class OpenTriggerCursors {
    private OpenTriggerCursors() { }

    public static String encode(long triggerOrderId) {
        if (triggerOrderId <= 0) throw new IllegalArgumentException("open-trigger cursor id must be positive");
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("trigger:" + triggerOrderId).getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0L;
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith("trigger:")) throw new IllegalArgumentException("invalid open-trigger cursor");
            long id = Long.parseLong(decoded.substring("trigger:".length()));
            if (id <= 0) throw new IllegalArgumentException("invalid open-trigger cursor");
            return id;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid open-trigger cursor", exception);
        }
    }
}
