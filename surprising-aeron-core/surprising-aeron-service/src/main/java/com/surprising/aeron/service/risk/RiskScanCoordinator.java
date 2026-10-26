package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.ArrayList;
import java.util.List;

/**
 * 风险扫描的跨线程续步：Owner 固定本片市场输入，账本准备各 Lane 的有界用户页，账户 Lane 估值，Owner 按固定顺序分配强平编号，再交原 Lane 写入并提交游标。异步轮询在其他线程未完成时返回 false。
 */
public final class RiskScanCoordinator {
    /** 本命令固定的交易运行时，任务完成前按既有控制命令边界稳定风险输入。 */
    private final TradingAccountRuntime runtime;
    /** 本命令共用的业务编号映射，Owner 准备路由和 Lane 估值时使用。 */
    private final RuntimeIdentityRegistry identities;
    /** 按币对及 Lane 查询候选持仓用户的账本索引。 */
    private final PositionUserIndex positionUsers;
    /** 本命令按配置和待扫币对数计算的总工作预算。 */
    private int budget;
    /** 本命令尚未消耗的工作预算，Owner 每片完成后扣减。 */
    private int remaining;
    /** 每个 Lane 唯一的扫描输入槽，账本准备路由后由对应 Lane 只读消费。 */
    private final RiskLaneProgress[] inputs;
    /** Owner 按 Lane 编号收集的估值回执，后续用于编号和合并游标。 */
    private final RiskLaneProcessor.Page[] results;
    /** 账本为各参与 Lane 分配的本片工作量，Lane 估值时读取。 */
    private final int[] allocations;
    /** 每个 Lane 复用的有界用户页，由账本清空填充，发布就绪后交给所属 Lane 读取。 */
    private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] userPages;
    /** 当前币对片段最多可分给各 Lane 的工作预算。 */
    private int sliceBudget;
    /** 准备用户路由时的账本命令引用，发布路由前用来同步风险持仓索引输入。 */
    private SystemLedgerCommand ledgerScope;
    /** Owner 选中的本片原扫描记录，含行情序号及各 Lane 上次进度。 */
    private RiskScanRuntime initial;
    /** Owner 捕获的币对配置引用，Lane 估值只读使用，不在这里深复制配置。 */
    private CoreInstrument instrument;
    /** Owner 捕获且与扫描序号一致的行情记录，交 Lane 估值。 */
    private MarkPriceRuntime mark;
    /** 当前币对结算资产的整数编号，用于合并同资产风险。 */
    private int settleAssetId;
    /** laneMask：账本准备的参与估值 Lane 位集合，Owner 在路由发布后据此派发；creationMask：Owner 根据估值回执整理的新增强平写入 Lane 位集合；nextLiquidationId：Owner 给当前片段分配完需求后得到的下一强平编号。 */
    private long laneMask, creationMask, nextLiquidationId;
    /** nextLane：下一轮从哪个 Lane 开始分配预算，Owner 在本片结束时提交为轮转位置；work：当前片段实际扣减的预算，参与 Lane 或空访问至少各计一个单位。 */
    private int nextLane, work;
    /** 0 捕获本片输入，4 等账本路由，1 等 Lane 估值，2 等 Lane 写入新强平，3 命令完成。 */
    private int phase;

    /**
     * 创建使用持仓用户索引的风险续扫对象，初始化一次各 Lane 缓冲区，再按本命令设置预算。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     * @param positionUsers 按币对和 Lane 定位有持仓用户的索引，避免为每轮风险扫描遍历全体账户
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    public RiskScanCoordinator(int maxWork, PositionUserIndex positionUsers, TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities) {
        if (maxWork <= 0 || maxWork > 4096 || positionUsers == null
                || runtime == null || identities == null) throw new IllegalArgumentException("invalid risk scan budget");
        runtime.assertOwner();
        this.runtime = runtime;
        this.identities = identities;
        this.positionUsers = positionUsers;
        // 运行时配置的账户 Lane 数量，用来一次性创建对应长度的复用数组。
        int lanes = runtime.topology().accountLaneCount();
        inputs = new RiskLaneProgress[lanes];
        results = new RiskLaneProcessor.Page[lanes];
        allocations = new int[lanes];
        userPages = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[lanes];
        for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < lanes; lane++) userPages[lane] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(9);
        resetForCommand(maxWork);
    }

    /**
     * Owner 为下一命令重新设置预算和阶段，复用各 Lane 数组；账本仍持有路由时拒绝复用，旧命令完成或失败回滚后的残留片段在这里丢弃。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     */
    public void resetForCommand(int maxWork) {
        runtime.assertOwner();
        if (maxWork <= 0 || maxWork > 4096) {
            throw new IllegalArgumentException("invalid risk scan budget");
        }
        // 直接命令槽由 Owner 串行复用。续步失败且 Lane 工作已回滚后，
        // 这里可能仍停在阶段 1 或 2；下一命令重置时必须丢弃旧页和旧输入。
        budget = runtime.riskScanControl().enabled()
                ? Math.min(maxWork, adaptiveBudget(runtime.riskScanControl().scanBatchSize(),
                        runtime.incompleteRiskScanCount())) : 0;
        remaining = budget;
        initial = null;
        instrument = null;
        mark = null;
        settleAssetId = 0;
        laneMask = creationMask = nextLiquidationId = 0;
        nextLane = work = 0;
        phase = 0;
    }

    /**
     * 取配置的最低工作量与待扫币对数乘 8 的较大值，再封顶到 4096，使积压币对增加时获得确定且有界的预算。
     * @param minimum 扫描配置保证的最低工作量，范围为 1 到 4096
     * @param pendingInstruments 当前尚未完成风险扫描的币对数量，必须非负
     */
    public static int adaptiveBudget(int minimum, int pendingInstruments) {
        if (minimum < 1 || minimum > 4096 || pendingInstruments < 0)
            throw new IllegalArgumentException("invalid adaptive risk scan budget");
        return (int) Math.min(4096L, Math.max(minimum, pendingInstruments * 8L));
    }

    /**
     * Owner 非阻塞推进已有阶段：准备一片、准备用户页、派发并收集 Lane 估值、给新强平编号并等原 Lane 写入，最后提交游标；预算未用完时继续下一币对片段。
     */
    public boolean poll() {
        runtime.assertOwner();
        if (phase == 3) return true;
        if (phase == 0) {
            // 尚有预算时按公平调度顺序选出的下一币对扫描，没有待扫任务时结束命令。
            RiskScanRuntime selected = remaining == 0 ? null : runtime.firstRiskIncompleteScan();
            if (selected == null) { phase = 3; return true; }
            prepare(selected, Math.min(8, remaining));
            runtime.dispatchLedgerRiskInput(this);
            if (laneMask != 0) runtime.dispatchRiskLanes(laneMask, this::scanLane);
            phase = 1;
        }
        if (laneMask != 0 && !runtime.pollControlLanes()) return false;
        if (phase == 1) {
            for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < inputs.length; lane++)
                if ((laneMask & (1L << lane)) != 0)
                    results[lane] = (RiskLaneProcessor.Page) runtime.controlLaneResult(lane);
            assignLiquidationIds();
            if (creationMask != 0) {
                runtime.dispatchRiskLanes(creationMask, this::applyLiquidations);
                phase = 2;
                return false;
            }
        }
        finish();
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        remaining -= work;
        phase = remaining == 0 || runtime.firstRiskIncompleteScan() == null ? 3 : 0;
        return phase == 3;
    }

    /**
     * 返回本命令已扣除的工作预算，空币对或空 Lane 的访问也至少计一个单位。
     */
    public int completedWork() { return budget - remaining; }

    /**
     * Owner 捕获所选币对当前扫描、配置、匹配序号的行情和结算资产，记录本片预算并重置路由发布标记；没有有效待扫输入时直接报错。
     * @param selected Owner 选中的尚未完成账户风险扫描
     * @param sliceBudget 当前币对片段允许消耗的预算，必须为正
     */
    private void prepare(RiskScanRuntime selected, int sliceBudget) {
        initial = selected;
        if (initial == null || initial.riskComplete() || sliceBudget <= 0)
            throw new IllegalStateException("risk scan is not pending");
        instrument = runtime.instrument(identities.instrumentId(initial.symbolId()));
        mark = runtime.markPrice(initial.symbolId());
        if (instrument == null || mark == null || mark.priceSequence() != initial.priceSequence())
            throw new IllegalStateException("risk scan input is missing");
        settleAssetId = identities.assetId(instrument.settleAsset());
        this.sliceBudget = sliceBudget;
    }

    /**
     * 先发布持仓用户索引输入，再为每个 Lane 准备有界用户页与恢复游标，从上次轮转点给未完成 Lane 分配预算。
     */
    public void applyRoutes() {
        if (ledgerScope != null) ledgerScope.publishRiskPositionInputs();
        // 原扫描已经保存的各 Lane 独立进度；空列表表示新扫描尚未分派。
        List<RiskLaneProgress> cursors = initial.laneProgress();
        if (!cursors.isEmpty() && cursors.size() != inputs.length)
            throw new IllegalStateException("risk scan Lane topology differs");
        laneMask = creationMask = 0;
        work = 0;
        // 尚未完成风险遍历、可以获得本片工作预算的 Lane 数量。
        int participants = 0;
        for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < inputs.length; lane++) {
            // 当前 Lane 的恢复游标，若没有下一用户则可标记为完成。
            RiskLaneProgress cursor = cursors.isEmpty()
                    ? new RiskLaneProgress(0, initial.riskComplete(), 0, 0, "-", 0, 0, 0, 0, 0)
                    : cursors.get(lane);
            // 该 Lane 的复用用户页，清空后只填充本片需要的后续用户及少量前瞻项。
            var page = userPages[lane];
            page.clear();
            // 索引中游标之后的下一用户编号，0 表示没有后续用户。
            long next = positionUsers.higherUserId(instrument.instrumentId(), lane, cursor.lastUserId());
            for (/* 用户页已经加入的编号数量，限制前瞻页最多到片段预算加一项。 */ int count = 0; next != 0 && count <= sliceBudget; count++) {
                page.add(next); next = positionUsers.higherUserId(instrument.instrumentId(), lane, next);
            }
            if (!cursor.complete() && cursor.userId() == 0 && page.isEmpty())
                cursor = new RiskLaneProgress(cursor.lastUserId(), true, 0, 0, "-", 0, 0, 0, 0, 0);
            inputs[lane] = cursor;
            results[lane] = null;
            allocations[lane] = 0;
            if (!cursor.complete()) participants++;
        }
        if (participants == 0) { nextLane = initial.accountLaneId(); publishRoutes(); return; }
        // 本片真正能获得预算的 Lane 数量，不能超过参与数和片段预算。
        int selectedCount = Math.min(participants, sliceBudget);
        // 已经分配到工作量的 Lane 数量，用于均分及分配余数。
        int assigned = 0;
        nextLane = initial.accountLaneId();
        for (/* 从上次轮转点开始的 Lane 偏移量，确保各 Lane 按确定顺序轮流获得预算。 */ int offset = 0; offset < inputs.length && assigned < selectedCount; offset++) {
            // 本轮正在初始化、收集、执行或合并的账户 Lane 下标。
            int lane = (initial.accountLaneId() + offset) % inputs.length;
            if (inputs[lane].complete()) continue;
            allocations[lane] = sliceBudget / selectedCount + (assigned < sliceBudget % selectedCount ? 1 : 0);
            laneMask |= 1L << lane;
            nextLane = (lane + 1) % inputs.length;
            assigned++;
        }
        publishRoutes();
    }

    /**
     * 账本释放当前路由命令引用。
     */
    private void publishRoutes() {
        ledgerScope = null;
    }

    /**
     * 绑定本次账本命令作用域，账本准备路由前用它发布风险持仓索引输入。
     * @param scope 当前账本路由命令的作用域引用
     */
    public void bindLedgerScope(SystemLedgerCommand scope) { ledgerScope = scope; }

    /**
     * 在所属账户 Lane 内读取自己的有界用户页、分配预算和上次修订号，执行估值并返回本 Lane 回执。
     * @param lane 本次准备、估值或提交的账户 Lane 编号
     */
    private RiskLaneProcessor.Page scanLane(int lane) {
        return RiskLaneProcessor.scan(runtime, lane, inputs[lane], userPages[lane],
                instrument, mark, settleAssetId, initial.symbolId(), allocations[lane], identities);
    }

    /**
     * 由原账户 Lane 将 Owner 已经编号的新强平需求逐条写入运行时，本次回调不返回额外结果。
     * @param lane 本次准备、估值或提交的账户 Lane 编号
     */
    private Object applyLiquidations(int lane) {
        results[lane].creations().apply(runtime);
        return null;
    }

    /**
     * Owner 按 Lane 编号从小到大收集工作量，为新增强平需求分配连续编号，结果与线程完成先后无关。
     */
    private void assignLiquidationIds() {
        nextLiquidationId = runtime.nextLiquidationId();
        if (laneMask == 0) work = 1;
        // 固定 Lane 顺序及 Lane 内用户/持仓顺序；不使用线程完成顺序。
        for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < results.length; lane++) {
            if ((laneMask & (1L << lane)) == 0) continue;
            // 当前参与 Lane 的估值回执，提供消耗工作量及新增强平需求。
            RiskLaneProcessor.Page result = results[lane];
            work += Math.max(1, result.workUnits());
            if (result.creations().size() == 0) continue;
            nextLiquidationId = result.creations().assign(nextLiquidationId);
            creationMask |= 1L << lane;
        }
    }

    /**
     * Owner 合并各 Lane 的游标和修订号，提交下一强平编号及轮转位置；旧轮完成但期间来过新价格时，从头安排最新价格的第二轮扫描。
     */
    private void finish() {
        List<RiskLaneProgress> cursors = new ArrayList<>(inputs.length);
        boolean complete = true;
        for (int lane = 0; lane < inputs.length; lane++) {
            RiskLaneProcessor.Page page = results[lane];
            RiskLaneProgress cursor = page == null ? inputs[lane] : page.progress();
            boolean done = cursor.complete();
            long userRevision = cursor.userRevision();
            long marketRevision = page == null ? inputs[lane].marketRevision() : runtime.marketRevision();
            // 只保存各 Lane 自身的游标；没有未完成用户时清除累计值所依据的修订号。
            cursors.add(new RiskLaneProgress(cursor.lastUserId(), done, cursor.userId(), cursor.phase(),
                    cursor.positionCursor(), cursor.reservationCursor(), cursor.unrealizedPnlUnits(),
                    cursor.maintenanceMarginUnits(), cursor.isolatedMarginUnits(), cursor.isolatedReservationUnits(),
                    cursor.userId() == 0 ? 0 : userRevision, cursor.userId() == 0 ? 0 : marketRevision));
            complete &= done;
        }
        if (nextLiquidationId != runtime.nextLiquidationId()) runtime.setNextLiquidationId(nextLiquidationId);
        if (complete) nextLane = inputs.length - 1;
        if (!complete) {
            while (cursors.get(nextLane).complete()) nextLane = (nextLane + 1) % inputs.length;
        }
        RiskScanRuntime next = new RiskScanRuntime(initial.symbolId(), nextLane, initial.priceSequence(),
                initial.scanStartPriceSequence(), complete, initial.triggerComplete(), initial.triggerPhase(),
                initial.triggerPriceCursor(), initial.triggerOrderCursor(), initial.triggerUpperId(),
                initial.triggerMarkPriceTicks(), initial.triggerGeneratedAtEpochMillis(),
                initial.triggerOcoOrderId(), initial.triggerOcoCursor(), initial.lastScheduledRevision(), cursors);
        if (complete && initial.scanStartPriceSequence() != initial.priceSequence()) {
            next = new RiskScanRuntime(initial.symbolId(), 0, initial.priceSequence(), initial.priceSequence(),
                    false, next.triggerComplete(), next.triggerPhase(), next.triggerPriceCursor(),
                    next.triggerOrderCursor(), next.triggerUpperId(), next.triggerMarkPriceTicks(),
                    next.triggerGeneratedAtEpochMillis(), 0, 0, 0, List.of());
        }
        runtime.putRiskScan(next.withLastScheduledRevision(Math.incrementExact(runtime.revision())));
    }
}
