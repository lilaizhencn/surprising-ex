package com.surprising.gateway.provider.auth.model;

import com.surprising.gateway.provider.auth.utils.AdminCursorPage.Cursor;

/** HTTP绑定后的原生分页；排序只表示业务字段/方向，不包含SQL列名。 */
public record AuthPageQuery(int limit, Cursor after, Order order) {
    public enum Order {
        CREATED_DESC("createdAt", true), CREATED_ASC("createdAt", false),
        UPDATED_DESC("updatedAt", true), UPDATED_ASC("updatedAt", false),
        REQUESTED_DESC("requestedAt", true), REQUESTED_ASC("requestedAt", false);
        private final String field;
        private final boolean descending;
        Order(String field, boolean descending) {
            this.field = field;
            this.descending = descending;
        }
        public String field() {
            return field;
        }
        public boolean descending() {
            return descending;
        }
        public String token() {
            return field + (descending ? ".desc" : ".asc");
        }
    }
}
