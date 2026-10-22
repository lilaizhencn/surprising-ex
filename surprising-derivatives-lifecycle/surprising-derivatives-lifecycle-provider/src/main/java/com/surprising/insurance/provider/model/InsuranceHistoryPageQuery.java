package com.surprising.insurance.provider.model;

import java.time.Instant;

/** Controller绑定的历史查询坐标；不含HTTP游标文本或SQL列。 */
public record InsuranceHistoryPageQuery(int limit, Instant afterTime, long afterId, boolean descending) {
}
