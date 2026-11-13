package com.surprising.adl.provider.model;

import java.time.Instant;

/** Controller绑定的历史查询坐标；不含HTTP游标文本或SQL列。 */
public record AdlEventPageQuery(int limit, Instant afterTime, long afterId, boolean descending) {
}
