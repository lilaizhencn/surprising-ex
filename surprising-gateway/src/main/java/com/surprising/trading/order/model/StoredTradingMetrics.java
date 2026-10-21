package com.surprising.trading.order.model;

/** core_trading_statistics_snapshot的原生持久化事实，JSON保持Core投影的原始版本与数值。 */
public record StoredTradingMetrics(long clusterPosition, long generatedAtEpochMs, String metricsJson) {}
