package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 直接验证 Owner 调度字段及响应窗口：控制结果序号、依赖末项引用、提交边界和等待扫描缓存。辅助请求为空负载，仅供窗口身份使用，不执行实际下单。
 */
class OwnerCommandPipelineStateTest {
    /**
     * 空窗口中忽略其他序号，窗口非空则拒绝完成；退休窗口后接受指定序号，并拒绝第二次相同终态。
     */
    @Test
    void controlCompletionAcceptsOnlyItsSequenceWithAnEmptyTradingWindow() {
        // 直接测试的 Owner 调度状态，不带真实交易运行时。
        var state = new OwnerCommandPipelineState();
        state.rememberMatchingResponseSequence(7);
        // 人工构造的指定序号 APPLIED 响应。
        var response = new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 7, CoreMessage.EMPTY_PAYLOAD);
        state.completeControlMatching(8, response);
        assertThat(state.matchingResponse()).isNull();
        add(state, 1);
        assertThatThrownBy(() -> state.completeControlMatching(7, response))
                .isInstanceOf(IllegalStateException.class);
        retire(state);
        assertThat(state.matchingResponse()).isNull();
        state.completeControlMatching(7, response);
        assertThat(state.matchingResponse()).isSameAs(response);
        assertThatThrownBy(() -> state.completeControlMatching(7, response))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
    }
    /**
     * 依赖前缀为两项，退休无关首项仍阻塞，退休真正末项后解除；随后跨容量反复复用，旧请求依赖不会复活。
     */
    @Test
    void dependencyWaitSurvivesUnrelatedRetirementAndEndsAtActualBlocker() {
        // 直接测试的 Owner 调度状态，不带真实交易运行时。
        var state = new OwnerCommandPipelineState();
        // 当前被记录为依赖阻塞的请求对象。
        var request = request(9);
        add(state, 1);
        add(state, 2);
        add(state, 3);
        state.rememberBlockedIngress(request, 2);
        retire(state);
        assertThat(state.isIngressBlocked(request)).isTrue();
        assertThat(state.isIngressBlocked(request(10))).isFalse();
        retire(state);
        assertThat(state.isIngressBlocked(request)).isFalse();
        assertThat(state.commandWindow().size()).isOne();
        retire(state);
        // 复用所有槽位后，旧依赖不能重新生效。
        for (/* 跨窗口容量反复复用的迭代下标。 */ int i = 0; i < state.commandWindow().capacity() + 1; i++) {
            add(state, i + 10);
            retire(state);
        }
        assertThat(state.isIngressBlocked(request)).isFalse();
    }

    /**
     * 记录覆盖整个窗口的依赖，退休一项仍阻塞，开始第二项提交后 reset，要求引用、提交边界和窗口全清。
     */
    @Test
    void controlFenceWaitsForWholeWindowAndResetDropsReferences() {
        // 直接测试的 Owner 调度状态，不带真实交易运行时。
        var state = new OwnerCommandPipelineState();
        // 当前被记录为依赖阻塞的请求对象。
        var request = request(9);
        add(state, 1);
        add(state, 2);
        state.rememberBlockedIngress(request, state.commandWindow().size());
        retire(state);
        assertThat(state.isIngressBlocked(request)).isTrue();
        state.beginHeadCommit();
        state.reset();
        assertThat(state.isIngressBlocked(request)).isFalse();
        assertThat(state.hasCommittingHead()).isFalse();
        assertThat(state.commandWindow().size()).isZero();
    }

    /**
     * 窗口条目未绑定撮合序号也可建立提交边界，重复 begin 或无边界 finish 拒绝，正常 finish 清掉队首引用。
     */
    @Test
    void headBoundaryUsesWindowEntryEvenWithoutMatchingSequence() {
        // 直接测试的 Owner 调度状态，不带真实交易运行时。
        var state = new OwnerCommandPipelineState();
        add(state, 1);
        // 当前未绑定撮合序号的窗口首条目。
        var head = state.commandWindow().get(0);
        assertThat(head.sequence).isZero();
        state.beginHeadCommit();
        assertThat(state.hasCommittingHead()).isTrue();
        assertThat(state.committingHead()).isSameAs(head);
        assertThatThrownBy(state::beginHeadCommit).isInstanceOf(IllegalStateException.class);
        state.finishHeadCommit();
        assertThat(state.hasCommittingHead()).isFalse();
        assertThatThrownBy(state::finishHeadCommit).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 保存无进展等待后，只有派发上限、进度和通知都未变才可跳过；队首退休或仍有本地工作都必须重新扫描。
     */
    @Test
    void waitingProbeIsInvalidatedByProgressNotificationsAndHeadRetirement() {
        // 直接测试的 Owner 调度状态，不带真实交易运行时。
        var state = new OwnerCommandPipelineState();
        add(state, 1);
        add(state, 2);
        state.beginHeadCommit();
        state.rememberMatchingWait(2, 7, 7, false);
        assertThat(state.canSkipMatchingPoll(2, 7, false)).isTrue();
        assertThat(state.canSkipMatchingPoll(3, 7, false)).isFalse();
        assertThat(state.canSkipMatchingPoll(2, 8, false)).isFalse();
        assertThat(state.canSkipMatchingPoll(2, 7, true)).isFalse();
        state.finishHeadCommit();
        state.beginHeadCommit();
        assertThat(state.canSkipMatchingPoll(2, 7, false)).isFalse();
        state.rememberMatchingWait(2, 7, 7, true);
        assertThat(state.canSkipMatchingPoll(2, 7, false)).isFalse();
    }

    /**
     * 顺序建立并结束一个队首提交边界，不执行实际业务完成校验。
     * @param state 当前 Owner 调度状态
     */
    private static void retire(OwnerCommandPipelineState state) {
        state.beginHeadCommit();
        state.finishHeadCommit();
    }

    /**
     * 给指定用户准备静态路由并加入身份请求，条目时间和日志位置都使用该编号。
     * @param state 当前 Owner 调度状态
     * @param id 测试请求的用户、源序号及相关身份编号
     */
    private static void add(OwnerCommandPipelineState state, long id) {
        // 当前状态持有的响应 FIFO。
        var window = state.commandWindow();
        window.resetCandidate(id);
        window.route(0, 1L);
        window.add(ClusterServiceEgress.NO_SESSION_ID, request(id), id, id);
    }

    /**
     * 构造现货 PLACE_ORDER 头及空负载，只用于窗口引用和依赖测试。
     * @param id 测试请求的用户、源序号及相关身份编号
     */
    private static CoreMessage request(long id) {
        return CoreMessage.owned(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,
                new UUID(1, id), ProductLine.SPOT, CommandSource.GATEWAY, 1, id, id, id, id), new byte[0]);
    }
}
