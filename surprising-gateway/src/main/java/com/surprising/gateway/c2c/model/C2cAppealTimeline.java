package com.surprising.gateway.c2c.model;

import java.time.Instant;
import java.util.UUID;

/** 订单双方可见的申诉时间线；不包含后台审批人与审批记录身份。 */
public record C2cAppealTimeline(UUID appealId, long openerUserId, String reason, String status, String decision,
                                String decisionReason, Instant createdAt, Instant resolvedAt) { }
