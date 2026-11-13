package com.surprising.price.index.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.index.config.IndexPriceProperties;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** 维护指数价格运行时使用的不可变配置快照。 */
@Service
public class IndexInstrumentConfigService {

    private final IndexInstrumentConfigLoader configLoader;
    private volatile List<IndexPriceProperties.SymbolConfig> symbols = List.of();

    public IndexInstrumentConfigService(IndexInstrumentConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    @EventListener
    public void onInstrumentEvent(InstrumentEvent event) {
        if (event != null) {
            refresh();
        }
    }

    public void refresh() {
        symbols = List.copyOf(configLoader.load());
    }

    public List<IndexPriceProperties.SymbolConfig> symbols() {
        return symbols;
    }
}
