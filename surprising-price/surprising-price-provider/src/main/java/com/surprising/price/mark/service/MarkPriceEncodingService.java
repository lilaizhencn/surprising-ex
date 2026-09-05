package com.surprising.price.mark.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @org.springframework.beans.factory.annotation.Autowired
    public MarkPriceEncodingService(MarkPriceProperties properties,
                                    @Qualifier("markInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache) {
        this.properties = properties == null ? new MarkPriceProperties() : properties;
        this.snapshotCache = snapshotCache;
    }

    public MarkPriceEncoding currentEncoding(String symbol) {
        if (snapshotCache == null || !snapshotCache.initialized(properties.getKafka().getProductLine())) {
            throw new IllegalStateException("标记价格合约 JVM 快照尚未就绪");
        }
        var instrument = snapshotCache.current(properties.getKafka().getProductLine(), symbol)
                .orElseThrow(() -> notFound(symbol));
        return encoding(instrument);
    }

    public MarkPriceEncoding encoding(String symbol, long instrumentVersion) {
        if (snapshotCache == null || !snapshotCache.initialized(properties.getKafka().getProductLine())) {
            throw new IllegalStateException("标记价格合约 JVM 快照尚未就绪");
        }
        var instrument = snapshotCache.version(properties.getKafka().getProductLine(), symbol, instrumentVersion)
                .orElseThrow(() -> notFound(symbol, instrumentVersion));
        return encoding(instrument);
    }

    private MarkPriceEncoding encoding(com.surprising.instrument.api.model.InstrumentResponse instrument) {
        long quoteScaleUnits = snapshotCache.scale(properties.getKafka().getProductLine(), instrument.quoteAsset())
                .orElseThrow(() -> notFound(instrument.symbol(), instrument.version()));
        long baseScaleUnits = snapshotCache.scale(properties.getKafka().getProductLine(), instrument.baseAsset())
                .orElseThrow(() -> notFound(instrument.symbol(), instrument.version()));
        return new MarkPriceEncoding(instrument.version(), quoteScaleUnits, instrument.priceTickUnits(),
                baseScaleUnits, instrument.quantityStepUnits());
    }

    private IllegalStateException notFound(String symbol) {
        return new IllegalStateException("mark price encoding not found for " + symbol);
    }

    private IllegalStateException notFound(String symbol, long instrumentVersion) {
        return new IllegalStateException("mark price encoding not found for " + symbol
                + " version " + instrumentVersion);
    }
}
