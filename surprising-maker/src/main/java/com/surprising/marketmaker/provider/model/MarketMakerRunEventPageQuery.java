package com.surprising.marketmaker.provider.model;

import java.time.Instant;

/** 诊断日志的原生时间/序号分页，Controller负责文本绑定。 */
public record MarketMakerRunEventPageQuery(int limit, Instant afterTime, long afterId, boolean descending) {
}
