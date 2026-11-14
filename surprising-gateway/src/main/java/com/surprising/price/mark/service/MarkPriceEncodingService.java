package com.surprising.price.mark.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 读取标记价格定点编码参数。
 *
 * <p>当前版本、合约正文和资产精度统一来自本进程不可变合约快照。</p>
 */
@Service
public class MarkPriceEncodingService {

    private final MarkPriceProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private ObjectProvider<InstrumentService> instrumentService;
    private com.surprising.instrument.api.client.InstrumentRpcApi instrumentRpc;

    public MarkPriceEncodingService(MarkPriceProperties properties,
                                    InstrumentSnapshotCache snapshotCache) {
        this.properties = properties == null ? new MarkPriceProperties() : properties;
        this.snapshotCache = snapshotCache;
    }

    @Autowired
    public MarkPriceEncodingService(MarkPriceProperties properties,
                                    InstrumentSnapshotCache cache,
                                    ObjectProvider<InstrumentService> instrumentService) {
        this(properties, cache);
        this.instrumentService = instrumentService;
    }

    MarkPriceEncodingService(MarkPriceProperties properties,
                             InstrumentSnapshotCache cache,
                             com.surprising.instrument.api.client.InstrumentRpcApi rpc) {
        this(properties, cache);
        this.instrumentRpc = rpc;
    }

    public MarkPriceEncoding currentEncoding(String instrumentId) {
        if (snapshotCache == null || !snapshotCache.initialized(properties.getProductLine())) {
            throw new IllegalStateException("标记价格合约 JVM 快照尚未就绪");
        }
        var instrument = snapshotCache.current(properties.getProductLine(), com.surprising.product.api.utils.InstrumentIds.parse(instrumentId))
                .orElseThrow(() -> notFound(instrumentId));
        return encoding(instrument);
    }

    public MarkPriceEncoding encoding(String instrumentId, long instrumentChangeId) {
        if (snapshotCache == null || !snapshotCache.initialized(properties.getProductLine())) {
            throw new IllegalStateException("标记价格合约 JVM 快照尚未就绪");
        }
        var instrument = snapshotCache.current(properties.getProductLine(), com.surprising.product.api.utils.InstrumentIds.parse(instrumentId), instrumentChangeId).orElse(null);
        if (instrument == null) {
            int parsedId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            InstrumentService localInstrument = instrumentService == null ? null : instrumentService.getIfAvailable();
            com.surprising.instrument.api.model.InstrumentTradeEncoding units;
            if (localInstrument != null) {
                units = localInstrument.tradeEncoding(properties.getProductLine(), parsedId, instrumentChangeId);
            } else if (instrumentRpc != null) {
                units = instrumentRpc.tradeEncoding(properties.getProductLine(), parsedId, instrumentChangeId);
            } else {
                throw notFound(instrumentId, instrumentChangeId);
            }
            return new MarkPriceEncoding(instrumentChangeId, units.quoteScaleUnits(), units.priceTickUnits(), units.baseScaleUnits(), units.quantityStepUnits());
        }
        return encoding(instrument);
    }

    private MarkPriceEncoding encoding(com.surprising.instrument.api.model.InstrumentResponse instrument) {
        long quoteScaleUnits = snapshotCache.scale(properties.getProductLine(), instrument.quoteAsset())
                .orElseThrow(() -> notFound(Integer.toString(instrument.instrumentId()), instrument.changeId()));
        long baseScaleUnits = snapshotCache.scale(properties.getProductLine(), instrument.baseAsset())
                .orElseThrow(() -> notFound(Integer.toString(instrument.instrumentId()), instrument.changeId()));
        return new MarkPriceEncoding(instrument.changeId(), quoteScaleUnits, instrument.priceTickUnits(),
                baseScaleUnits, instrument.quantityStepUnits(), instrument.expiryTime());
    }

    private IllegalStateException notFound(String instrumentId) {
        return new IllegalStateException("mark price encoding not found for " + instrumentId);
    }

    private IllegalStateException notFound(String instrumentId, long instrumentChangeId) {
        return new IllegalStateException("mark price encoding not found for " + instrumentId
                + " version " + instrumentChangeId);
    }
}
