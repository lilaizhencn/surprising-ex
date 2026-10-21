package com.surprising.gateway.provider.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 提现意图及原生数据库状态，供业务聚合和单表持久化共同使用。 */
public final class CustodyWithdrawalModels {
    private CustodyWithdrawalModels() { }
    public record CreateRequest(long userId, String idempotencyKey, String requestHash, String chain,
                                String assetSymbol, UUID custodyAddressId, String toAddress, String amount,
                                long amountUnits, BigDecimal usdtValue, String externalReference,
                                String spotDebitReference, String requestPayload, boolean requiresApproval,
                                BigDecimal dailyLimitUsdt) {
    }

    public record CreateResult(WithdrawalRecord record, boolean created) {
    }

    public record WithdrawalRecord(UUID withdrawalId, long userId, String idempotencyKey, String requestHash,
                                   String chain, String assetSymbol, UUID custodyAddressId, String toAddress,
                                   String amount, long amountUnits, BigDecimal usdtValue, String externalReference,
                                   String spotDebitReference, String requestPayload, String status,
                                   String walletResponse, String walletWithdrawalId, String errorCode,
                                   String errorMessage, Instant createdAt, Instant updatedAt, Instant submittedAt,
                                   Instant completedAt, Long adminUserId, String adminUsername, String adminReason) {
    }
}
