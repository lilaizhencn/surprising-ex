package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderFeeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 订单模块启动时从费率事实表恢复本产品线的 JVM 快照。 */
@Service
public class FeeScheduleSnapshotInitializer {

    private static final Logger log = LoggerFactory.getLogger(FeeScheduleSnapshotInitializer.class);

    private final TradingOrderProperties properties;
    private final OrderFeeRepository repository;
    private final FeeScheduleSnapshotCache cache;

    public FeeScheduleSnapshotInitializer(TradingOrderProperties properties,
                                           OrderFeeRepository repository,
                                           FeeScheduleSnapshotCache cache) {
        this.properties = properties;
        this.repository = repository;
        this.cache = cache;
    }

    @PostConstruct
    public void initialize() {
        ProductLine productLine = properties.getKafka().getProductLine();
        try {
            cache.replace(productLine, repository.loadSnapshotSchedules(productLine));
        } catch (RuntimeException ex) {
            // 费率表恢复失败时仍标记快照已初始化；下单会安全回退到 Instrument 默认费率。
            cache.replace(productLine, java.util.List.of());
            log.error("费率快照启动恢复失败，将使用 Instrument 默认费率 productLine={}", productLine, ex);
        }
    }
}
