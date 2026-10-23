package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.instrument.api.model.ContractType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单价格、预留资产和费率决策测试，覆盖价格保护配置的协议与快照、固定上下文、价格鲜度、现货市价边界、产品格式差异及大数缩放取整。
 */
class CoreOrderDecisionResolverTest {

    /**
     * 自定义滑点、价格年龄和限价保护带，核对注册协议及快照保留配置；市价买单按 2% 边界解析，过期价格和越带限价单应拒绝。
     */
    @Test
    void configuredProtectionSurvivesCodecAndSnapshotAndControlsOrders() {
        // 自定义 2% 市价滑点、八秒价格年龄和启用的限价保护配置，用于协议及快照检查。
        var policy = new com.surprising.aeron.protocol.CoreOrderProtection(20_000, 8_000, true, 30_000, 4_000);
        // 包含自定义保护配置和风险档位的币对注册命令。
        var command = new com.surprising.aeron.protocol.RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1_000_000,
                new CoreInstrumentRiskLimits(
                        100_000, 10_000_000, Long.MAX_VALUE, 0, 1,
                        List.of(new CoreRiskLimitBracket(1,0,Long.MAX_VALUE,10_000_000,100_000,50_000))),
                -10, 25, 0, -1, 0, 1, true, true, true, 3, 15, 1, policy);
        // 注册命令编码后再解码的结果，应完整保留所有自定义参数。
        var decoded = com.surprising.aeron.protocol.TradingCommandCodec.decodeRegisterInstrument(
                com.surprising.aeron.protocol.TradingCommandCodec.encodeRegisterInstrument(command),
                com.surprising.product.api.ProductLine.LINEAR_PERPETUAL);
        assertThat(decoded).isEqualTo(command);
        // 从解码注册命令创建的规范币对，价格事实与决策必须引用同一身份。
        var instrument=CoreInstrument.from(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,decoded);
        // 当前测试的运行时身份映射，为币对 1 提供同一稠密编号。
        var identities=new RuntimeIdentityRegistry();
        try(/* 当前测试直接调用决策入口的运行时，保存规范币对、价格和费率。 */ var runtime=runtime(instrument)) {
            // 配置保护用例里币对 1 的稠密身份，价格和决策查找沿用此值。
            int symbol=identities.symbolId("1");
            runtime.putMarkPrice(new MarkPriceRuntime(symbol,instrument,60_000,9,1_000));
            // 原价格为零的衍生品市价买单，应由自定义滑点计算保护价。
            var buy=new PlaceOrderCommand(91,"1",CoreOrderSide.BUY,0,2,false,CoreMarginMode.CROSS,
                    CorePositionSide.NET,CoreOrderType.MARKET,CoreTimeInForce.IOC,false,"buy");
            assertThat(CoreOrderDecisionResolver.resolve(runtime,identities,1001,buy,7_000).matchingPriceTicks()).isEqualTo(61_200);
            assertThatThrownBy(()->CoreOrderDecisionResolver.resolve(runtime,identities,1001,buy,9_001)).isInstanceOf(CoreStateRejectedException.class);
            // 高于配置价格保护带的限价买单，应返回带 price band 原因的拒绝。
            var limit=new PlaceOrderCommand(92,"1",CoreOrderSide.BUY,62_000,2,false,CoreMarginMode.CROSS,
                    CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"limit");
            assertThatThrownBy(()->CoreOrderDecisionResolver.resolve(runtime,identities,1001,limit,1_500))
                    .isInstanceOf(CoreStateRejectedException.class).hasMessageContaining("price band");
        }
        try(/* 只为注册及快照恢复断言创建的核心实例，结束时关闭。 */ var core=new com.surprising.aeron.service.core.TradingCoreRuntime(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL)) {
            // 把自定义配置写入核心的注册消息，以便快照中保留真实注册状态。
            var message=new com.surprising.aeron.protocol.CoreMessage(
                    com.surprising.aeron.protocol.CoreMessageHeader.command(
                    com.surprising.aeron.protocol.CoreMessageType.REGISTER_INSTRUMENT,java.util.UUID.randomUUID(),
                    com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,com.surprising.aeron.protocol.CommandSource.OPERATIONS,0,1,0,1,1),
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeRegisterInstrument(command));
            assertThat(core.apply(message).commandStatus()).isEqualTo(com.surprising.aeron.protocol.ResponseStatus.APPLIED);
            try(/* 从刚捕获快照恢复的核心实例，核对原价格保护配置仍一致。 */ var restored=com.surprising.aeron.service.core.TradingCoreRuntime.fromSnapshot(
                    com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,core.snapshot(71))) {
                assertThat(restored.tradingState().instruments().get("1").orderProtection()).isEqualTo(policy);
            }
        }
    }

    /**
     * 先创建决策上下文，再替换在线价格和费率表；旧上下文仍用旧标记价和旧费率表，新运行态决策使用新值，同一上下文的买卖和限价规则仍一致。
     */
    @Test
    void batchContextKeepsItsMarkAndUsesTheCanonicalInstrumentForEveryOrder() {
        // 当前测试的运行时身份映射，为币对 1 提供同一稠密编号。
        var identities = new RuntimeIdentityRegistry();
        try (/* 当前测试直接调用决策入口的运行时，保存规范币对、价格和费率。 */ var runtime = runtime(linearInstrument())) {
            // 当前币对 1 的稠密身份，所有价格替换使用同一编号。
            int symbolId = identities.symbolId("1");
            runtime.putMarkPrice(new MarkPriceRuntime(symbolId, runtime.instrument("1"), 60_000, 9, 1_000));
            // 在线价格和费率更新前保存的决策上下文，继续引用当时的价格及费率表。
            var context = CoreOrderDecisionResolver.context(runtime, identities, "1", 1_500);
            runtime.putMarkPrice(new MarkPriceRuntime(symbolId, runtime.instrument("1"), 80_000, 10, 1_500));
            runtime.upsertFeePolicy(new UpsertFeePolicyCommand(71, 1, 1001, "1", -25, 75, 0, true, 900, 0));
            // 同一旧上下文下的市价买单，用来核对旧价格上界及旧费率。
            var buy = new PlaceOrderCommand(91, "1", CoreOrderSide.BUY, 0, 2,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                    CoreTimeInForce.IOC, false, "buy");
            // 同一旧上下文下的市价卖单，用来核对旧价格下界。
            var sell = new PlaceOrderCommand(92, "1", CoreOrderSide.SELL, 0, 2,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                    CoreTimeInForce.IOC, false, "sell");
            assertThat(CoreOrderDecisionResolver.resolve(context, 1001, buy).matchingPriceTicks()).isEqualTo(60_600);
            assertThat(CoreOrderDecisionResolver.resolve(context, 1001, buy).makerFeeRatePpm())
                    .isEqualTo(runtime.instrument("1").makerFeeRatePpm());
            assertThat(CoreOrderDecisionResolver.resolve(runtime, identities, 1001, buy, 1_500).makerFeeRatePpm())
                    .isEqualTo(-25);
            assertThat(CoreOrderDecisionResolver.resolve(context, 1001, sell).matchingPriceTicks()).isEqualTo(59_400);
            assertThat(CoreOrderDecisionResolver.resolve(runtime, identities, 1001, buy, 1_500).markPriceTicks())
                    .isEqualTo(80_000);
            // 同一上下文下的普通限价单，应沿用订单限价而非市价保护边界。
            var limit = new PlaceOrderCommand(93, "1", CoreOrderSide.BUY, 100, 2,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                    CoreTimeInForce.GTC, false, "limit");
            assertThat(CoreOrderDecisionResolver.resolve(context, 1001, limit).matchingPriceTicks()).isEqualTo(100);
        }
    }

    /**
     * 为市价买单准备用户费率覆盖，核对保护价、预留价、原标记价、保证金资产和 Maker 负费率、Taker 正费率。
     */
    @Test
    void resolvesProtectionReservationAndFeeInsideCore() {
        // 当前测试的运行时身份映射，为币对 1 提供同一稠密编号。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 当前测试直接调用决策入口的运行时，保存规范币对、价格和费率。
        TradingAccountRuntime runtime = runtime(linearInstrument());
        // 当前币对 1 的稠密身份，所有价格替换使用同一编号。
        int symbolId = identities.symbolId("1");
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, runtime.instrument("1"), 60_000, 9, 1_000));
        runtime.upsertFeePolicy(new UpsertFeePolicyCommand(
                71, 2, 1001, "1", -25, 75, 4, true, 900, 0));
        // 当前用例尚待解析的原始订单意图，按场景检查费率、鲜度或现货资产。
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "1", CoreOrderSide.BUY, 0, 2,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, false, "client-91");

        // 决策入口返回的确定输入，核对实际撮合边界、预留价格、资产及费率。
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime, identities, 1001, intent, 1_500);

        assertThat(resolved.matchingPriceTicks()).isEqualTo(60_600);
        assertThat(resolved.reservationPriceTicks()).isEqualTo(60_600);
        assertThat(resolved.markPriceTicks()).isEqualTo(60_000);
        assertThat(resolved.reservationKind()).isEqualTo(ReservationKind.DERIVATIVE_MARGIN);
        assertThat(resolved.reservationAsset()).isEqualTo("USDT");
        assertThat(resolved.makerFeeRatePpm()).isEqualTo(-25);
        assertThat(resolved.takerFeeRatePpm()).isEqualTo(75);
    }

    /**
     * 只用 Core 已存价格构造超龄限价请求，核对 STALE_MARK_PRICE 异常及协议错误码映射。
     */
    @Test
    void rejectsAStaleCoreMarkWithoutReadingProviderState() {
        // 当前测试的运行时身份映射，为币对 1 提供同一稠密编号。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 当前测试直接调用决策入口的运行时，保存规范币对、价格和费率。
        TradingAccountRuntime runtime = runtime(linearInstrument());
        runtime.putMarkPrice(new MarkPriceRuntime(identities.symbolId("1"),
                runtime.instrument("1"), 60_000, 9, 1_000));
        // 当前用例尚待解析的原始订单意图，按场景检查费率、鲜度或现货资产。
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "1", CoreOrderSide.SELL, 59_000, 2,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "client-91");

        assertThatThrownBy(() -> CoreOrderDecisionResolver.resolve(runtime, identities, 1001, intent, 6_001))
                .isInstanceOf(CoreStateRejectedException.class)
                .hasMessageContaining("freshness")
                .satisfies(/* 断言捕获的过期价格异常，进一步检查具体业务码和协议映射。 */ exception -> {
                    // 转换成明确业务拒绝类型的异常，code 应为 STALE_MARK_PRICE。
                    CoreStateRejectedException rejection = (CoreStateRejectedException) exception;
                    assertThat(rejection.code()).isEqualTo("STALE_MARK_PRICE");
                    assertThat(CoreResultCode.fromRejectionCode(rejection.code()))
                            .isEqualTo(CoreResultCode.STALE_MARK_PRICE);
                });
    }

    /**
     * 现货卖出限价单在没有标记价时仍可解析，撮合价与预留价用原限价，冻结 BTC 并沿用币对默认费率。
     */
    @Test
    void spotLimitUsesItsLimitAndInstrumentDefaultWithoutAMark() {
        // 当前测试的运行时身份映射，为币对 1 提供同一稠密编号。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 当前测试直接调用决策入口的运行时，保存规范币对、价格和费率。
        TradingAccountRuntime runtime = runtime(spotInstrument());
        identities.symbolId("1");
        // 当前用例尚待解析的原始订单意图，按场景检查费率、鲜度或现货资产。
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "1", CoreOrderSide.SELL, 60_000, 2,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "client-91");

        // 决策入口返回的确定输入，核对实际撮合边界、预留价格、资产及费率。
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime, identities, 1001, intent, 1_500);

        assertThat(resolved.matchingPriceTicks()).isEqualTo(60_000);
        assertThat(resolved.reservationPriceTicks()).isEqualTo(60_000);
        assertThat(resolved.reservationKind()).isEqualTo(ReservationKind.SPOT_ASSET);
        assertThat(resolved.reservationAsset()).isEqualTo("BTC");
        assertThat(resolved.makerFeeRatePpm()).isEqualTo(-10);
        assertThat(resolved.takerFeeRatePpm()).isEqualTo(25);
    }

    /**
     * 枚举现货买卖及 IOC、FOK，核对下单协议保留记录的价格边界，决策不需要标记价且按方向选择冻结资产。
     */
    @Test
    void spotMarketUsesItsLoggedProtectionBoundWithoutAMark() {
        // 当前测试的运行时身份映射，为币对 1 提供同一稠密编号。
        var identities = new RuntimeIdentityRegistry();
        identities.symbolId("1");
        try (/* 当前测试直接调用决策入口的运行时，保存规范币对、价格和费率。 */ var runtime = runtime(spotInstrument())) {
            for (var /* 本轮现货市价测试的买卖方向，决定冻结 USDT 还是 BTC。 */ side : CoreOrderSide.values()) {
                for (var /* 本轮现货市价测试的 IOC 或 FOK 有效期策略，协议应原样保留。 */ tif : List.of(CoreTimeInForce.IOC, CoreTimeInForce.FOK)) {
                    // 带正保护边界的现货市价指令，不依赖另行生成标记价。
                    var command = new PlaceOrderCommand(91, "1", side, 60_000, 2, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET, tif, false, "market");
                    // 现货下单指令的协议往返结果，边界、方向和有效期应与原请求相同。
                    var decoded = com.surprising.aeron.protocol.TradingCommandCodec.decodePlaceOrder(
                            com.surprising.aeron.protocol.TradingCommandCodec.encodePlaceOrder(command));
                    assertThat(decoded).isEqualTo(command);
                    // 决策入口返回的确定输入，核对实际撮合边界、预留价格、资产及费率。
                    var resolved = CoreOrderDecisionResolver.resolve(runtime, identities, 1001, decoded, 1_500);
                    assertThat(resolved.matchingPriceTicks()).isEqualTo(60_000);
                    assertThat(resolved.reservationPriceTicks()).isEqualTo(60_000);
                    assertThat(resolved.reservationKind()).isEqualTo(ReservationKind.SPOT_ASSET);
                    assertThat(resolved.reservationAsset()).isEqualTo(side == CoreOrderSide.BUY ? "USDT" : "BTC");
                }
            }
        }
    }

    /**
     * 现货市价必须自己带正保护边界，衍生品市价请求的原价格必须为零；两种错误在各自 Core 内直接拒绝。
     */
    @Test
    void marketPriceContractIsValidatedInsideItsProductCore() {
        // 当前测试的运行时身份映射，为币对 1 提供同一稠密编号。
        var identities = new RuntimeIdentityRegistry();
        identities.symbolId("1");
        try (/* 只注册现货币对的测试运行时，用它拒绝缺少市价保护边界的请求。 */ var spot = runtime(spotInstrument()); /* 只注册线性永续币对的测试运行时，用它拒绝原价格非零的市价请求。 */ var derivative = runtime(linearInstrument())) {
            // 价格边界为零的现货市价买单，违反现货必须有保护边界的约束。
            var unboundedSpot = new PlaceOrderCommand(91, "1", CoreOrderSide.BUY, 0, 2, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET, CoreTimeInForce.IOC, false, "spot");
            assertThatThrownBy(() -> CoreOrderDecisionResolver.resolve(spot, identities, 1001, unboundedSpot, 1_500))
                    .isInstanceOf(CoreStateRejectedException.class).hasMessageContaining("protection bound");
            // 原请求携带非零限价的衍生品市价单，违反该产品的价格格式约束。
            var boundedDerivative = new PlaceOrderCommand(92, "1", CoreOrderSide.BUY, 60_000, 2, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET, CoreTimeInForce.IOC, false, "derivative");
            assertThatThrownBy(() -> CoreOrderDecisionResolver.resolve(derivative, identities, 1001, boundedDerivative, 1_500))
                    .isInstanceOf(CoreStateRejectedException.class).hasMessageContaining("price must be zero");
        }
    }

    /**
     * 用大价格核对分块缩放可得到正确的下取整与上取整结果，最终超出 long 的放大仍必须抛算术异常。
     */
    @Test
    void scalesPricesWithoutOverflowingTheIntermediateProduct() {
        // 大于九千万亿的价格整数，用来证明不必先构造会溢出的完整中间乘积。
        long value = 9_000_000_000_000_001L;

        assertThat(CoreOrderDecisionResolver.scalePpm(value, 990_000, false))
                .isEqualTo(8_910_000_000_000_000L);
        assertThat(CoreOrderDecisionResolver.scalePpm(value, 1_010_000, true))
                .isEqualTo(9_090_000_000_000_002L);
        assertThatThrownBy(() -> CoreOrderDecisionResolver.scalePpm(Long.MAX_VALUE, 1_010_000, true))
                .isInstanceOf(ArithmeticException.class);
    }

    /**
     * 创建仅用于直接决策测试的运行时，设置所属产品线并注册给定规范币对，不启动外部服务。
     * @param instrument 要注册到直接决策测试运行时的规范币对配置
     */
    private static TradingAccountRuntime runtime(CoreInstrument instrument) {
        // 当前测试直接调用决策入口的运行时，保存规范币对、价格和费率。
        TradingAccountRuntime runtime = new TradingAccountRuntime();
        runtime.setMetadata(instrument.contractType().productLine(), 0);
        runtime.registerInstrument(instrument);
        return runtime;
    }

    /**
     * 返回具有默认 1% 市价保护等标准配置的线性永续测试币对。
     */
    private static CoreInstrument linearInstrument() {
        return instrument(ContractType.LINEAR_PERPETUAL);
    }

    /**
     * 返回现货测试币对，用于验证无标记价时的订单价格及资产规则。
     */
    private static CoreInstrument spotInstrument() {
        return instrument(ContractType.SPOT);
    }

    /**
     * 按给定合约类型创建相同币对身份和精度的测试配置，Maker 默认返佣、Taker 默认收费，并提供单档风险限制。
     * @param contractType 当前用例需要的现货或线性永续合约类型
     */
    private static CoreInstrument instrument(ContractType contractType) {
        return new CoreInstrument(
                "1", contractType, "BTC", "USDT", "USDT", 1, 1, 1_000_000,
                (contractType == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, 10_000_000, Long.MAX_VALUE, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE,
                        10_000_000, 100_000, 50_000)))),
                -10, 25, 0, null, 0);
    }
}
