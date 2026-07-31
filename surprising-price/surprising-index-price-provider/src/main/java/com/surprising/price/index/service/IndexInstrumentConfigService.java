package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
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
    private volatile List<IndexPriceProperties.SymbolConfig> symbols = List.of();

    public IndexInstrumentConfigService(IndexPriceProperties properties, IndexInstrumentConfigLoader configLoader) {
        this.properties = properties;
        this.configLoader = configLoader;
    }

    @PostConstruct
    public void initialize() {
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
            if (properties.getInstrument().isFallbackToStaticSymbols()) {
                symbols = List.copyOf(properties.getSymbols());
            }
        } catch (Exception ex) {
            log.error("Failed to refresh index instrument snapshot; keeping previous snapshot", ex);
            if (symbols.isEmpty() && properties.getInstrument().isFallbackToStaticSymbols()) {
                symbols = List.copyOf(properties.getSymbols());
            }
        }
    }

    public List<IndexPriceProperties.SymbolConfig> symbols() {
        if (symbols.isEmpty() && properties.getInstrument().isFallbackToStaticSymbols()) {
            return properties.getSymbols();
        }
        return symbols;
    }
}
