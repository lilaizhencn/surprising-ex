package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matcher.CoreMatchingResult;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证固定命令槽池的 Matcher 路由、Lane 位确认、普通结果首项保留、拒绝原因及挂起提交缓冲所有权。资金数据是用于容器转交的人工 posting，不执行真实账户扣减或财库守恒校验。
 */
class CommandSlotRingTest {

    /**
     * 即使 Lane 位已收齐，Matcher 路由未释放仍不能回收；错误分片释放拒绝，正确释放后相同低位新序号的路由必须为负一。
     */
    @Test
    void matcherRouteMustBeConsumedBeforeTheSequenceSlotCanBeReused() {
        // 当前固定容量命令槽池。
        var ring = new CommandSlotRing(2);
        // 当前已占用的命令槽。
        var context = ring.claim(1);
        context.claimMatcherSubmission(0);
        context.result(new CoreMatchingResult(true, "ACCEPTED").withCoreSequenceInPlace(1), 1, 1);
        context.completeLanes(1);
        assertThatThrownBy(() -> ring.release(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> context.releaseMatcherSubmission(1)).isInstanceOf(IllegalStateException.class);
        context.releaseMatcherSubmission(0);
        ring.release(1);
        assertThat(ring.claim(3).submittedMatcherShard()).isEqualTo(-1);
    }

    /** 不同序号分别保存变化和发布标记，恢复一个槽不修改另一个槽。 */
    @Test
    void suspendedSequencesKeepTheirOwnChangesAndPublicationFlags() {
        var ring = new CommandSlotRing(4);
        var first = ring.claim(1);
        var second = ring.claim(2);
        var builder = new CommandResultBuilder(null);
        builder.changedUserIds().add(11L);
        builder.changedOrderIds().add(101L);
        first.suspendCommitContext(builder, true, false);
        builder.changedUserIds().add(22L);
        builder.changedOrderIds().add(202L);
        second.suspendCommitContext(builder, false, true);
        first.restoreCommitContext(builder);
        assertThat(builder.changedUserIds()).containsExactly(11L);
        assertThat(builder.changedOrderIds()).containsExactly(101L);
        assertThat(first.commitSnapshotDirty()).isTrue();
        assertThat(first.commitSnapshotProvisionalOnly()).isFalse();
        first.clearCommitContext();
        ring.discard(1);
        second.restoreCommitContext(builder);
        assertThat(builder.changedUserIds()).containsExactly(22L);
        assertThat(builder.changedOrderIds()).containsExactly(202L);
        assertThat(second.commitSnapshotDirty()).isFalse();
        assertThat(second.commitSnapshotProvisionalOnly()).isTrue();
        second.clearCommitContext();
        ring.discard(2);
        assertThat(builder.changedUserIds()).containsExactly(22L);
        assertThat(builder.changedOrderIds()).containsExactly(202L);
    }

    /**
     * 要求先绑定事实才能加控制 Lane，只能并入合法位；确认控制位后原 Matcher 位仍需完成，重复/意外位及完成后再扩参与集合都被拒绝。
     */
    @Test
    void synchronousControlLanesCannotEraseMatcherParticipantsOrWeakenAckValidation() {
        // 当前固定容量命令槽池。
        var ring=new CommandSlotRing(4);
        // 当前已占用的命令槽。
        var context=ring.claim(1);
        assertThatThrownBy(()->context.includeControlLanes(2,15)).isInstanceOf(IllegalStateException.class);
        context.result(new CoreMatchingResult(true,"ACCEPTED").withCoreSequenceInPlace(1),1,15);
        context.includeControlLanes(2,15);
        assertThat(context.expectedLaneMask()).isEqualTo(3);
        assertThatThrownBy(()->context.includeControlLanes(16,15)).isInstanceOf(IllegalStateException.class);
        context.completeLanes(2);
        assertThat(context.complete()).isFalse();
        assertThatThrownBy(()->context.completeLanes(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->context.completeLanes(4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->context.includeControlLanes(4,15)).isInstanceOf(IllegalStateException.class);
        context.completeLanes(1);
        assertThat(context.complete()).isTrue();
    }

    /**
     * 反射检查 CommandSlot 的直接声明字段里没有类型恰好为 CompletableFuture 的字段；不递归检查子对象或所有分配行为。
     */
    @Test
    void commandContextDoesNotRetainAPerCommandMatchingFuture() {
        assertThat(Arrays.stream(CommandSlot.class.getDeclaredFields())
                .anyMatch(/* 反射枚举到的 CommandSlot 直接声明字段。 */ field -> field.getType() == java.util.concurrent.CompletableFuture.class)).isFalse();
    }

    /**
     * 人工绑定两个非相邻 Lane 位，部分确认不完成、全部确认才可释放，检查掩码与原事实引用和池计数/峰值；释放后字段清空由实现执行，本项未再单独断言旧引用为空。
     */
    @Test
    void aggregatesExactlyOneAckPerExpectedLaneAndReleasesTheResultReference() {
        // 当前固定容量命令槽池。
        CommandSlotRing ring = new CommandSlotRing(4);
        // 当前已占用的命令槽。
        CommandSlot context = ring.claim(1);
        // 人工撮合事实，绑定同一 Core 序号后用于 Lane 确认。
        CoreMatchingResult result = new CoreMatchingResult(true, "ACCEPTED").withCoreSequenceInPlace(1);
        context.result(result, 0b101, 0b1111);

        context.completeLanes(0b001);
        assertThat(context.complete()).isFalse();
        context.completeLanes(0b100);

        assertThat(context.complete()).isTrue();
        assertThat(context.completedLaneMask()).isEqualTo(0b101);
        assertThat(context.matchingResult()).isSameAs(result);
        ring.release(1);
        assertThat(ring.inFlight()).isZero();
        assertThat(ring.highWaterMark()).isEqualTo(1);
    }

    /**
     * 同步辅助发布两次同 Core 序号结果，要求收集仍是第一项、第二次取空，随后丢弃预备槽；不同于 Matcher 路由的严格重复发布入口。
     */
    @Test
    void storesSynchronousMatchingResultAndKeepsTheFirstCompletion() {
        // 当前固定容量命令槽池。
        CommandSlotRing ring = new CommandSlotRing(4);
        // 当前已占用的命令槽。
        CommandSlot context = ring.claim(3);
        // 首个槽或首个同步完成结果，具体见当前声明。
        CoreMatchingResult first = new CoreMatchingResult(true, "SUCCESS").withCoreSequenceInPlace(3);
        // 同 Core 序号的迟到普通辅助结果，不能覆盖已经保存的第一项。
        CoreMatchingResult duplicate = new CoreMatchingResult(false, "LATE").withCoreSequenceInPlace(3);

        context.publishMatchingCompletion(first);
        context.publishMatchingCompletion(duplicate);

        assertThat(context.takeMatchingCompletion()).isSameAs(first);
        assertThat(context.takeMatchingCompletion()).isNull();
        context.resetMatchingContinuation();
        ring.discard(3);
    }

    /**
     * 确认一个预期位后，重复该位、意外位和超拓扑位都要求拒绝。
     */
    @Test
    void failsClosedForDuplicateUnexpectedOrOutOfRangeAck() {
        // 当前已占用的命令槽。
        CommandSlot context = new CommandSlotRing(4).claim(1);
        // 人工撮合事实，绑定同一 Core 序号后用于 Lane 确认。
        CoreMatchingResult result = new CoreMatchingResult(true, "ACCEPTED").withCoreSequenceInPlace(1);
        context.result(result, 0b11, 0b1111);
        context.completeLanes(0b01);

        assertThatThrownBy(() -> context.completeLanes(0b01))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> context.completeLanes(0b100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected");
        assertThatThrownBy(() -> context.completeLanes(0b1_0000))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unexpected");
    }

    /**
     * 在槽内首次保存撮合拒绝码并读取，第二个拒绝原因不能覆盖，最后丢弃槽。
     */
    @Test
    void storesMatchingRejectionInTheSequenceSlot() {
        // 当前固定容量命令槽池。
        CommandSlotRing ring = new CommandSlotRing(4);
        // 当前已占用的命令槽。
        CommandSlot context = ring.claim(1);

        context.rejectMatching(CoreResultCode.MATCHING_REJECTED);

        assertThat(context.hasMatchingRejection()).isTrue();
        assertThat(context.matchingRejection()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
        assertThatThrownBy(() -> context.rejectMatching(CoreResultCode.INVALID_COMMAND))
                .isInstanceOf(IllegalStateException.class);
        ring.discard(1);
    }

    /**
     * 挂起用户、订单变更，检查发布标记及数据，清上下文后丢弃槽。
     */
    @Test
    void ownsSuspendedCommitStateBySequence() {
        // 当前固定容量命令槽池。
        CommandSlotRing ring = new CommandSlotRing(4);
        // 当前已占用的命令槽。
        CommandSlot context = ring.claim(2);
        // 当前结果构建器。
        CommandResultBuilder builder = new CommandResultBuilder(null);
        builder.changedUserIds().add(7L);
        builder.changedOrderIds().add(11L);
        context.suspendCommitContext(builder, true, false);

        assertThat(context.hasCommitContext()).isTrue();
        assertThat(context.commitChangedUserIds()).containsExactly(7L);
        assertThat(context.commitChangedOrderIds()).containsExactly(11L);
        assertThat(context.commitSnapshotDirty()).isTrue();
        assertThat(context.commitSnapshotProvisionalOnly()).isFalse();
        context.clearCommitContext();
        assertThat(context.hasCommitContext()).isFalse();

        ring.discard(2);
    }

    /**
     * 记录挂起前及恢复构建器原有缓冲对象，恢复后要求原数据缓冲交回、备用缓冲进入槽，直接以对象身份验证交换。
     */
    @Test
    void restoresSuspendedPrimitiveChangeBuffersByOwnershipTransfer() {
        // 当前固定容量命令槽池。
        CommandSlotRing ring = new CommandSlotRing(2);
        // 当前已占用的命令槽。
        CommandSlot context = ring.claim(1);
        // 提交输入被挂起的原构建器。
        CommandResultBuilder suspended = new CommandResultBuilder(null);
        suspended.changedUserIds().add(7L);
        suspended.changedOrderIds().add(11L);
        // 原用户变更缓冲的对象引用。
        PrimitiveLongChangeSet userBuffer = suspended.changedUserIds();
        // 原订单变更缓冲的对象引用。
        PrimitiveLongChangeSet orderBuffer = suspended.changedOrderIds();
        context.suspendCommitContext(suspended, false, false);
        // 挂起后交给原构建器的备用用户缓冲。
        PrimitiveLongChangeSet suspendedReusableUserIds = suspended.changedUserIds();
        // 挂起后交给原构建器的备用订单缓冲。
        PrimitiveLongChangeSet suspendedReusableOrderIds = suspended.changedOrderIds();

        // 准备接回挂起输入的构建器。
        CommandResultBuilder restored = new CommandResultBuilder(null);
        // 恢复构建器原有、交换后由槽持有的用户缓冲。
        PrimitiveLongChangeSet restoredReusableUserIds = restored.changedUserIds();
        // 恢复构建器原有、交换后由槽持有的订单缓冲。
        PrimitiveLongChangeSet restoredReusableOrderIds = restored.changedOrderIds();
        context.restoreCommitContext(restored);

        assertThat(restored.changedUserIds()).isSameAs(userBuffer);
        assertThat(restored.changedOrderIds()).isSameAs(orderBuffer);
        assertThat(suspendedReusableUserIds).isNotSameAs(userBuffer);
        assertThat(suspendedReusableOrderIds).isNotSameAs(orderBuffer);
        assertThat(context.commitChangedUserIds()).isSameAs(restoredReusableUserIds);
        assertThat(context.commitChangedOrderIds()).isSameAs(restoredReusableOrderIds);
        context.clearCommitContext();
        ring.discard(1);
    }

}
