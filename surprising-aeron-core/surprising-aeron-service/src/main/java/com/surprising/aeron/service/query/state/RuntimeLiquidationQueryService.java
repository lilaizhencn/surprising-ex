package com.surprising.aeron.service.query.state;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreRiskScanContinuation;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.CoreRiskPolicy;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskScanRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.treasury.InsuranceAllocationPolicy;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 强平可执行性和待办工作查询。按执行、保险补亏、ADL 用途筛选记录，在查询边界用条数和编码字节预算分页，并携带尚未完成风险扫描的继续信息；这里不执行平仓或修改亏空。 构造约定：只组装强平只读查询结果，不持有另一份账户或强平状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeLiquidationQueryService {

    /**
     * 核对强平仍在计划或撤单阶段、当前标记价及序号未变，再确认配置、持仓模式与带方向数量仍匹配，风险记录也仍在该序号判为强平；过期价格直接业务拒绝，其他失效返回 false。
     * @param runtime 允许读取本批强平、持仓及行情的运行时，调用方负责满足账户与市场读取边界
     * @param identities 外部币对、资产和持仓键与运行时整数身份的映射
     * @param command 需要检查是否仍可执行的强平命令，包含价格版本和执行价
     */
    public static boolean isExecutable(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, ExecuteLiquidationCommand command) {
        // 按命令编号读到的源强平记录，阶段不再可执行时直接返回 false。
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED)) return false;
        // 当前币对行情，用来核对触发序号或构造该动作的执行标记价。
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || (command.triggerPriceSequence() > 0
                && command.triggerPriceSequence() != liquidation.triggerPriceSequence())
                || command.executionPriceTicks() != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
        // 该强平记录币对整数编号还原的外部合约编号。
        String instrumentId = identities.instrumentId(liquidation.symbolId());
        // 当前合约配置，核对可强平性、产品线和补亏结算资产时使用。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        // 根据净持仓或双向腿拼出的业务持仓键。
        String positionName = liquidation.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                ? instrumentId : instrumentId + ':' + liquidation.positionSide().name();
        // 身份表中源用户目标持仓的整数键，没有登记时为空。
        Long positionKey = identities.findPositionKey(liquidation.userId(), positionName);
        // 源用户当前持仓，配置引用、保证金模式和数量都必须与强平计划一致。
        PositionRuntime position = positionKey == null ? null : runtime.position(positionKey);
        // 该持仓腿的风险缓存，必须对应触发序号且仍为强平等级。
        RiskSnapshotRuntime risk = positionKey == null ? null : runtime.riskSnapshot(positionKey);
        return position != null && position.instrument() == liquidation.instrument()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    /**
     * 保险用途先根据全部候选计算确定分配推荐额，其余用途用空推荐映射，再按本页候选进入统一工作视图分页。
     * @param runtime 允许读取本批强平、持仓及行情的运行时，调用方负责满足账户与市场读取边界
     * @param identities 外部币对、资产和持仓键与运行时整数身份的映射
     * @param productLine 这页强平工作的产品线，补亏筛选时还核对合约产品归属
     * @param query 本次用途、起始强平游标、最大条数及最大编码字节数
     * @param candidateIds 本页候选强平编号，应按递增顺序提供，跳过起始游标及之前的项
     * @param allCandidateIds 保险推荐额计算使用的完整候选范围，不能只用当前页候选代替全局分配
     */
    public static CoreLiquidationWorkView work(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, ProductLine productLine,
            CoreLiquidationWorkView.Query query, Iterable<Long> candidateIds,
            Iterable<Long> allCandidateIds) {
        // 保险用途才计算的完整候选推荐金额映射，其他用途用空映射。
        java.util.Map<Long, Long> allocations = query.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                ? InsuranceAllocationPolicy.allocations(runtime, identities, allCandidateIds) : java.util.Map.of();
        return work(runtime, identities, productLine, query, candidateIds, allocations);
    }

    /**
     * 沿递增强平编号筛选本用途工作，跳过不符合记录时仍推进游标；条数、扫描数量或编码字节达到边界时停在最后已消费编号，返回当前页、完成标记及风险继续信息。
     * @param runtime 允许读取本批强平、持仓及行情的运行时，调用方负责满足账户与市场读取边界
     * @param identities 外部币对、资产和持仓键与运行时整数身份的映射
     * @param productLine 这页强平工作的产品线，补亏筛选时还核对合约产品归属
     * @param query 本次用途、起始强平游标、最大条数及最大编码字节数
     * @param candidateIds 本页候选强平编号，应按递增顺序提供，跳过起始游标及之前的项
     * @param insuranceAllocations 按强平编号给出的保险推荐覆盖额，不存在的编号按 0 展示
     */
    public static CoreLiquidationWorkView work(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            ProductLine productLine, CoreLiquidationWorkView.Query query, Iterable<Long> candidateIds,
            java.util.Map<Long, Long> insuranceAllocations) {
        // 本页已经接纳的强平执行动作，超过字节预算的最后一项不保留。
        ArrayList<CoreLiquidationActionView> actions = new ArrayList<>();
        // 本页补亏待办列表，保险推荐填充方法中则表示替换推荐额后的新列表。
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>();
        // 本执行查询携带的下一份风险续扫信息，保险和 ADL 用途不附带。
        CoreRiskScanContinuation continuation = continuation(runtime, identities, query.purpose());
        // 最后已经消费的强平编号，包含被过滤掉的候选，但不跨过尚未放进本页的有效项。
        long nextCursor = query.afterLiquidationId();
        // 当前页已占用的编码字节数，包含页头及已接纳的条目。
        int encodedBytes = CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                productLine, nextCursor, false, continuation, actions, resolutions)).length;
        // 本次已经查看的起始游标之后候选数量，超出统一索引上限就结束为未完页。
        int scanned = 0;
        // 该页是否已消费完来源范围，条数、扫描或字节边界使它变为 false。
        boolean complete = true;
        for (Long /* 本轮遍历的强平候选编号，空值或不大于起点的项不参与本页。 */ liquidationId : candidateIds) {
            if (liquidationId == null || liquidationId <= query.afterLiquidationId()) continue;
            if (++scanned > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
                complete = false;
                break;
            }
            // 当前强平事实或原补亏视图，读取后按本用途筛选或补入推荐额。
            LiquidationRuntime value = runtime.liquidation(liquidationId);
            if (!eligible(runtime, identities, productLine, query.purpose(), value)) {
                nextCursor = liquidationId;
                continue;
            }
            if (actions.size() + resolutions.size() >= query.maxItems()) {
                complete = false;
                break;
            }
            // 当前候选的执行动作视图，补亏用途或无有效执行回执时为空。
            CoreLiquidationActionView action = null;
            // 当前候选的保险或 ADL 待办视图，执行用途或无有效补亏回执时为空。
            CoreLiquidationWorkView.Resolution resolution = null;
            // 该强平记录币对整数编号还原的外部合约编号。
            String instrumentId = identities.instrumentId(value.symbolId());
            if (query.purpose() == CoreLiquidationWorkView.Purpose.EXECUTION) {
                // 当前币对行情，用来核对触发序号或构造该动作的执行标记价。
                MarkPriceRuntime mark = runtime.markPrice(value.symbolId());
                action = new CoreLiquidationActionView(value.liquidationId(), value.userId(), instrumentId,
                        value.marginMode(), value.positionSide(),
                        value.triggerPriceSequence(), value.signedQuantitySteps(), value.closeQuantitySteps(),
                        mark.markPriceTicks(), value.status().name(),
                        value.status() == CoreLiquidationState.Status.ORDERED ? value.nextCancelOrderId() : 0);
                actions.add(action);
            } else {
                // 当前合约配置，核对可强平性、产品线和补亏结算资产时使用。
                CoreInstrument instrument = runtime.instrument(instrumentId);
                resolution = new CoreLiquidationWorkView.Resolution(value.liquidationId(), value.userId(), instrumentId,
                        instrument.settleAsset(), value.marginMode(), value.positionSide(),
                        value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(),
                        insuranceAllocations.getOrDefault(value.liquidationId(), 0L), query.purpose());
                resolutions.add(resolution);
            }
            // 当前有效项若纳入页面后要推进到的强平编号。
            long candidateCursor = value.liquidationId();
            // 使用同一候选游标的空页面，只为计算新增条目的准确编码增量。
            CoreLiquidationWorkView emptyAtCursor = new CoreLiquidationWorkView(productLine, candidateCursor,
                    false, continuation, java.util.List.of(), java.util.List.of());
            // 只有当前动作或补亏项的临时页面，编码后减掉同游标空页长度得到条目增量。
            CoreLiquidationWorkView singleItem = new CoreLiquidationWorkView(productLine, candidateCursor,
                    false, continuation, action == null ? java.util.List.of() : java.util.List.of(action),
                    resolution == null ? java.util.List.of() : java.util.List.of(resolution));
            // 当前一条有效项增加的编码字节数，加入之前先与页面总上限比较。
            int itemBytes = Math.subtractExact(CoreLiquidationWorkCodec.encodeWork(singleItem).length,
                    CoreLiquidationWorkCodec.encodeWork(emptyAtCursor).length);
            if (Math.addExact(encodedBytes, itemBytes) > query.maxBytes()) {
                if (action != null) actions.removeLast();
                if (resolution != null) resolutions.removeLast();
                complete = false;
                break;
            }
            encodedBytes = Math.addExact(encodedBytes, itemBytes);
            nextCursor = candidateCursor;
        }
        return new CoreLiquidationWorkView(productLine, nextCursor, complete, continuation, actions, resolutions);
    }

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
        int encoded = CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                productLine, cursor, false, continuation, List.of(), List.of())).length;
        for (/* 当前候选编号及对应动作、补亏回执的共同数组下标。 */ int index = 0; index < ids.length; index++) {
            if (ids[index] <= query.afterLiquidationId()) continue;
            // 当前候选的执行动作视图，补亏用途或无有效执行回执时为空。
            var action = actionReceipts[index];
            // 当前候选的保险或 ADL 待办视图，执行用途或无有效补亏回执时为空。
            var resolution = resolutionReceipts[index];
            if (action == null && resolution == null) { cursor = ids[index]; continue; }
            if (actions.size() + resolutions.size() == query.maxItems()) { complete = false; break; }
            // 当前一条有效项增加的编码字节数，加入之前先与页面总上限比较。
            int itemBytes = CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                    productLine, ids[index], false, continuation,
                    action == null ? List.of() : List.of(action),
                    resolution == null ? List.of() : List.of(resolution))).length
                    - CoreLiquidationWorkCodec.encodeWork(new CoreLiquidationWorkView(
                    productLine, ids[index], false, continuation, List.of(), List.of())).length;
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
     * 工作列表的轻量用途筛选：执行只看可处理阶段及触发行情是否仍在；保险和 ADL 还核对当前配置引用及产品线。它不替代执行前的持仓与风险完整检查。
     * @param runtime 允许读取本批强平、持仓及行情的运行时，调用方负责满足账户与市场读取边界
     * @param identities 外部币对、资产和持仓键与运行时整数身份的映射
     * @param productLine 这页强平工作的产品线，补亏筛选时还核对合约产品归属
     * @param purpose 本次只选择执行、保险补亏还是 ADL 待办
     * @param value 本轮候选强平记录，缺失时不具备任何查询用途
     */
    private static boolean eligible(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, ProductLine productLine,
            CoreLiquidationWorkView.Purpose purpose, LiquidationRuntime value) {
        if (value == null) return false;
        // 候选强平阶段是否符合执行、待保险或待 ADL 的查询用途。
        boolean statusMatches = switch (purpose) {
            case EXECUTION -> value.status() == CoreLiquidationState.Status.PLANNED
                    || value.status() == CoreLiquidationState.Status.ORDERED;
            case INSURANCE -> value.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED;
            case ADL -> value.status() == CoreLiquidationState.Status.ADL_REQUIRED;
        };
        if (!statusMatches) return false;
        if (purpose == CoreLiquidationWorkView.Purpose.EXECUTION) {
            // 当前币对行情，用来核对触发序号或构造该动作的执行标记价。
            MarkPriceRuntime mark = runtime.markPrice(value.symbolId());
            return mark != null && mark.priceSequence() == value.triggerPriceSequence();
        }
        // 当前合约配置，核对可强平性、产品线和补亏结算资产时使用。
        CoreInstrument instrument = runtime.instrument(identities.instrumentId(value.symbolId()));
        return instrument != null && instrument == value.instrument()
                && instrument.contractType().productLine() == productLine;
    }

    /**
     * 只在执行用途携带风险续扫信息；先检查币对扫描索引规模，再选下一份未完风险扫描，保留其外部币对、价格序号和最后完成用户。
     * @param runtime 允许读取本批强平、持仓及行情的运行时，调用方负责满足账户与市场读取边界
     * @param identities 外部币对、资产和持仓键与运行时整数身份的映射
     * @param purpose 本次只选择执行、保险补亏还是 ADL 待办
     */
    public static CoreRiskScanContinuation continuation(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            CoreLiquidationWorkView.Purpose purpose) {
        if (purpose != CoreLiquidationWorkView.Purpose.EXECUTION) return null;
        // 风险扫描元数据集合，先用它的规模限制查询扫描范围。
        var scans = runtime.riskScansForSnapshot();
        if (scans.size() > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        }
        // 公平调度中下一份未完成的账户风险扫描，若没有就不生成继续信息。
        RiskScanRuntime selected = runtime.firstRiskIncompleteScan();
        return selected == null ? null
                : new CoreRiskScanContinuation(identities.instrumentId(selected.symbolId()),
                        selected.priceSequence(), selected.lastUserId());
    }
}
