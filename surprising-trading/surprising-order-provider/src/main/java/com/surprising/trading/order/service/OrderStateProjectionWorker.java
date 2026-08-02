package com.surprising.trading.order.service;

import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderUserState;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 按用户分区把订单 RocksDB 状态异步投影到数据库。
 *
 * <p>投影水位只在数据库事务成功后推进；崩溃发生在事务提交和水位推进之间时，下一轮会
 * 幂等重放同一份完整快照。投影失败不会阻塞其他用户分区，也不会让订单事实流越过本地序号。</p>
 */
@Service
public class OrderStateProjectionWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderStateProjectionWorker.class);

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final UserPartitionWal wal;
    private final UserPartitionStateStore stateStore;
    private final OrderStateProjectionService projectionService;

    public OrderStateProjectionWorker(ObjectMapper objectMapper,
                                      TradingOrderProperties properties,
                                      UserPartitionWal wal,
                                      UserPartitionStateStore stateStore,
                                      OrderStateProjectionService projectionService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.stateStore = stateStore;
        this.projectionService = projectionService;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.wal.projection-delay-ms:25}")
    public void projectPending() {
        for (UserPartitionKey partition : wal.partitions()) {
            try {
                projectPartition(partition);
            } catch (RuntimeException ex) {
                log.warn("订单数据库投影失败 partition={}", partition.value(), ex);
            }
        }
    }

    private void projectPartition(UserPartitionKey partition) {
        ProductLine currentLine = properties.getKafka().getProductLine();
        if (partition.productLine() != currentLine) {
            throw new IllegalStateException("订单投影产品线不匹配 partition=" + partition.value());
        }
        UserPartitionStateStore.StateSnapshot stored = stateStore.read(partition).orElse(null);
        if (stored == null) {
            // 本地状态尚未完成初始化，不能把数据库清空成“空用户”。
            return;
        }
        long applied = stored.sequence();
        long projected = wal.lastProjectedSequence(partition);
        if (applied < projected) {
            throw new IllegalStateException("订单投影水位领先本地状态 partition=" + partition.value());
        }
        if (applied == projected) {
            return;
        }
        OrderUserState state = decode(stored.state(), partition);
        projectionService.project(currentLine, partition.userId(), state);
        // 只有上面的数据库事务返回成功后，才允许推进本地投影水位。
        wal.markProjectedThrough(partition, applied);
    }

    private OrderUserState decode(byte[] bytes, UserPartitionKey partition) {
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), OrderUserState.class);
        } catch (Exception ex) {
            throw new IllegalStateException("订单本地状态无法解析 partition=" + partition.value(), ex);
        }
    }
}
