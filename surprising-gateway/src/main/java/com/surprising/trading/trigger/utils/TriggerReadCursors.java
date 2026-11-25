package com.surprising.trading.trigger.utils;

import com.surprising.trading.trigger.model.TriggerReadCursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 条件单历史游标的稳定协议格式；无业务状态或SQL职责。 */
public final class TriggerReadCursors {
    private TriggerReadCursors() {}

    public static TriggerReadCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3 || !parts[0].equals("trigger-v1")) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[1]);
            long id = Long.parseLong(parts[2]);
            if (time <= 0 || id <= 0) throw new IllegalArgumentException();
            return new TriggerReadCursor(time, id);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("trigger cursor is invalid", invalid);
        }
    }

    public static String encode(TriggerReadCursor cursor) {
        String text = "trigger-v1:" + cursor.createdAt() + ':' + cursor.triggerOrderId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
