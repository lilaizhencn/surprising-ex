package com.surprising.gateway.provider.auth.model;

import com.surprising.gateway.provider.auth.utils.AdminCursorPage.Cursor;

/** 已绑定的创建时间/long主键分页坐标，不携带HTTP游标文本或SQL列名。 */
public record CreatedAtPageQuery(int limit, Cursor after, boolean descending) {
}
