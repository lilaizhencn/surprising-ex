package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证普通、改单和批量准入按业务需要读取市场持仓量输入：固定上限或已足够的下限无需读 OI，比例约束必须保留。另覆盖 FUSED 在账本尚未发布 OI 时先返回等待，不能同线程死等或跳过检查。
 */
class CoreAdmissionInputOwnershipTest {
    /** 固定复制业务时间，供本测试命令及结果一致性使用。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试实例递增的来源序号。 */
    private long sequence;

    /**
     * 遍历各产品与四种限制配置；只有衍生品的有效比例约束才声明需要全市场 OI 输入。
     * @param product 当前参数化产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void marketOiDependencyFollowsProductRateAndCappedFloor(ProductLine product) {
        for (String /* 本轮遍历的限制配置场景 */ mode : List.of("RATE_ZERO", "FLOOR_CAP", "FLOOR_ABOVE", "PROPORTIONAL")) {
            // 按当前产品和限制模式构造的合约，用来检查 OI 依赖
            var value = CoreInstrument.from(product, instrument(product, mode));
            assertThat(value.usesOpenInterestForUserLimit()).isEqualTo(product.isDerivative() && mode.equals("PROPORTIONAL"));
        }
    }

    /**
     * 替换市场 OI 字典为计数守卫，在 Owner 账户读保护下执行预检、下单、改单及批量请求；无需 OI 时禁止读取，同时核对固定上限、下限和比例拒单及资金不变量。
     * @param mode 比例系数和下限组合的测试模式
     */
    @ParameterizedTest @ValueSource(strings = {"RATE_ZERO", "FLOOR_CAP", "FLOOR_ABOVE", "PROPORTIONAL"})
    void ownerSkipsUnneededOiWithoutDroppingFixedFloorCapOrProportionalChecks(String mode) throws Exception {
        try (/* 本场景自动关闭的真实交易 Core */ var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(core, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(instrument(core.productLine, mode), ProductLine.LINEAR_PERPETUAL));
            applied(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), ProductLine.LINEAR_PERPETUAL));
            applied(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            // 市场 OI 索引内部字典的反射字段
            var totals = core.openInterestIndex().getClass().getDeclaredField("totals");
            totals.setAccessible(true);
            // 带读取计数和禁止多余访问断言的 OI Map
            var guarded = /* 仅用于观察市场 OI 读取的 Map 守卫，初始 OI 为空，get 覆盖会计数并禁止不必要的访问 */ new HashMap<String, Object>() {
                /** 守卫 OI 字典被实际调用 get 的次数，用于判断是否读取了市场输入。 */
                int reads;
                /**
                 * 记录一次 OI 字典读取；非比例场景一旦读取立即断言失败，比例场景继续返回原 Map 查询结果。
                 * @param key 当前 OI Map 查询键，交给原 Map 返回对应值
                 */
                @Override public Object get(Object key) {
                    reads++;
                    if (!mode.equals("PROPORTIONAL")) throw new AssertionError("fixed user limit read market OI");
                    return super.get(key);
                }
            };
            totals.set(core.openInterestIndex(), guarded);
            try (/* 禁止 Owner 跨线程读取账户的保护器 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // 订单预检查询命令
                var request = message(core, CoreMessageType.ORDER_PREFLIGHT_QUERY, 7, TradingCommandCodec.encodePlaceOrder(place(900, 1)));
                // 初始请求或完成回调交出的业务响应
                var response = core.apply(request);
                if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                    // 预检查询等待的盘口查询序号
                    var id = request.header().commandId();
                    // 本次等待查询或异步终态的五秒单调截止时间
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ((response = core.bookQueries.takeQueryResult(id)) == null) {
                        core.assertHealthy();
                        if (System.nanoTime() >= deadline) throw new AssertionError("preflight timeout");
                        Thread.onSpinWait();
                    }
                }
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                applied(core, CoreMessageType.PLACE_ORDER, 7, TradingCommandCodec.encodePlaceOrder(place(101, 1)));
                applied(core, CoreMessageType.REPLACE_ORDER, 7,
                        TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(101, place(102, 1))));
                applied(core, CoreMessageType.AMEND_ORDER, 7, TradingCommandCodec.encodeAmendOrder(
                        new AmendOrderCommand(102, 103, "client-103", 80L, 1L, CoreTimeInForce.GTC, false)));
                response = applied(core, CoreMessageType.PLACE_ORDER_BATCH, 7, TradingOrderBatchCodec.encodePlaceOrderBatch(
                        new PlaceOrderBatchCommand(List.of(place(201, 1), place(202, 1)))));
                // 批量下单终态解码后的逐项结果
                var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                assertThat(items.getFirst().status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(items.getLast().status()).isEqualTo(mode.equals("FLOOR_CAP") || mode.equals("FLOOR_ABOVE")
                        ? ResponseStatus.APPLIED : ResponseStatus.REJECTED);
                if (items.getLast().status() == ResponseStatus.REJECTED)
                    assertThat(items.getLast().resultCode()).isEqualTo(CoreResultCode.OPEN_INTEREST_LIMIT_EXCEEDED);
                applied(core, CoreMessageType.AMEND_ORDER_BATCH, 7, TradingOrderBatchCodec.encodeAmendOrderBatch(
                        new AmendOrderBatchCommand(List.of(new AmendOrderCommand(201, 301, "client-301", 80L, 1L, CoreTimeInForce.GTC, false)))));
                response = CoreTestCompletion.applyAsynchronously(core, message(core, CoreMessageType.PLACE_ORDER, 7,
                        TradingCommandCodec.encodePlaceOrder(place(999, 100))));
                assertThat(response.resultCode()).isEqualTo(CoreResultCode.POSITION_NOTIONAL_LIMIT_EXCEEDED);
                assertThat(guarded.reads > 0).isEqualTo(mode.equals("PROPORTIONAL"));
            }
            // 完成后导出的用户状态，用于核对资金及持仓
            var user = CoreTestCompletion.userState(core, 7);
            assertThat(user.balances().getFirst().availableUnits() + user.balances().getFirst().lockedUnits()).isEqualTo(10_000);
            assertThat(user.positions()).isEmpty();
        }
    }

    /**
     * 按指定产品和四种限制场景组装合约，保持固定总上限，改变比例系数和下限以覆盖是否需要 OI。
     * @param line 需要组装合约的产品线
     * @param mode 比例系数和下限组合的测试模式
     */
    private static RegisterInstrumentCommand instrument(ProductLine line, String mode) {
        // 当前产品对应的币对或合约类型
        var type = ContractType.valueOf(line.contractTypeCode());
        // 按模式选择的用户限额下限，覆盖低于、达到或超过固定上限
        long floor = mode.equals("FLOOR_CAP") ? 1_000 : mode.equals("FLOOR_ABOVE") ? 2_000 : 200;
        return new RegisterInstrumentCommand(
                "1", "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1, type.isInverse() ? 1_000 : 1,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, 10_000_000, 1_000, mode.equals("RATE_ZERO") ? 0 : 100_000, floor,
                        List.of(new CoreRiskLimitBracket(1_000, 10_000_000, 100_000, 50_000)))),
                0, 0, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0, type.isOption() ? 0 : -1,
                type.isOption() ? 100 : 0);
    }

    /**
     * 构造买入限价单，使用固定合约和价格，并按业务 ID 生成独立客户端订单号。
     * @param id 本次限价单订单 ID
     * @param quantity 本次订单数量
     */
    private static PlaceOrderCommand place(long id, long quantity) {
        return new PlaceOrderCommand(id, "1", CoreOrderSide.BUY, 80, quantity, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-" + id);
    }
    /**
     * 构造命令后调用现有异步完成助手，断言最终成功并返回终态。
     * @param core 当前夹具交易运行时
     * @param type 本次命令类型，用于选择头类型与载荷
     * @param user 命令所属用户 ID
     * @param payload 与该类型对应的业务载荷
     */
    private CoreResponse applied(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 初始请求或完成回调交出的业务响应
        var response = CoreTestCompletion.applyAsynchronously(core, message(core, type, user, payload));
        assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
    /**
     * 分配唯一来源序号与 UUID；预检使用 QUERY 头，其余使用 COMMAND 头，业务时间保持固定。
     * @param core 当前夹具交易运行时
     * @param type 本次命令类型，用于选择头类型与载荷
     * @param user 命令所属用户 ID
     * @param payload 与该类型对应的业务载荷
     */
    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 本次命令新分配的来源序号
        long next = ++sequence;
        // 按预检查询或普通命令类型构造的协议头
        var header = type == CoreMessageType.ORDER_PREFLIGHT_QUERY
                ? CoreMessageHeader.query(type, new UUID(510, next), core.productLine,
                        CommandSource.OPERATIONS, 510, next, user, TIME, next)
                : CoreMessageHeader.command(type, new UUID(510, next), core.productLine,
                        CommandSource.OPERATIONS, 510, next, user, TIME, next);
        return new CoreMessage(header, payload);
    }
}
