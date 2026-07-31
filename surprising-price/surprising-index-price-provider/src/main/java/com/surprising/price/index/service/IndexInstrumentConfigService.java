package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 维护指数价格运行时使用的不可变配置快照。 */
@Service
    public class IndexInstrumentConfigService {

    private static final Logger log = LoggerFactory.getLogger(IndexInstrumentConfigService.class);

    private final IndexPriceProperties properties;
    private final IndexInstrumentConfigLoader configLoader;
    private final InstrumentSnapshotCache snapshotCache;
    private final InstrumentRpcApi instrumentRpcApi;
    private volatile List<IndexPriceProperties.SymbolConfig> symbols = List.of();

    public IndexInstrumentConfigService(IndexPriceProperties properties, IndexInstrumentConfigLoader configLoader) {
        this(properties, configLoader, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IndexInstrumentConfigService(IndexPriceProperties properties,
                                        IndexInstrumentConfigLoader configLoader,
                                        InstrumentSnapshotCache snapshotCache,
                                        InstrumentRpcApi instrumentRpcApi) {
        this.properties = properties;
        this.configLoader = configLoader;
        this.snapshotCache = snapshotCache;
        this.instrumentRpcApi = instrumentRpcApi;
    }

    @PostConstruct
    public void initialize() {
        if (snapshotCache != null && instrumentRpcApi != null) {
            var productLine = properties.getKafka().getProductLine();
            var snapshot = instrumentRpcApi.snapshot(productLine);
            if (snapshot == null || snapshot.productLine() != productLine) {
                throw new IllegalStateException("指数服务合约快照产品线不匹配: " + productLine);
            }
            snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
            if (!snapshotCache.ready(productLine)) {
                throw new IllegalStateException("指数价格服务合约快照为空，拒绝启动行情流量: " + productLine);
            }
        }
        refresh();
    }

    public void refresh() {
        if (!properties.getInstrument().isEnabled()) {
            symbols = List.copyOf(properties.getSymbols());
            return;
        }
        try {
            List<IndexPriceProperties.SymbolConfig> loaded = configLoader.load();
            if (!loaded.isEmpty()) {
                symbols = loaded;
                return;
            }
            if (snapshotReady()) {
                // 快照已成功初始化但当前没有可交易品种时，空集合是权威结果，不能回退旧配置。
                symbols = List.of();
                return;
            }
            if (properties.getInstrument().isFallbackToStaticSymbols()) {
                symbols = List.copyOf(properties.getSymbols());
            }
        } catch (Exception ex) {
            log.error("Failed to refresh index instrument snapshot; keeping previous snapshot", ex);
            if (!snapshotReady() && symbols.isEmpty() && properties.getInstrument().isFallbackToStaticSymbols()) {
                symbols = List.copyOf(properties.getSymbols());
            }
        }
    }

    public List<IndexPriceProperties.SymbolConfig> symbols() {
        if (symbols.isEmpty() && !snapshotReady() && properties.getInstrument().isFallbackToStaticSymbols()) {
            return properties.getSymbols();
        }
        return symbols;
    }

    private boolean snapshotReady() {
        return snapshotCache != null && snapshotCache.initialized(properties.getKafka().getProductLine());
    }
}
