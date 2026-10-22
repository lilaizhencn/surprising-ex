package com.surprising.aeron.service.core;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreAlgoOrderCodec;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.query.state.RuntimeOperationalQueryService;
import com.surprising.aeron.service.retention.TerminalStateRetention;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 算法父单查询的账户 Lane 集成测试：检查全局调度顺序、保留子订单的成交汇总、扫描规模上限，以及慢 Lane 不阻塞 Owner 和结果只能消费一次。
 */
class CoreAlgoLaneQueryTest {
    /**
     * 核对相邻两个查询截止时间的命中边界；终态父单仍可按编号读取，但不应占用活动查询的一条结果名额。
     */
    @Test void deadlinePrecedesTheClaimLeaseAndTerminalParentsDoNotHideActiveWork() {
        // 直接构造的算法父单索引，用相邻截止时间检查活动父单的查询边界。
        var index = new com.surprising.aeron.service.core.index.AlgoOrderIndex(TradingCoreState.empty(LINE));
        index.apply(999,algo(999,7,"1",TIME+30000,List.of()));
        assertThat(index.query(7,"",TIME+9999,1)).isEmpty();
        assertThat(index.query(7,"",TIME+10000,1)).containsExactly(999L);
        // 已结束的父单 998，仍可读取详情，但不应挤占活动父单查询名额。
        var terminal = new CoreAlgoOrderState(998,7,"ended","1",0,CoreOrderSide.BUY,100,100,10,
                1,10,CoreMarginMode.CROSS,CorePositionSide.NET,false,false,CoreTimeInForce.GTC,3,
                0,"",TIME,0,TIME+1,TIME,TIME+1,2,List.of());
        index.apply(998,terminal);
        assertThat(index.query(7,"",0,1)).containsExactly(999L);
        assertThat(index.value(998)).isNotNull();
    }
    /** 这些测试独立使用的 U 本位永续产品线。 */
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    /** 固定业务时间，供父单创建时间、调度时间和查询边界共用。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 当前测试实例的消息来源序号，每构造一个查询递增一次。 */
    private long sequence;

    /**
     * 用冷投影结果核对跨 Lane 父单查询，验证用户、币对、截止时间和条数筛选；已终态子订单虽无活跃路由，成交量仍参与父单汇总，查询不改变账户交接次数和业务状态。
     */
    @Test
    void queriesIncludeRetainedChildrenAndKeepGlobalDueOrderAcrossLanes() throws Exception {
        // 包含两个用户、三个算法父单及保留子订单的测试业务状态。
        var state = preparedState(false);
        // 冷投影使用的运行时身份映射，不与在线账户查询共享实体状态。
        var identities = new RuntimeIdentityRegistry();
        try (/* 由同一业务状态生成的冷运行时，直接查询它以构造协议字节参照，结束时关闭。 */ var cold = RuntimeStateProjector.project(state, identities); /* 本用例激活的在线核心，查询结果必须通过所属 Lane 返回，结束时释放工作资源。 */ var core = initial(state)) {
            // 冷运行时按 502、501、503 顺序汇总的父单视图，用于核对在线编码结果。
            var expected = RuntimeOperationalQueryService.algoOrders(cold, List.of(502L, 501L, 503L));
            core.activate();
            assertThat(core.activeOrderIndex.activeOrderRoute(102)).isNull();
            assertThat(core.activeOrderIndex.activeOrderRoute(104)).isNull();
            // 查询前账户所有权交接计数，查询后不能因 Owner 读账户而增加。
            long handoff = core.runtimeState.laneHandoffEpoch();
            assertThat(java.util.Arrays.stream(TradingRuntimeState.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                    .doesNotContain("publishedAlgoOrders");
            try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询只能在所属 Lane 读取。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, 0, 0, "", 0, 10).data()).isEqualTo(CoreAlgoOrderCodec.encodeList(expected));
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 1, 0, "", 0, 10).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(501L, 503L);
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 0, "2", 0, 10).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(503L);
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 0, "", TIME + 700, 10).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(502L);
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 0, "", 0, 1).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(502L);
                // 父单 501 的查询结果，应包含终态子单成交量和一笔仍活动的子单。
                var one = CoreAlgoOrderCodec.decodeList(query(core, 0, 501, "", 0, 1).data()).getFirst();
                assertThat(one.executedQuantitySteps()).isEqualTo(7);
                assertThat(one.activeQuantitySteps()).isEqualTo(3);
                assertThat(one.activeChildOrderCount()).isOne();
                // 父单 502 的查询结果，核对部分成交子单的已成交量、剩余量和活动条数。
                var partial = CoreAlgoOrderCodec.decodeList(query(core, 0, 502, "", 0, 1).data()).getFirst();
                assertThat(partial.executedQuantitySteps()).isEqualTo(4);
                assertThat(partial.activeQuantitySteps()).isEqualTo(6);
                assertThat(partial.activeChildOrderCount()).isOne();
            }
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(state);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 不存在的父单或用户返回空列表，畸形载荷返回 INVALID_COMMAND；随后合法查询仍成功，Owner 不需要为准备查询读取账户。
     */
    @Test
    void missingAndMalformedQueriesKeepTheirProtocolAndDoNotPoisonNextQuery() throws Exception {
        try (/* 本用例激活的在线核心，查询结果必须通过所属 Lane 返回，结束时释放工作资源。 */ var core = initial(preparedState(false))) {
            core.activate();
            try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询只能在所属 Lane 读取。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 0, 999, "", 0, 10).data())).isEmpty();
                assertThat(CoreAlgoOrderCodec.decodeList(query(core, 99, 0, "", 0, 10).data())).isEmpty();
                // 只有一个字节的畸形查询消息，应被拒绝且不影响后续合法查询。
                var bad = message(new byte[]{1});
                assertThat(core.requiresOwnerLaneAccessForPreparation(bad)).isFalse();
                assertThat(core.apply(bad).resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
                assertThat(query(core, 0, 503, "", 0, 10).status()).isEqualTo(ResponseStatus.OK);
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 用两个父单各九千个重复子单引用触发全局扫描上限，确认超限查询清理编号；随后单查无子单父单仍能返回正常结果。
     */
    @Test
    void scanLimitIsGlobalAcrossSelectedParentsAndFollowingSingleParentStillWorks() throws Exception {
        try (/* 本用例激活的在线核心，查询结果必须通过所属 Lane 返回，结束时释放工作资源。 */ var core = initial(preparedState(true))) {
            core.activate();
            try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询只能在所属 Lane 读取。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // 跨两个父单列举的查询，合计子单扫描项超限。
                var request = message(CoreAlgoOrderCodec.encodeQuery(0, 0, "", 0, 10));
                assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
                assertThat(CoreTestCompletion.querySynchronously(core, request).resultCode()).isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
                assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
                // 超限之后精确查询无子单父单 503 的响应，应正常成功。
                var response = query(core, 0, 503, "", 0, 10);
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(CoreAlgoOrderCodec.decodeList(response.data())).singleElement().satisfies(/* 无子单父单 503 的唯一返回项，其成交和活动数量都应为零。 */ view -> {
                    assertThat(view.algoOrderId()).isEqualTo(503);
                    assertThat(view.executedQuantitySteps()).isZero();
                    assertThat(view.activeQuantitySteps()).isZero();
                });
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 在 PIPELINED 模式实际阻塞用户 1 的账户 Lane；Owner 先返回待完成状态，放行 Lane 后取得排序后的父单页，并检查结果与查询关联只消费一次。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void ownerCanReturnWhileAnAlgoLaneIsBlockedAndConsumesPageExactlyOnce() throws Exception {
        // 控制慢 Lane 放行的门闩，正常结束或失败退出都要释放。
        var release = new CountDownLatch(1);
        try (/* 本用例激活的在线核心，查询结果必须通过所属 Lane 返回，结束时释放工作资源。 */ var core = initial(preparedState(false))) {
            core.activate();
            // 确认阻塞回调已经进入所属账户 Lane 的门闩，避免阻塞尚未发生就发送查询。
            var entered = new CountDownLatch(1);
            assertThat(core.runtimeState.dispatchAccountQuery(core.runtimeState.topology().accountLaneMask(1), /* 用户 1 所属的账户 Lane，在此回调里等待放行以构造真实慢消费者。 */ lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (/* 阻塞回调等待时收到的中断，保留原因并使测试失败。 */ InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询只能在所属 Lane 读取。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // 慢 Lane 尚未放行时提交的父单查询，用来检查 Owner 可先返回。
                var request = message(CoreAlgoOrderCodec.encodeQuery(0, 0, "", 0, 10));
                assertThat(core.apply(request).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                // 本次父单查询的内部编号，用来检查未完成、首次消费和关联清理。
                long id = core.bookQueries.querySequence(request.header().commandId());
                assertThat(core.bookQueries.takeQueryResult(id)).isNull();
                assertThat(release.getCount()).isOne();
                release.countDown();
                assertThat(CoreAlgoOrderCodec.decodeList(await(core, request, id).data()))
                        .extracting(CoreAlgoOrderView::algoOrderId).containsExactly(502L, 501L, 503L);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    /**
     * 准备两个用户的算法父单和已成交、部分成交、已撤子单；超限分支重复已有子单编号来增加扫描项，不创建九千笔不同订单。
     * @param oversized 为 true 时让两个父单各含九千个扫描项，以超过合并后的全局扫描上限
     */
    private static TradingCoreState preparedState(boolean oversized) {
        // 主测试币对 1 的永续合约配置，币对 2 另在返回状态中创建。
        var instrument1 = instrument("1");
        // 保留的普通子订单，包含成交终态、撤单终态和仍活动的部分成交单。
        var orders = Map.of(102L, order(102, 1, 4, 4, 0, CoreOrderStatus.FILLED),
                103L, order(103, 1, 5, 2, 3, CoreOrderStatus.OPEN),
                104L, order(104, 1, 2, 1, 1, CoreOrderStatus.CANCELED),
                201L, order(201, 7, 10, 4, 6, CoreOrderStatus.OPEN));
        // 用户 1 的账户，保留订单 103 的十单位保证金冻结。
        var first = new CoreUserState(LINE, 1, 0, Map.of("USDT", new AssetBalance("USDT", 990, 10)),
                Map.of(103L, OrderReservation.create(103, "1", ReservationKind.DERIVATIVE_MARGIN, "USDT", 10, 5)), Map.of());
        // 用户 7 的账户，保留订单 201 的六十单位保证金冻结。
        var second = new CoreUserState(LINE, 7, 0, Map.of("USDT", new AssetBalance("USDT", 940, 60)),
                Map.of(201L, OrderReservation.create(201, "1", ReservationKind.DERIVATIVE_MARGIN, "USDT", 60, 10)), Map.of());
        // 每父单9,000个扫描项均在单单上限内；两个Lane合起来超过全局16,384扫描项。
        var firstChildren = oversized ? Collections.nCopies(9000, 102L) : List.of(102L, 103L, 104L);
        // 父单 502 的子单引用；超限分支重复订单 201，用来增加扫描项。
        var secondChildren = oversized ? Collections.nCopies(9000, 201L) : List.of(201L);
        // 跨用户、币对和不同调度时间的三个活动算法父单。
        var algos = Map.of(501L, algo(501, 1, "1", TIME + 1000, firstChildren),
                502L, algo(502, 7, "1", TIME + 500, secondChildren),
                503L, algo(503, 1, "2", TIME + 1000, List.of()));
        return new TradingCoreState(LINE, 0, Map.of(1L, first, 7L, second), orders,
                Map.of("1", instrument1, "2", instrument("2")), CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), algos);
    }

    /**
     * 创建测试币对的线性永续配置，提供固定精度和保证金参数。
     * @param name 测试币对的永久数字文本编号
     */
    private static CoreInstrument instrument(String name) {
        return CoreInstrument.from(LINE, new RegisterInstrumentCommand(name, ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
    }

    /**
     * 创建买入方向、固定价格的普通子订单，由调用方指定总量、成交量、剩余量和终态。
     * @param id 普通子订单的 Core 编号
     * @param user 子订单所属用户编号
     * @param quantity 子订单原始数量步数
     * @param executed 子订单已成交数量步数
     * @param remaining 子订单剩余数量步数
     * @param status 子订单的活动、已成交或已撤状态
     */
    private static CoreOrderState order(long id, long user, long quantity, long executed, long remaining, CoreOrderStatus status) {
        return new CoreOrderState(id, LINE, user, "1", CoreOrderSide.BUY, 100, quantity, executed, remaining, false, status, 1);
    }

    /**
     * 创建活动算法父单，使用全仓净持仓、固定拆单参数，并保留传入的调度时间和子单编号列表。
     * @param id 算法父单编号
     * @param user 父单所属用户编号
     * @param symbol 算法父单对应的币对编号
     * @param due 父单状态中保存的下一次调度时间
     * @param children 需要在查询时汇总的保留子订单编号列表
     */
    private static CoreAlgoOrderState algo(long id, long user, String symbol, long due, List<Long> children) {
        return new CoreAlgoOrderState(id, user, "algo-query-" + id, symbol, 0, CoreOrderSide.BUY, 100, 100, 10,
                1, 10, CoreMarginMode.CROSS, CorePositionSide.NET, false, false, CoreTimeInForce.GTC, 1,
                0, "", TIME, due, 0, TIME, TIME, 1, children);
    }

    /**
     * 从给定业务状态创建尚未激活的核心运行时，查询测试自行激活并在结束时关闭。
     * @param state 测试预先准备的用户、订单、币对和算法父单业务状态
     */
    private TradingCoreRuntime initial(TradingCoreState state) {
        return new TradingCoreRuntime(LINE, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    /**
     * 编码算法父单查询并核对准备阶段无须 Owner 读取账户；同步结果直接返回，异步结果通过查询编号等待。
     * @param core 已激活并负责本次查询的核心运行时
     * @param user 按用户筛选的编号，0 表示不限定用户
     * @param algo 精确查询的父单编号，0 表示按条件列举
     * @param symbol 币对筛选条件，空文本表示不限定币对
     * @param due 调度时间筛选上界，0 表示不限定该条件
     * @param limit 最多返回的算法父单条数
     */
    private CoreResponse query(TradingCoreRuntime core, long user, long algo, String symbol, long due, int limit) {
        // 按本次筛选条件编码的查询消息，用于同步入口或异步等待。
        var request = message(CoreAlgoOrderCodec.encodeQuery(user, algo, symbol, due, limit));
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        // 核心提交查询后的直接响应，若仍待完成则继续等待内部编号。
        var response = core.apply(request);
        return response.resultCode() == CoreResultCode.MATCHING_PENDING
                ? await(core, request, core.bookQueries.querySequence(request.header().commandId())) : response;
    }

    /**
     * 最多等待五秒取得算法查询结果，期间检查运行时健康；消费后再次读取为空，外部命令与内部查询编号的关联也应清除。
     * @param core 已激活并负责本次查询的核心运行时
     * @param request 已经提交的查询消息，用其命令编号核对关联清理
     * @param id 内部异步查询编号，必须非零且只消费一次
     */
    private CoreResponse await(TradingCoreRuntime core, CoreMessage request, long id) {
        assertThat(id).isNotZero();
        // 使用单调时钟计算的五秒等待上限，不参与任何业务时间判断。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // 不断尝试消费的最终响应，成功读取后同一查询不能再返回结果。
        CoreResponse response;
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("algo query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
        return response;
    }

    /**
     * 用递增来源序号构造算法查询消息，使每次请求具有不同命令编号和查询头。
     * @param payload 已经编码的算法父单查询载荷，畸形用例也从此入口发送
     */
    private CoreMessage message(byte[] payload) {
        // 本次新分配的来源序号，同时参与命令 UUID 和查询头构造。
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.ALGO_ORDER_QUERY, new UUID(975, next), LINE,
                CommandSource.OPERATIONS, 975, next, 0, TIME, next), payload);
    }
}
