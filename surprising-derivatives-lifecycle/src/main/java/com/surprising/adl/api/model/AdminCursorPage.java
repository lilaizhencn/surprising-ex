package com.surprising.adl.api.model;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

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
            int split = decoded.indexOf(':');
            if (split <= 0 || split == decoded.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            long epochMillis = Long.parseLong(decoded.substring(0, split));
            long id = Long.parseLong(decoded.substring(split + 1));
            return new Cursor(Instant.ofEpochMilli(epochMillis), id);
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
        String raw = timestamp.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public record SortSpec(String field, String column, String idColumn, boolean descending) {
        public String token() {
            return field + "." + (descending ? "desc" : "asc");
        }

        public String directionSql() {
            return descending ? "DESC" : "ASC";
        }
    }

    public record Cursor(Instant timestamp, long id) {
    }

    public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore, String sort, int limit) {
    }
}
