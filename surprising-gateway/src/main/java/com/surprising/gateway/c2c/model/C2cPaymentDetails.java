package com.surprising.gateway.c2c.model;

import java.util.UUID;

/** 授权后解密得到的明确字段；不包含密文、内部用途标记或用户身份声明。 */
public record C2cPaymentDetails(String fiat, String methodCode, String holderName, String accountNumber,
                                String bankName, UUID qrAttachmentId) { }
