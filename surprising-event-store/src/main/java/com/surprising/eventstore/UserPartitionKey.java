package com.surprising.eventstore;

import com.surprising.product.api.ProductLine;

/**
 * 账户和订单事实流的固定分区键。
 * 同一个产品线和用户只能进入一个 Kafka 分区以及一个本地写入队列。
 */
public record UserPartitionKey(ProductLine productLine, long userId) {

    public UserPartitionKey {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }

    public String value() {
        return productLine.name() + ':' + userId;
    }

    public static UserPartitionKey parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("partition key is required");
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("partition key must be PRODUCT_LINE:userId");
        }
        try {
            return new UserPartitionKey(ProductLine.valueOf(value.substring(0, separator)),
                    Long.parseLong(value.substring(separator + 1)));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid partition key: " + value, ex);
        }
    }
}
