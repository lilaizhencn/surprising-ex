package com.surprising.trading.order.utils;

import com.surprising.trading.order.model.TradeReadCursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/** 稳定游标格式转换；SQL只使用原生坐标。 */
public final class TradeReadCursors {
    private TradeReadCursors() {}

    public static TradeReadCursor.Fill fill(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = decoded(cursor);
            if (parts.length != 5 || !parts[0].equals("user-fill-v1")) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[1]);
            long position = Long.parseLong(parts[2]);
            long index = Long.parseLong(parts[3]);
            if (time <= 0 || position <= 0 || index < 0 || index > Integer.MAX_VALUE || !Set.of("MAKER", "TAKER").contains(parts[4]))
                throw new IllegalArgumentException();
            return new TradeReadCursor.Fill(time, position, (int) index, parts[4]);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("fill cursor is invalid", invalid);
        }
    }

    public static TradeReadCursor.Execution execution(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = decoded(cursor);
            if (parts.length != 4 || !parts[0].equals("trade-v1")) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[1]);
            long position = Long.parseLong(parts[2]);
            long index = Long.parseLong(parts[3]);
            if (time <= 0 || position <= 0 || index < 0 || index > Integer.MAX_VALUE) throw new IllegalArgumentException();
            return new TradeReadCursor.Execution(time, position, (int) index);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("trade cursor is invalid", invalid);
        }
    }

    public static String encode(TradeReadCursor.Fill cursor) {
        return encoded("user-fill-v1:" + cursor.time() + ':' + cursor.position() + ':' + cursor.index() + ':' + cursor.role());
    }

    public static String encode(TradeReadCursor.Execution cursor) {
        return encoded("trade-v1:" + cursor.time() + ':' + cursor.position() + ':' + cursor.index());
    }

    private static String[] decoded(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
