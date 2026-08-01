package com.surprising.liquidation.provider.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.client.TradingFeeRpcApi;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 强平启动时通过唯一费率 RPC 初始化本地快照，不直接访问费率表。 */
@Service
public class FeeScheduleSnapshotInitializer {

    private static final Logger log = LoggerFactory.getLogger(FeeScheduleSnapshotInitializer.class);

    private final LiquidationProperties properties;
    private final TradingFeeRpcApi tradingFeeRpcApi;
    private final FeeScheduleSnapshotCache cache;

    public FeeScheduleSnapshotInitializer(LiquidationProperties properties,
                                          TradingFeeRpcApi tradingFeeRpcApi,
                                          FeeScheduleSnapshotCache cache) {
        this.properties = properties;
        this.tradingFeeRpcApi = tradingFeeRpcApi;
        this.cache = cache;
    }

    @PostConstruct
    public void initialize() {
        ProductLine productLine = properties.getKafka().getProductLine();
        try {
            var snapshot = tradingFeeRpcApi.snapshot(productLine);
            cache.replace(productLine, snapshot.schedules());
        } catch (RuntimeException ex) {
            // 费率 RPC 暂不可用时不阻塞强平启动，使用 Instrument 默认费率继续安全计算。
            cache.replace(productLine, java.util.List.of());
            log.error("费率快照 RPC 初始化失败，将使用 Instrument 默认费率 productLine={}", productLine, ex);
        }
    }
}
