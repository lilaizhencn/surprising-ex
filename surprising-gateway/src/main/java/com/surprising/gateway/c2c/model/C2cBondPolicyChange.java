package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;

public record C2cBondPolicyChange(long revision,String asset,BigDecimal amount) { }
