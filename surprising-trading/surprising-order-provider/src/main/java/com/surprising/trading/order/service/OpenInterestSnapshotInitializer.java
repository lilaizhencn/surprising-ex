package com.surprising.trading.order.service;

import com.surprising.account.api.client.AccountOpenInterestRpcApi;
import com.surprising.account.api.model.OpenInterestSnapshotResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 订单模块启动时通过账户内部 RPC 恢复未平仓量 JVM 快照。 */
@Service
public class OpenInterestSnapshotInitializer {

    private static final Logger log = LoggerFactory.getLogger(OpenInterestSnapshotInitializer.class);

    private final TradingOrderProperties properties;
    private final AccountOpenInterestRpcApi accountApi;
    private final OpenInterestSnapshotCache cache;

    public OpenInterestSnapshotInitializer(TradingOrderProperties properties,
                                           AccountOpenInterestRpcApi accountApi,
                                           OpenInterestSnapshotCache cache) {
        this.properties = properties;
        this.accountApi = accountApi;
        this.cache = cache;
    }

    @PostConstruct
    public void initialize() {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!productLine.isDerivative()) {
            cache.replace(productLine, java.util.List.of());
            return;
        }
        cache.markNotReady(productLine);
        try {
            OpenInterestSnapshotResponse response = accountApi.snapshot(productLine);
            if (response == null || response.productLine() != productLine) {
                throw new IllegalStateException("账户未平仓量快照产品线不匹配");
            }
            cache.replace(productLine, response.shards());
        } catch (RuntimeException ex) {
            // 初始化失败时保持未就绪；调用方必须失败关闭，禁止使用过期快照或回查数据库。
            log.error("未平仓量 JVM 快照初始化失败 productLine={}", productLine, ex);
        }
    }
}
