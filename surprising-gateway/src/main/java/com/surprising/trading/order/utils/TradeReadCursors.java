package com.surprising.trading.order.utils;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.TradeReadCursor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** 稳定游标格式转换；请求入口验证作用域，SQL只使用原生坐标。 */
public final class TradeReadCursors {
    private TradeReadCursors() {}

    public static String fillScope(ProductLine product, long user, String instrument, Long order, Long start, Long end) {
        return digest(product.name() + '|' + user + '|' + instrument + '|' + order + '|' + start + '|' + end);
    }

    public static String executionScope(ProductLine product, Long user, String instrument, Long order, boolean ascending) {
        return digest(product.name() + '|' + user + '|' + instrument + '|' + order + '|' + (ascending ? "eventTime.asc" : "eventTime.desc"));
    }

    public static TradeReadCursor.Fill fill(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = decoded(cursor);
            if (parts.length != 6 || !parts[0].equals("user-fill-v1") || !parts[1].equals(scope)) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[2]);
            long position = Long.parseLong(parts[3]);
            long index = Long.parseLong(parts[4]);
            if (time <= 0 || position <= 0 || index < 0 || index > Integer.MAX_VALUE || !Set.of("MAKER", "TAKER").contains(parts[5]))
                throw new IllegalArgumentException();
            return new TradeReadCursor.Fill(time, position, (int) index, parts[5]);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("fill cursor does not match query", invalid);
        }
    }

    public static TradeReadCursor.Execution execution(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = decoded(cursor);
            if (parts.length != 5 || !parts[0].equals("trade-v1") || !parts[1].equals(scope)) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[2]);
            long position = Long.parseLong(parts[3]);
            long index = Long.parseLong(parts[4]);
            if (time <= 0 || position <= 0 || index < 0 || index > Integer.MAX_VALUE) throw new IllegalArgumentException();
            return new TradeReadCursor.Execution(time, position, (int) index);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("trade cursor does not match query", invalid);
        }
    }

    public static String encode(String scope, TradeReadCursor.Fill cursor) {
        return encoded("user-fill-v1:" + scope + ':' + cursor.time() + ':' + cursor.position() + ':' + cursor.index() + ':' + cursor.role());
    }

    public static String encode(String scope, TradeReadCursor.Execution cursor) {
        return encoded("trade-v1:" + scope + ':' + cursor.time() + ':' + cursor.position() + ':' + cursor.index());
    }

    private static String[] decoded(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
