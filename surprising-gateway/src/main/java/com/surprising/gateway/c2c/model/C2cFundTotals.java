package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;

public record C2cFundTotals(String asset,BigDecimal escrow,BigDecimal fees) { }
