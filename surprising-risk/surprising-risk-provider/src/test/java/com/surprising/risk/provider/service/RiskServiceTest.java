package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.repository.CoreRiskLiquidationProjectionRepository;
import com.surprising.risk.provider.repository.RiskRuleRepository;
import com.surprising.risk.provider.repository.RiskRuleRepository.RiskRuleOverride;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskServiceTest {

    private RiskAeronGateway aeron;
    private CoreRiskLiquidationProjectionRepository liquidations;
    private RiskRuleRepository rules;
    private RiskService service;

    @BeforeEach
    void setUp() {
        RiskProperties properties = new RiskProperties();
        aeron = mock(RiskAeronGateway.class);
        liquidations = mock(CoreRiskLiquidationProjectionRepository.class);
        rules = mock(RiskRuleRepository.class);
        service = new RiskService(properties, aeron, liquidations, rules);
    }

    @Test
    void aggregatesCrossPositionsUsingAuthoritativeWallet() {
        when(aeron.userState(7)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 4,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 700, 300)), List.of(), List.of()));
        when(aeron.riskState(7)).thenReturn(List.of(
                risk("BTC-USDT", CoreMarginMode.CROSS, 50, -100, 200, 222_222),
                risk("ETH-USDT", CoreMarginMode.CROSS, 51, 50, 100, 105_263),
                risk("SOL-USDT", CoreMarginMode.ISOLATED, 52, -20, 40, 500_000)));

        var account = service.latestAccount(7, "USDT_PERPETUAL", "USDT");
        assertThat(account.walletBalanceUnits()).isEqualTo(1_000);
        assertThat(account.unrealizedPnlUnits()).isEqualTo(-50);
        assertThat(account.equityUnits()).isEqualTo(950);
        assertThat(account.maintenanceMarginUnits()).isEqualTo(300);
        assertThat(account.marginRatioPpm()).isEqualTo(315_789);
        assertThat(account.status()).isEqualTo(RiskStatus.NORMAL);
        assertThat(account.snapshotId()).isEqualTo(52);
    }

    @Test
    void mapsCorePositionRiskWithoutRecalculation() {
        when(aeron.riskState(7)).thenReturn(List.of(risk("BTC-USDT", CoreMarginMode.ISOLATED,
                61, -5, 40, 800_000)));
        var result = service.latestPositions(7);
        assertThat(result.count()).isOne();
        assertThat(result.positions().getFirst()).satisfies(value -> {
            assertThat(value.marginMode()).isEqualTo(MarginMode.ISOLATED);
            assertThat(value.positionSide()).isEqualTo(PositionSide.LONG);
            assertThat(value.markPriceTicks()).isEqualTo(55_000);
            assertThat(value.notionalUnits()).isEqualTo(550_000);
            assertThat(value.status()).isEqualTo(RiskStatus.WARNING);
        });
    }

    @Test
    void accountStatusUsesCoreSnapshotStatusInsteadOfLocalThresholds() {
        when(aeron.userState(7)).thenReturn(new CoreUserStateView(ProductLine.LINEAR_PERPETUAL, 7, 4,
                CorePositionMode.ONE_WAY, List.of(new CoreBalanceView("USDT", 700, 300)), List.of(), List.of()));
        when(aeron.riskState(7)).thenReturn(List.of(
                riskWithStatus("BTC-USDT", CoreMarginMode.CROSS, 61, -5, 40, 100, "LIQUIDATION")));

        var account = service.latestAccount(7, "USDT_PERPETUAL", "USDT");

        assertThat(account.status()).isEqualTo(RiskStatus.LIQUIDATION);
    }

    @Test
    void rejectsRiskProviderOwnedMarginPolicyUpdates() {
        assertThatThrownBy(() -> service.updateRiskRule("GLOBAL_MARGIN_POLICY", "admin",
                new RiskService.RiskRuleUpdateCommand("override", true, null, null,
                        "must not be local")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owned by versioned Aeron Core instrument state");
    }

    @Test
    void doesNotExposePersistedMarginThresholdOverride() {
        when(rules.findAll()).thenReturn(List.of(new RiskRuleOverride("GLOBAL_MARGIN_POLICY", "legacy",
                "GLOBAL_MARGIN", true, null, null, "admin", "legacy",
                Instant.now(), Instant.now())));

        var result = service.riskRules();

        assertThat(result.rules()).first().satisfies(value -> {
            assertThat(value.source()).isEqualTo("core");
        });
    }

    @Test
    void enrichesCoreLiquidationProjectionFromAeronRiskState() {
        var projected = new LiquidationCandidateResponse(9, 9, 7, "BTC-USDT", MarginMode.CROSS,
                PositionSide.LONG, 3, "USDT_PERPETUAL", "USDT", 10, 0, 0, 0, 0,
                LiquidationCandidateStatus.NEW, Instant.ofEpochMilli(100));
        when(liquidations.find(LiquidationCandidateStatus.NEW, 3, null, false)).thenReturn(List.of(projected));
        when(aeron.riskState(7)).thenReturn(List.of(risk("BTC-USDT", CoreMarginMode.CROSS,
                77, -10, 400, 1_200_000)));
        var result = service.liquidationCandidates("NEW", 2, null, null);
        assertThat(result.candidates()).singleElement().satisfies(value -> {
            assertThat(value.snapshotId()).isEqualTo(77);
            assertThat(value.markPriceTicks()).isEqualTo(55_000);
            assertThat(value.marginRatioPpm()).isEqualTo(1_200_000);
        });
    }

    private static CoreRiskSnapshotView risk(String symbol, CoreMarginMode mode, long sequence,
                                             long unrealized, long maintenance, long ratio) {
        return riskWithStatus(symbol, mode, sequence, unrealized, maintenance, ratio,
                ratio >= 1_000_000 ? "LIQUIDATION" : ratio >= 800_000 ? "WARNING" : "NORMAL");
    }

    private static CoreRiskSnapshotView riskWithStatus(String symbol, CoreMarginMode mode, long sequence,
                                                       long unrealized, long maintenance, long ratio,
                                                       String status) {
        return new CoreRiskSnapshotView(7, symbol, mode, CorePositionSide.LONG, 3, "USDT",
                10, 50_000, 55_000, 550_000, mode == CoreMarginMode.ISOLATED ? 100 : 0,
                sequence, 1_000, 900, unrealized, maintenance, ratio,
                status);
    }
}
