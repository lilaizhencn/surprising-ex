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

    public MarkPriceEncoding encoding(String symbol) {
        if (snapshotCache == null || !snapshotCache.initialized(properties.getKafka().getProductLine())) {
            throw new IllegalStateException("标记价格合约 JVM 快照尚未就绪");
        }
        var instrument = snapshotCache.current(properties.getKafka().getProductLine(), symbol)
                .orElseThrow(() -> notFound(symbol));
        long quoteScaleUnits = snapshotCache.scale(properties.getKafka().getProductLine(), instrument.quoteAsset())
                .orElseThrow(() -> notFound(symbol));
        return new MarkPriceEncoding(instrument.version(), quoteScaleUnits, instrument.priceTickUnits());
    }

    private IllegalStateException notFound(String symbol) {
        return new IllegalStateException("mark price encoding not found for " + symbol);
    }
}
