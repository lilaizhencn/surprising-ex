package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 下单聚合已确定的交易双方、价格、费用与收款快照；Repository只负责写入这些事实。 */
public record C2cOrderPlacement(UUID id, UUID quoteId, UUID adId, UUID marketId, long creatorUserId,
                                long buyerUserId, long sellerUserId, long quantityUnits, long feeUnits,
                                BigDecimal fiatAmount, BigDecimal price, UUID paymentAccountId,
                                String paymentCiphertext, int paymentWindowSeconds, String terms,
                                C2cOrderStatus status, Instant paymentExpiresAt) { }
