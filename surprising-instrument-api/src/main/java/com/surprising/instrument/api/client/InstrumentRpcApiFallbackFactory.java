package com.surprising.instrument.api.client;

import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentTradeEncoding;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 当网关下游服务发生超时、网络中断或触发熔断保护时的降级工厂实现。
 */
@Component
public class InstrumentRpcApiFallbackFactory implements FallbackFactory<InstrumentRpcApi> {

    private static final Logger log = LoggerFactory.getLogger(InstrumentRpcApiFallbackFactory.class);

    @Override
    public InstrumentRpcApi create(Throwable cause) {
        return new InstrumentRpcApi() {
            @Override
            public InstrumentResponse latest(int instrumentId, ProductLine productLine) {
                log.warn("InstrumentRpcApi#latest 熔断触发: instrumentId={}, productLine={}, 原因: {}",
                        instrumentId, productLine, cause != null ? cause.getMessage() : "unknown");
                throw new IllegalStateException("交易网关合约服务不可用或熔断中: " + (cause != null ? cause.getMessage() : "timeout"), cause);
            }

            @Override
            public InstrumentQueryResponse list(ProductLine productLine, InstrumentType type, InstrumentStatus status) {
                log.warn("InstrumentRpcApi#list 熔断触发: productLine={}, type={}, status={}, 原因: {}",
                        productLine, type, status, cause != null ? cause.getMessage() : "unknown");
                return new InstrumentQueryResponse(0, Collections.emptyList());
            }

            @Override
            public InstrumentTradeEncoding tradeEncoding(ProductLine productLine, int instrumentId, long changeId) {
                log.warn("InstrumentRpcApi#tradeEncoding 熔断触发: productLine={}, instrumentId={}, 原因: {}",
                        productLine, instrumentId, cause != null ? cause.getMessage() : "unknown");
                throw new IllegalStateException("交易网关合约编码服务不可用或熔断中: " + (cause != null ? cause.getMessage() : "timeout"), cause);
            }

            @Override
            public InstrumentSnapshotResponse snapshot(ProductLine productLine) {
                log.warn("InstrumentRpcApi#snapshot 熔断触发: productLine={}, 原因: {}",
                        productLine, cause != null ? cause.getMessage() : "unknown");
                return new InstrumentSnapshotResponse(productLine, 0L, "FALLBACK", Collections.emptyList());
            }
        };
    }
}
