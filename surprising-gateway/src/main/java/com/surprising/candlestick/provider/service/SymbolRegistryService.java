package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.product.api.ProductLine;
import java.util.Set;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * K 线运行时 instrumentId 门禁；服务层组合品种版本与当前版本快照。
 */
@Service
public class SymbolRegistryService {

    private final CandlestickProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private volatile Set<String> enabledSymbols = Set.of();

    public SymbolRegistryService(CandlestickProperties properties,
                                 InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (snapshotCache.initialized(properties.getProductLine())) {
            refresh();
        }
    }

    @EventListener
    public void onInstrumentEvent(InstrumentEvent event) {
        if (event != null && event.productLine() == properties.getProductLine()
                && snapshotCache.initialized(properties.getProductLine())) {
            refresh();
        }
    }

    /** 从当前合约 JVM 快照重建 K 线交易对注册表。 */
    public void refresh() {
        var productLine = properties.getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("K 线合约 JVM 快照尚未就绪: " + productLine);
        }
        enabledSymbols = snapshotCache.current(productLine).stream()
                .filter(instrument -> instrument.status() == InstrumentStatus.PRE_TRADING
                        || instrument.status() == InstrumentStatus.TRADING
                        || instrument.status() == InstrumentStatus.HALT
                        // Settling reductions and closed-contract replay are committed trades.
                        || instrument.status() == InstrumentStatus.SETTLING
                        || instrument.status() == InstrumentStatus.CLOSED)
                .map(instrument -> Integer.toString(instrument.instrumentId()))
                .map(CandleKey::normalizeSymbol)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled(String instrumentId) {
        return enabledSymbols.contains(CandleKey.normalizeSymbol(instrumentId));
    }

}
