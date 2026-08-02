package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/** 订单用户命令的可重放终态，结果库和 Kafka 结果 Topic 使用同一结构。 */
public record OrderUserCommandResult(
        int schemaVersion,
        String commandId,
        ProductLine productLine,
        long userId,
        OrderUserCommandType commandType,
        String commandFingerprint,
        OrderUserCommandStatus status,
        String resultPayload,
        String errorCode,
        String errorMessage,
        Instant occurredAt,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public OrderUserCommandResult {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的订单命令结果版本: " + schemaVersion);
        }
        commandId = requireText(commandId, "commandId", 200);
        if (productLine == null || userId <= 0L || commandType == null || status == null) {
            throw new IllegalArgumentException("订单命令结果身份不能为空");
        }
        commandFingerprint = requireText(commandFingerprint, "commandFingerprint", 128);
        resultPayload = normalize(resultPayload, 16 * 1024 * 1024);
        errorCode = normalize(errorCode, 200);
        errorMessage = normalize(errorMessage, 2_000);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        traceId = normalize(traceId, 200);
    }

    public String partitionKey() {
        return OrderUserCommand.partitionKey(productLine, userId);
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
