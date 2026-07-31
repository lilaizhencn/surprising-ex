package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentCurrentVersionRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository.InstrumentVersion;
import com.surprising.candlestick.provider.repository.CandlestickSymbolRepository;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * K 线运行时 symbol 门禁；服务层组合品种版本与当前版本快照。
 */
@Service
    public class SymbolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SymbolRegistryService.class);

    private final CandlestickProperties properties;
    private final CandlestickInstrumentRepository instrumentRepository;
    private final CandlestickInstrumentCurrentVersionRepository currentVersionRepository;
    private final CandlestickSymbolRepository symbolRepository;
    private final InstrumentSnapshotCache snapshotCache;
    private final InstrumentRpcApi instrumentRpcApi;
    private volatile Set<String> enabledSymbols = Set.of();

    public SymbolRegistryService(CandlestickProperties properties,
                                 CandlestickInstrumentRepository instrumentRepository,
                                 CandlestickInstrumentCurrentVersionRepository currentVersionRepository,
                                 CandlestickSymbolRepository symbolRepository) {
        this(properties, instrumentRepository, currentVersionRepository, symbolRepository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SymbolRegistryService(CandlestickProperties properties,
                                 CandlestickInstrumentRepository instrumentRepository,
                                 CandlestickInstrumentCurrentVersionRepository currentVersionRepository,
                                 CandlestickSymbolRepository symbolRepository,
                                 InstrumentSnapshotCache snapshotCache,
                                 InstrumentRpcApi instrumentRpcApi) {
        this.properties = properties;
        this.instrumentRepository = instrumentRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.symbolRepository = symbolRepository;
        this.snapshotCache = snapshotCache;
        this.instrumentRpcApi = instrumentRpcApi;
    }

    @PostConstruct
    public void initialize() {
        if (snapshotCache != null && instrumentRpcApi != null) {
            var productLine = properties.getKafka().getProductLine();
            var snapshot = instrumentRpcApi.snapshot(productLine);
            if (snapshot == null || snapshot.productLine() != productLine) {
                throw new IllegalStateException("K 线服务合约快照产品线不匹配: " + productLine);
            }
            snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
            if (!snapshotCache.ready(productLine)) {
                throw new IllegalStateException("K 线服务合约快照为空，拒绝启动行情流量: " + productLine);
            }
        }
        refresh();
    }

    /**
     * 在严格注册表模式下刷新可用 symbol 快照。
     */
    public void refresh() {
        if (properties.getSymbols().isAcceptUnknownSymbols()) {
            return;
        }
        try {
            Set<String> symbols = ConcurrentHashMap.newKeySet();
            if ("CANDLESTICK_SYMBOLS".equalsIgnoreCase(properties.getSymbols().getSource())) {
                symbolRepository.findEnabledSymbols().stream()
                        .map(CandleKey::normalizeSymbol)
                        .forEach(symbols::add);
            } else if (snapshotCache != null && snapshotCache.initialized(properties.getKafka().getProductLine())) {
                snapshotCache.current(properties.getKafka().getProductLine()).stream()
                        .filter(instrument -> instrument.status() == com.surprising.instrument.api.model.InstrumentStatus.PRE_TRADING
                                || instrument.status() == com.surprising.instrument.api.model.InstrumentStatus.TRADING
                                || instrument.status() == com.surprising.instrument.api.model.InstrumentStatus.HALT)
                        .map(com.surprising.instrument.api.model.InstrumentResponse::symbol)
                        .map(CandleKey::normalizeSymbol)
                        .forEach(symbols::add);
            } else if (snapshotCache == null) {
                Map<String, Long> currentVersions = currentVersionRepository.findAll();
                List<InstrumentVersion> versions = properties.getKafka().isProductTopicsEnabled()
                        ? instrumentRepository.findEnabledVersionsByContractType(
                        properties.getKafka().getProductLine().contractTypeCode())
                        : instrumentRepository.findEnabledPerpetualVersions();
                versions.stream()
                        .filter(instrument -> currentVersions.getOrDefault(instrument.symbol(), -1L)
                                == instrument.version())
                        .map(InstrumentVersion::symbol)
                        .map(CandleKey::normalizeSymbol)
                        .forEach(symbols::add);
            } else {
                throw new IllegalStateException("K 线合约 JVM 快照尚未就绪");
            }
            enabledSymbols = Set.copyOf(symbols);
        } catch (Exception ex) {
            log.error("Failed to refresh candlestick symbol registry; keeping previous snapshot", ex);
        }
    }

    public boolean isEnabled(String symbol) {
        if (properties.getSymbols().isAcceptUnknownSymbols()) {
            return true;
        }
        return enabledSymbols.contains(CandleKey.normalizeSymbol(symbol));
    }

}
