package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.price.index.repository.IndexInstrumentCurrentVersionRepository;
import com.surprising.price.index.repository.IndexInstrumentKey;
import com.surprising.price.index.repository.IndexInstrumentRepository;
import com.surprising.price.index.repository.IndexInstrumentRepository.IndexInstrument;
import com.surprising.price.index.repository.IndexInstrumentSourceRepository;
import com.surprising.price.index.repository.IndexInstrumentSourceRepository.IndexSource;
import com.surprising.instrument.api.model.InstrumentResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 从本地不可变合约快照统一读取合约正文、当前版本和指数源配置。
 */
@Service
public class IndexInstrumentConfigLoader {

    private final IndexPriceProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final IndexInstrumentRepository instrumentRepository;
    private final IndexInstrumentCurrentVersionRepository currentVersionRepository;
    private final IndexInstrumentSourceRepository sourceRepository;

    /** 兼容旧测试构造方式；运行时不会使用这些数据库仓储。 */
    public IndexInstrumentConfigLoader(IndexPriceProperties properties,
                                       IndexInstrumentRepository instrumentRepository,
                                       IndexInstrumentCurrentVersionRepository currentVersionRepository,
                                       IndexInstrumentSourceRepository sourceRepository) {
        this.properties = properties;
        this.snapshotCache = null;
        this.instrumentRepository = instrumentRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.sourceRepository = sourceRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IndexInstrumentConfigLoader(IndexPriceProperties properties,
                                       InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.instrumentRepository = null;
        this.currentVersionRepository = null;
        this.sourceRepository = null;
    }

    public List<IndexPriceProperties.SymbolConfig> load() {
        var productLine = properties.getKafka().getProductLine();
        if (snapshotCache != null && snapshotCache.initialized(productLine)) {
            return snapshotCache.current(productLine).stream()
                    .filter(instrument -> instrument.status() == com.surprising.instrument.api.model.InstrumentStatus.TRADING)
                    .map(this::toSymbol)
                    .filter(symbol -> !symbol.getSources().isEmpty())
                    .toList();
        }
        if (snapshotCache != null) {
            throw new IllegalStateException("指数价格合约 JVM 快照尚未就绪");
        }
        Map<String, Long> currentVersions = currentVersionRepository.findAll();
        List<IndexInstrument> instruments = instrumentRepository.findTradingVersions(contractType()).stream()
                .filter(instrument -> currentVersions.getOrDefault(instrument.symbol(), -1L)
                        == instrument.version())
                .toList();
        List<IndexInstrumentKey> keys = instruments.stream().map(IndexInstrument::key).toList();
        Map<IndexInstrumentKey, List<IndexSource>> sources = sourceRepository.findEnabled(keys);
        return instruments.stream()
                .map(instrument -> toSymbol(instrument, sources.getOrDefault(instrument.key(), List.of())))
                .filter(symbol -> !symbol.getSources().isEmpty())
                .toList();
    }

    private String contractType() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine().contractTypeCode() : null;
    }

    private IndexPriceProperties.SymbolConfig toSymbol(IndexInstrument instrument, List<IndexSource> sources) {
        IndexPriceProperties.SymbolConfig config = new IndexPriceProperties.SymbolConfig();
        config.setSymbol(instrument.symbol());
        config.setMinValidSources(instrument.minValidSources());
        config.setSources(sources.stream().map(this::toSource).toList());
        return config;
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

    private IndexPriceProperties.SourceConfig toSource(IndexSource row) {
        IndexPriceProperties.SourceConfig source = new IndexPriceProperties.SourceConfig();
        source.setName(row.name());
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
