package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.utils.InstrumentSnapshotSupport;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 启动时直接从本地 InstrumentService 加载全产品线合约快照，并监听进程内变更事件刷新共享缓存。
 */
@Service("gatewayInstrumentSnapshotBridge")
public class GatewayInstrumentSnapshotBridge {

    private final InstrumentService instrumentService;
    private final InstrumentSnapshotCache snapshotCache;

    public GatewayInstrumentSnapshotBridge(InstrumentService instrumentService,
                                           InstrumentSnapshotCache snapshotCache) {
        this.instrumentService = instrumentService;
        this.snapshotCache = snapshotCache;
    }

    @PostConstruct
    public void initialize() {
        for (ProductLine productLine : ProductLine.values()) {
            var snapshot = instrumentService.snapshot(productLine);
            if (snapshot == null || snapshot.productLine() != productLine) {
                throw new IllegalStateException("合约快照产品线不匹配: " + productLine);
            }
            snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        }
    }

    @EventListener
    public void onInstrumentEvent(InstrumentEvent event) {
        if (event != null) {
            InstrumentSnapshotSupport.apply(snapshotCache, InstrumentEventKeys.key(event), event,
                    event.productLine(), "Gateway");
        }
    }
}
