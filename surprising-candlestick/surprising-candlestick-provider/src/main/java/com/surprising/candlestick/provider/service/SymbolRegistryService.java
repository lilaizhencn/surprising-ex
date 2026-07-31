package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * K 线运行时 symbol 门禁；服务层组合品种版本与当前版本快照。
 */
@Service
public class SymbolRegistryService {

    private final CandlestickProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final InstrumentRpcApi instrumentRpcApi;
    private volatile Set<String> enabledSymbols = Set.of();

    public SymbolRegistryService(CandlestickProperties properties,
                                 InstrumentSnapshotCache snapshotCache,
                                 InstrumentRpcApi instrumentRpcApi) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.instrumentRpcApi = instrumentRpcApi;
    }

    @PostConstruct
    public void initialize() {
        var productLine = properties.getKafka().getProductLine();
        InstrumentSnapshotSupport.initialize(instrumentRpcApi, snapshotCache, productLine, "K 线服务");
        refresh();
    }

    /** 从当前合约 JVM 快照重建 K 线交易对注册表。 */
    public void refresh() {
        var productLine = properties.getKafka().getProductLine();
        if (!snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("K 线合约 JVM 快照尚未就绪: " + productLine);
        }
        enabledSymbols = snapshotCache.current(productLine).stream()
                .filter(instrument -> instrument.status() == InstrumentStatus.PRE_TRADING
                        || instrument.status() == InstrumentStatus.TRADING
                        || instrument.status() == InstrumentStatus.HALT)
                .map(InstrumentResponse::symbol)
                .map(CandleKey::normalizeSymbol)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled(String symbol) {
        return enabledSymbols.contains(CandleKey.normalizeSymbol(symbol));
    }

}
