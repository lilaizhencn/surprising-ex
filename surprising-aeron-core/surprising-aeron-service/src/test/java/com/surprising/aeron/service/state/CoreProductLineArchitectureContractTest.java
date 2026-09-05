package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreProductLineArchitectureContractTest {

    private final TradingCoreReducer reducer = new TradingCoreReducer();

    @Test
    void everyProductLineUsesOneMappedContractAndOneCoreState() {
        for (ProductLine productLine : ProductLine.values()) {
            ContractType contractType = ContractType.valueOf(productLine.contractTypeCode());
            TradingCoreState state = TradingCoreState.empty(productLine);

            assertThat(contractType.productLine()).isEqualTo(productLine);
            assertThat(state.productLine()).isEqualTo(productLine);
            assertThat(productLine.supportsUserPositionMarginFlow())
                    .isEqualTo(contractType != ContractType.SPOT);
            assertThat(productLine.isFundingProduct()).isEqualTo(contractType.isPerpetual());
            assertThat(productLine.isOptionProduct()).isEqualTo(contractType.isOption());
            assertThat(productLine.isDeliveryProduct())
                    .isEqualTo(contractType.isDelivery() || contractType.isOption());

            TradingCoreState configured = reducer.upsertInstrument(state, instrument(productLine));
            assertThat(configured.instruments()).containsKey("BTC-USDT-" + productLine.name());
            assertThat(configured.instruments().get("BTC-USDT-" + productLine.name()).contractType())
                    .isEqualTo(contractType);
        }
    }

    @Test
    void everyProductLineRejectsEveryForeignContractWithoutMutation() {
        for (ProductLine target : ProductLine.values()) {
            for (ProductLine foreign : ProductLine.values()) {
                if (target == foreign) {
                    continue;
                }
                TradingCoreState state = TradingCoreState.empty(target);
                long beforeHash = state.businessStateHash();

                assertThatThrownBy(() -> reducer.upsertInstrument(state, instrument(foreign)))
                        .isInstanceOfSatisfying(CoreStateRejectedException.class,
                                exception -> assertThat(exception.code()).isEqualTo("PRODUCT_LINE_MISMATCH"));
                assertThat(state.businessStateHash()).isEqualTo(beforeHash);
                assertThat(state.instruments()).isEmpty();
            }
        }
    }

    @Test
    void productRulesRejectForeignInstrumentsAndUnsupportedLifecycleOperations() {
        for (ProductLine line : ProductLine.values()) {
            ProductTradingRules rules = ProductTradingRulesRegistry.forProductLine(line);
            CoreInstrumentState own = reducer.upsertInstrument(TradingCoreState.empty(line), instrument(line))
                    .instruments().values().iterator().next();
            rules.requireInstrument(own);
            for (ProductLine other : ProductLine.values()) {
                if (other == line) continue;
                CoreInstrumentState foreign = reducer.upsertInstrument(
                        TradingCoreState.empty(other), instrument(other)).instruments().values().iterator().next();
                assertThatThrownBy(() -> rules.requireInstrument(foreign))
                        .isInstanceOfSatisfying(CoreStateRejectedException.class,
                                error -> assertThat(error.code()).isEqualTo("PRODUCT_LINE_UNSUPPORTED"));
            }
            if (!line.isFundingProduct()) {
                assertThatThrownBy(() -> rules.fundingDeltaUnits(own, 1, 100, 1_000))
                        .isInstanceOf(CoreStateRejectedException.class);
            }
            if (!line.isDeliveryProduct()) {
                assertThatThrownBy(() -> rules.lifecycleCashDeltaUnits(own, 1, 100, 110))
                        .isInstanceOf(CoreStateRejectedException.class);
            }
            if (line == ProductLine.OPTION) {
                assertThat(rules.lifecycleCashDeltaUnits(own, 2, 100, 110)).isEqualTo(20);
                assertThat(rules.lifecycleCashDeltaUnits(own, -2, 100, 110)).isEqualTo(-20);
            }
        }
    }

    private static UpsertInstrumentCommand instrument(ProductLine productLine) {
        ContractType contractType = ContractType.valueOf(productLine.contractTypeCode());
        boolean inverse = contractType.isInverse();
        boolean lifecycle = contractType.isDelivery() || contractType.isOption();
        return new UpsertInstrumentCommand(
                "BTC-USDT-" + productLine.name(), 1, contractType.ordinal(), "BTC",
                inverse ? "USD" : "USDT", inverse ? "BTC" : "USDT",
                inverse ? 100 : 1, 1, inverse ? 100 : 1,
                100_000, 50_000, 100_000, 200_000,
                lifecycle ? 2_000_000_000_000L : 0,
                contractType.isOption() ? 0 : -1,
                contractType.isOption() ? 100 : 0,
                100_000_000, 25_000_000_000_000L, 0, 1,
                List.of(new CoreRiskLimitBracket(1, 0, 25_000_000_000_000L,
                        100_000_000, 100_000, 50_000)));
    }
}
