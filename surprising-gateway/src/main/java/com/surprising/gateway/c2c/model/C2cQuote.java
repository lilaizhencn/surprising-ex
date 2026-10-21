package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record C2cQuote(UUID id,long userId,UUID adId,long adRevision,long marketRevision,long quantityUnits,long feeUnits,
                 BigDecimal fiatAmount,BigDecimal price,UUID paymentAccountId,long accountRevision,Instant expiresAt) { }
