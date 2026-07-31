package com.surprising.instrument.api.cache;

import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.product.api.ProductLine;

/**
 * 统一处理各业务模块的合约快照初始化和增量事件校验。
 */
public final class InstrumentSnapshotSupport {

    private InstrumentSnapshotSupport() {
    }

    /**
     * 通过 Instrument 聚合 RPC 加载指定产品线的完整快照。
     */
    public static void initialize(InstrumentRpcApi instrumentRpcApi,
                                  InstrumentSnapshotCache snapshotCache,
                                  ProductLine productLine,
                                  String serviceName) {
        InstrumentSnapshotResponse snapshot = instrumentRpcApi.snapshot(productLine);
        if (snapshot == null || snapshot.productLine() != productLine) {
            throw new IllegalStateException(serviceName + "合约快照产品线不匹配: " + productLine);
        }
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        if (!snapshotCache.ready(productLine)) {
            throw new IllegalStateException(serviceName + "合约快照为空，拒绝启动: " + productLine);
        }
    }

    /**
     * 校验并应用 Instrument 增量事件。
     */
    public static void apply(InstrumentSnapshotCache snapshotCache,
                             String recordKey,
                             InstrumentEvent event,
                             ProductLine productLine,
                             String serviceName) {
        if (!InstrumentEventKeys.matches(recordKey, event)
                || event.resolvedProductLine() != productLine
                || !snapshotCache.apply(event)) {
            throw new IllegalArgumentException(serviceName + "合约事件产品线、key 或快照不匹配");
        }
    }
}
