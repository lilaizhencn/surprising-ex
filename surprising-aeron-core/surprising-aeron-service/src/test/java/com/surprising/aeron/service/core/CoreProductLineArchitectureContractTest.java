package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 六产品线合约映射及业务规则边界测试：注册类型取本线映射，跨线合约不能混入快照且原状态不变；资金费和到期结算能力由各产品规则决定，不把共享调用入口当成产品模型可混用。
 */
class CoreProductLineArchitectureContractTest {

    /** 只供测试把业务转换应用到冷状态的既有辅助，不是新增生产状态入口。 */
    private final RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();

    /**
     * 遍历全部产品线，核对唯一合约类型、持仓保证金/资金费/期权/到期能力标记，注册本类型合约后冷状态仍属于该产品线。
     */
    @Test
    void everyProductLineUsesOneMappedContractAndOneCoreState() {
        for (ProductLine /* 本轮检查合约映射和能力标记的产品线 */ productLine : ProductLine.values()) {
            // 由产品线唯一映射出的合约类型
            ContractType contractType = ContractType.valueOf(productLine.contractTypeCode());
            // 本轮尚未注册合约的原冷状态
            TradingCoreState state = TradingCoreState.empty(productLine);

            assertThat(contractType.productLine()).isEqualTo(productLine);
            assertThat(state.productLine()).isEqualTo(productLine);
            assertThat(productLine.supportsUserPositionMarginFlow())
                    .isEqualTo(contractType != ContractType.SPOT);
            assertThat(productLine.isFundingProduct()).isEqualTo(contractType.isPerpetual());
            assertThat(productLine.isOptionProduct()).isEqualTo(contractType.isOption());
            assertThat(productLine.isDeliveryProduct())
                    .isEqualTo(contractType.isDelivery() || contractType.isOption());

            // 成功注册本线合约后返回的新冷状态
            TradingCoreState configured = reducer.registerInstrument(state, instrument(productLine));
            assertThat(configured.instruments()).containsKey("1");
            assertThat(configured.instruments().get("1").contractType())
                    .isEqualTo(contractType);
        }
    }

    /**
     * 对每对不同产品线构造含外线合约的冷状态，拒绝把外线合约编码为当前产品，原状态不变。
     */
    @Test
    void snapshotRejectsEveryForeignInstrumentWithoutMutation() {
        for (ProductLine target : ProductLine.values()) {
            for (ProductLine foreign : ProductLine.values()) {
                if (target == foreign) continue;
                TradingCoreState state = TradingCoreState.empty(target);
                CoreInstrument instrument = CoreInstrument.from(foreign, instrument(foreign));
                TradingCoreState mixed = new TradingCoreState(target, 0, state.users(), state.orders(),
                        java.util.Map.of(instrument.instrumentId(), instrument), state.riskState(), state.treasuryState());
                long hash = mixed.businessStateHash();
                assertThatThrownBy(() -> com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec.encode(mixed))
                        .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                        .hasMessage("snapshot instrument product line mismatch");
                assertThat(mixed.businessStateHash()).isEqualTo(hash);
            }
        }
    }

    /**
     * 各产品规则接受本线合约并拒绝其他线；不支持的资金费、到期现金计算拒绝，另核对期权双向现金值及永续/交割生命周期结算所用公式入口。
     */
    @Test
    void productRulesRejectForeignInstrumentsAndUnsupportedLifecycleOperations() {
        for (ProductLine /* 当前要验证规则及生命周期能力的产品线 */ line : ProductLine.values()) {
            // 该产品线唯一规则实现，其他线合约应拒绝
            ProductTradingRules rules = ProductTradingRulesRegistry.forProductLine(line);
            // 已经在本产品冷状态成功注册的原生合约
            CoreInstrument own = reducer.registerInstrument(TradingCoreState.empty(line), instrument(line))
                    .instruments().values().iterator().next();
            rules.requireInstrument(own);
            for (ProductLine /* 当前用来构造不兼容合约的另一产品线 */ other : ProductLine.values()) {
                if (other == line) continue;
                // 不同产品线或由它构造的外部合约，用来验证边界拒绝
                CoreInstrument foreign = reducer.registerInstrument(
                        TradingCoreState.empty(other), instrument(other)).instruments().values().iterator().next();
                assertThatThrownBy(() -> rules.requireInstrument(foreign))
                        .isInstanceOfSatisfying(CoreStateRejectedException.class,
                                /* 产品规则给出的跨产品或不支持操作拒绝 */ error -> assertThat(error.code()).isEqualTo("PRODUCT_LINE_UNSUPPORTED"));
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
            if (line.isFundingProduct()) {
                assertThat(rules.lifecycleSettlementCashDeltaUnits(own, 1, 100, 110))
                        .isEqualTo(rules.realizedPnlUnits(own, 1, 100, 110));
            } else if (line.isDeliveryProduct()) {
                assertThat(rules.lifecycleSettlementCashDeltaUnits(own, 1, 100, 110))
                        .isEqualTo(rules.lifecycleCashDeltaUnits(own, 1, 100, 110));
            }
        }
    }

    /**
     * 对本产品合约构造到期结算请求，交割和期权规则允许，其余规则给 PRODUCT_LINE_UNSUPPORTED；准入能力由产品规则判定。
     */
    @Test
    void lifecycleSettlementAdmissionIsOwnedByTheProductRule() {
        for (ProductLine /* 当前要验证规则及生命周期能力的产品线 */ line : ProductLine.values()) {
            // 该产品线唯一规则实现，其他线合约应拒绝
            ProductTradingRules rules = ProductTradingRulesRegistry.forProductLine(line);
            // 已经在本产品冷状态成功注册的原生合约
            CoreInstrument own = reducer.registerInstrument(TradingCoreState.empty(line), instrument(line))
                    .instruments().values().iterator().next();
            // 为当前本线合约构造的到期结算请求
            SettleInstrumentCommand command = new SettleInstrumentCommand(7, own.instrumentId(), 100);

            if (line.isDeliveryProduct()) {
                rules.validateLifecycleSettlement(own, command);
            } else {
                assertThatThrownBy(() -> rules.validateLifecycleSettlement(own, command))
                        .isInstanceOfSatisfying(CoreStateRejectedException.class,
                                /* 产品规则给出的跨产品或不支持操作拒绝 */ error -> assertThat(error.code()).isEqualTo("PRODUCT_LINE_UNSUPPORTED"));
            }
        }
    }

    /**
     * 按产品合约类型生成标的一注册参数：币本位采用 USD 报价、BTC 结算及面值，交割/期权给到期时间，期权给类型和行权价，并提供一档风险限额。
     * @param productLine 本次要构造合约注册参数的独立产品线
     */
    private static RegisterInstrumentCommand instrument(ProductLine productLine) {
        // 由产品线唯一映射出的合约类型
        ContractType contractType = ContractType.valueOf(productLine.contractTypeCode());
        // 当前合约是否为币本位，决定报价、结算资产和面值
        boolean inverse = contractType.isInverse();
        // 当前合约是否有到期时间，即交割或期权
        boolean lifecycle = contractType.isDelivery() || contractType.isOption();
        return new RegisterInstrumentCommand(
                "1", "BTC", inverse ? "USD" : "USDT", inverse ? "BTC" : "USDT", inverse ? 100 : 1, 1, inverse ? 100 : 1,
                (contractType == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, 100_000_000, 25_000_000_000_000L, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 25_000_000_000_000L,
                        100_000_000, 100_000, 50_000)))),
                100_000, 200_000, lifecycle ? 2_000_000_000_000L : 0, contractType.isOption() ? 0 : -1,
                contractType.isOption() ? 100 : 0);
    }
}
