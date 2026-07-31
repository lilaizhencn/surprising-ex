package com.surprising.price.mark.service;

import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.price.mark.repository.MarkAssetScaleRepository;
import com.surprising.price.mark.repository.MarkInstrumentCurrentVersionRepository;
import com.surprising.price.mark.repository.MarkInstrumentRepository;
import com.surprising.price.mark.repository.MarkInstrumentRepository.MarkInstrument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读取标记价格定点编码参数。
 *
 * <p>当前版本、合约正文和资产精度由三个单表 Repository 读取，并在可重复读事务中聚合。</p>
 */
@Service
public class MarkPriceEncodingService {

    private final MarkInstrumentRepository instrumentRepository;
    private final MarkInstrumentCurrentVersionRepository currentVersionRepository;
    private final MarkAssetScaleRepository assetScaleRepository;
    private final MarkPriceProperties properties;

    public MarkPriceEncodingService(MarkInstrumentRepository instrumentRepository,
                                    MarkInstrumentCurrentVersionRepository currentVersionRepository,
                                    MarkAssetScaleRepository assetScaleRepository,
                                    MarkPriceProperties properties) {
        this.instrumentRepository = instrumentRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.assetScaleRepository = assetScaleRepository;
        this.properties = properties == null ? new MarkPriceProperties() : properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MarkPriceEncoding encoding(String symbol) {
        long version = currentVersionRepository.findVersion(symbol)
                .orElseThrow(() -> notFound(symbol));
        MarkInstrument instrument = instrumentRepository.find(symbol, version, contractType())
                .orElseThrow(() -> notFound(symbol));
        long quoteScaleUnits = assetScaleRepository.findScaleUnits(instrument.quoteAsset())
                .orElseThrow(() -> notFound(symbol));
        return new MarkPriceEncoding(instrument.version(), quoteScaleUnits, instrument.priceTickUnits());
    }

    private String contractType() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine().contractTypeCode()
                : null;
    }

    private IllegalStateException notFound(String symbol) {
        return new IllegalStateException("mark price encoding not found for " + symbol);
    }
}
