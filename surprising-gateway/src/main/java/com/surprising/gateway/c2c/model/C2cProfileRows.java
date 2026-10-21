package com.surprising.gateway.c2c.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 本人设置及列表的持久化投影；数值/标识保持原生类型，HTTP格式化由聚合层负责。 */
public final class C2cProfileRows {
    private C2cProfileRows() { }
    public record Profile(String nickname,boolean strangers,int verificationMinutes,long revision) { }
    public record OrderStats(long completed,long canceled,long ongoing,long appealed) { }
    public record QuickReply(UUID id,long userId,String text) { }
    public record Relation(long targetUserId,String nickname,String kind,String merchantStatus,String tier) { }
    public record PriceAlert(UUID id,UUID marketId,String side,BigDecimal targetPrice,boolean enabled,long revision,Instant triggeredAt) { }
    public record Notification(long id,String kind,UUID entityId,JsonNode details,boolean read,Instant createdAt) { }
    public record Escrow(String asset,long assetScale,BigDecimal held,long pending,long failed) { }
}
