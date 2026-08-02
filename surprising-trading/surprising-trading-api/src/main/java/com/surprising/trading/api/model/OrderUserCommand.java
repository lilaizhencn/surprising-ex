package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/**
 * 按用户分区路由的订单事实命令。
 *
 * <p>命令载荷保持为 JSON 字符串，使订单服务可以演进内部订单模型，同时通过 Kafka key
 * 固定同一用户的单写入顺序。</p>
 */
public record OrderUserCommand(
        int schemaVersion,
        String commandId,
        ProductLine productLine,
        long userId,
        OrderUserCommandType commandType,
        String payload,
        Instant occurredAt,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public OrderUserCommand {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的订单用户命令版本: " + schemaVersion);
        }
        commandId = requireText(commandId, "commandId", 200);
        if (productLine == null) {
            throw new IllegalArgumentException("订单用户命令产品线不能为空");
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("订单用户命令用户编号必须为正数");
        }
        if (commandType == null) {
            throw new IllegalArgumentException("订单用户命令类型不能为空");
        }
        payload = requireText(payload, "payload", 16 * 1024 * 1024);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        traceId = normalize(traceId, 200);
    }

    public String partitionKey() {
        return partitionKey(productLine, userId);
    }

    public static String partitionKey(ProductLine productLine, long userId) {
        if (productLine == null || userId <= 0L) {
            throw new IllegalArgumentException("订单用户分区键无效");
        }
        return productLine.name() + ":" + userId;
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = normalize(value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("字段长度不能超过 " + maxLength);
        }
        return normalized;
    }
}
