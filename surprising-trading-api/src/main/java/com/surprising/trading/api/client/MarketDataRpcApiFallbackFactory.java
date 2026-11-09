package com.surprising.trading.api.client;

import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;

/**
 * 盘口行情 RPC 客户端熔断保护。
 */
@Component
public class MarketDataRpcApiFallbackFactory implements FallbackFactory<MarketDataRpcApi> {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRpcApiFallbackFactory.class);

    @Override
    public MarketDataRpcApi create(Throwable cause) {
        return new MarketDataRpcApi() {
            @Override
            public OrderBookSnapshotResponse orderBook(String instrumentId, int depth) {
                log.warn("MarketDataRpcApi#orderBook 熔断触发: instrumentId={}, depth={}, 原因: {}",
                        instrumentId, depth, cause != null ? cause.getMessage() : "circuit breaker");
                return new OrderBookSnapshotResponse(
                        instrumentId,
                        -1L,
                        depth,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Instant.now());
            }
        };
    }
}
