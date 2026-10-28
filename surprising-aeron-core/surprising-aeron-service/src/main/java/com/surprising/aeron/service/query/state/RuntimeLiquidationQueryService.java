package com.surprising.aeron.service.query.state;

import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreRiskScanContinuation;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.risk.RiskScanRuntime;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 强平待办工作查询。接收所属 Lane 已筛选的执行、保险补亏和 ADL 回执，在查询边界用条数和编码字节预算分页，并携带尚未完成风险扫描的继续信息；这里不执行平仓或修改亏空。 构造约定：只组装强平只读查询结果，不持有另一份账户或强平状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeLiquidationQueryService {

    /**
     * 把账户 Lane 已捕获的只读动作和补亏回执归并成一页。空回执也消费对应编号，未纳入的有效项保留给下页；同时保持来源是否完整、条数及编码字节预算语义。
     * @param productLine 这页强平工作的产品线，补亏筛选时还核对合约产品归属
     * @param query 本次用途、起始强平游标、最大条数及最大编码字节数
     * @param ids 与两个回执数组按相同下标对应的候选编号，顺序与分页遍历一致
     * @param actionReceipts 各账户 Lane 捕获的执行动作回执，不符合用途或失效时该位置为空
     * @param resolutionReceipts 与编号同下标的保险或 ADL 补亏回执，没有可展示项时为空
     * @param continuation 执行用途附带的风险扫描继续信息，没有待扫任务时可为空
     * @param sourceComplete 上游候选编号读取是否已到末尾，分页自己也可能因条数或字节上限变为未完
     */
    public static CoreLiquidationWorkView workPage(ProductLine productLine,
            CoreLiquidationWorkView.Query query, long[] ids, CoreLiquidationActionView[] actionReceipts,
            CoreLiquidationWorkView.Resolution[] resolutionReceipts, CoreRiskScanContinuation continuation,
            boolean sourceComplete) {
        // 本页已经接纳的强平执行动作，超过字节预算的最后一项不保留。
        ArrayList<CoreLiquidationActionView> actions = new ArrayList<>();
        // 本页补亏待办列表，保险推荐填充方法中则表示替换推荐额后的新列表。
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>();
        // 回执合页已消费到的强平编号，空回执也推进，有效项超限时留在前一项。
        long cursor = query.afterLiquidationId();
        // 该页是否已消费完来源范围，条数、扫描或字节边界使它变为 false。
        boolean complete = sourceComplete;
        // 回执合页当前已接纳的编码总长度，每次加项前都检查预算。
        int encoded = CoreLiquidationWorkCodec.emptyWorkLength(continuation);
        for (/* 当前候选编号及对应动作、补亏回执的共同数组下标。 */ int index = 0; index < ids.length; index++) {
            if (ids[index] <= query.afterLiquidationId()) continue;
            // 当前候选的执行动作视图，补亏用途或无有效执行回执时为空。
            var action = actionReceipts[index];
            // 当前候选的保险或 ADL 待办视图，执行用途或无有效补亏回执时为空。
            var resolution = resolutionReceipts[index];
            if (action == null && resolution == null) { cursor = ids[index]; continue; }
            if (actions.size() + resolutions.size() == query.maxItems()) { complete = false; break; }
            // 当前一条有效项增加的编码字节数，加入之前先与页面总上限比较。
            int itemBytes = Math.addExact(action == null ? 0 : CoreLiquidationWorkCodec.actionLength(action),
                    resolution == null ? 0 : CoreLiquidationWorkCodec.resolutionLength(resolution));
            if (Math.addExact(encoded, itemBytes) > query.maxBytes()) { complete = false; break; }
            encoded = Math.addExact(encoded, itemBytes);
            if (action != null) actions.add(action);
            if (resolution != null) resolutions.add(resolution);
            cursor = ids[index];
        }
        return new CoreLiquidationWorkView(productLine, cursor, complete, continuation, actions, resolutions);
    }

    /**
     * 在已有分页结果的补亏项上填入账本推荐覆盖额，缺少推荐时为 0；不改变索赔阶段、动作选择、游标或页面完成标记。
     * @param work 已经选好动作、补亏项及游标的原分页工作结果
     * @param allocations 账本按完整同资产索赔范围计算的推荐覆盖金额映射
     */
    public static CoreLiquidationWorkView insuranceRecommendations(CoreLiquidationWorkView work,
                                                                  java.util.Map<Long, Long> allocations) {
        // 本页补亏待办列表，保险推荐填充方法中则表示替换推荐额后的新列表。
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>(work.resolutions().size());
        for (var /* 当前强平事实或原补亏视图，读取后按本用途筛选或补入推荐额。 */ value : work.resolutions()) resolutions.add(new CoreLiquidationWorkView.Resolution(
                value.liquidationId(), value.userId(), value.instrumentId(), value.asset(), value.marginMode(), value.positionSide(),
                value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(),
                allocations.getOrDefault(value.liquidationId(), 0L), value.purpose()));
        return new CoreLiquidationWorkView(work.productLine(), work.nextCursorLiquidationId(), work.complete(),
                work.riskScanContinuation(), work.actions(), resolutions);
    }

    /**
     * 只在执行用途携带风险续扫信息；先检查币对扫描索引规模，再选下一份未完风险扫描，保留其外部币对、价格序号和最后完成用户。
     * @param runtime 允许读取本批强平、持仓及行情的运行时，调用方负责满足账户与市场读取边界
     * @param identities 外部币对、资产和持仓键与运行时整数身份的映射
     * @param purpose 本次只选择执行、保险补亏还是 ADL 待办
     */
    public static CoreRiskScanContinuation continuation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            CoreLiquidationWorkView.Purpose purpose) {
        if (purpose != CoreLiquidationWorkView.Purpose.EXECUTION) return null;
        // 风险扫描元数据集合，先用它的规模限制查询扫描范围。
        if (runtime.riskScanCount() > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        }
        // 公平调度中下一份未完成的账户风险扫描，若没有就不生成继续信息。
        RiskScanRuntime selected = runtime.firstRiskIncompleteScan();
        return selected == null ? null
                : new CoreRiskScanContinuation(identities.instrumentId(selected.symbolId()),
                        selected.priceSequence(), selected.lastUserId());
    }
}
