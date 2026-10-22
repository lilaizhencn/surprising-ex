package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.ArrayList;
import java.util.List;

/**
 * 风险扫描的跨线程续步：Owner 固定本片市场输入，账本准备各 Lane 的有界用户页，账户 Lane 估值，Owner 按固定顺序分配强平编号，再交原 Lane 写入并提交游标。异步轮询在其他线程未完成时返回 false。
 */
public final class RiskScanCoordinator implements SystemLedgerLane.Entry {
    /** 本命令固定的交易运行时，任务完成前按既有控制命令边界稳定风险输入。 */
    private final TradingAccountRuntime runtime;
    /** 本命令共用的业务编号映射，Owner 准备路由和 Lane 估值时使用。 */
    private final RuntimeIdentityRegistry identities;
    /** 按币对及 Lane 查询候选持仓用户的索引，可为空以使用另一有序用户来源。 */
    private final PositionUserIndex positionUsers;
    /** 没有持仓用户索引时使用的有序用户集合，由账本按 Lane 筛选。 */
    private final Iterable<Long> indexedUserIds;
    /** 本命令按配置和待扫币对数计算的总工作预算。 */
    private int budget;
    /** 本命令尚未消耗的工作预算，Owner 每片完成后扣减。 */
    private int remaining;
    /** 每个 Lane 唯一的扫描输入槽，账本准备路由后由对应 Lane 只读消费。 */
    private final RiskScanRuntime[] inputs;
    /** Owner 按 Lane 编号收集的估值回执，后续用于编号和合并游标。 */
    private final RiskLaneProcessor.Page[] results;
    /** 账本为各参与 Lane 分配的本片工作量，Lane 估值时读取。 */
    private final int[] allocations;
    /** 每个 Lane 复用的有界用户页，由账本清空填充，发布就绪后交给所属 Lane 读取。 */
    private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] userPages;
    /** 账本已完整写好路由的 volatile 发布标记，Owner 看到 true 后才能消费输入数组。 */
    private volatile boolean routesReady;
    /** Owner 是否已成功把本片路由请求交给账本，避免重复入队。 */
    private boolean routesQueued;
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
        this(maxWork, positionUsers, null, runtime, identities);
    }

    /**
     * 校验 Owner、预算及用户来源，创建按账户 Lane 编号索引的任务和用户页缓冲区；后续命令可以复用这些数组。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     * @param positionUsers 按币对和 Lane 定位有持仓用户的索引，避免为每轮风险扫描遍历全体账户
     * @param indexedUserIds 调用方提供的有序用户编号集合；没有持仓用户索引时据此筛选各 Lane 的候选用户
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    private RiskScanCoordinator(int maxWork, PositionUserIndex positionUsers, Iterable<Long> indexedUserIds,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        if (maxWork <= 0 || maxWork > 4096 || (positionUsers == null && indexedUserIds == null)
                || runtime == null || identities == null) throw new IllegalArgumentException("invalid risk scan budget");
        runtime.assertOwner();
        this.runtime = runtime;
        this.identities = identities;
        this.positionUsers = positionUsers;
        this.indexedUserIds = indexedUserIds;
        // 运行时配置的账户 Lane 数量，用来一次性创建对应长度的复用数组。
        int lanes = runtime.topology().accountLaneCount();
        inputs = new RiskScanRuntime[lanes];
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
        if (routesQueued && !routesReady) throw new IllegalStateException("Ledger still owns risk routes");
        routesReady = routesQueued = false;
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
     * Owner 非阻塞推进已有阶段：准备一片、等账本用户页、派发并收集 Lane 估值、给新强平编号并等原 Lane 写入，最后提交游标；预算未用完时继续下一币对片段。
     */
    public boolean poll() {
        runtime.assertOwner();
        if (phase == 3) return true;
        if (phase == 0) {
            // 尚有预算时按公平调度顺序选出的下一币对扫描，没有待扫任务时结束命令。
            RiskScanRuntime selected = remaining == 0 ? null : runtime.firstRiskIncompleteScan();
            if (selected == null) { phase = 3; return true; }
            prepare(selected, Math.min(8, remaining));
            phase = 4;
        }
        if (phase == 4) {
            if (!routesReady) {
                if (!routesQueued) routesQueued = runtime.dispatchLedgerRiskInput(this);
                if (!routesReady) return false;
            }
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
     * 同步入口复用相同的路由、估值、编号和游标规则；必要时在五秒内取得 Owner 账户访问权，按 Lane 执行后在 finally 释放，再提交本片进度。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     * @param symbolId 要继续扫描的币对整数编号
     * @param positionUsers 按币对和 Lane 定位有持仓用户的索引，避免为每轮风险扫描遍历全体账户
     * @param indexedUserIds 调用方提供的有序用户编号集合；没有持仓用户索引时据此筛选各 Lane 的候选用户
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    static int runSlice(int maxWork, int symbolId, PositionUserIndex positionUsers, Iterable<Long> indexedUserIds,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        // 同步路径本次使用的续扫对象，内部仍按相同 Lane 规则完成一片。
        RiskScanCoordinator c = new RiskScanCoordinator(maxWork, positionUsers, indexedUserIds, runtime, identities);
        c.prepare(runtime.riskScan(symbolId), c.budget);
        c.apply(null);
        // 本同步入口是否需要临时取得账户 Lane 访问权，取得后必须在 finally 释放。
        boolean acquired = runtime.accountLanesStarted() && !runtime.ownerLaneAccess();
        try {
            if (acquired) {
                // 等待 Owner 账户访问权的单调时钟截止值，最多等待五秒。
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!runtime.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() - deadline >= 0) {
                        throw new IllegalStateException("timed out waiting for owner lane access in risk runSlice");
                    }
                    Thread.onSpinWait();
                }
            }
            for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < c.inputs.length; lane++) {
                if ((c.laneMask & (1L << lane)) == 0) continue;
                // 为当前执行回调固定的 Lane 下标，避免循环变量变化影响回调目标。
                final int id = lane;
                c.results[lane] = runtime.executeRiskLane(lane, () -> c.scanLane(id));
            }
            c.assignLiquidationIds();
            for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < c.inputs.length; lane++) {
                if ((c.creationMask & (1L << lane)) == 0) continue;
                // 为当前执行回调固定的 Lane 下标，避免循环变量变化影响回调目标。
                final int id = lane;
                runtime.executeRiskLane(lane, () -> c.applyLiquidations(id));
            }
        } finally {
            if (acquired) runtime.releaseOwnerLaneAccess();
        }
        c.finish();
        return c.work;
    }

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
        routesReady = routesQueued = false;
    }

    /**
     * 先发布持仓用户索引输入，再为每个 Lane 准备有界用户页与恢复游标，从上次轮转点给未完成 Lane 分配预算，最后发布路由就绪。异步路径由账本执行，同步 runSlice 在调用线程直接执行。
     * @param ignored 账本回调统一签名中的资金状态参数；准备用户路由不使用它
     */
    @Override public void apply(TreasuryRuntime ignored) {
        if (ledgerScope != null) ledgerScope.publishRiskPositionInputs();
        // 原扫描已经保存的各 Lane 独立进度，空列表时按标量起点生成。
        List<RiskLaneProgress> cursors = initial.laneProgress();
        if (!cursors.isEmpty() && cursors.size() != inputs.length)
            throw new IllegalStateException("risk scan Lane topology differs");
        laneMask = creationMask = 0;
        work = 0;
        // 尚未完成风险遍历、可以获得本片工作预算的 Lane 数量。
        int participants = 0;
        for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < inputs.length; lane++) {
            // 当前 Lane 的恢复游标，若没有下一用户则可标记为完成。
            RiskLaneProgress cursor = cursors.isEmpty() ? initialCursor(initial, lane) : cursors.get(lane);
            // 该 Lane 的复用用户页，清空后只填充本片需要的后续用户及少量前瞻项。
            var page = userPages[lane];
            page.clear();
            // 索引中游标之后的下一用户编号，0 表示没有后续用户。
            long next = higherIndexedUser(lane, cursor.lastUserId());
            for (/* 用户页已经加入的编号数量，限制前瞻页最多到片段预算加一项。 */ int count = 0; next != 0 && count <= sliceBudget; count++) {
                page.add(next); next = higherIndexedUser(lane, next);
            }
            if (!cursor.complete() && cursor.userId() == 0 && page.isEmpty())
                cursor = new RiskLaneProgress(cursor.lastUserId(), true, 0, 0, "-", 0, 0, 0, 0, 0);
            inputs[lane] = laneInput(initial, lane, cursor);
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
            if (inputs[lane].riskComplete()) continue;
            allocations[lane] = sliceBudget / selectedCount + (assigned < sliceBudget % selectedCount ? 1 : 0);
            laneMask |= 1L << lane;
            nextLane = (lane + 1) % inputs.length;
            assigned++;
        }
        publishRoutes();
    }

    /**
     * 账本释放当前路由命令引用，以 volatile 就绪标记发布用户页和分配结果，再通知 Owner 继续轮询。
     */
    private void publishRoutes() {
        ledgerScope = null;
        // 发布就绪前暂存的运行时引用，完成信号通过它通知 Owner。
        TradingAccountRuntime completion = runtime;
        routesReady = true;
        completion.signalOwnerCompletion();
    }

    /**
     * 绑定本次账本命令作用域，账本准备路由前用它发布风险持仓索引输入。
     * @param scope 当前账本路由命令的作用域引用
     */
    public void bindLedgerScope(SystemLedgerCommand scope) { ledgerScope = scope; }

    /**
     * 优先从币对持仓用户索引取当前 Lane 游标后的用户；没有该索引时要求输入为有序集合，再跳过不属于当前 Lane 的用户，末尾返回 0。
     * @param lane 本次准备、估值或提交的账户 Lane 编号
     * @param cursor 当前 Lane 最后处理完的用户编号，后续从它之后寻找
     */
    private long higherIndexedUser(int lane, long cursor) {
        if (positionUsers != null) return positionUsers.higherUserId(instrument.instrumentId(), lane, cursor);
        if (!(indexedUserIds instanceof java.util.NavigableSet<?>))
            throw new IllegalStateException("risk user index must be ordered for online scanning");
        // 已经确认是可导航有序集合的用户索引，用 higher 取得严格大于游标的用户。
        @SuppressWarnings("unchecked")
        var users = (java.util.NavigableSet<Long>) indexedUserIds;
        for (/* 有序集合中待检查的下一用户，只接受分配到当前 Lane 的编号。 */ Long next = users.higher(cursor); next != null; next = users.higher(next))
            if (runtime.topology().accountLaneId(next) == lane) return next;
        return 0;
    }

    /**
     * 在所属账户 Lane 内读取自己的有界用户页、分配预算和上次修订号，执行估值并返回本 Lane 回执。
     * @param lane 本次准备、估值或提交的账户 Lane 编号
     */
    private RiskLaneProcessor.Page scanLane(int lane) {
        // 该 Lane 上一次完整保存的进度，当前没有新回执时保留其修订号。
        RiskLaneProgress previous = initial.laneProgress().isEmpty()
                ? initialCursor(initial, lane) : initial.laneProgress().get(lane);
        return RiskLaneProcessor.scan(runtime, inputs[lane], userPages[lane],
                instrument, mark, settleAssetId, initial.symbolId(), allocations[lane], identities,
                previous.userRevision(), previous.marketRevision());
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
        // 本片合并后的各 Lane 恢复游标列表，稍后整体写回扫描记录。
        List<RiskLaneProgress> cursors = new ArrayList<>(inputs.length);
        // 只有每个账户 Lane 都完成，该币对的本轮账户风险遍历才算完成。
        boolean complete = true;
        for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < inputs.length; lane++) {
            // 当前 Lane 本片的新回执；未分配预算的 Lane 为 null，保留原输入进度。
            RiskLaneProcessor.Page page = results[lane];
            // 有回执时使用新游标，没有回执时继续使用该 Lane 原输入。
            RiskScanRuntime scan = page == null ? inputs[lane] : page.scan();
            // 当前 Lane 是否已遍历完本轮用户，来源于新回执或保留的完成标记。
            boolean done = page == null ? scan.riskComplete() : page.complete();
            // 该 Lane 上一次完整保存的进度，当前没有新回执时保留其修订号。
            RiskLaneProgress previous = initial.laneProgress().isEmpty()
                    ? initialCursor(initial, lane) : initial.laneProgress().get(lane);
            cursors.add(cursor(scan, done, page == null ? previous.userRevision() : page.userRevision(),
                    page == null ? previous.marketRevision() : runtime.marketRevision()));
            complete &= done;
        }
        if (nextLiquidationId != runtime.nextLiquidationId()) runtime.setNextLiquidationId(nextLiquidationId);
        if (complete) nextLane = inputs.length - 1;
        if (!complete) {
            while (cursors.get(nextLane).complete()) nextLane = (nextLane + 1) % inputs.length;
        }
        // 准备写回的币对扫描记录，标量字段展示下一 Lane，列表保存全部 Lane 的完整进度。
        RiskScanRuntime progress = laneInput(initial, nextLane, cursors.get(nextLane)).withLaneProgress(cursors);
        if (complete) {
            progress = progress.withRiskProgress(true, 0, 0, "-", 0, 0, 0, 0, 0,
                    progress.lastUserId()).withLaneProgress(cursors);
        }
        if (complete && initial.scanStartPriceSequence() != initial.priceSequence()) {
            progress = new RiskScanRuntime(initial.symbolId(), 0, initial.priceSequence(), initial.priceSequence(),
                    0, false, 0, 0, "-", 0, 0, 0, 0, 0, progress.triggerComplete(), progress.triggerPhase(),
                    progress.triggerPriceCursor(), progress.triggerOrderCursor(), progress.triggerUpperId(),
                    progress.triggerMarkPriceTicks(), progress.triggerGeneratedAtEpochMillis(), 0, 0);
        }
        runtime.putRiskScan(progress.withLastScheduledRevision(Math.incrementExact(runtime.revision())));
    }

    /**
     * 尚无独立 Lane 游标时，从标量进度生成各 Lane 起点；此前已越过的 Lane 标为完成，全程不读取账户数据。
     * @param scan 包含账户风险及条件单游标的原扫描记录
     * @param lane 本次准备、估值或提交的账户 Lane 编号
     */
    private static RiskLaneProgress initialCursor(RiskScanRuntime scan, int lane) {
        if (lane == scan.accountLaneId()) return cursor(scan, scan.riskComplete());
        return new RiskLaneProgress(0, lane < scan.accountLaneId() || scan.riskComplete(),
                0, 0, "-", 0, 0, 0, 0, 0);
    }

    /**
     * 把标量扫描字段转成单 Lane 游标，未显式提供的用户和市场修订号记为 0。
     * @param scan 包含账户风险及条件单游标的原扫描记录
     * @param complete 该 Lane 的账户风险遍历是否已完成
     */
    private static RiskLaneProgress cursor(RiskScanRuntime scan, boolean complete) {
        return cursor(scan, complete, 0, 0);
    }

    /**
     * 把当前 Lane 标量进度连同有效修订号封装成恢复游标；没有正在处理的用户时，两项修订号都清零。
     * @param scan 包含账户风险及条件单游标的原扫描记录
     * @param complete 该 Lane 的账户风险遍历是否已完成
     * @param userRevision 当前未完成用户的累计值对应的用户修订号
     * @param marketRevision 当前累计值对应的市场修订号
     */
    private static RiskLaneProgress cursor(RiskScanRuntime scan, boolean complete,
            long userRevision, long marketRevision) {
        return new RiskLaneProgress(scan.lastUserId(), complete, scan.riskUserId(), scan.riskPhase(),
                scan.riskPositionCursor(), scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(),
                scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(),
                scan.riskUserId() == 0 ? 0 : userRevision, scan.riskUserId() == 0 ? 0 : marketRevision);
    }

    /**
     * 将一个 Lane 的恢复游标装入本币对扫描输入，保留原来的行情、条件单及关联撤单进度，供该 Lane 单独处理。
     * @param scan 包含账户风险及条件单游标的原扫描记录
     * @param lane 本次准备、估值或提交的账户 Lane 编号
     * @param cursor 该 Lane 的独立恢复游标，包含未完成用户、阶段和累计金额
     */
    private static RiskScanRuntime laneInput(RiskScanRuntime scan, int lane, RiskLaneProgress cursor) {
        return new RiskScanRuntime(scan.symbolId(), lane, scan.priceSequence(), scan.scanStartPriceSequence(),
                cursor.lastUserId(), cursor.complete(), cursor.userId(), cursor.phase(), cursor.positionCursor(),
                cursor.reservationCursor(), cursor.unrealizedPnlUnits(), cursor.maintenanceMarginUnits(),
                cursor.isolatedMarginUnits(), cursor.isolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor(), scan.lastScheduledRevision());
    }
}
