package com.surprising.gateway.c2c.model;

import java.util.UUID;

/** 可用于当前广告的脱敏收款选项，不选择或返回密文及完整账号。 */
public record C2cPaymentOption(UUID accountId, String methodCode, String maskedLabel) { }
