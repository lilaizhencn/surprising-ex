package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.List;

/**
 * 订单准入异步交接的骨架事件基类。
 * 统一管理账本 OI 异步输入状态、完成标记、业务拒绝异常以及前置撤单列表。
 */
public abstract class BaseOrderAdmissionEvent extends ResolvedPlaceOrder
        implements SettlementLaneWorker.Command, TreasuryRuntime.Entry {

    /** 账本准备的币对 OI 数量步数，账户准入用它检查比例限额。 */
    protected long openInterestSteps;

    /** OI 输入已经就绪的可见性标记，不需要 OI 或只减仓时可直接就绪。 */
    protected volatile boolean inputReady = true;

    /** 需要的账本输入任务是否已经成功投递。 */
    protected boolean inputQueued;

    /** Lane 捕获的业务拒绝、算术或参数异常，完成后由 Owner 或 Matcher 读取。 */
    protected RuntimeException rejection;

    /** 撮合前应执行的撤单编号列表。 */
    protected List<Long> closeCapacityCancellations = List.of();

    /** Lane 写完准入数据后发布的完成位。 */
    protected volatile boolean completed;

    @Override
    public boolean accountReady() {
        return inputReady;
    }

    public void inputQueued() {
        inputQueued = true;
    }

    public boolean inputPending() {
        return inputQueued && !inputReady;
    }

    public boolean complete() {
        return completed;
    }

    public RuntimeException rejection() {
        if (!completed) throw new IllegalStateException("admission is incomplete");
        return rejection;
    }

    public List<Long> closeCapacityCancellations() {
        if (!completed) throw new IllegalStateException("admission is incomplete");
        return rejection == null ? closeCapacityCancellations : List.of();
    }

    protected void clearBaseAdmission() {
        clearResolvedOrder();
        openInterestSteps = 0;
        inputReady = true;
        inputQueued = false;
        rejection = null;
        closeCapacityCancellations = List.of();
        completed = false;
    }
}
