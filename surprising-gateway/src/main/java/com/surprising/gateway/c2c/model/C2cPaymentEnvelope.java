package com.surprising.gateway.c2c.model;

import java.util.UUID;

/** 加密资料的持久格式，只由付款Service验证后转换为公开解密结果。 */
public record C2cPaymentEnvelope(String purpose, String userId, String fiat, String methodCode,
                                 String holderName, String accountNumber, String bankName, UUID qrAttachmentId) { }
