package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import java.util.List;
import org.springframework.stereotype.Service;

/** 维护指数价格运行时使用的不可变配置快照。 */
@Service
public class IndexInstrumentConfigService {

    private final IndexInstrumentConfigLoader configLoader;
    private volatile List<IndexPriceProperties.SymbolConfig> symbols = List.of();

    public IndexInstrumentConfigService(IndexInstrumentConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    public void refresh() {
        symbols = List.copyOf(configLoader.load());
    }

    public List<IndexPriceProperties.SymbolConfig> symbols() {
        return symbols;
    }
}
