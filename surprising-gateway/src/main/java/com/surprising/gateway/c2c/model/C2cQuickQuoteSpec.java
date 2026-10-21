package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.util.UUID;

public record C2cQuickQuoteSpec(UUID marketId, C2cOfferFilter.Side side, BigDecimal amount,
                                String methodCode, UUID paymentAccountId) { }
