package com.surprising.price.index.model;

import java.time.Instant;
import java.util.Map;

/** 提供方原始十进制报价；Service 按配置选择币种并执行精确换算与入库。 */
public record FiatRateSnapshot(String provider, Instant rateTime, Map<String, String> rates) {
}
