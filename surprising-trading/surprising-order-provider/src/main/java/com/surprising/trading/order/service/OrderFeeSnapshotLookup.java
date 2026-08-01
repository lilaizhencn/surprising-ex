package com.surprising.trading.order.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 统一解析订单费率。
 *
 * <p>在线只读取 Instrument 和费率 JVM 快照。费率快照不可用、事件延迟或缓存恢复失败时，
 * 统一回退到当前 Instrument 默认费率，不在下单热路径访问数据库。</p>
 */
@Service
public class OrderFeeSnapshotLookup {

    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final FeeScheduleSnapshotCache feeScheduleSnapshotCache;

    public OrderFeeSnapshotLookup(TradingOrderProperties properties,
                                  InstrumentSnapshotCache instrumentSnapshotCache,
                                  FeeScheduleSnapshotCache feeScheduleSnapshotCache) {
        this.properties = properties;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.feeScheduleSnapshotCache = feeScheduleSnapshotCache;
    }

    public Optional<OrderFeeSnapshot> lookup(ProductLine requestedProductLine,
                                             long userId,
                                             String symbol,
                                             long instrumentVersion,
                                             Instant now) {
        ProductLine productLine = requestedProductLine == null
                ? properties.getKafka().getProductLine() : requestedProductLine;
        var instrument = instrumentSnapshotCache.version(productLine, symbol, instrumentVersion);
        if (instrument.isEmpty()) {
            return Optional.empty();
        }
        var value = instrument.get();
        Optional<FeeScheduleResponse> schedule;
        try {
            schedule = feeScheduleSnapshotCache.effective(productLine, userId, symbol, now);
        } catch (RuntimeException ex) {
            // 费率覆盖快照损坏或尚未恢复时，使用 Instrument 默认费率继续完成安全校验。
            schedule = Optional.empty();
        }
        if (schedule.isEmpty()) {
            return Optional.of(new OrderFeeSnapshot(productLine, value.makerFeeRatePpm(), value.takerFeeRatePpm(),
                    "INSTRUMENT"));
        }
        FeeScheduleResponse selected = schedule.get();
        String scope = selected.symbol() == null ? "GLOBAL" : "SYMBOL";
        return Optional.of(new OrderFeeSnapshot(productLine, selected.makerFeeRatePpm(),
                selected.takerFeeRatePpm(), selected.sourceType().name() + "_" + scope));
    }
}
