package com.surprising.price.index.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.index.client.ExternalSpotWebSocketManager;
import com.surprising.price.index.config.IndexPriceProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/** 维护指数价格运行时使用的不可变配置快照。 */
@Service
public class IndexInstrumentConfigService {

    private final IndexInstrumentConfigLoader configLoader;
    private final ExternalSpotWebSocketManager webSocketManager;
    private volatile List<IndexPriceProperties.SymbolConfig> symbols = List.of();

    public IndexInstrumentConfigService(IndexInstrumentConfigLoader configLoader) {
        this(configLoader, null);
    }

    @Autowired
    public IndexInstrumentConfigService(IndexInstrumentConfigLoader configLoader,
                                        @Autowired(required = false) ExternalSpotWebSocketManager webSocketManager) {
        this.configLoader = configLoader;
        this.webSocketManager = webSocketManager;
        if (webSocketManager != null) {
            webSocketManager.registerConfigService(this);
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ContextRefreshedEvent.class)
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
        if (webSocketManager != null) {
            webSocketManager.refreshConnections();
        }
    }

    public List<IndexPriceProperties.SymbolConfig> symbols() {
        return symbols;
    }
}
