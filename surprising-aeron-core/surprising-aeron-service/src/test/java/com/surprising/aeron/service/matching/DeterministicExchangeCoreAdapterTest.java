package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.CoreTreasuryState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommandType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.surprising.aeron.service.state.DirectMatcherTestInputs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原生撮合适配器的直接命令、分片证据、订单簿查询和内存快照测试。真实调用本地引擎或用指定模拟原生回执检查证据，不启动 Aeron Cluster；每个场景的资金、并发、持久化和损坏校验范围以实际断言为准。
 */
class DeterministicExchangeCoreAdapterTest {

    /**
     * 直接下单缺少结算事件目标时必须拒绝，随后查询本币对订单簿仍为空。
     */
    @Test
    void directCommandRejectsMissingSettlementBeforeChangingTheBook() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            assertThatThrownBy(() -> adapter.placeDirect(adapter.matcherShardId("1"), 1,
                    new java.util.UUID(7, 1), 1000, 7, bid(1, 90), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(adapter.orderBookLevelsAsync("1", 30).join()).isEmpty();
        }
    }

    /**
     * 第一笔前置撤单成功后第二笔不存在导致失败，要求回执保留一条成功前缀并标为致命分歧，新下单身份仍可核对且订单簿为空。
     */
    @Test
    void failedDirectPrefixRetainsEarlierCancellationAndNeverSubmitsThePlacement() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 7, bid(1, 90));
            // 本次待提交或用于前置 Core 元数据的订单。
            var order = bid(3, 80);
            // 原命令 UUID，或构造控制分片前置订单时的普通订单编号。
            var id = new java.util.UUID(7, 3);
            // 已按当前命令准备好的原结算事件目标。
            var target = preparePlace(adapter, 3, id, 1000, 7, order, List.of(1L, 2L));
            adapter.cancelDirectPrefix(1000, 7, 1, "1");
            target.recordDirectPreCancellation(1);
            // 第二笔前置撤单失败时取得的原生回执。
            var failure = adapter.cancelDirectPrefix(1000, 7, 2, "1");
            adapter.publishDirectPrefixFailure(adapter.matcherShardId("1"), 3, id, 3, 1000, failure, target);
            assertThat(target.accepted()).isFalse();
            assertThat(target.plan().preCancellationCount()).isEqualTo(1);
            assertThat(target.outcome()).isEqualTo(MatchingResult.Outcome.FATAL_DIVERGENCE);
            assertThat(target.nativeMatches(id)).isTrue();
            assertThat(target.nativeOrderId()).isEqualTo(3);
            assertThat(adapter.orderBookLevelsAsync("1", 30).join()).isEmpty();
        }
    }

    /**
     * 挂一百五十一个价格档，分别查询五十和一百档后再用 IOC 买完全部，要求产生一百五十一条成交且末价正确，说明查询显示深度没有删除隐藏档。
     */
    @Test
    void depthQueriesDoNotDiscardRestingOrdersBeyondVisibleLevels() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            for (/* 当前正在构造的价格档位下标。 */ int level = 0; level < 151; level++) {
                assertThat(placeDirect(adapter, 11, new CoreMatchingOrder(1000 + level, "21",
                        CoreOrderSide.SELL, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        100_000 + level, 1)).accepted()).isTrue();
            }
            assertThat(adapter.orderBookLevelsAsync("21", 50).join()).hasSize(50);
            assertThat(adapter.orderBookLevelsAsync("21", 100).join()).hasSize(100);
            // 当前直接命令取得的撮合回执。
            var result = placeDirect(adapter, 22, new CoreMatchingOrder(2000, "21",
                    CoreOrderSide.BUY, CoreOrderType.LIMIT, CoreTimeInForce.IOC,
                    100_150, 151));
            // 从回执事件中筛选出的 TRADE 列表。
            var trades = result.matcherEvents().stream()
                    .filter(/* 当前用于筛选 TRADE 类型的原生事件。 */ e -> e.eventType() == exchange.core2.core.common.MatcherEventType.TRADE)
                    .toList();
            assertThat(trades).hasSize(151);
            assertThat(trades.stream().mapToLong(MatcherResult.MatcherEvent::size).sum()).isEqualTo(151);
            assertThat(trades.getLast().price()).isEqualTo(100_150);
            assertThat(adapter.orderBookLevelsAsync("21", 100).join()).isEmpty();
        }
    }

    /**
     * 买卖两个方向各构造三档深度，市价单滑点参数为一百百万分比，要求只成交基准及万分之一边界两档、数量共四。
     * @param side 当前参数化的市价买入或卖出方向
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CoreOrderSide.class)
    void linearMarketOrderCannotSweepBeyondOneBasisPoint(CoreOrderSide side) {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 当前参数是否买入，决定 Maker 方向和递进价格符号。
            boolean buy = side == CoreOrderSide.BUY;
            // 为市价单提供深度的反向 Maker 方向。
            CoreOrderSide makerSide = buy ? CoreOrderSide.SELL : CoreOrderSide.BUY;
            for (/* 当前正在构造的价格档位下标。 */ int level = 0; level < 3; level++) {
                placeDirect(adapter, 11, new CoreMatchingOrder(101 + level, "21", makerSide,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        100_000 + (buy ? 1 : -1) * level * 10, 2));
            }
            // 本次待提交或用于前置 Core 元数据的订单。
            var order = new CoreMatchingOrder(201, "21", side, CoreOrderType.MARKET,
                    CoreTimeInForce.IOC, buy ? 110_000 : 90_000, 6, 100);
            // 当前直接命令取得的撮合回执。
            var result = placeDirect(adapter, 4, new java.util.UUID(22, 4), 1000, 22, order);
            // 从回执事件中筛选出的 TRADE 列表。
            var trades = result.matcherEvents().stream()
                    .filter(/* 当前用于筛选 TRADE 类型的原生事件。 */ e -> e.eventType() == exchange.core2.core.common.MatcherEventType.TRADE).toList();
            assertThat(trades).extracting(MatcherResult.MatcherEvent::price)
                    .containsExactly(100_000L, buy ? 100_010L : 99_990L);
            assertThat(trades.stream().mapToLong(MatcherResult.MatcherEvent::size).sum()).isEqualTo(4);
        }
    }

    /**
     * 在九十九和一百两个卖价档前提交带窄滑点的市价买单，要求只成交九十九，舍入不能额外放宽一跳。
     */
    @Test
    void marketSlippageRoundingDoesNotAllowAnExtraTick() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 11, ask(101, 99));
            placeDirect(adapter, 11, ask(102, 100));
            // 当前直接命令取得的撮合回执。
            var result = placeDirect(adapter, 22, new CoreMatchingOrder(201, "1", CoreOrderSide.BUY,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 110, 100, 100));
            assertThat(result.matcherEvents().stream()
                    .filter(/* 当前用于筛选 TRADE 类型的原生事件。 */ e -> e.eventType() == exchange.core2.core.common.MatcherEventType.TRADE))
                    .extracting(MatcherResult.MatcherEvent::price).containsExactly(99L);
        }
    }

    /**
     * 先成交十份买单中的四份，再撤剩余六份；第二次撤同一单拒绝且标为未改状态，但有新证据身份和更大序号，第一次事件仍保留六份。
     */
    @Test
    void nativeCancellationPreservesEvidenceForPartialFillsAndRepeatedCancellation() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 本次待提交或用于前置 Core 元数据的订单。
            var order = new CoreMatchingOrder(901, "31", CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 10);
            placeDirect(adapter, 7, order);
            placeDirect(adapter, 8, new CoreMatchingOrder(902, "31", CoreOrderSide.SELL,
                    CoreOrderType.LIMIT, CoreTimeInForce.IOC, 90, 4));
            // 首次成功撤销剩余六份数量的结果。
            var accepted = cancelDirect(adapter, 3, new java.util.UUID(7, 3), 3000, 7, 901, "31");
            assertThat(accepted.accepted()).isTrue();
            assertThat(accepted.matcherEvents()).extracting(MatcherResult.MatcherEvent::size).containsExactly(6L);
            // 第二次撤相同订单时的未改状态拒绝结果。
            var rejected = cancelDirect(adapter, 4, new java.util.UUID(7, 4), 4000, 7, 901, "31");
            assertThat(rejected.accepted()).isFalse();
            assertThat(rejected.outcome()).isEqualTo(MatchingResult.Outcome.REJECTED_UNCHANGED);
            assertThat(rejected.nativeCoreSequence()).isEqualTo(4);
            assertThat(rejected.nativeMatches(new java.util.UUID(7, 4))).isTrue();
            assertThat(rejected.nativeOrderId()).isEqualTo(901);
            assertThat(rejected.nativeMatcherSequence()).isGreaterThan(accepted.nativeMatcherSequence());
            assertThat(accepted.matcherEvents()).extracting(MatcherResult.MatcherEvent::size).containsExactly(6L);
        }
    }

    /**
     * Core 序号零的请求在改簿前拒绝，合法请求成功后将适配器置为 poisoned，后续请求必须按已中毒状态拒绝。
     */
    @Test
    void nativePlacementHonorsPoisonAndValidatesEvidenceBeforeMatching() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 本次待提交或用于前置 Core 元数据的订单。
            var order = bid(901, 90);
            // 原命令 UUID，或构造控制分片前置订单时的普通订单编号。
            var id = new java.util.UUID(0, 1);
            // 已按当前命令准备好的原结算事件目标。
            var target = preparePlace(adapter, 1, id, 1000, 7, order);
            assertThatThrownBy(() -> adapter.placeDirect(adapter.matcherShardId("1"), 0, id,
                    1000, 7, order, target)).isInstanceOf(IllegalArgumentException.class);
            assertThat(adapter.orderBookLevelsAsync("1", 30).join()).isEmpty();
            adapter.placeDirect(adapter.matcherShardId("1"), 1, id, 1000, 7, order, target);
            assertThat(target.accepted()).isTrue();
            assertThat(target.nativeMatcherSequence()).isEqualTo(1);
            adapter.poisonFromOwner("test divergence");
            assertThatThrownBy(() -> placeDirect(adapter, 7, bid(902, 90)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("matcher is poisoned");
        }
    }

    /**
     * 连续四个直接命令逐项核对 Core 序号、UUID、订单号、分片证据及原生时间，最后再次检查首个回执身份和空事件，避免后续槽位污染早先结果。
     */
    @Test
    void directPlacementRetainsEvidenceWithoutChangingEarlierSlots() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 首个直接命令回执，或当前时间复用测试的第一条回执。
            MatchingResult first = null;
            for (/* 当前连续下单或发起异步查询的循环下标。 */ int i = 1; i <= 4; i++) {
                // 本次待提交或用于前置 Core 元数据的订单。
                var order = new CoreMatchingOrder(i, "21", i == 2 ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1);
                // 原命令 UUID，或构造控制分片前置订单时的普通订单编号。
                var id = new java.util.UUID(7, i);
                // 当前直接下单的实际回执，逐项核对命令证据。
                var actual = placeDirect(adapter, i, id, 1000 + i, i == 2 ? 8 : 7, order);
                assertThat(actual.accepted()).isTrue();
                assertThat(actual.nativeCoreSequence()).isEqualTo(i);
                assertThat(actual.nativeMatches(id)).isTrue();
                assertThat(actual.nativeOrderId()).isEqualTo(i);
                assertThat(actual.nativeMatcherSequence()).isEqualTo(i);
                assertThat(actual.nativeMatcherShardId()).isEqualTo(adapter.matcherShardId("21"));
                assertThat(actual.nativeMatcherResult().timestamp()).isEqualTo(1000 + i);
                if (first == null) first = actual;
            }
            assertThat(first.nativeCoreSequence()).isEqualTo(1);
            assertThat(first.matcherEvents()).isEmpty();
        }
    }

    /**
     * 持有适配器监视器时，异步已注册币对查询仍须在两秒内返回；随后三十二个异步新币对查询得到相同分片。没有屏障保证全部调用同时进入注册。
     */
    @Test
    void registeredSymbolLookupDoesNotAcquireRegistrationMonitor() throws Exception {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 先注册币对二十二后得到的预期分片编号。
            int expected = adapter.matcherShardId("22");
            synchronized (adapter) {
                // 持有注册监视器时发起的异步查询 future，或循环中待检查的 future。
                var result = CompletableFuture.supplyAsync(() -> adapter.matcherShardId("22"));
                assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(expected);
            }
            // 当前三十二个异步分片查询 future 的列表。
            var calls = new ArrayList<CompletableFuture<Integer>>();
            for (/* 当前连续下单或发起异步查询的循环下标。 */ int i = 0; i < 32; i++) calls.add(CompletableFuture.supplyAsync(() -> adapter.matcherShardId("23")));
            for (var /* 持有注册监视器时发起的异步查询 future，或循环中待检查的 future。 */ result : calls) assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(adapter.matcherShardId("23"));
        }
    }

    /**
     * 临时配置两个撮合分片，选择不同分片币对并制造不同原生进度；控制撤单业务证据继续递增时，另一分片原生序号允许更小，不能跨分片误判倒退。finally 恢复原系统属性。
     */
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void controlEvidenceChecksNativeSequenceWithinItsActualOrderBookPartition() {
        // 测试前系统属性原值，结束时按是否为空恢复或移除。
        String previous = System.getProperty("surprising.aeron.matching-engines");
        System.setProperty("surprising.aeron.matching-engines", "2");
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 正在寻找与第一币对不同分片的第二币对编号文本。
            // 第一个测试币对编号文本，作为高原生序号的前置分片。
            String first = "25", second = "26";
            for (/* 寻找另一分片币对的尝试次数，达到一百二十八次仍失败则终止测试。 */ int attempt = 0; adapter.matcherShardId(first) == adapter.matcherShardId(second); attempt++) {
                if (attempt == 128) throw new AssertionError("could not select a second partition");
                second = Integer.toString(100 + attempt);
            }
            for (/* 原命令 UUID，或构造控制分片前置订单时的普通订单编号。 */ long id = 1; id <= 16; id++) placeDirect(adapter, 7, bid(id, first, 90));
            placeDirect(adapter, 8, bid(17, second, 90));
            // 第一实际订单簿分片上的控制撤单原生结果。
            var firstNative = adapter.cancelControlOrder(1000, 7, 16, first);
            // 第一控制撤单完成后的业务证据回执。
            var a = adapter.completeControlCancellations(18, new java.util.UUID(7, 18), 16, 1000,
                    List.of(new DeterministicExchangeCoreAdapter.CancellationOrder(16, 7, first)), List.of(firstNative));
            // 另一实际订单簿分片上的控制撤单原生结果。
            var secondNative = adapter.cancelControlOrder(1000, 8, 17, second);
            // 第二控制撤单完成后的业务证据回执。
            var b = adapter.completeControlCancellations(19, new java.util.UUID(8, 19), 17, 1000,
                    List.of(new DeterministicExchangeCoreAdapter.CancellationOrder(17, 8, second)), List.of(secondNative));
            assertThat(a.accepted()).isTrue();
            assertThat(b.accepted()).isTrue();
            assertThat(secondNative.nativeSequence()).isLessThan(firstNative.nativeSequence());
            assertThat(b.nativeMatcherSequence()).isGreaterThan(a.nativeMatcherSequence());
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.matching-engines");
            else System.setProperty("surprising.aeron.matching-engines", previous);
        }
    }

    /**
     * 连续传入两个非零时间和一个零时间，核对每条原生回执保存各自输入，不继承上一条时间。
     */
    @Test
    void reusedCommandScopeDoesNotLeakTimestampIntoTheNextCommand() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 首个直接命令回执，或当前时间复用测试的第一条回执。
            var first = placeDirect(adapter, 1, new java.util.UUID(0, 1), 1234, 7, bid(901, 90));
            // 时间复用场景的第二条直接命令回执。
            var second = placeDirect(adapter, 2, new java.util.UUID(0, 2), 5678, 7, bid(902, 90));
            // 显式输入零时间的第三条直接命令回执。
            var third = placeDirect(adapter, 3, new java.util.UUID(0, 3), 0, 7, bid(903, 90));
            assertThat(first.nativeMatcherResult().timestamp()).isEqualTo(1234);
            assertThat(second.nativeMatcherResult().timestamp()).isEqualTo(5678);
            assertThat(third.nativeMatcherResult().timestamp()).isZero();
        }
    }

    /**
     * 按一、二、三三个编号撤单，二不存在时要求只成功一，第三条记未提交且仍可另行撤销，整体标记已有状态变化。
     */
    @Test
    void cancelBatchStopsAtFirstFailureAndReturnsSuccessfulPrefix() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 7, bid(1, 90));
            placeDirect(adapter, 7, bid(3, 80));
            // 当前直接命令取得的撮合回执。
            var result = adapter.cancelControlOrders(4, new java.util.UUID(7, 4), 0, 1000, List.of(
                    new DeterministicExchangeCoreAdapter.CancellationOrder(1, 7, "1"),
                    new DeterministicExchangeCoreAdapter.CancellationOrder(2, 7, "1"),
                    new DeterministicExchangeCoreAdapter.CancellationOrder(3, 7, "1")));
            assertThat(result.accepted()).isFalse();
            assertThat(result.successfulPrefixCount()).isEqualTo(1);
            assertThat(result.matcherStateChanged()).isTrue();
            assertThat(result.cancellations()).extracting(CoreCancellationResult::resultCode)
                    .containsExactly("SUCCESS", "MATCHING_UNKNOWN_ORDER_ID", "NOT_SUBMITTED");
            assertThat(cancelDirect(adapter, 5, new java.util.UUID(7, 5), 1000, 7, 3, "1").accepted()).isTrue();
        }
    }

    /**
     * 先撤自己的旧单，再放会吃单的 GTX 买单，被拒后仍保留成功撤单前缀，结果分类为 KNOWN_PREFIX_APPLIED。
     */
    @Test
    void rejectedPostOnlyPlacementRetainsSuccessfulDirectCancellationPrefix() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 7, bid(1, 100));
            placeDirect(adapter, 8, ask(2, 110));
            // 当前需要准备并直接执行的撮合订单。
            var command = postOnlyBid(3, 120);
            // 原命令 UUID，或构造控制分片前置订单时的普通订单编号。
            var id = new java.util.UUID(7, 3);
            // 已按当前命令准备好的原结算事件目标。
            var target = preparePlace(adapter, 3, id, 1000, 7, command, List.of(1L));
            assertThat(adapter.cancelDirectPrefix(1000, 7, 1, "1").resultCode())
                    .isEqualTo(CommandResultCode.SUCCESS);
            target.recordDirectPreCancellation(1);
            adapter.placeDirect(adapter.matcherShardId("1"), 3, id, 1000, 7, command, target);
            assertThat(target.accepted()).isFalse();
            assertThat(target.matcherStateChanged()).isTrue();
            assertThat(target.plan().preCancellationCount()).isEqualTo(1);
            assertThat(target.outcome()).isEqualTo(MatchingResult.Outcome.KNOWN_PREFIX_APPLIED);
        }
    }

    /**
     * 一次下单与两 Maker 成交，核对 Maker 顺序、原生身份及事件/行情引用直接复用；此用例没有采样或计数每命令报告与对象分配。
     */
    @Test
    void consumesImmutableMatcherResultWithoutPerCommandStateReports() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            placeDirect(adapter, 11, ask(101, 100));
            placeDirect(adapter, 12, ask(102, 100));
            // 当前需要准备并直接执行的撮合订单。
            var command = new CoreMatchingOrder(201, "1", CoreOrderSide.BUY,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 100, 4);
            // 当前直接命令取得的撮合回执。
            var result = placeDirect(adapter, 22, command);
            assertThat(result.matcherEvents()).extracting(MatcherResult.MatcherEvent::matchedOrderId)
                    .containsExactly(101L, 102L);
            assertThat(result.nativeOrderId()).isEqualTo(201);
            assertThat(result.nativeMatcherResult().sequence()).isPositive();
            assertThat(result.matcherEvents()).isSameAs(result.nativeMatcherResult().events());
            assertThat(result.marketData()).isSameAs(result.nativeMatcherResult().marketData());
        }
    }

    /**
     * 用模拟原生成功回执绑定两次证据，要求同一分片业务序号从一到二；没有运行原生撮合。
     */
    @Test
    void matcherEvidenceAdvancesSequences() {
        // 仅用于分片序号验证的新证据账本。
        var ledger = new MatcherEvidenceLedger(com.surprising.aeron.service.lane.LaneTopology.productionDefault());
        // 首个直接命令回执，或当前时间复用测试的第一条回执。
        var first = ledger.bind(1, new java.util.UUID(0, 1), 101, 1000,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 1));
        // 时间复用场景的第二条直接命令回执。
        var second = ledger.bind(2, new java.util.UUID(0, 2), 102, 1001,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 2));
        assertThat(first.nativeMatcherSequence()).isEqualTo(1);
        assertThat(second.nativeMatcherSequence()).isEqualTo(2);
    }

    /**
     * 先绑定原生序号二，再尝试同分片序号一，要求按原生序号非严格递增拒绝。
     */
    @Test
    void fixedMatcherShardRejectsOutOfNativeSequenceCompletion() {
        // 仅用于分片序号验证的新证据账本。
        var ledger = new MatcherEvidenceLedger(com.surprising.aeron.service.lane.LaneTopology.productionDefault());
        ledger.bind(2, new java.util.UUID(0, 2), 202, 1001,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 2));
        assertThatThrownBy(() -> ledger.bind(1, new java.util.UUID(0, 1), 201, 1000,
                ledger.nextSequence(0), 0, 0, nativeResult(2, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("native sequence is not strictly increasing");
    }

    /**
     * 准备直接命令目标后把适配器置为 poisoned，要求执行拒绝、目标尚无结果且反射取得的证据进度不变。
     */
    @Test
    void poisonRejectsPreparedDirectCommandWithoutAdvancingEvidence() throws Exception {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ var adapter = new DeterministicExchangeCoreAdapter()) {
            // 当前需要准备并直接执行的撮合订单。
            var command = bid(1, 90);
            // 原命令 UUID，或构造控制分片前置订单时的普通订单编号。
            var id = new java.util.UUID(0, 1);
            // 中毒之前已经准备的直接命令结算目标。
            var prepared = preparePlace(adapter, 1, id, 1000, 7, command);
            adapter.poisonFromOwner("fatal divergence");
            // 中毒后、尝试执行之前取得的证据进度参照。
            var progress = matcherProgress(adapter);
            assertThatThrownBy(() -> adapter.placeDirect(adapter.matcherShardId("1"), 1, id, 1000,
                    7, command, prepared)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matcher is poisoned");
            assertThat(prepared.resultPrepared()).isFalse();
            assertThat(matcherProgress(adapter)).isEqualTo(progress);
        }
    }

    /**
     * 单币对查询只含该币对，整体启动快照包含两个币对及其档位，空币对查询以原参数异常失败。
     */
    @Test
    void singleSymbolBookQueryAndBootstrapUseSeparateScopes() {
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(placeDirect(adapter, 7, bid(1, 100)).accepted()).isTrue();
            assertThat(placeDirect(adapter, 8, bid(2, "2", 200)).accepted()).isTrue();

            assertThat(adapter.orderBookLevelsAsync("1", 30).join())
                    .extracting(/* 当前用于提取币对名称的盘口档位。 */ value -> value.instrumentId()).containsOnly("1");
            // 包含所有币对范围和档位的盘口启动快照。
            BookBootstrapSnapshot bootstrap = adapter.orderBookBootstrapAsync(30).join();
            assertThat(bootstrap.symbols()).containsExactly("1", "2");
            assertThat(bootstrap.levels()).extracting(/* 当前用于提取币对名称的盘口档位。 */ value -> value.instrumentId())
                    .containsExactly("1", "2");
            assertThatThrownBy(() -> adapter.orderBookLevelsAsync("", 30).join())
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 参数化多种永久币对编号，保存真实原生模块并编码解码，逐项核对注册表、分片进度、模块编号和载荷；恢复后比较订单簿哈希并继续挂一笔新单，证据序号必须越过原快照分片水位。
     * @param instrumentId 测试使用的永久币对十进制编号文本
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "1", "1023", "1024", "10000", "10127", "2147483647"})
    void nativeSnapshotRoundTripRestoresTheOnlyExecutableBook(String instrumentId) {
        // 只为配对原生挂单快照构造的 Core 元数据，未真实冻结账户资金。
        TradingCoreState state = stateWithOpenBid(100, instrumentId);
        // 从当前原生引擎取得的模块及配对清单。
        MatcherSnapshot snapshot;
        // 快照前原生订单簿内容哈希。
        int bookHash;
        // 生成快照前成功挂单的直接命令回执。
        MatchingResult beforeSnapshot;
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            beforeSnapshot = placeDirect(adapter, 1, new java.util.UUID(0, 100), 1000, 7,
                    bid(1, instrumentId, 100));
            assertThat(beforeSnapshot.accepted()).isTrue();
            snapshot = adapter.snapshotAsync(
                    91, 1, state.businessStateHash(), state, activeOrders(state)).join();
            bookHash = adapter.orderBooksStateHashAsync().join();
        }

        // Matcher 清单编码字节，损坏测试只修改内存副本。
        byte[] encoded = MatcherSnapshotCodec.encode(snapshot);
        // 从编码字节恢复出的 Matcher 清单。
        MatcherSnapshot decoded = MatcherSnapshotCodec.decode(encoded);
        assertThat(decoded.symbols()).containsEntry(instrumentId, Integer.parseInt(instrumentId));
        assertThat(decoded.matcherShardProgress()).isEqualTo(snapshot.matcherShardProgress());
        assertThat(decoded.symbols()).containsExactlyEntriesOf(snapshot.symbols());
        assertThat(decoded.users()).containsExactlyElementsOf(snapshot.users());
        assertThat(decoded.modules()).hasSize(
                decoded.matchingEngineCount() + decoded.riskEngineCount());
        assertThat(decoded.modules().stream()
                .filter(/* 当前用来筛选和检查类型、实例编号的原生模块。 */ module -> module.type().name().equals("MATCHING_ENGINE_ROUTER"))
                .map(/* 当前用来筛选和检查类型、实例编号的原生模块。 */ module -> module.instanceId()).sorted().toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(
                        0, decoded.matchingEngineCount()).boxed().toList());
        assertThat(decoded.modules()).zipSatisfy(snapshot.modules(), (/* 解码清单中当前待比较的模块记录。 */ actual, /* 原始清单中同一位置的模块记录。 */ expected) -> {
            assertThat(actual.type()).isEqualTo(expected.type());
            assertThat(actual.sequence()).isEqualTo(expected.sequence());
            assertThat(actual.checksum()).isEqualTo(expected.checksum());
            assertThat(actual.data()).containsExactly(expected.data());
        });

        try (/* 从 Core 元数据和原生模块恢复的适配器，结束时关闭。 */ DeterministicExchangeCoreAdapter restored =
                     new DeterministicExchangeCoreAdapter(state, activeOrders(state), 1, decoded)) {
            assertThat(restored.orderBooksStateHashAsync().join()).isEqualTo(bookHash);
            // 恢复后继续提交新买单得到的回执。
            var afterRestore = placeDirect(restored, 2, new java.util.UUID(0, 101), 1001, 8,
                    bid(101, instrumentId, 90));
            assertThat(afterRestore.accepted()).isTrue();
            assertThat(restored.orderBookLevelsAsync(instrumentId, 30).join()).hasSize(2);
            assertThat(afterRestore.nativeMatcherSequence()).isGreaterThan(
                    snapshot.progress(afterRestore.nativeMatcherShardId()).matcherSequence());
        }
    }

    /**
     * 用可控 future 暂缓真正原生持久化，要求外层取消返回 false，同快照重试得到不同 future 但共用一次底层提交；释放后两者取得相同清单。信号及完成使用 join，当前用例没有另加本地等待截止点。
     */
    @Test
    void snapshotPipelineCompletesPersistAndExportWithoutCallerCancellation() {
        // 只为配对原生挂单快照构造的 Core 元数据，未真实冻结账户资金。
        TradingCoreState state = stateWithOpenBid(100);
        // 测试钩子已经接到持久化提交的信号 future。
        CompletableFuture<Void> persistEntered = new CompletableFuture<>();
        // 由测试显式完成、随后才允许实际原生持久化开始的 future。
        CompletableFuture<Void> releasePersist = new CompletableFuture<>();
        // 测试钩子收到实际持久化提交次数的原子计数器。
        AtomicInteger persistSubmissions = new AtomicInteger();
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(/* 钩子收到的真正原生持久化任务提供器，释放门闩后才调用 get。 */ nativePersist -> {
            persistSubmissions.incrementAndGet();
            persistEntered.complete(null);
            return releasePersist.thenCompose(/* 门闩 future 的空完成值，回调不读取它。 */ ignored -> nativePersist.get());
        })) {
            assertThat(placeDirect(adapter, 7, bid(100)).accepted()).isTrue();
            // 第一次快照请求返回的不可取消外层 future。
            CompletableFuture<MatcherSnapshot> first =
                    adapter.snapshotAsync(94, 1, state.businessStateHash(), state, activeOrders(state));

            persistEntered.join();
            assertThat(first.isDone()).isFalse();
            assertThat(first.cancel(false))
                    .as("caller cancellation must not cancel the nested persist/export operation")
                    .isFalse();
            assertThat(first.isCancelled()).isFalse();

            // 同快照重试得到的另一外层 future，共享相同底层持久化。
            CompletableFuture<MatcherSnapshot> retry =
                    adapter.snapshotAsync(94, 1, state.businessStateHash(), state, activeOrders(state));
            assertThat(retry).isNotSameAs(first);
            assertThat(retry.isDone()).isFalse();
            assertThat(persistSubmissions).hasValue(1);

            releasePersist.complete(null);
            // 门闩释放后首次快照请求最终取得的完整清单。
            MatcherSnapshot completed = first.join();

            assertThat(retry.join()).isEqualTo(completed);
            assertThat(completed.snapshotId()).isEqualTo(94);
            assertThat(completed.modules()).isNotEmpty();
            assertThat(persistSubmissions).hasValue(1);
        }
    }

    /**
     * 故意把 Core 买价改为另一值，并让清单业务哈希也配对该改值，原生模块却仍是旧订单簿，要求恢复适配器按 Core OPEN 与原生挂单不一致拒绝。
     */
    @Test
    void restoreFailsClosedWhenCoreMetadataDoesNotMatchNativeOpenOrders() {
        // 与原生快照买价一百配对的原 Core 订单元数据。
        TradingCoreState original = stateWithOpenBid(100);
        // 从当前原生引擎取得的模块及配对清单。
        MatcherSnapshot snapshot;
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(placeDirect(adapter, 7, bid(100)).accepted()).isTrue();
            snapshot = adapter.snapshotAsync(
                    92, 1, original.businessStateHash(), original, activeOrders(original)).join();
        }
        // 故意把同笔 Core 买价改为一百零一的分歧元数据。
        TradingCoreState divergent = stateWithOpenBid(101);
        // 业务哈希也改为分歧 Core 的清单，但其原生模块仍保存旧订单簿。
        MatcherSnapshot divergentManifest = new MatcherSnapshot(
                snapshot.productLine(), snapshot.topology(), snapshot.snapshotId(),
                snapshot.coreSequence(), snapshot.matcherSequence(), snapshot.matcherShardProgress(),
                divergent.businessStateHash(),
                snapshot.engineStateHash(),
                snapshot.symbols(), snapshot.users(), snapshot.modules());

        assertThatThrownBy(() -> new DeterministicExchangeCoreAdapter(
                divergent, activeOrders(divergent), 1, divergentManifest))
                .isInstanceOf(FatalMatchingDivergenceException.class)
                .hasMessageContaining("Core OPEN orders do not exactly match exchange-core open orders");
    }

    /**
     * 翻转编码快照中间一位后要求正文校验错误；没有重算正文 CRC 后再独立构造模块 CRC 错误。
     */
    @Test
    void matcherSnapshotCodecRejectsCorruption() {
        // 只为配对原生挂单快照构造的 Core 元数据，未真实冻结账户资金。
        TradingCoreState state = stateWithOpenBid(100);
        // Matcher 清单编码字节，损坏测试只修改内存副本。
        byte[] encoded;
        try (/* 本场景创建并在结束时关闭的原生撮合适配器。 */ DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(placeDirect(adapter, 7, bid(100)).accepted()).isTrue();
            encoded = MatcherSnapshotCodec.encode(adapter.snapshotAsync(
                    93, 1, state.businessStateHash(), state, activeOrders(state)).join());
        }
        encoded[encoded.length / 2] ^= 1;

        assertThatThrownBy(() -> MatcherSnapshotCodec.decode(encoded))
                .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                .hasMessageContaining("checksum");
    }

    /**
     * 构造固定用户七、币对一的一份现货 OPEN 买单元数据，不做真实冻结。
     * @param orderId 要构造的普通订单编号
     */
    private static CoreOrderState order(long orderId) {
        return new CoreOrderState(orderId, ProductLine.SPOT, 7, "1",
                CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
    }

    /**
     * 构造默认订单一、币对一、两份数量的限价 GTC 买单。
     * @param priceTicks 测试订单价格
     */
    private static CoreMatchingOrder bid(long priceTicks) {
        return bid(1, priceTicks);
    }

    /**
     * 构造指定订单编号、币对一、两份数量的限价 GTC 买单。
     * @param orderId 要构造的普通订单编号
     * @param priceTicks 测试订单价格
     */
    private static CoreMatchingOrder bid(long orderId, long priceTicks) {
        return bid(orderId, "1", priceTicks);
    }

    /**
     * 构造指定币对和订单编号、两份数量的限价 GTC 买单。
     * @param orderId 要构造的普通订单编号
     * @param instrumentId 测试使用的永久币对十进制编号文本
     * @param priceTicks 测试订单价格
     */
    private static CoreMatchingOrder bid(long orderId, String instrumentId, long priceTicks) {
        return new CoreMatchingOrder(orderId, instrumentId, CoreOrderSide.BUY, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, priceTicks, 2);
    }

    /**
     * 构造币对一、两份数量的限价 GTC 卖单。
     * @param orderId 要构造的普通订单编号
     * @param priceTicks 测试订单价格
     */
    private static CoreMatchingOrder ask(long orderId, long priceTicks) {
        return new CoreMatchingOrder(orderId, "1", CoreOrderSide.SELL, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, priceTicks, 2);
    }

    /**
     * 构造币对一、两份数量的 GTX 买单，供只挂单拒绝测试。
     * @param orderId 要构造的普通订单编号
     * @param priceTicks 测试订单价格
     */
    private static CoreMatchingOrder postOnlyBid(long orderId, long priceTicks) {
        return new CoreMatchingOrder(orderId, "1", CoreOrderSide.BUY, CoreOrderType.LIMIT,
                CoreTimeInForce.GTX, priceTicks, 2);
    }

    /**
     * 构造币对一的一笔 OPEN 买单对应 Core 元数据。
     * @param priceTicks 测试订单价格
     */
    private static TradingCoreState stateWithOpenBid(long priceTicks) {
        return stateWithOpenBid(priceTicks, "1");
    }

    /**
     * 构造现货注册币对、空用户七和指定价格的两份 OPEN 买单元数据，仅用于原生快照配对；没有真实账户余额或订单预留。
     * @param priceTicks 测试订单价格
     * @param instrumentId 测试使用的永久币对十进制编号文本
     */
    private static TradingCoreState stateWithOpenBid(long priceTicks, String instrumentId) {
        // 本次待提交或用于前置 Core 元数据的订单。
        CoreOrderState order = new CoreOrderState(1, ProductLine.SPOT, 7, instrumentId,
                CoreOrderSide.BUY, priceTicks, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        return new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(1L, order),
                Map.of(instrumentId, CoreInstrument.from(ProductLine.SPOT,
                        new RegisterInstrumentCommand(instrumentId, ContractType.SPOT.ordinal(),
                                "BTC", "USDT", "USDT", 1, 1, 1,
                                100_000, 50_000, 0, 0, 0, -1, 0))),
                CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    /**
     * 从给定 Core 元数据只筛选状态为 OPEN 的订单并收集到列表，不把其他终态作为原生挂单恢复。
     * @param state 筛选活动订单的 Core 业务状态
     */
    private static Iterable<CoreOrderState> activeOrders(TradingCoreState state) {
        return state.orders().values().stream().filter(/* 本次待提交或用于前置 Core 元数据的订单。 */ order -> order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN).toList();
    }

    /**
     * 构造指定接受标记与结果码的普通撮合回执。
     * @param accepted 普通回执是否接受
     * @param resultCode 普通回执结果码
     */
    private static CoreMatchingResult result(boolean accepted, String resultCode) {
        return new CoreMatchingResult(accepted, resultCode);
    }

    /**
     * 构造指定序号和符号编号的模拟原生成功下单回执，事件及行情为空，再包装成未绑定普通结果；不调用引擎撮合。
     * @param symbolId 模拟原生回执的符号编号
     * @param sequence 模拟原生回执的原生序号，同时用作该样本订单编号
     */
    private static CoreMatchingResult nativeResult(int symbolId, long sequence) {
        // 当前直接命令取得的撮合回执。
        MatcherResult result = new MatcherResult(sequence, OrderCommandType.PLACE_ORDER, sequence, symbolId,
                100, 1, 100, OrderAction.BID, OrderType.GTC, 7, 1_000, 0,
                CommandResultCode.SUCCESS, List.of(), new MatcherResult.MarketData(List.of(), List.of(), 0, 0));
        return CoreMatchingResult.fromNative(result);
    }

    /**
     * 反射取得适配器内部证据账本并导出当前分片进度；本辅助方法不额外建立跨分片快照栅栏。
     * @param adapter 需要读取内部证据进度的当前适配器
     */
    private static List<MatcherShardProgress> matcherProgress(
            DeterministicExchangeCoreAdapter adapter) throws Exception {
        // 反射取得的 matcherEvidence 字段句柄，只用于读取内部进度。
        var field = DeterministicExchangeCoreAdapter.class.getDeclaredField("matcherEvidence");
        field.setAccessible(true);
        return ((MatcherEvidenceLedger) field.get(adapter)).snapshot();
    }
}
