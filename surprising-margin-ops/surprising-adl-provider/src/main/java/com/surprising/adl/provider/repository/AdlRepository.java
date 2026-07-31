package com.surprising.adl.provider.repository;

import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlCandidate;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.adl.provider.service.AdlMath;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ADL 在线决策聚合仓储。
 *
 * <p>合约正文和资产精度来自本 JVM 的 Instrument 快照，持仓、保证金、缺口和保险余额分别由单表
 * Repository 读取，再在 Service 事务边界内聚合；本类不执行跨表连接。</p>
 */
@Repository
public class AdlRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final AdlPositionRepository positionRepository;
    private final AdlPositionMarginRepository marginRepository;
    private final AdlProductDeficitRepository productDeficitRepository;
    private final AdlLegacyDeficitRepository legacyDeficitRepository;
    private final AdlInsuranceFundBalanceRepository insuranceFundRepository;
    private final AdlProperties properties;
    private final LatestMarkPriceCache markPriceCache;
    private final InstrumentSnapshotCache snapshotCache;

    public AdlRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AdlProperties(), null);
    }

    public AdlRepository(JdbcTemplate jdbcTemplate, AdlProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    public AdlRepository(JdbcTemplate jdbcTemplate,
                         AdlProperties properties,
                         LatestMarkPriceCache markPriceCache) {
        AdlProperties resolved = properties == null ? new AdlProperties() : properties;
        this.positionRepository = new AdlPositionRepository(jdbcTemplate);
        this.marginRepository = new AdlPositionMarginRepository(jdbcTemplate);
        this.productDeficitRepository = new AdlProductDeficitRepository(jdbcTemplate);
        this.legacyDeficitRepository = new AdlLegacyDeficitRepository(jdbcTemplate);
        this.insuranceFundRepository = new AdlInsuranceFundBalanceRepository(jdbcTemplate);
        this.properties = resolved;
        this.markPriceCache = markPriceCache;
        this.snapshotCache = new InstrumentSnapshotCache();
    }

    @Autowired
    public AdlRepository(AdlPositionRepository positionRepository,
                         AdlPositionMarginRepository marginRepository,
                         AdlProductDeficitRepository productDeficitRepository,
                         AdlLegacyDeficitRepository legacyDeficitRepository,
                         AdlInsuranceFundBalanceRepository insuranceFundRepository,
                         AdlProperties properties,
                         LatestMarkPriceCache markPriceCache,
                         InstrumentSnapshotCache snapshotCache) {
        this.positionRepository = positionRepository;
        this.marginRepository = marginRepository;
        this.productDeficitRepository = productDeficitRepository;
        this.legacyDeficitRepository = legacyDeficitRepository;
        this.insuranceFundRepository = insuranceFundRepository;
        this.properties = properties == null ? new AdlProperties() : properties;
        this.markPriceCache = markPriceCache;
        this.snapshotCache = snapshotCache;
    }

    /**
     * 先锁定缺口表记录，再单表读取保险基金余额；不再通过跨表连接筛选。
     */
    public List<DeficitRow> claimResidualDeficits(int batchSize, Duration minAge) {
        List<DeficitRow> deficits = claimResidualDeficitsFromTable(
                Math.max(1, batchSize), minAge);
        if (deficits.isEmpty()) {
            return List.of();
        }
        Map<String, Long> balances = insuranceFundRepository.findBalances(accountType(),
                deficits.stream().map(DeficitRow::asset).distinct().toList());
        return deficits.stream()
                .filter(deficit -> balances.getOrDefault(deficit.asset(), 0L) == 0L)
                .toList();
    }

    public List<AdlCandidate> queue(String asset, int limit, Duration maxMarkAge) {
        return queue(asset, 0L, limit, maxMarkAge);
    }

    public List<String> candidateAssets() {
        return positionRepository.open(productLine()).stream()
                .map(this::instrument)
                .flatMap(Optional::stream)
                .map(InstrumentResponse::settleAsset)
                .distinct()
                .sorted()
                .toList();
    }

    public List<AdlCandidate> queue(String asset,
                                   long excludedUserId,
                                   int limit,
                                   Duration maxMarkAge) {
        Map<PositionKey, Long> marks = freshMarks(maxMarkAge, null);
        if (marks.isEmpty()) {
            return List.of();
        }
        List<AdlPositionRepository.PositionRow> positions = positionRepository.open(productLine());
        return candidates(positions, asset, excludedUserId, limit, marks);
    }

    public Optional<AdlCandidate> lockCandidate(long userId,
                                                String symbol,
                                                MarginMode marginMode,
                                                PositionSide positionSide,
                                                String asset,
                                                Duration maxMarkAge) {
        AdlPositionRepository.PositionRow position = positionRepository.lock(
                        productLine(), userId, symbol, marginMode, positionSide)
                .orElse(null);
        if (position == null) {
            return Optional.empty();
        }
        Map<PositionKey, Long> marks = freshMarks(maxMarkAge, symbol);
        if (marks.isEmpty()) {
            return Optional.empty();
        }
        return candidate(position, asset, marks,
                remainingDeficits(productLine(), asset, List.of(userId)),
                marginRepository.find(productLine(), userId, symbol, asset,
                        position.marginMode(), position.positionSide()).orElse(0L));
    }

    public Optional<AdlCandidate> lockCandidate(long userId,
                                                String symbol,
                                                String asset,
                                                Duration maxMarkAge) {
        return lockCandidate(userId, symbol, MarginMode.CROSS, PositionSide.NET, asset, maxMarkAge);
    }

    private List<AdlCandidate> candidates(List<AdlPositionRepository.PositionRow> positions,
                                          String asset,
                                          long excludedUserId,
                                          int limit,
                                          Map<PositionKey, Long> marks) {
        List<Long> userIds = positions.stream().map(AdlPositionRepository.PositionRow::userId).distinct().toList();
        Map<Long, Long> deficits = remainingDeficits(productLine(), asset, userIds);
        Map<AdlPositionMarginRepository.MarginKey, Long> margins = marginRepository.findAll(productLine());
        return positions.stream()
                .filter(position -> position.userId() != excludedUserId)
                .map(position -> {
                    AdlPositionMarginRepository.MarginKey key = new AdlPositionMarginRepository.MarginKey(
                            position.userId(), position.symbol(), asset, position.marginMode(), position.positionSide());
                    return candidate(position, asset, marks, deficits, margins.getOrDefault(key, 0L));
                })
                .flatMap(Optional::stream)
                .filter(value -> value.profitTicksPerStep() > 0 && value.unrealizedProfitUnits() > 0)
                .sorted(Comparator.comparingLong(AdlCandidate::priorityScorePpm).reversed()
                        .thenComparing(Comparator.comparingLong(AdlCandidate::unrealizedProfitUnits).reversed())
                        .thenComparing(AdlCandidate::symbol))
                .limit(Math.max(1, limit))
                .toList();
    }

    private Optional<AdlCandidate> candidate(AdlPositionRepository.PositionRow position,
                                             String requestedAsset,
                                             Map<PositionKey, Long> marks,
                                             Map<Long, Long> deficits,
                                             long marginUnits) {
        if (deficits.getOrDefault(position.userId(), 0L) > 0L) {
            return Optional.empty();
        }
        InstrumentResponse instrument = instrument(position).orElse(null);
        if (instrument == null || !instrument.contractType().isPerpetual()
                || !instrument.settleAsset().equals(requestedAsset)) {
            return Optional.empty();
        }
        Long markPriceTicks = marks.get(new PositionKey(position.symbol(), position.instrumentVersion()));
        if (markPriceTicks == null) {
            return Optional.empty();
        }
        long settleScaleUnits = snapshotCache.scale(productLine(), instrument.settleAsset())
                .orElse(0L);
        if (settleScaleUnits <= 0L) {
            return Optional.empty();
        }
        long notionalUnits = PerpetualContractMath.notionalUnits(instrument.contractType(),
                position.signedQuantitySteps(), markPriceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), settleScaleUnits);
        long profitUnits = Math.max(0L, PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(),
                position.signedQuantitySteps(), position.entryPriceTicks(), markPriceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), settleScaleUnits));
        long absQuantitySteps = Math.absExact(position.signedQuantitySteps());
        long profitTicksPerStep = position.signedQuantitySteps() > 0
                ? Math.subtractExact(markPriceTicks, position.entryPriceTicks())
                : Math.subtractExact(position.entryPriceTicks(), markPriceTicks);
        long profitRatePpm = AdlMath.profitRatePpm(profitUnits, notionalUnits);
        long effectiveLeveragePpm = AdlMath.effectiveLeveragePpm(notionalUnits, marginUnits);
        long priorityScorePpm = AdlMath.priorityScorePpm(profitRatePpm, effectiveLeveragePpm);
        return Optional.of(new AdlCandidate(
                position.userId(), instrument.settleAsset(), position.symbol(), position.marginMode(),
                position.positionSide(), position.signedQuantitySteps() > 0 ? AdlSide.LONG : AdlSide.SHORT,
                position.signedQuantitySteps(), absQuantitySteps, position.entryPriceTicks(), markPriceTicks,
                profitTicksPerStep, notionalUnits, profitUnits, marginUnits, profitRatePpm,
                effectiveLeveragePpm, priorityScorePpm));
    }

    private Optional<InstrumentResponse> instrument(AdlPositionRepository.PositionRow position) {
        if (!snapshotCache.ready(productLine())) {
            return Optional.empty();
        }
        return snapshotCache.version(productLine(), position.symbol(), position.instrumentVersion());
    }

    private Map<PositionKey, Long> freshMarks(Duration maxAge, String symbol) {
        if (markPriceCache == null) {
            throw new IllegalStateException("mark price cache is not configured");
        }
        List<MarkPriceEvent> snapshots = symbol == null
                ? markPriceCache.freshSnapshots(maxAge)
                : markPriceCache.fresh(symbol, maxAge).stream().toList();
        Map<PositionKey, Long> result = new HashMap<>();
        for (MarkPriceEvent snapshot : snapshots) {
            result.put(new PositionKey(snapshot.symbol(), snapshot.instrumentVersion()), snapshot.markPriceTicks());
        }
        return Map.copyOf(result);
    }

    private ProductLine productLine() {
        return properties.getKafka().getProductLine();
    }

    private List<DeficitRow> claimResidualDeficitsFromTable(int batchSize, Duration minAge) {
        return properties.getKafka().isProductTopicsEnabled()
                ? productDeficitRepository.claimResidual(accountType(), batchSize, minAge)
                : legacyDeficitRepository.claimResidual(accountType(), batchSize, minAge);
    }

    private Map<Long, Long> remainingDeficits(ProductLine productLine, String asset, List<Long> userIds) {
        return properties.getKafka().isProductTopicsEnabled()
                ? productDeficitRepository.remainingByUsers(productLine, asset, userIds)
                : legacyDeficitRepository.remainingByUsers(productLine, asset, userIds);
    }

    private String accountType() {
        String accountType = properties.getKafka().getAccountType();
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private record PositionKey(String symbol, long instrumentVersion) {
    }
}
