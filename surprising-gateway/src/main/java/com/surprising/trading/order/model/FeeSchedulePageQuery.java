package com.surprising.trading.order.model;

import com.surprising.trading.api.utils.AdminCursorPage.Cursor;

/** 费率历史查询的原生分页；入口解析文本，Repository映射固定SQL列。 */
public record FeeSchedulePageQuery(int limit, Cursor after, Order order) {
    public enum Order {
        UPDATED_DESC("updatedAt", true), UPDATED_ASC("updatedAt", false),
        CREATED_DESC("createdAt", true), CREATED_ASC("createdAt", false),
        EFFECTIVE_DESC("effectiveTime", true), EFFECTIVE_ASC("effectiveTime", false);

        private final String field;
        private final boolean descending;

        Order(String field, boolean descending) {
            this.field = field;
            this.descending = descending;
        }

        public String field() { return field; }
        public boolean descending() { return descending; }
        public String token() { return field + "." + (descending ? "desc" : "asc"); }
    }
}
