package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.OpenInterestIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.admission.RuntimeOrderAdmission;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 下单只读预检的 Lane 集成测试，覆盖六产品的预留金额规则、订单与客户标识冲突、只减仓容量、配置捕获和慢 Lane；核对预检不写资金或订单，持仓身份检查点和账户交接计数不变。
 */
class CorePreflightLaneQueryTest {
    /** 固定业务时间，预检鲜度、合约到期和命令头都基于它。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 当前测试实例的消息来源序号，普通准备命令和预检查询共享递增。 */
    private long sequence;

    /**
     * 对六产品及买卖两方向，用冷运行时的正式决策和预留公式核对在线预检字节；前后业务状态、持仓身份检查点和账户交接计数都应不变。
     * @param line 本轮测试独立使用的产品线，决定合约、结算资产和预留公式
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sixProductPreflightsMatchExistingRulesWithoutReservingFundsOrAllocatingIdentities(ProductLine line) throws Exception {
        try (/* 本用例持有的核心运行时，通过原有异步入口预检，结束时关闭工作资源。 */ var core = prepared(line)) {
            // 预检前已经提交的完整业务状态，查询后必须保持相同。
            var before = core.tradingState();
            // 查询前持仓身份表检查点，预检不能分配新的持仓身份。
            long positionCheckpoint = core.identities.positionCheckpoint();
            // 查询前账户所有权交接计数，Lane 原位只读查询后不应增加。
            long handoff = core.runtimeState.laneHandoffEpoch();
            // 冷参照运行时独立使用的身份表，用于调用相同正式预留规则。
            var identities = new RuntimeIdentityRegistry();
            try (/* 从已提交状态投影的冷运行时，用其决策和金额计算核对在线预检。 */ var cold = RuntimeStateProjector.project(before, identities)) {
                for (var /* 本轮六产品参数化测试中的买卖方向，分别计算各自冻结资产及金额。 */ side : CoreOrderSide.values()) {
                    // 尚未执行的预检下单意图，原单编号固定而买卖方向逐次变化。
                    var command = place(900, side, 95, 3, false, "preflight-900");
                    // 在冷运行时通过订单规则解析的确定输入，供预留公式作为参照。
                    var resolved = CoreOrderDecisionResolver.resolve(cold, identities, 1, command, TIME);
                    // 冷运行时按产品规则算出的应预留金额，不在查询时真的冻结。
                    long reserved = RuntimeOrderAdmission.requiredReservation(cold, identities, 1, resolved,
                            0, cold.admissionOrders(1, resolved.symbolId()));
                    try (/* 禁止 Owner 越界读取活动账户的守卫，确保预检在所属 Lane 执行。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                        // 当前产品和方向的最终预检响应，应与冷规则资产及金额编码完全一致。
                        var response = query(core, 1, command);
                        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                        assertThat(response.data()).isEqualTo(CoreOrderPreflightCodec.encode(
                                new CoreOrderPreflightView(resolved.reservationAsset(), reserved)));
                    }
                }
            }
            assertThat(core.identities.positionCheckpoint()).isEqualTo(positionCheckpoint);
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(before);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 准备跨用户普通订单编号和本用户客户标识占用，分别核对重复码、无仓只减仓、算术溢出及畸形载荷；之后合法预检仍成功，业务状态不变。
     */
    @Test
    void duplicateIdUsesOwnerIndexAndDuplicateClientUsesLaneWithoutCrossAccountReads() throws Exception {
        try (/* 本用例持有的核心运行时，通过原有异步入口预检，结束时关闭工作资源。 */ var core = prepared(ProductLine.LINEAR_PERPETUAL)) {
            command(core, CoreMessageType.PLACE_ORDER, 7,
                    TradingCommandCodec.encodePlaceOrder(place(201, CoreOrderSide.SELL, 120, 1, false, "owner-201")));
            command(core, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(place(202, CoreOrderSide.BUY, 80, 1, false, "client-202")));
            // 预检前已经提交的完整业务状态，查询后必须保持相同。
            var before = core.tradingState();
            try (/* 禁止 Owner 越界读取活动账户的守卫，确保预检在所属 Lane 执行。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, 1, place(201, CoreOrderSide.BUY, 95, 1, false, "other-owner")).resultCode())
                        .isEqualTo(CoreResultCode.DUPLICATE_ORDER_ID);
                assertThat(query(core, 1, place(203, CoreOrderSide.BUY, 95, 1, false, "client-202")).resultCode())
                        .isEqualTo(CoreResultCode.DUPLICATE_CLIENT_ORDER_ID);
                assertThat(query(core, 1, place(204, CoreOrderSide.SELL, 95, 1, true, "reduce-empty")).resultCode())
                        .isEqualTo(CoreResultCode.REDUCE_ONLY_REQUIRES_POSITION_STATE);
                assertThat(query(core, 1, place(206, CoreOrderSide.BUY, 95, Long.MAX_VALUE, false, "overflow")).resultCode())
                        .isEqualTo(CoreResultCode.ARITHMETIC_OVERFLOW);
                // 只有一字节的畸形预检载荷，必须返回 INVALID_COMMAND 而不影响后续查询。
                var bad = message(core, 1, new byte[]{1});
                assertThat(core.requiresOwnerLaneAccessForPreparation(bad)).isFalse();
                assertThat(core.apply(bad).resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
                assertThat(query(core, 1, place(205, CoreOrderSide.BUY, 95, 1, false, "valid")).status()).isEqualTo(ResponseStatus.OK);
            }
            assertThat(core.tradingState()).isEqualTo(before);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 先成交十步持仓再挂八步只减仓单，新增三步应因容量不足拒绝，两步应与冷规则金额一致，两次预检都不改变资金及业务状态。
     */
    @Test
    void reduceOnlyCapacityUsesLanePendingOrdersAndRejectionDoesNotChangeFunds() throws Exception {
        try (/* 本用例持有的核心运行时，通过原有异步入口预检，结束时关闭工作资源。 */ var core = prepared(ProductLine.LINEAR_PERPETUAL)) {
            command(core, CoreMessageType.PLACE_ORDER, 7,
                    TradingCommandCodec.encodePlaceOrder(place(301, CoreOrderSide.SELL, 100, 10, false, "maker")));
            command(core, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(place(302, CoreOrderSide.BUY, 100, 10, false, "taker")));
            command(core, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(place(303, CoreOrderSide.SELL, 110, 8, true, "close-pending")));
            // 预检前已经提交的完整业务状态，查询后必须保持相同。
            var before = core.tradingState();
            // 冷参照运行时独立使用的身份表，用于调用相同正式预留规则。
            var identities = new RuntimeIdentityRegistry();
            try (/* 从已提交状态投影的冷运行时，用其决策和金额计算核对在线预检。 */ var cold = RuntimeStateProjector.project(before, identities)) {
                // 已有十步持仓和八步平仓占用后，仍可容纳的两步只减仓意图。
                var valid = place(305, CoreOrderSide.SELL, 110, 2, true, "close-valid");
                // 在冷运行时通过订单规则解析的确定输入，供预留公式作为参照。
                var resolved = CoreOrderDecisionResolver.resolve(cold, identities, 1, valid, TIME);
                // 冷运行时结合实际 OI 和挂单汇总算出的两步只减仓预留金额。
                long expected = RuntimeOrderAdmission.requiredReservation(cold, identities, 1, resolved,
                        new OpenInterestIndex(before).openInterestSteps("1"), cold.admissionOrders(1, resolved.symbolId()));
                try (/* 禁止 Owner 越界读取活动账户的守卫，确保预检在所属 Lane 执行。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                    assertThat(query(core, 1, place(304, CoreOrderSide.SELL, 110, 3, true, "close-oversized")).resultCode())
                            .isEqualTo(CoreResultCode.REDUCE_ONLY_CAPACITY_EXCEEDED);
                    assertThat(query(core, 1, valid).data()).isEqualTo(CoreOrderPreflightCodec.encode(
                            new CoreOrderPreflightView("USDT", expected)));
                }
            }
            assertThat(core.tradingState()).isEqualTo(before);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 真实阻塞目标 Lane，Owner 先返回待完成；测试随后直接更新在线币对配置和标记价，已提交查询仍用捕获前输入，新查询使用更新后输入，并且结果只消费一次。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void blockedLaneUsesCapturedMarketConfigurationAndOwnerReturnsImmediately() throws Exception {
        // 控制目标 Lane 放行的门闩，成功或失败收尾都要释放。
        var release = new CountDownLatch(1);
        try (/* 本用例持有的核心运行时，通过原有异步入口预检，结束时关闭工作资源。 */ var core = prepared(ProductLine.LINEAR_PERPETUAL)) {
            // 慢 Lane 场景反复使用的同一下单意图，用来比较捕获前后配置的影响。
            var intent = place(400, CoreOrderSide.BUY, 95, 3, false, "frozen-preflight");
            // 慢 Lane 用例冷参照使用的独立身份表。
            var ids = new RuntimeIdentityRegistry();
            // 配置和价格更新前算出的预检编码，已排队的查询必须仍匹配它。
            byte[] expected;
            try (/* 从已提交状态投影的冷运行时，用其决策和金额计算核对在线预检。 */ var cold = RuntimeStateProjector.project(core.tradingState(), ids)) {
                // 更新前冷配置解析的订单输入，用来计算旧预检编码。
                var order = CoreOrderDecisionResolver.resolve(cold, ids, 1, intent, TIME);
                expected = CoreOrderPreflightCodec.encode(new CoreOrderPreflightView(order.reservationAsset(),
                        RuntimeOrderAdmission.requiredReservation(cold, ids, 1, order, 0, cold.admissionOrders(1, order.symbolId()))));
            }
            // 确认阻塞任务已在用户所属 Lane 执行的门闩，避免尚未阻塞就发送查询。
            var entered = new CountDownLatch(1);
            // 用户 1 所属账户 Lane 的位集合，只阻塞目标消费者。
            long mask = core.runtimeState.topology().accountLaneMask(1);
            assertThat(core.runtimeState.dispatchAccountQuery(mask, /* 当前被真实阻塞的账户 Lane，等待门闩放行后才继续消费查询。 */ lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (/* 阻塞回调等待时收到的中断，转换为保留原因的断言失败。 */ InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 查询前账户所有权交接计数，Lane 原位只读查询后不应增加。
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (/* 禁止 Owner 越界读取活动账户的守卫，确保预检在所属 Lane 执行。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // Lane 尚被阻塞时提交的旧配置预检消息。
                var request = message(core, 1, TradingCommandCodec.encodePlaceOrder(intent));
                assertThat(core.apply(request).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                // 慢 Lane 查询的内部编号，未放行时无结果，放行后按此编号消费一次。
                var id = request.header().commandId();
                assertThat(core.bookQueries.takeQueryResult(id)).isNull();
                assertThat(release.getCount()).isOne();
                // 测试直接更新的在线规范币对，与早先捕获的查询配置区分。
                var instrument = core.runtimeState.instrument("1");
                instrument.updateConfiguration(CoreInstrument.from(core.productLine, instrument(core.productLine, 200_000)));
                core.runtimeState.putMarkPrice(new MarkPriceRuntime(core.identities.findSymbolId("1"), instrument, 120, 2, TIME));
                release.countDown();
                assertThat(await(core, request, id).data()).isEqualTo(expected);
                assertThat(query(core, 1, intent).data()).isNotEqualTo(expected);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    /**
     * 按产品注册相应合约，衍生品补标记价，给用户 1 和 7 准备反向 BTC 或其他产品 USDT 余额；准备失败先关闭核心再原样上抛。
     * @param line 本轮测试独立使用的产品线，决定合约、结算资产和预留公式
     */
    private TradingCoreRuntime prepared(ProductLine line) {
        // 本用例持有的核心运行时，通过原有异步入口预检，结束时关闭工作资源。
        var core = new TradingCoreRuntime(line);
        try {
            command(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(instrument(line, 100_000), line));
            if (line != ProductLine.SPOT) command(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line.isOptionProduct() ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME), line));
            // 当前产品准备余额所用资产，反向为 BTC，其他产品为 USDT。
            String asset = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
            for (long /* 本轮准备余额的测试用户，依次为 1 和 7。 */ user : new long[]{1, 7}) command(core, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 10_000)));
            return core;
        } catch (/* 准备业务状态时的原始失败，关闭核心后继续上抛。 */ Throwable failure) { core.close(); throw failure; }
    }

    /**
     * 按产品创建币对 1 配置，反向使用 BTC 结算和合约面值，交割与期权设置将来到期时间，期权另有方向与行权价。
     * @param line 本轮测试独立使用的产品线，决定合约、结算资产和预留公式
     * @param margin 测试币对的初始保证金百万分比参数，用更新前后不同值检查配置捕获
     */
    private static RegisterInstrumentCommand instrument(ProductLine line, long margin) {
        // 由产品代码确定的合约类型，控制反向、到期和期权参数。
        var type = ContractType.valueOf(line.contractTypeCode());
        return new RegisterInstrumentCommand(
                "1", "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1, type.isInverse() ? 1000 : 1,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        margin, (1_000_000_000_000L / (margin)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (margin)), margin, 50_000)))),
                500, 1000, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0, type.isOption() ? 0 : -1,
                type.isOption() ? 100 : 0);
    }

    /**
     * 构造全仓净持仓的限价 GTC 测试意图，允许指定只减仓和客户端标识，意图本身不表示下单已执行。
     * @param id 测试订单意图的 Core 编号
     * @param side 测试订单的买入或卖出方向
     * @param price 测试限价刻度
     * @param quantity 测试数量步数
     * @param reduce 是否只允许减少已有持仓
     * @param client 测试意图携带的客户端订单标识
     */
    private static PlaceOrderCommand place(long id, CoreOrderSide side, long price, long quantity, boolean reduce, String client) {
        return new PlaceOrderCommand(id, "1", side, price, quantity, reduce, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, client);
    }

    /**
     * 给业务准备命令分配来源序号和 UUID，经原测试辅助器推进到最终 APPLIED。
     * @param core 负责本轮业务准备或异步预检的测试核心
     * @param type 准备业务状态时要执行的普通命令类型
     * @param user 消息头中的用户编号，非用户准备命令可为 0
     * @param payload 普通命令或预检查询已经编码的载荷
     */
    private void command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 本次新分配的来源序号，同时参与命令 UUID 和消息头。
        long next = ++sequence;
        // 准备状态的正式业务命令，辅助器等待到最终应用成功。
        var request = new CoreMessage(CoreMessageHeader.command(type, new UUID(980, next), core.productLine,
                CommandSource.OPERATIONS, 980, next, user, TIME, next), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 编码 ORDER_PREFLIGHT_QUERY 并禁止 Owner 在准备阶段借账户权限；即时响应直接返回，待完成则按内部查询编号等待。
     * @param core 负责本轮业务准备或异步预检的测试核心
     * @param user 消息头中的用户编号，非用户准备命令可为 0
     * @param intent 需要做只读预检的原始下单意图
     */
    private CoreResponse query(TradingCoreRuntime core, long user, PlaceOrderCommand intent) {
        // 为当前意图构造的只读预检消息，供即时返回或异步等待使用。
        var request = message(core, user, TradingCommandCodec.encodePlaceOrder(intent));
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        // 核心直接返回的预检提交状态，待完成时继续等待最终结果。
        var response = core.apply(request);
        return response.resultCode() == CoreResultCode.MATCHING_PENDING
                ? await(core, request, request.header().commandId()) : response;
    }

    /**
     * 最多等待五秒消费最终预检结果，期间检查核心健康；结果取走后再次读取为空，外部命令关联也应清除。
     * @param core 负责本轮业务准备或异步预检的测试核心
     * @param request 已经提交的预检消息，用其命令 UUID 核对内部关联清理
     * @param id 本次预检内部异步编号，要求非零并只消费一次
     */
    private CoreResponse await(TradingCoreRuntime core, CoreMessage request, java.util.UUID id) {
        assertThat(core.bookQueries.hasPendingQuery(id)).isTrue();
        // 异步预检的五秒单调时钟等待上限，不作为业务价格时间。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // 持续尝试消费的最终预检结果，成功取得后不允许再次返回。
        CoreResponse response;
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("preflight query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.hasPendingQuery(request.header().commandId())).isFalse();
        return response;
    }

    /**
     * 使用独立的查询 UUID 前缀和递增来源序号，为指定用户构造预检消息头。
     * @param core 负责本轮业务准备或异步预检的测试核心
     * @param user 消息头中的用户编号，非用户准备命令可为 0
     * @param payload 普通命令或预检查询已经编码的载荷
     */
    private CoreMessage message(TradingCoreRuntime core, long user, byte[] payload) {
        // 本次新分配的来源序号，同时参与命令 UUID 和消息头。
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.ORDER_PREFLIGHT_QUERY, new UUID(981, next), core.productLine,
                CommandSource.OPERATIONS, 981, next, user, TIME, next), payload);
    }
}
