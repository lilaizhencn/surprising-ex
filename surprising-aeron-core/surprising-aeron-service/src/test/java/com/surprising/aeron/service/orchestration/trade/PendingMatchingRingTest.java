package com.surprising.aeron.service.orchestration.trade;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.state.ledger.RuntimeFundsDelta;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证待撮合环的全局链接、独立资金派发游标、分片提交链、UUID 索引及槽位复用。辅助消息只作为容器身份载体，未执行合法余额业务或真实 Matcher 结算。
 */
class PendingMatchingRingTest {

    /**
     * 推进资金游标时全局响应队首与 size 保持原值，全部旧项已派发后新尾项仍可见；移除旧项、清环并复用后，游标继续指向新项。
     */
    @Test
    void ledgerDispatchAdvancesWithoutRetiringResponsesAndSurvivesSlotReuse() {
        // 当前测试创建的 Owner 待撮合环。
        var ring = new PendingMatchingRing(4, 1, 4);
        // 最早的已占用命令槽。
        var first = acquire(ring, 1, UUID.randomUUID(), 1001);
        // 第二个命令槽。
        var second = acquire(ring, 2, UUID.randomUUID(), 1002);
        ring.put(first); ring.put(second);
        assertThat(ring.ledgerDispatchHead()).isSameAs(first);
        assertThatThrownBy(() -> ring.completeLedgerDispatch(2)).isInstanceOf(IllegalStateException.class);
        ring.completeLedgerDispatch(1);
        assertThat(ring.ledgerDispatchHead()).isSameAs(second);
        assertThat(ring.head()).isSameAs(first);
        ring.completeLedgerDispatch(2);
        assertThat(ring.ledgerDispatchHead()).isNull();
        assertThat(ring.size()).isEqualTo(2);
        // 即使仍保留的旧项都已派发，新入队尾项也必须成为可见资金游标。
        // 第三个命令槽。
        var third = acquire(ring, 3, UUID.randomUUID(), 1003);
        ring.put(third);
        assertThat(ring.ledgerDispatchHead()).isSameAs(third);
        ring.remove(1); ring.remove(2);
        assertThat(ring.ledgerDispatchHead()).isSameAs(third);
        ring.clear();
        assertThat(ring.ledgerDispatchHead()).isNull();
        // 物理槽复用后承载新 Core 序号的同一对象。
        var reused = acquire(ring, 5, UUID.randomUUID(), 1004);
        ring.put(reused);
        assertThat(ring.ledgerDispatchHead()).isSameAs(reused);
        ring.completeLedgerDispatch(5);
        assertThat(ring.ledgerDispatchHead()).isNull();
    }

    /**
     * 直接移除首项和尾项，要求资金游标保持在中项，完成后重复派发拒绝；未设置真实拒绝原因或投递资金，只测容器移除语义。
     */
    @Test
    void removingUnfundedOrRejectedHeadKeepsTheLedgerCursorInOriginalOrder() {
        // 当前测试创建的 Owner 待撮合环。
        var ring = new PendingMatchingRing(4, 1, 4);
        // 最早的已占用命令槽。
        var first = acquire(ring, 1, UUID.randomUUID(), 1001);
        // 第二个命令槽。
        var second = acquire(ring, 2, UUID.randomUUID(), 1002);
        // 第三个命令槽。
        var third = acquire(ring, 3, UUID.randomUUID(), 1003);
        ring.put(first); ring.put(second); ring.put(third);
        // 此处直接移除未产生资金事实的项，验证容器同步推进游标，不执行真实拒单业务。
        ring.remove(1);
        assertThat(ring.ledgerDispatchHead()).isSameAs(second);
        ring.remove(3);
        assertThat(ring.ledgerDispatchHead()).isSameAs(second);
        ring.completeLedgerDispatch(2);
        assertThat(ring.ledgerDispatchHead()).isNull();
        assertThatThrownBy(() -> ring.completeLedgerDispatch(2)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 请求容量三向上取四，重登记同一槽不重复链接，中间项移除不推进最老队首，物理冲突仍拒绝；释放最老项后新一代顺序快照和水位正确。
     */
    @Test
    void keepsTheLowWatermarkWhileAllowingIndependentPartitionCompletion() {
        // 当前测试创建的 Owner 待撮合环。
        PendingMatchingRing ring = new PendingMatchingRing(3, 1, 4);
        // 最早的已占用命令槽。
        CommandSlot first = acquire(ring, 7, UUID.randomUUID(), 1001);
        // 首槽原对象的另一个引用，用于验证同序号重登记不另加项。
        CommandSlot replacement = first;
        // 第二个命令槽。
        CommandSlot second = acquire(ring, 8, UUID.randomUUID(), 1003);
        // 第三个命令槽。
        CommandSlot third = acquire(ring, 9, UUID.randomUUID(), 1004);
        // 填满四槽窗口的第四项。
        CommandSlot fourth = acquire(ring, 10, UUID.randomUUID(), 1005);

        ring.put(first);
        assertThat(ring.get(7)).isSameAs(first);
        ring.put(replacement);
        ring.put(second);
        ring.put(third);
        ring.put(fourth);

        assertThat(ring.capacity()).isEqualTo(4);
        assertThat(ring.size()).isEqualTo(4);
        assertThat(ring.firstSequence()).isEqualTo(7);
        assertThat(ring.get(7)).isSameAs(replacement);
        assertThat(ring.findByCommandId(second.command().header().commandId())).isSameAs(second);
        assertThat(ring.remove(8)).isSameAs(second);
        assertThat(ring.remove(9)).isSameAs(third);
        assertThat(ring.remove(10)).isSameAs(fourth);
        assertThat(ring.firstSequence()).isEqualTo(7);
        assertThatThrownBy(() -> ring.put(acquire(ring, 11, UUID.randomUUID(), 1006)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ring is full");
        assertThat(ring.remove(7)).isSameAs(replacement);
        assertThat(ring.contexts().claimed(7)).isFalse();
        ring.put(acquire(ring, 11, UUID.randomUUID(), 1006));
        ring.put(acquire(ring, 12, UUID.randomUUID(), 1007));
        ring.put(acquire(ring, 13, UUID.randomUUID(), 1008));
        ring.put(acquire(ring, 14, UUID.randomUUID(), 1009));
        assertThat(ring.snapshot().keySet()).containsExactly(11L, 12L, 13L, 14L);
        assertThat(ring.firstSequence()).isEqualTo(11);
        ring.clear();
        assertThat(ring.snapshot()).isEmpty();
    }

    /**
     * 登记两个分片的三个顺序项，完成零分片首项只推进该链，删除一分片唯一项后其头为空；没有在此创建实际分片线程。
     */
    @Test
    void advancesEachMatcherSubmissionShardWithoutScanningThePendingRing() {
        // 当前测试创建的 Owner 待撮合环。
        PendingMatchingRing ring = new PendingMatchingRing(4, 2, 4);
        // 零分片最早提交项。
        CommandSlot shardZeroFirst = acquire(ring, 1, UUID.randomUUID(), 1001);
        // 一分片唯一提交项。
        CommandSlot shardOne = acquire(ring, 2, UUID.randomUUID(), 1002);
        // 零分片后续提交项。
        CommandSlot shardZeroSecond = acquire(ring, 3, UUID.randomUUID(), 1003);
        ring.put(shardZeroFirst);
        ring.put(shardOne);
        ring.put(shardZeroSecond);
        ring.registerSubmission(1, 0);
        ring.registerSubmission(2, 1);
        ring.registerSubmission(3, 0);

        assertThat(ring.submissionHead(0)).isSameAs(shardZeroFirst);
        assertThat(ring.submissionHead(1)).isSameAs(shardOne);
        assertThat(ring.isSubmissionHead(3, 0)).isFalse();
        ring.completeSubmission(1);
        assertThat(ring.submissionHead(0)).isSameAs(shardZeroSecond);
        assertThat(ring.isSubmissionHead(3, 0)).isTrue();
        ring.remove(2);
        assertThat(ring.submissionHead(1)).isNull();
    }

    /**
     * 全局先入项移除后队首先后变为第二项和空。
     */
    @Test
    void exposesOnlyTheDeterministicSubmissionHead() {
        // 当前测试创建的 Owner 待撮合环。
        PendingMatchingRing ring = new PendingMatchingRing(2, 1, 4);
        // 最早的已占用命令槽。
        CommandSlot first = acquire(ring, 1, UUID.randomUUID(), 1001);
        // 第二个命令槽。
        CommandSlot second = acquire(ring, 2, UUID.randomUUID(), 1002);
        ring.put(first);
        ring.put(second);

        assertThat(ring.head()).isSameAs(first);
        assertThat(ring.remove(1)).isSameAs(first);
        assertThat(ring.head()).isSameAs(second);
        assertThat(ring.remove(2)).isSameAs(second);
        assertThat(ring.head()).isNull();
    }

    /**
     * 移除序号一后预备序号五，要求返回同一物理槽但完整身份与用户已换新，最后丢弃未链接预备项。
     */
    @Test
    void reusesTheSequenceContextPendingCarrierAfterTheSlotIsReleased() {
        // 当前测试创建的 Owner 待撮合环。
        PendingMatchingRing ring = new PendingMatchingRing(4, 1, 4);
        // 最早的已占用命令槽。
        CommandSlot first = acquire(ring, 1, UUID.randomUUID(), 1001);
        ring.put(first);
        ring.remove(1);

        // 物理槽复用后承载新 Core 序号的同一对象。
        CommandSlot reused = acquire(ring, 5, UUID.randomUUID(), 1002);

        assertThat(reused).isSameAs(first);
        assertThat(reused.sequence()).isEqualTo(5);
        assertThat(reused.command().header().userId()).isEqualTo(1002);
        ring.discardPrepared(5);
    }

    /**
     * 用本环 acquire 占槽并初始化人工身份、空资金与解码输入，调用方再决定 put 入队。
     * @param ring 当前测试待撮合环
     * @param sequence 本次预备的完整 Core 序号
     * @param commandId 本条人工载体命令 UUID
     * @param userId 用于用户计数索引的请求用户号
     */
    private static CommandSlot acquire(PendingMatchingRing ring, long sequence, UUID commandId, long userId) {
        // 只为容器测试提供头部身份的人工消息。
        CoreMessage command = command(commandId, userId);
        return ring.acquire(sequence, CommandSlot.Operation.PLACE, command,
                com.surprising.aeron.protocol.CommandFingerprint.of(command), java.util.List.of(),
                RuntimeFundsDelta.empty(), DecodedMatchingCommand.decode(command), null);
    }

    /**
     * 构造用于索引测试的 ADJUST_BALANCE 头和单字节占位负载；它不是合法完整余额调整输入，Operation.PLACE 也只是当前测试载体标签。
     * @param commandId 本条人工载体命令 UUID
     * @param userId 用于用户计数索引的请求用户号
     */
    private static CoreMessage command(UUID commandId, long userId) {
        return new CoreMessage(CoreMessageHeader.command(
                CoreMessageType.ADJUST_BALANCE, commandId, ProductLine.LINEAR_PERPETUAL,
                CommandSource.GATEWAY, 1, 1, userId, 1_000, 1), new byte[]{1});
    }
}
