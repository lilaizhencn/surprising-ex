package com.surprising.trading.api.utils;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * 统一管理端基于时间戳与主键 ID 的 Base64 URL 安全游标分页工具。
 */
public final class AdminCursorPage {

    private AdminCursorPage() {
    }

    public static int limit(int value, int maxLimit) {
        return Math.max(1, Math.min(value, maxLimit));
    }

    public static SortSpec parseSort(String value, SortSpec defaultSort, List<SortSpec> allowed) {
        if (value == null || value.isBlank()) {
            return defaultSort;
        }
        String normalized = value.trim();
        return allowed.stream()
                .filter(spec -> spec.token().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + value));
    }

    public static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            int splitPipe = decoded.lastIndexOf('|');
            if (splitPipe > 0 && splitPipe < decoded.length() - 1) {
                Instant timestamp = Instant.parse(decoded.substring(0, splitPipe));
                long id = Long.parseLong(decoded.substring(splitPipe + 1));
                return new Cursor(timestamp, id);
            }
            int splitColon = decoded.indexOf(':');
            if (splitColon > 0 && splitColon < decoded.length() - 1) {
                long epochMillis = Long.parseLong(decoded.substring(0, splitColon));
                long id = Long.parseLong(decoded.substring(splitColon + 1));
                return new Cursor(Instant.ofEpochMilli(epochMillis), id);
            }
            throw new IllegalArgumentException("invalid cursor");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    public static String seekCondition(SortSpec sort) {
        String operator = sort.descending() ? "<" : ">";
        return " AND (" + sort.column() + " " + operator + " ? OR ("
                + sort.column() + " = ? AND " + sort.idColumn() + " " + operator + " ?))";
    }

    public static String seekCondition(SortSpec sort, Cursor cursor) {
        return cursor == null ? "" : seekCondition(sort);
    }

    public static void addCursorArgs(List<Object> args, Cursor cursor) {
        if (cursor == null) {
            return;
        }
        Timestamp time = Timestamp.from(cursor.timestamp());
        args.add(time);
        args.add(time);
        args.add(cursor.id());
    }

    public static <T> CursorPage<T> page(List<T> fetchedRows,
                                         int limit,
                                         SortSpec sort,
                                         Function<T, Instant> timestampExtractor,
                                         ToLongFunction<T> idExtractor) {
        boolean hasMore = fetchedRows.size() > limit;
        List<T> rows = hasMore ? List.copyOf(fetchedRows.subList(0, limit)) : List.copyOf(fetchedRows);
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            T last = rows.get(rows.size() - 1);
            nextCursor = encodeCursor(timestampExtractor.apply(last), idExtractor.applyAsLong(last));
        }
        return new CursorPage<>(rows, nextCursor, hasMore, sort.token(), limit);
    }

    private static String encodeCursor(Instant timestamp, long id) {
        if (timestamp == null) {
            throw new IllegalArgumentException("cursor timestamp is required");
        }
        String raw = timestamp + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public record SortSpec(String field, String column, String idColumn, boolean descending) {
        public SortSpec(String field, boolean descending) {
            this(field, field, "id", descending);
        }

        public String token() {
            return field + "." + (descending ? "desc" : "asc");
        }

        public String directionSql() {
            return descending ? "DESC" : "ASC";
        }
    }

    public record Cursor(Instant timestamp, long id) {
        public Cursor {
            if (timestamp == null) {
                throw new IllegalArgumentException("cursor timestamp is required");
            }
        }
    }

    public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore, String sort, int limit) {
        public CursorPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
