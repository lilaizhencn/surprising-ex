package com.surprising.account.provider.service;

import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.AccountSettlementBalanceRepository;
import com.surprising.account.provider.repository.AdminBalanceAdjustmentRepository;
import com.surprising.account.provider.repository.LiquidationOrderContextRepository;
import com.surprising.account.provider.repository.OpenInterestShardRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionModeAlgoOrderRepository;
import com.surprising.account.provider.repository.PositionModeLockRepository;
import com.surprising.account.provider.repository.PositionModeOrderRepository;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.account.provider.repository.PositionModeTriggerOrderRepository;
import com.surprising.account.provider.repository.PositionModeUnsettledTradeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductDeficitRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.ProductSettlementBalanceRepository;
import com.surprising.account.provider.repository.ProductTransferRepository;
import com.surprising.account.provider.repository.RiskPositionSnapshotRepository;
import com.surprising.account.provider.repository.SpotOrderReservationRepository;
import com.surprising.account.provider.repository.TradeSettlementSideRepository;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 仅供单元测试使用的账户结算装配器。
 *
 * <p>生产 Service 只接收已经装配好的单表 Repository；测试需要共享同一个模拟
 * {@link JdbcTemplate} 时，由本工厂集中完成依赖构造。</p>
 */
final class AccountSettlementServiceTestFactory {

    private AccountSettlementServiceTestFactory() {
    }

    static AccountSettlementService create(JdbcTemplate jdbcTemplate,
                                           AccountSequenceRepository sequenceRepository) {
        return create(jdbcTemplate, sequenceRepository, null, null);
    }

    static AccountSettlementService create(JdbcTemplate jdbcTemplate,
                                           AccountSequenceRepository sequenceRepository,
                                           LatestMarkPriceCache markPriceCache) {
        return create(jdbcTemplate, sequenceRepository, markPriceCache, null);
    }

    static AccountSettlementService create(JdbcTemplate jdbcTemplate,
                                           AccountSequenceRepository sequenceRepository,
                                           LatestMarkPriceCache markPriceCache,
                                           PositionCacheAfterCommitSynchronizer positionCacheSynchronizer) {
        return create(jdbcTemplate, sequenceRepository, markPriceCache, positionCacheSynchronizer, List.of());
    }

    static AccountSettlementService create(JdbcTemplate jdbcTemplate,
                                           AccountSequenceRepository sequenceRepository,
                                           LatestMarkPriceCache markPriceCache,
                                           PositionCacheAfterCommitSynchronizer positionCacheSynchronizer,
                                           InstrumentResponse... instruments) {
        return create(jdbcTemplate, sequenceRepository, markPriceCache, positionCacheSynchronizer,
                instruments == null ? List.of() : List.of(instruments));
    }

    private static AccountSettlementService create(JdbcTemplate jdbcTemplate,
                                                   AccountSequenceRepository sequenceRepository,
                                                   LatestMarkPriceCache markPriceCache,
                                                   PositionCacheAfterCommitSynchronizer positionCacheSynchronizer,
                                                   List<InstrumentResponse> instruments) {
        AccountLedgerRepository accountLedgers = new AccountLedgerRepository(jdbcTemplate);
        ProductLedgerRepository productLedgers = new ProductLedgerRepository(jdbcTemplate);
        AdminBalanceAdjustmentRepository adminAdjustments =
                new AdminBalanceAdjustmentRepository(jdbcTemplate);
        ProductTransferRepository productTransfers = new ProductTransferRepository(jdbcTemplate);
        AccountBalanceRepository accountBalances = new AccountBalanceRepository(jdbcTemplate);
        AccountDeficitRepository accountDeficits = new AccountDeficitRepository(jdbcTemplate);
        ProductBalanceRepository productBalances = new ProductBalanceRepository(jdbcTemplate);
        ProductDeficitRepository productDeficits = new ProductDeficitRepository(jdbcTemplate);
        PositionModeRepository positionModes = new PositionModeRepository(jdbcTemplate);
        TradeSettlementSideRepository tradeSides = new TradeSettlementSideRepository(jdbcTemplate);
        PositionRepository positions = new PositionRepository(jdbcTemplate);
        PositionMarginRepository positionMargins = new PositionMarginRepository(jdbcTemplate);
        AccountProperties accountProperties = new AccountProperties();
        InstrumentSnapshotCache snapshotCache = new InstrumentSnapshotCache();
        for (ProductLine productLine : ProductLine.values()) {
            List<InstrumentResponse> lineInstruments = instruments.stream()
                    .filter(value -> value.contractType().productLine() == productLine)
                    .toList();
            snapshotCache.replace(productLine, lineInstruments, java.util.Map.of());
        }
        RiskPositionSnapshotRepository riskSnapshots = new RiskPositionSnapshotRepository(jdbcTemplate);
        LiquidationOrderContextRepository liquidationContexts =
                new LiquidationOrderContextRepository(jdbcTemplate);
        AccountSettlementBalanceRepository settlementBalances =
                new AccountSettlementBalanceRepository(jdbcTemplate);
        ProductSettlementBalanceRepository productSettlementBalances =
                new ProductSettlementBalanceRepository(jdbcTemplate);
        OpenInterestShardRepository openInterestShards = new OpenInterestShardRepository(jdbcTemplate);

        AccountQueryService accountQueries = new AccountQueryService(
                accountLedgers,
                productLedgers,
                productTransfers,
                adminAdjustments,
                accountBalances,
                accountDeficits,
                productBalances,
                productDeficits);
        AccountBalanceCommandService balanceCommands = new AccountBalanceCommandService(
                sequenceRepository,
                accountLedgers,
                productLedgers,
                productTransfers,
                accountBalances,
                productBalances,
                accountQueries);
        PositionModeSwitchGuard positionModeSwitchGuard = new PositionModeSwitchGuard(
                new PositionModeLockRepository(jdbcTemplate),
                positions,
                new PositionModeOrderRepository(jdbcTemplate),
                new PositionModeTriggerOrderRepository(jdbcTemplate),
                new PositionModeAlgoOrderRepository(jdbcTemplate),
                new PositionModeUnsettledTradeRepository(jdbcTemplate));
        PositionModeCommandService positionModeCommands =
                new PositionModeCommandService(positionModes, positionModeSwitchGuard);
        PositionQueryService positionQueries =
                new PositionQueryService(positions, positionMargins, accountProperties, snapshotCache);
        PositionOpenInterestService positionOpenInterest =
                new PositionOpenInterestService(positions, openInterestShards);
        SpotTradeSettlementService spotSettlement = new SpotTradeSettlementService(
                sequenceRepository,
                productBalances,
                productLedgers,
                new SpotOrderReservationRepository(jdbcTemplate));

        return new AccountSettlementService(
                sequenceRepository,
                markPriceCache,
                positionCacheSynchronizer,
                accountLedgers,
                productLedgers,
                adminAdjustments,
                productTransfers,
                accountBalances,
                accountDeficits,
                productBalances,
                productDeficits,
                balanceCommands,
                positionModes,
                tradeSides,
                positionModeCommands,
                positions,
                positionMargins,
                accountProperties,
                snapshotCache,
                riskSnapshots,
                liquidationContexts,
                settlementBalances,
                productSettlementBalances,
                positionQueries,
                openInterestShards,
                positionOpenInterest,
                spotSettlement);
    }

    static InstrumentResponse instrument(String symbol, long version, ProductLine productLine) {
        ContractType contractType = ContractType.valueOf(productLine.contractTypeCode());
        InstrumentType instrumentType = switch (productLine) {
            case SPOT -> InstrumentType.SPOT;
            case LINEAR_PERPETUAL, INVERSE_PERPETUAL -> InstrumentType.PERPETUAL;
            case LINEAR_DELIVERY, INVERSE_DELIVERY -> InstrumentType.DELIVERY;
            case OPTION -> InstrumentType.OPTION;
        };
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new InstrumentResponse(symbol, version, instrumentType, contractType,
                "BTC", "USDT", "USDT", 1_000_000L, "USDT",
                1L, 1L, 1L, 100_000L, 1L, 1_000_000_000L, 1L,
                1, 3, List.of("LIMIT"), List.of("GTC"), true, true, true,
                100_000_000L, 10_000L, 5_000L, 2L, 5L,
                500_000_000_000_000L, 300_000L, 25_000_000_000_000L,
                8, 0L, 100_000L, -100_000L, 1_000_000_000L, 1,
                null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }
}
