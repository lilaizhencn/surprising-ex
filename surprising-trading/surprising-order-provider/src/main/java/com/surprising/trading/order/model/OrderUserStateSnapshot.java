package com.surprising.trading.order.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/**
 * 用户订单完整状态广播事件。
 *
 * <p>事件按 {@code productLine:userId} 写入压缩 Topic，用于 Kafka 分区迁移或新节点启动时
 * 初始化本地 RocksDB。{@link #stateRevision()} 是跨节点单调修订号，与每个节点自己的 WAL
 * 序号分离；本地 WAL 只能从快照之后重新编号，不能拿本地序号比较不同节点的状态新旧。</p>
 */
public record OrderUserStateSnapshot(
        int schemaVersion,
        ProductLine productLine,
        long userId,
        long stateRevision,
        OrderUserState state,
        Instant eventTime) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public OrderUserStateSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的订单完整快照版本: " + schemaVersion);
        }
        if (productLine == null || userId <= 0L || stateRevision <= 0L || state == null
                || state.revision() != stateRevision || eventTime == null) {
            throw new IllegalArgumentException("订单完整快照字段不完整");
        }
        if (state.orders().stream().anyMatch(order -> order.productLine() != productLine
                || order.userId() != userId)) {
            throw new IllegalArgumentException("订单完整快照包含其他产品线或用户订单");
        }
    }

    public String partitionKey() {
        return productLine.name() + ":" + userId;
    }
}
