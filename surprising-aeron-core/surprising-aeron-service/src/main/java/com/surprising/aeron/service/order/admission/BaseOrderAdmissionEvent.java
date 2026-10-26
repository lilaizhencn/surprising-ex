package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import java.util.List;

/**
 * 订单准入交接的骨架事件基类。
 * 统一管理币对 OI 输入、完成标记、业务拒绝异常以及前置撤单列表。
 */
public abstract class BaseOrderAdmissionEvent extends ResolvedPlaceOrder
        implements SettlementLaneWorker.Command {

    /** 币对 OI 数量步数，账户准入用它检查比例限额。 */
    protected long openInterestSteps;

    /** Lane 捕获的业务拒绝、算术或参数异常，完成后由 Owner 或 Matcher 读取。 */
    protected RuntimeException rejection;

    /** 撮合前应执行的撤单编号列表。 */
    protected List<Long> closeCapacityCancellations = List.of();

    /** Lane 写完准入数据后发布的完成位。 */
    protected volatile boolean completed;

    public void bindOpenInterest(long openInterestSteps) {
        this.openInterestSteps = openInterestSteps;
    }

    public long openInterestSteps() {
        return openInterestSteps;
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
        rejection = null;
        closeCapacityCancellations = List.of();
        completed = false;
    }
}
