package com.surprising.aeron.service.state.settlement;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.AccountLaneTestAccess;
import com.surprising.aeron.service.state.account.DerivativeAccountCommandProcessor;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.change.LaneCommitDelta;
import com.surprising.aeron.service.state.change.LaneCommitTestAccess;
import com.surprising.aeron.service.state.change.RuntimeUserChangeBuffer;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/**
 * 核对普通用户值、Lane 权威用户、命令缓冲槽和实时元数据各自的生命周期，覆盖独立发布、结算修订聚合、溢出、缓冲复用及实际 Lane 回滚。
 */
class UserRevisionReuseTest {
    /**
     * 按六产品线更新 Lane 权威用户，核对旧普通值、发布值及命令槽均保持原字段，普通值更新返回新对象且命令槽拒绝直接 withState。
     * @param product 当前枚举到的独立产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void ownedValueAndPublishedReceiptStayIndependentFromLaneAdvances(ProductLine product) {
        // 原普通用户业务值，转换为 Lane 权威值后本对象应保持原字段。
        var original = new UserRuntime(product, 7, 3, CorePositionMode.ONE_WAY);
        // 当前场景的 Lane 权威用户，同一 Lane 的修订更新可原位进行。
        var authority = original.laneValue();
        // 权威用户变化前生成的独立普通发布值。
        var published = authority.publicationValue();
        // 权威用户变化前复制的命令值槽，不允许直接通过 withState 改写。
        var receipt = authority.copyIntoCommandSlot(null);
        assertThat(authority.withState(4, CorePositionMode.HEDGE)).isSameAs(authority);
        assertThat(authority.laneValue()).isSameAs(authority);
        assertThat(original).isEqualTo(new UserRuntime(product, 7, 3, CorePositionMode.ONE_WAY));
        assertThat(published).isEqualTo(original);
        assertThat(receipt).isEqualTo(original);
        assertThat(authority).isEqualTo(new UserRuntime(product, 7, 4, CorePositionMode.HEDGE));
        // 由原普通值 withState 返回的新普通值；变量名虽含 Owned，并不是 Lane 权威对象。
        var advancedOwned = original.withState(5, CorePositionMode.HEDGE);
        assertThat(advancedOwned).isNotSameAs(original);
        assertThat(original.revision()).isEqualTo(3);
        assertThatThrownBy(() -> receipt.withState(6, CorePositionMode.HEDGE))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 让两个在途命令各有用户槽，反复清空及复用第一缓冲，不影响第二缓冲旧修订和已转为普通发布值的快照。
     */
    @Test void commandBuffersReuseCellsWithoutAliasingOtherInFlightCommands() {
        // 当前场景的 Lane 权威用户，同一 Lane 的修订更新可原位进行。
        var authority = new UserRuntime(7).laneValue();
        // 第一个在途命令独占的用户变化缓冲。
        var first = new RuntimeUserChangeBuffer();
        // 第二个在途命令独占的用户变化缓冲。
        var second = new RuntimeUserChangeBuffer();
        first.put(7, authority);
        // 第一命令的可复用用户值槽，只能在其租约结束后覆盖。
        var firstCell = first.get(7);
        authority.withState(1, CorePositionMode.HEDGE);
        second.put(7, authority);
        // 第二命令独立的用户值槽，第一命令复用不应改变它。
        var secondCell = second.get(7);
        assertThat(firstCell.revision()).isZero();
        assertThat(secondCell.revision()).isOne();
        // 从第一命令槽转换得到的独立普通发布值。
        var stable = firstCell.publicationValue();
        first.clear();
        first.put(7, authority);
        assertThat(first.get(7)).isSameAs(firstCell);
        assertThat(stable.revision()).isZero();
        assertThat(second.get(7)).isSameAs(secondCell);
        for (/* 第一缓冲反复重用时本轮写入的用户修订号。 */ int round = 2; round < 1000; round++) {
            authority.withState(round, CorePositionMode.ONE_WAY);
            first.put(7, authority);
            assertThat(first.get(7)).isSameAs(firstCell);
            assertThat(first.get(7).revision()).isEqualTo(round);
            assertThat(second.get(7).revision()).isOne();
            first.clear();
        }
        first.put(9, new UserRuntime(9));
        assertThat(first.get(9)).isSameAs(firstCell);
        assertThat(first.get(9).userId()).isEqualTo(9);
    }

    /**
     * 直接用结算变化对象把同用户两次修订增量合计应用到所属 Lane，保留权威对象身份、当前回执值和更早命令值，消费后增量归零。
     * @param product 当前枚举到的独立产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void coalescedSettlementKeepsAuthorityAndEarlierCommandReceipt(ProductLine product) {
        // 当前测试线程或控制回调受控持有的账户 Lane。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 当前场景的 Lane 权威用户，同一 Lane 的修订更新可原位进行。
        var authority = new UserRuntime(product, 7, 3, CorePositionMode.ONE_WAY).laneValue();
        AccountLaneTestAccess.users(lane).put(7, authority);
        // 结算之前已记录用户修订三的另一命令缓冲。
        var earlier = new RuntimeUserChangeBuffer();
        earlier.put(7, authority);
        // 为一个 Lane 保存修订增量及提交变化的结算对象。
        var changes = new MatcherSettlementChanges(1);
        changes.ensureActiveLanes(1);
        changes.addUserRevision(0, 7, 2);
        changes.addUserRevision(0, 7, 3);
        changes.applyUserRevisions(0, lane);
        assertThat(lane.user(7)).isSameAs(authority);
        assertThat(authority.revision()).isEqualTo(8);
        assertThat(LaneCommitTestAccess.user(changes.laneDeltas[0], 7).revision()).isEqualTo(8);
        assertThat(earlier.get(7).revision()).isEqualTo(3);
        assertThat(changes.userRevisionDeltas[0].isEmpty()).isTrue();
        authority.withState(9, CorePositionMode.HEDGE);
        assertThat(LaneCommitTestAccess.user(changes.laneDeltas[0], 7).revision()).isEqualTo(8);
    }

    /**
     * 捕获四十用户前值促使缓冲扩容，更新权威用户后再次捕获不得覆盖最早前值或原权威引用，原本不存在的用户也要保留存在标记。
     */
    @Test void grownBeforeBufferRetainsFirstValueAndAuthorityAcrossUpdatesAndRemoval() {
        // 测试批量捕获最早用户前值及原权威引用的专用缓冲。
        var before = new RuntimeUserChangeBuffer();
        // 四十个持有原位可变 Lane 用户的数组，前值缓冲必须另存独立命令值。
        var users = new UserRuntime[40];
        for (/* 当前捕获及核对用户数组中的下标。 */ int index = 0; index < users.length; index++) {
            users[index] = new UserRuntime(index + 1).laneValue();
            before.captureBefore(index + 1, users[index]);
            users[index].withState(9, CorePositionMode.HEDGE);
            before.captureBefore(index + 1, users[index]);
            assertThat(before.get(index + 1).revision()).isZero();
            assertThat(before.preparedAt(index)).isSameAs(users[index]);
        }
        before.captureBefore(99, null);
        assertThat(before.containsKey(99)).isTrue();
        assertThat(before.get(99)).isNull();
        assertThat(before.preparedAt(40)).isNull();
        before.clear();
        assertThat(before.isEmpty()).isTrue();
    }

    /**
     * 制造用户修订溢出，检查没有写用户或失败收据；随后另一命令可正常借用收据并完成，失败命令原增量仍保留。
     */
    @Test void failedRevisionTraversalReleasesItsReceiptBeforeTheNextCommand() {
        // 当前测试线程或控制回调受控持有的账户 Lane。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 修订已达到 long 上限的 Lane 用户权威值。
        var overflowing = new UserRuntime(ProductLine.LINEAR_PERPETUAL, 7,
                Long.MAX_VALUE, CorePositionMode.ONE_WAY).laneValue();
        AccountLaneTestAccess.users(lane).put(7, overflowing);
        // 将要因用户修订溢出而失败的结算变化对象。
        var failed = new MatcherSettlementChanges(1);
        failed.ensureActiveLanes(1); failed.addUserRevision(0, 7, 1);
        assertThatThrownBy(() -> failed.applyUserRevisions(0, lane)).isInstanceOf(ArithmeticException.class);
        assertThat(lane.user(7)).isSameAs(overflowing);
        assertThat(overflowing.revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(LaneCommitTestAccess.usersEmpty(failed.laneDeltas[0])).isTrue();
        assertThat(failed.userRevisionDeltas[0].get(7)).isEqualTo(1);

        AccountLaneTestAccess.users(lane).put(8, new UserRuntime(8).laneValue());
        // 用于确认失败没有泄漏借用收据的下一结算变化对象。
        var next = new MatcherSettlementChanges(1);
        next.ensureActiveLanes(1); next.addUserRevision(0, 8, 3);
        next.applyUserRevisions(0, lane);
        assertThat(lane.user(8).revision()).isEqualTo(3);
        assertThat(LaneCommitTestAccess.user(next.laneDeltas[0], 8).revision()).isEqualTo(3);
        assertThat(next.userRevisionDeltas[0].isEmpty()).isTrue();
        assertThat(LaneCommitTestAccess.usersEmpty(failed.laneDeltas[0])).isTrue();
    }

    /**
     * 分别制造调用前修订算术溢出、负修订及空模式输入，要求原 Lane 权威用户修订与模式不变。
     */
    @Test void rejectedUserArithmeticAndModeLeaveTheAuthorityUnchanged() {
        // 修订达到上限、模式为双向的 Lane 权威用户。
        var user = new UserRuntime(ProductLine.LINEAR_PERPETUAL, 7, Long.MAX_VALUE, CorePositionMode.HEDGE).laneValue();
        assertThatThrownBy(() -> user.withState(Math.incrementExact(user.revision()), CorePositionMode.ONE_WAY))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> user.withState(-1, CorePositionMode.ONE_WAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user.withState(2, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(user.revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(user.positionMode()).isEqualTo(CorePositionMode.HEDGE);
    }

    /**
     * 记录修订三的用户元数据后继续修改权威值并复用收据，最终从实时 outbox 解码到的帧仍必须保存修订三及原模式。
     */
    @Test void delayedMetadataOutputKeepsItsCommandRevisionAfterAuthorityAndCellReuse() {
        try (/* 本场景独立交易运行时，作用域结束关闭工作资源。 */ var runtime = new TradingRuntimeState()) {
            // 接收实时编码结果的有界帧输出缓冲。
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(32, 65_536);
            // 负责捕获并编码当前命令实时变化的输出边界。
            var capture = new com.surprising.aeron.service.state.realtime.RealtimeStateCapture(
                    outbox, ProductLine.LINEAR_PERPETUAL, new RuntimeIdentityRegistry());
            runtime.realtimeCapture(capture);
            capture.begin(1, 1, 0, 0);
            // 当前场景的 Lane 权威用户，同一 Lane 的修订更新可原位进行。
            var authority = new UserRuntime(7).laneValue();
            // 保存用户修订三后值并传给实时边界的 Lane 提交收据，随后测试会清空并复用它。
            var changes = new LaneCommitDelta();
            authority.withState(3, CorePositionMode.HEDGE);
            changes.putUser(7, authority);
            authority.withState(4, CorePositionMode.ONE_WAY);
            changes.recordControlChanges(runtime, 0);
            changes.clear();
            changes.putUser(7, authority);
            authority.withState(5, CorePositionMode.HEDGE);
            capture.commit(1);
            // 实际从 outbox 解码到的用户元数据帧计数。
            int metadata = 0;
            // 本次从 outbox 取出的帧字节，null 表示已排空。
            byte[] bytes;
            while ((bytes = outbox.poll()) != null) {
                // 当前字节解码后的实时帧，测试仅检查 METADATA 类型。
                var frame = com.surprising.aeron.protocol.RealtimeFrameCodec.decode(bytes);
                if (frame.kind() != com.surprising.aeron.protocol.RealtimeFrame.Kind.METADATA) continue;
                // 从延迟元数据帧解码出的用户值，应保留命令边界修订三及双向模式。
                var user = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeUserState(frame.payload());
                assertThat(user.userId()).isEqualTo(7);
                assertThat(user.revision()).isEqualTo(3);
                assertThat(user.positionMode()).isEqualTo(CorePositionMode.HEDGE);
                metadata++;
            }
            assertThat(metadata).isOne();
            assertThat(capture.failures()).isZero();
            assertThat(outbox.droppedBatches()).isZero();
        }
    }

    /**
     * 实际启动账户 Lane，连续更新并回滚已有用户，检查原权威身份及最早修订模式恢复；再核对模式重试不重复变更、命令中新建用户回滚后消失。
     */
    @Test void actualLaneRollbackRestoresRevisionModeAndAuthorityIdentity() {
        try (/* 本场景独立交易运行时，作用域结束关闭工作资源。 */ var runtime = new TradingRuntimeState()) {
            runtime.putUser(new UserRuntime(7));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            // 用户七所属账户 Lane 编号，只把实际回滚测试派发到此分区。
            int laneId = runtime.topology().accountLaneId(7);
            runtime.dispatchControlLanes(1L << laneId, /* 控制回调提供的 Lane 编号，本测试用当前命令作用域访问，不读取此参数。 */ ignored -> {
                // 当前测试线程或控制回调受控持有的账户 Lane。
                var lane = runtime.currentLaneCommandScope();
                runtime.setUserState(7, 3, CorePositionMode.ONE_WAY);
                runtime.accountRollback().clearNonBalanceImages();
                // 当前场景的 Lane 权威用户，同一 Lane 的修订更新可原位进行。
                var authority = lane.user(7);
                runtime.setUserState(7, 4, CorePositionMode.HEDGE);
                runtime.setUserState(7, 5, CorePositionMode.HEDGE);
                assertThat(lane.user(7)).isSameAs(authority);
                runtime.accountRollback().restoreLane(lane);
                assertThat(lane.user(7)).isSameAs(authority);
                assertThat(authority.revision()).isEqualTo(3);
                assertThat(authority.positionMode()).isEqualTo(CorePositionMode.ONE_WAY);
                runtime.accountRollback().clearNonBalanceImages();
                // 把用户从单向切为双向的业务命令，第二次相同模式应返回未变化。
                var switchMode = new com.surprising.aeron.protocol.UpdatePositionModeCommand(CorePositionMode.HEDGE);
                assertThat(DerivativeAccountCommandProcessor.updateAccountPositionMode(runtime, 7, switchMode)).isTrue();
                assertThat(DerivativeAccountCommandProcessor.updateAccountPositionMode(runtime, 7, switchMode)).isFalse();
                assertThat(authority.revision()).isEqualTo(4);
                runtime.accountRollback().restoreLane(lane);
                assertThat(authority.revision()).isEqualTo(3);
                assertThat(authority.positionMode()).isEqualTo(CorePositionMode.ONE_WAY);
                runtime.accountRollback().clearNonBalanceImages();
                // 逐步寻找与用户七同 Lane、且原本不存在的新用户编号。
                long absentUser = 8;
                while (runtime.topology().accountLaneId(absentUser) != laneId) absentUser++;
                runtime.setUserState(absentUser, 1, CorePositionMode.HEDGE);
                runtime.accountRollback().restoreLane(lane);
                assertThat(lane.user(absentUser)).isNull();
                return null;
            });
            // 等待真实 Lane 回滚及其断言完成的五秒单调时钟截止时间。
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() > deadline) throw new AssertionError("user rollback command timed out");
                Thread.onSpinWait();
            }
        }
    }
}
