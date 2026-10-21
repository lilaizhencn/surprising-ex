package com.surprising.gateway.c2c.model;

import java.time.Instant;
import java.util.UUID;

/** 后台有界列表的数据库读取投影；不含付款密文、完整账号或证件原件。 */
public final class C2cAdminRows {
    private C2cAdminRows() { }

    public record Merchant(long userId, String nickname, String status, String tier, String application,
                           String reviewReason, long revision, String kycStatus, Instant kycExpiresAt,
                           String bondStatus, String bondAsset, Long bondUnits, long bondScale, Instant createdAt) { }

    public record PaymentAccount(UUID accountId, long userId, String nickname, String fiat, String methodCode,
                                 String maskedLabel, String status, long revision, String reviewReason, Instant createdAt) { }

    public record FundIntent(UUID intentId, long transferId, String ownerType, UUID ownerId, String purpose,
                             long sourceUserId, long targetUserId, String asset, long amountUnits, String status,
                             String errorCode, int attempts, Instant retryAfter, Instant updatedAt) { }

    public record Appeal(UUID appealId, UUID orderId, long openerUserId, String status, String reason, String decision,
                         String decisionReason, Long approvalId, Long decidedBy, Instant createdAt) { }

    public record Report(UUID reportId, long userId, long targetUserId, UUID orderId, String reason, String status,
                         String reviewReason, Instant createdAt) { }
}
