package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record C2cOrder(UUID id,UUID quoteId,UUID adId,UUID marketId,long creatorUserId,long buyerUserId,long sellerUserId,
                 long quantityUnits,long feeUnits,BigDecimal fiatAmount,BigDecimal price,
                 UUID paymentAccountId,String paymentCiphertext,int paymentWindowSeconds,String terms,C2cOrderStatus status,
                 String requestedTerminal,Instant paymentExpiresAt,Instant paidAt,Instant completedAt,Instant canceledAt,
                 String cancelReason,long revision,Instant createdAt) { }
