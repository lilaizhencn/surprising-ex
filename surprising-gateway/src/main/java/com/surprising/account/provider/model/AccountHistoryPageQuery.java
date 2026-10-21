package com.surprising.account.provider.model;

import com.surprising.account.api.utils.AdminCursorPage.Cursor;

/** 历史投影入口绑定的创建时间/long主键分页；不携带HTTP文本或SQL列名。 */
public record AccountHistoryPageQuery(int limit, Cursor after, boolean descending) {
}
