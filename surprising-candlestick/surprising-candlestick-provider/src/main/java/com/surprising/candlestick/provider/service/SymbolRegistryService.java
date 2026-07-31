package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentCurrentVersionRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository.InstrumentVersion;
import com.surprising.candlestick.provider.repository.CandlestickSymbolRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * K 线运行时 symbol 门禁；服务层组合品种版本与当前版本快照。
 */
@Service
public class SymbolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SymbolRegistryService.class);

    private final CandlestickProperties properties;
    private final CandlestickInstrumentRepository instrumentRepository;
    private final CandlestickInstrumentCurrentVersionRepository currentVersionRepository;
    private final CandlestickSymbolRepository symbolRepository;
    private volatile Set<String> enabledSymbols = Set.of();

    public SymbolRegistryService(CandlestickProperties properties,
                                 CandlestickInstrumentRepository instrumentRepository,
                                 CandlestickInstrumentCurrentVersionRepository currentVersionRepository,
                                 CandlestickSymbolRepository symbolRepository) {
        this.properties = properties;
        this.instrumentRepository = instrumentRepository;
        this.currentVersionRepository = currentVersionRepository;
        this.symbolRepository = symbolRepository;
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    /**
     * 在严格注册表模式下刷新可用 symbol 快照。
     */
    @Scheduled(fixedDelayString = "${surprising.candlestick.symbols.refresh-delay-ms:30000}")
    public void refresh() {
        if (properties.getSymbols().isAcceptUnknownSymbols()) {
            return;
        }
        try {
            Set<String> symbols = ConcurrentHashMap.newKeySet();
            if ("CANDLESTICK_SYMBOLS".equalsIgnoreCase(properties.getSymbols().getSource())) {
                symbolRepository.findEnabledSymbols().stream()
                        .map(CandleKey::normalizeSymbol)
                        .forEach(symbols::add);
            } else {
                Map<String, Long> currentVersions = currentVersionRepository.findAll();
                eligibleInstrumentVersions().stream()
                        .filter(instrument -> currentVersions.getOrDefault(instrument.symbol(), -1L)
                                == instrument.version())
                        .map(InstrumentVersion::symbol)
                        .map(CandleKey::normalizeSymbol)
                        .forEach(symbols::add);
            }
            enabledSymbols = Set.copyOf(symbols);
        } catch (Exception ex) {
            log.error("Failed to refresh candlestick symbol registry; keeping previous snapshot", ex);
        }
    }

    public boolean isEnabled(String symbol) {
        if (properties.getSymbols().isAcceptUnknownSymbols()) {
            return true;
        }
        return enabledSymbols.contains(CandleKey.normalizeSymbol(symbol));
    }

    private List<InstrumentVersion> eligibleInstrumentVersions() {
        if (properties.getKafka().isProductTopicsEnabled()) {
            return instrumentRepository.findEnabledVersionsByContractType(
                    properties.getKafka().getProductLine().contractTypeCode());
        }
        return instrumentRepository.findEnabledPerpetualVersions();
    }
}
