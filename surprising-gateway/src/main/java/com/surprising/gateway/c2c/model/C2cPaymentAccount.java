package com.surprising.gateway.c2c.model;

import java.util.UUID;

/** 持久收款事实；HTTP只能通过脱敏视图或授权解密结果返回，禁止直接序列化本记录。 */
public record C2cPaymentAccount(UUID id, long userId, String fiat, String method, String ciphertext,
                                String maskedLabel, String status, long revision) { }
