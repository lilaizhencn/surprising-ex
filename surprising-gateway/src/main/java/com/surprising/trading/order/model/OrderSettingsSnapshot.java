package com.surprising.trading.order.model;

import com.surprising.trading.order.model.OrderBusinessSettings;
import java.time.Instant;

/** 单一产品线数据库版本与完整订单规则。 */
public record OrderSettingsSnapshot(long configurationVersion, OrderBusinessSettings settings,
                                    String updatedBy, String reason, Instant updatedAt) { }
