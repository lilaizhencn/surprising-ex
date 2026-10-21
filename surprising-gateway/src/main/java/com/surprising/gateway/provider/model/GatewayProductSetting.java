package com.surprising.gateway.provider.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/** 数据库中保存的产品线接入意图，与本机连接状态分开。 */
public record GatewayProductSetting(ProductLine productLine, boolean enabled, long configurationVersion,
                                    String updatedBy, String reason, Instant updatedAt) { }
