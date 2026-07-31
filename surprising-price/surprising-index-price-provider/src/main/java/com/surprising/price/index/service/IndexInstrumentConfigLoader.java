package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 从本地不可变合约快照统一读取合约正文、当前版本和指数源配置。
 */
@Service
public class IndexInstrumentConfigLoader {

    private final IndexPriceProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    @org.springframework.beans.factory.annotation.Autowired
    public IndexInstrumentConfigLoader(IndexPriceProperties properties,
                                       InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public List<IndexPriceProperties.SymbolConfig> load() {
        var productLine = properties.getKafka().getProductLine();
        if (snapshotCache == null || !snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("指数价格合约 JVM 快照尚未就绪");
        }
        return snapshotCache.current(productLine).stream()
                .filter(instrument -> instrument.status() == com.surprising.instrument.api.model.InstrumentStatus.TRADING)
                .map(this::toSymbol)
                .filter(symbol -> !symbol.getSources().isEmpty())
                .toList();
    }

    private IndexPriceProperties.SymbolConfig toSymbol(InstrumentResponse instrument) {
        IndexPriceProperties.SymbolConfig config = new IndexPriceProperties.SymbolConfig();
        config.setSymbol(instrument.symbol());
        config.setMinValidSources(instrument.minValidIndexSources());
        config.setSources(instrument.indexSources() == null ? List.of()
                : instrument.indexSources().stream().filter(IndexSourceConfig::enabled).map(this::toSource).toList());
        return config;
    }

    private IndexPriceProperties.SourceConfig toSource(IndexSourceConfig row) {
        IndexPriceProperties.SourceConfig source = new IndexPriceProperties.SourceConfig();
        source.setName(row.source());
        source.setEnabled(row.enabled());
        source.setBaseUrl(row.baseUrl());
        source.setPath(row.path());
        source.setSourceSymbol(row.sourceSymbol());
        source.setParser(row.parser());
        source.setQuoteCurrency(row.quoteCurrency());
        source.setTargetQuoteCurrency(row.targetQuoteCurrency());
        source.setConversionBaseUrl(row.conversionBaseUrl());
        source.setConversionPath(row.conversionPath());
        source.setConversionParser(row.conversionParser());
        source.setConversionMode(row.conversionMode());
        source.setConversionOperation(row.conversionOperation());
        source.setFallbackWeightMultiplier(ppm(row.fallbackWeightMultiplierPpm(), BigDecimal.ONE));
        source.setWebsocketEnabled(row.websocketEnabled());
        source.setWebsocketUrl(row.websocketUrl());
        source.setWebsocketSubscribeMessage(row.websocketSubscribeMessage());
        source.setWebsocketParser(row.websocketParser());
        source.setWeight(ppm(row.weightPpm(), BigDecimal.ONE));
        return source;
    }

    private BigDecimal ppm(long value, BigDecimal fallback) {
        return value > 0 ? BigDecimal.valueOf(value, 6) : fallback;
    }
}
