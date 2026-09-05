package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.OrderFeeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/** 订单模块启动时从费率事实表恢复本产品线的 JVM 快照。 */
@Service
public class FeeScheduleSnapshotInitializer {

    private final TradingOrderProperties properties;
    private final OrderFeeRepository repository;
    private final FeeScheduleSnapshotCache cache;
    private final FeePolicyCoreImporter coreImporter;

    public FeeScheduleSnapshotInitializer(TradingOrderProperties properties,
                                           OrderFeeRepository repository,
                                           FeeScheduleSnapshotCache cache,
                                           FeePolicyCoreImporter coreImporter) {
        this.properties = properties;
        this.repository = repository;
        this.cache = cache;
        this.coreImporter = coreImporter;
    }

    @PostConstruct
    public void initialize() {
        ProductLine productLine = properties.getKafka().getProductLine();
        var schedules = repository.loadSnapshotSchedules(productLine);
        schedules.forEach(coreImporter::importPolicy);
        cache.replace(productLine, schedules);
    }
}
