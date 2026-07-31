package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.IndexInstrumentCurrentVersionRepository;
import com.surprising.price.index.repository.IndexInstrumentKey;
import com.surprising.price.index.repository.IndexInstrumentRepository;
import com.surprising.price.index.repository.IndexInstrumentRepository.IndexInstrument;
import com.surprising.price.index.repository.IndexInstrumentSourceRepository;
import com.surprising.price.index.repository.IndexInstrumentSourceRepository.IndexSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在同一个可重复读快照中聚合合约正文、当前版本指针和指数源配置。
 */
@Service
public class IndexInstrumentConfigLoader {

    private final IndexPriceProperties properties;
    private final IndexInstrumentRepository instrumentRepository;
    private final IndexInstrumentCurrentVersionRepository currentVersionRepository;
    private final IndexInstrumentSourceRepository sourceRepository;

    public IndexInstrumentConfigLoader(IndexPriceProperties properties,
                                       IndexInstrumentRepository instrumentRepository,
                                       IndexInstrumentCurrentVersionRepository currentVersionRepository,
                                       IndexInstrumentSourceRepository sourceRepository) {
        this.properties = properties;
        this.instrumentRepository = instrumentRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.sourceRepository = sourceRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<IndexPriceProperties.SymbolConfig> load() {
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
                ? properties.getKafka().getProductLine().contractTypeCode()
                : null;
    }

    private IndexPriceProperties.SymbolConfig toSymbol(IndexInstrument instrument, List<IndexSource> sources) {
        IndexPriceProperties.SymbolConfig config = new IndexPriceProperties.SymbolConfig();
        config.setSymbol(instrument.symbol());
        config.setMinValidSources(instrument.minValidSources());
        config.setSources(sources.stream().map(this::toSource).toList());
        return config;
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
