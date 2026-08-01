package com.surprising.instrument.api.cache;

import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PostConstruct;
import java.util.Set;

/**
 * 各业务模块共用的合约快照启动模板。
 *
 * <p>子类只提供产品线集合和服务名称；RPC 加载、完整性校验及本地缓存替换统一在这里执行。
 * 产品线集合仍由业务模块自己决定，因此不会改变四条产品线的进程隔离。</p>
 */
public abstract class AbstractInstrumentSnapshotInitializer {

    private final InstrumentRpcApi instrumentRpcApi;
    protected final InstrumentSnapshotCache snapshotCache;

    protected AbstractInstrumentSnapshotInitializer(InstrumentRpcApi instrumentRpcApi,
                                                    InstrumentSnapshotCache snapshotCache) {
        this.instrumentRpcApi = instrumentRpcApi;
        this.snapshotCache = snapshotCache;
    }

    protected abstract Set<ProductLine> productLines();

    protected abstract String serviceName();

    /** 子类可在全部产品线快照就绪后刷新自己的派生配置。 */
    protected void afterInitialize() {
    }

    @PostConstruct
    public final void initialize() {
        for (ProductLine productLine : productLines()) {
            InstrumentSnapshotSupport.initialize(instrumentRpcApi, snapshotCache, productLine, serviceName());
        }
        afterInitialize();
    }
}
