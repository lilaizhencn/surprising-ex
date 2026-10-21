package com.surprising.trading.trigger.utils;

import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.model.TriggerReadCursor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** 条件单历史游标的稳定协议格式；无业务状态或SQL职责。 */
public final class TriggerReadCursors {
    private TriggerReadCursors() {}

    public static String scope(ProductLine product, Long user, String instrument, CoreTriggerOrderStatus status,
            Long trigger, boolean ascending) {
        String text = product.name() + '|' + user + '|' + instrument + '|' + status + '|' + trigger + '|' + (ascending ? "createdAt.asc" : "createdAt.desc");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static TriggerReadCursor decode(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 4 || !parts[0].equals("trigger-v1") || !parts[1].equals(scope)) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[2]);
            long id = Long.parseLong(parts[3]);
            if (time <= 0 || id <= 0) throw new IllegalArgumentException();
            return new TriggerReadCursor(time, id);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("trigger cursor does not match query", invalid);
        }
    }

    public static String encode(String scope, TriggerReadCursor cursor) {
        String text = "trigger-v1:" + scope + ':' + cursor.createdAt() + ':' + cursor.triggerOrderId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
