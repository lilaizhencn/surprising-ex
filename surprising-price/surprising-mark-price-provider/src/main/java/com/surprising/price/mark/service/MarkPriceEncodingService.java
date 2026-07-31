package com.surprising.price.mark.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.price.mark.repository.MarkAssetScaleRepository;
import com.surprising.price.mark.repository.MarkInstrumentCurrentVersionRepository;
import com.surprising.price.mark.repository.MarkInstrumentRepository;
import org.springframework.stereotype.Service;

/**
 * 读取标记价格定点编码参数。
 *
 * <p>当前版本、合约正文和资产精度统一来自本进程不可变合约快照。</p>
 */
@Service
public class MarkPriceEncodingService {

    private final MarkInstrumentRepository instrumentRepository;
    private final MarkInstrumentCurrentVersionRepository currentVersionRepository;
    private final MarkAssetScaleRepository assetScaleRepository;
    private final MarkPriceProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public MarkPriceEncodingService(MarkInstrumentRepository instrumentRepository,
                                    MarkInstrumentCurrentVersionRepository currentVersionRepository,
                                    MarkAssetScaleRepository assetScaleRepository,
                                    MarkPriceProperties properties) {
        this(instrumentRepository, currentVersionRepository, assetScaleRepository, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MarkPriceEncodingService(MarkInstrumentRepository instrumentRepository,
                                    MarkInstrumentCurrentVersionRepository currentVersionRepository,
                                    MarkAssetScaleRepository assetScaleRepository,
                                    MarkPriceProperties properties,
                                    InstrumentSnapshotCache snapshotCache) {
        this.instrumentRepository = instrumentRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.assetScaleRepository = assetScaleRepository;
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
