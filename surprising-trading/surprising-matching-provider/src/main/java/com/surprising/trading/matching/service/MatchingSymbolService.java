package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.trading.matching.repository.MatchingAssetRepository;
import com.surprising.trading.matching.repository.MatchingInstrumentCurrentVersionRepository;
import com.surprising.trading.matching.repository.MatchingInstrumentRepository;
import com.surprising.trading.matching.repository.MatchingInstrumentRepository.InstrumentVersion;
import com.surprising.trading.matching.repository.MatchingSequenceRepository;
import com.surprising.trading.matching.repository.MatchingSymbolRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聚合合约快照、撮合资产和撮合交易对。
 * 当前版本与品种正文通过两个单表 Repository 读取，并由只读可重复读事务保证同一快照。
 */
@Service
public class MatchingSymbolService {

    private final MatchingInstrumentRepository instrumentRepository;
    private final MatchingInstrumentCurrentVersionRepository currentVersionRepository;
    private final MatchingAssetRepository assetRepository;
    private final MatchingSymbolRepository symbolRepository;
    private final MatchingSequenceRepository sequenceRepository;
    private final MatchingProperties properties;

    public MatchingSymbolService(MatchingInstrumentRepository instrumentRepository,
                                 MatchingInstrumentCurrentVersionRepository currentVersionRepository,
                                 MatchingAssetRepository assetRepository,
                                 MatchingSymbolRepository symbolRepository,
                                 MatchingSequenceRepository sequenceRepository,
                                 MatchingProperties properties) {
        this.instrumentRepository = instrumentRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.assetRepository = assetRepository;
        this.symbolRepository = symbolRepository;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<InstrumentSymbol> currentTradingSymbols() {
        Map<String, Long> currentVersions = currentVersionRepository.findAll();
        return instrumentRepository.findTradingVersions(productContractTypeFilter().orElse(null)).stream()
                .filter(instrument -> currentVersions.getOrDefault(instrument.symbol(), -1L)
                        == instrument.version())
                .map(InstrumentVersion::toInstrumentSymbol)
                .toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
        return currentVersionRepository.findVersion(symbol)
                .flatMap(version -> instrumentRepository.findTrading(
                        symbol, version, productContractTypeFilter().orElse(null)))
                .map(InstrumentVersion::toInstrumentSymbol);
    }

    @Transactional(readOnly = true)
    public Optional<MatchingSymbol> existingMatchingSymbol(String symbol) {
        return symbolRepository.find(symbol);
    }

    @Transactional
    public MatchingSymbol ensureMatchingSymbol(InstrumentSymbol instrument) {
        int baseCurrencyId = ensureAsset(instrument.baseAsset());
        ensureAsset(instrument.quoteAsset());
        int quoteCurrencyId = ensureAsset(instrument.settleAsset());
        Optional<MatchingSymbol> existing = symbolRepository.find(instrument.symbol());
        if (existing.isPresent()) {
            return existing.get();
        }
        int symbolId = sequenceRepository.nextIntSequence("matching-symbol");
        symbolRepository.insert(instrument, symbolId, baseCurrencyId, quoteCurrencyId, Instant.now());
        return symbolRepository.find(instrument.symbol())
                .orElseThrow(() -> new IllegalStateException(
                        "failed to ensure matching symbol " + instrument.symbol()));
    }

    private int ensureAsset(String asset) {
        Optional<Integer> existing = assetRepository.findId(asset);
        if (existing.isPresent()) {
            return existing.get();
        }
        int assetId = sequenceRepository.nextIntSequence("matching-asset");
        assetRepository.insert(asset, assetId);
        return assetRepository.findId(asset)
                .orElseThrow(() -> new IllegalStateException("failed to ensure matching asset " + asset));
    }

    private Optional<String> productContractTypeFilter() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? Optional.of(kafka.getProductLine().contractTypeCode())
                : Optional.empty();
    }
}
