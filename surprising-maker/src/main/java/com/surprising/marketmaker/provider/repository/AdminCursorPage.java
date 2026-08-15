package com.surprising.marketmaker.provider.repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

final class AdminCursorPage {

    private AdminCursorPage() {
    }

    static int limit(int value, int maxLimit) {
        return Math.max(1, Math.min(value, maxLimit));
    }

    static SortSpec parseSort(String value, SortSpec defaultSort, List<SortSpec> allowed) {
        if (value == null || value.isBlank()) {
            return defaultSort;
        }
        String normalized = value.trim();
        return allowed.stream()
                .filter(spec -> spec.token().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + value));
    }

    static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf('|');
            if (split <= 0 || split == decoded.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            Instant timestamp = Instant.parse(decoded.substring(0, split));
            long id = Long.parseLong(decoded.substring(split + 1));
            return new Cursor(timestamp, id);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    static String seekCondition(SortSpec sort) {
        String operator = sort.descending() ? "<" : ">";
        return " AND (" + sort.column() + " " + operator + " ? OR ("
                + sort.column() + " = ? AND " + sort.idColumn() + " " + operator + " ?))";
    }

    static String seekCondition(SortSpec sort, Cursor cursor) {
        return cursor == null ? "" : seekCondition(sort);
    }

    static void addCursorArgs(List<Object> args, Cursor cursor) {
        if (cursor == null) {
            return;
        }
        Timestamp time = Timestamp.from(cursor.timestamp());
        args.add(time);
        args.add(time);
        args.add(cursor.id());
    }

    static <T> CursorPage<T> page(List<T> fetchedRows,
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

    record SortSpec(String field, String column, String idColumn, boolean descending) {
        String token() {
            return field + "." + (descending ? "desc" : "asc");
        }

        String directionSql() {
            return descending ? "DESC" : "ASC";
        }
    }

    record Cursor(Instant timestamp, long id) {
    }

    record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore, String sort, int limit) {
    }
}
