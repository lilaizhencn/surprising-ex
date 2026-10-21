package com.surprising.gateway.c2c.model;

/** HTTP 字段明确保留 int64/金额字符串以及可空字段，避免密文随数据库行直接序列化。 */
public final class C2cAdminViews {
    private C2cAdminViews() { }

    public record Merchant(String userId, String nickname, String status, String tier, String application,
                           String reviewReason, String revision, String kycStatus, String bondStatus,
                           String bondAsset, String bondAmount, String kycExpiresAt, String createdAt) { }

    public record PaymentAccount(String accountId, String userId, String nickname, String fiat, String methodCode,
                                 String maskedLabel, String status, String revision, String reviewReason, String createdAt) { }

    public record FundIntent(String intentId, String transferId, String ownerType, String ownerId, String purpose,
                             String sourceUserId, String targetUserId, String asset, String amountUnits, String status,
                             String errorCode, int attempts, String retryAfter, String updatedAt) { }

    public record Appeal(String appealId, String orderId, String openerUserId, String status, String reason, String decision,
                         String decisionReason, String approvalId, String decidedBy, String createdAt) { }

    public record Report(String reportId, String userId, String targetUserId, String orderId, String reason, String status,
                         String reviewReason, String createdAt) { }
}
