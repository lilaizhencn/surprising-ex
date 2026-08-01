package com.surprising.trading.matching.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.CRC32;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 只从 Instrument JVM 快照派生撮合所需的资产和交易对映射。
 *
 * <p>交易对和资产编号是 exchange-core 的进程内编号，不属于业务事实，也不再写入主库。
 * 编号由稳定哈希派生，并通过反向索引检测碰撞；订单恢复使用 symbol 字符串，因此重启后仍能得到相同编号。</p>
 */
@Service
public class MatchingSymbolService {

    private final MatchingProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final ConcurrentMap<String, Integer> assetIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> assetOwners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> symbolIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MatchingSymbol> symbols = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> symbolOwners = new ConcurrentHashMap<>();

    public MatchingSymbolService(MatchingProperties properties,
                                 @Qualifier("matchingInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public List<InstrumentSymbol> currentTradingSymbols() {
        return currentInstruments().stream()
                .filter(instrument -> instrument.status() == InstrumentStatus.TRADING
                        || instrument.status() == InstrumentStatus.HALT)
                .map(MatchingSymbolService::toInstrumentSymbol)
                .toList();
    }

    public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
        return currentInstrument(symbol)
                .filter(instrument -> instrument.status() == InstrumentStatus.TRADING
                        || instrument.status() == InstrumentStatus.HALT)
                .map(MatchingSymbolService::toInstrumentSymbol);
    }

    /** 返回当前快照中的交易对，包含结算中和已下架状态以支持撤单及订单恢复。 */
    public Optional<MatchingSymbol> existingMatchingSymbol(String symbol) {
        return currentInstrument(symbol)
                .map(MatchingSymbolService::toInstrumentSymbol)
                .map(this::ensureMatchingSymbol);
    }

    public MatchingSymbol ensureMatchingSymbol(InstrumentSymbol instrument) {
        if (instrument == null || instrument.symbol() == null || instrument.symbol().isBlank()) {
            throw new IllegalArgumentException("撮合交易对不能为空");
        }
        String productLine = productLine().name();
        String symbolKey = productLine + ":" + normalize(instrument.symbol());
        return symbols.compute(symbolKey, (key, existing) -> {
            int baseCurrencyId = ensureAsset(instrument.baseAsset());
            ensureAsset(instrument.quoteAsset());
            int quoteCurrencyId = ensureAsset(instrument.settleAsset());
            int symbolId = stableId("symbol:" + key, symbolOwners);
            MatchingSymbol resolved = new MatchingSymbol(instrument.symbol(), symbolId,
                    baseCurrencyId, quoteCurrencyId);
            if (existing != null && !existing.equals(resolved)) {
                throw new IllegalStateException("Instrument 资产配置不可变更: " + instrument.symbol());
            }
            return existing == null ? resolved : existing;
        });
    }

    private List<InstrumentResponse> currentInstruments() {
        ensureSnapshotReady();
        return snapshotCache.current(productLine());
    }

    private Optional<InstrumentResponse> currentInstrument(String symbol) {
        ensureSnapshotReady();
        return snapshotCache.current(productLine(), symbol);
    }

    private int ensureAsset(String asset) {
        String normalized = normalize(asset);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("撮合资产不能为空");
        }
        return stableId("asset:" + normalized, assetOwners);
    }

    private int stableId(String key, ConcurrentMap<Integer, String> owners) {
        ConcurrentMap<String, Integer> ids = key.startsWith("asset:") ? assetIds : symbolIds;
        Integer resolved = ids.computeIfAbsent(key, ignored -> {
            CRC32 crc = new CRC32();
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            crc.update(bytes, 0, bytes.length);
            return (int) (crc.getValue() % Integer.MAX_VALUE) + 1;
        });
        String owner = owners.putIfAbsent(resolved, key);
        if (owner != null && !owner.equals(key)) {
            throw new IllegalStateException("撮合编号哈希碰撞: " + owner + " 与 " + key);
        }
        return resolved;
    }

    private void ensureSnapshotReady() {
        if (snapshotCache == null || properties == null
                || !snapshotCache.initialized(productLine())) {
            throw new IllegalStateException("撮合合约 JVM 快照尚未就绪");
        }
    }

    private ProductLine productLine() {
        return properties.getKafka().getProductLine();
    }

    private static InstrumentSymbol toInstrumentSymbol(InstrumentResponse instrument) {
        return new InstrumentSymbol(instrument.symbol(), instrument.baseAsset(),
                instrument.quoteAsset(), instrument.settleAsset());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

}
