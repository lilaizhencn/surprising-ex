package com.surprising.instrument.provider.model;

import java.time.Instant;

/** 已在Controller解析的分页条件；SQL列名由Repository持有。 */
public record InstrumentPageQuery(int limit, Sort sort, Cursor cursor) {
    public enum Sort {
        SYMBOL_ASC("symbol", false), SYMBOL_DESC("symbol", true),
        UPDATED_AT_DESC("updatedAt", true), UPDATED_AT_ASC("updatedAt", false),
        CREATED_AT_DESC("createdAt", true), CREATED_AT_ASC("createdAt", false);
        private final String field;
        private final boolean descending;
        Sort(String field, boolean descending) { this.field = field; this.descending = descending; }
        public String field() { return field; }
        public boolean descending() { return descending; }
        public String token() { return field + (descending ? ".desc" : ".asc"); }
        public boolean symbolSort() { return field.equals("symbol"); }
    }
    public record Cursor(String symbol, Instant timestamp) {}
}
