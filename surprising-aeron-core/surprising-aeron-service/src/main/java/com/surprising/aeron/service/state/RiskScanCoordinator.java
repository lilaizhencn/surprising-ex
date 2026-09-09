package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.RiskLaneProgress;
import java.util.ArrayList;
import java.util.List;

/** owner 侧风险调度：有界分配预算、确定性分配清算编号、提交各 Lane 的可恢复游标。 */
public final class RiskScanCoordinator {
    /** 本命令固定的运行态和索引；命令完成前其他控制命令不能修改风险输入。 */
    private final TradingRuntimeState runtime;
    private final RuntimeIdentityRegistry identities;
    private final PositionUserIndex positionUsers;
    private final Iterable<Long> indexedUserIds;
    /** 全局预算，各轮扣除实际工作（空 Lane 访问也计一个工作单元）。 */
    private final int budget;
    private int remaining;
    /** 每条 Lane 唯一的有界任务槽，仅本控制命令使用。 */
    private final RiskScanRuntime[] inputs;
    private final RiskLaneProcessor.Page[] results;
    private final int[] allocations;
    /** 当前币对片段的不可变输入，Lane 仅读。 */
    private RiskScanRuntime initial;
    private CoreInstrumentState instrument;
    private MarkPriceRuntime mark;
    private int settleAssetId;
    /** 参与集合、新增清算集合及下一轮起点，均由 owner 写入。 */
    private long laneMask, creationMask, nextLiquidationId;
    private int nextLane, work;
    /** 0：准备；1：收集估值；2：收集新增清算写入；3：完成。 */
    private int phase;

    public RiskScanCoordinator(int maxWork, PositionUserIndex positionUsers, TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities) {
        this(maxWork, positionUsers, null, runtime, identities);
    }

    private RiskScanCoordinator(int maxWork, PositionUserIndex positionUsers, Iterable<Long> indexedUserIds,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (maxWork <= 0 || maxWork > 4096 || (positionUsers == null && indexedUserIds == null)
                || runtime == null || identities == null) throw new IllegalArgumentException("invalid risk scan budget");
        runtime.assertOwner();
        this.runtime = runtime;
        this.identities = identities;
        this.positionUsers = positionUsers;
        this.indexedUserIds = indexedUserIds;
        budget = runtime.riskScanControl().enabled()
                ? Math.min(maxWork, runtime.riskScanControl().scanBatchSize()) : 0;
        remaining = budget;
        int lanes = runtime.topology().accountLaneCount();
        inputs = new RiskScanRuntime[lanes];
        results = new RiskLaneProcessor.Page[lanes];
        allocations = new int[lanes];
    }

    /** 一次轮询只推进已就绪阶段；队列未完成立即返回，不等待其他线程。 */
    public boolean poll() {
        runtime.assertOwner();
        if (phase == 3) return true;
        if (phase == 0) {
            RiskScanRuntime selected = remaining == 0 ? null : runtime.firstRiskIncompleteScan();
            if (selected == null) { phase = 3; return true; }
            prepare(selected, Math.min(8, remaining));
            if (laneMask != 0) runtime.dispatchRiskLanes(laneMask, this::scanLane);
            phase = 1;
        }
        if (laneMask != 0 && !runtime.pollControlLanes()) return false;
        if (phase == 1) {
            for (int lane = 0; lane < inputs.length; lane++)
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

    public int completedWork() { return budget - remaining; }

    /** 同步调用与集群异步路径共享分片、编号和游标规则。 */
    static int runSlice(int maxWork, int symbolId, PositionUserIndex positionUsers, Iterable<Long> indexedUserIds,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        RiskScanCoordinator c = new RiskScanCoordinator(maxWork, positionUsers, indexedUserIds, runtime, identities);
        c.prepare(runtime.riskScan(symbolId), c.budget);
        for (int lane = 0; lane < c.inputs.length; lane++) {
            if ((c.laneMask & (1L << lane)) == 0) continue;
            final int id = lane;
            c.results[lane] = runtime.executeRiskLane(lane, () -> c.scanLane(id));
        }
        c.assignLiquidationIds();
        for (int lane = 0; lane < c.inputs.length; lane++) {
            if ((c.creationMask & (1L << lane)) == 0) continue;
            final int id = lane;
            runtime.executeRiskLane(lane, () -> c.applyLiquidations(id));
        }
        c.finish();
        return c.work;
    }

    private void prepare(RiskScanRuntime selected, int sliceBudget) {
        initial = selected;
        if (initial == null || initial.riskComplete() || sliceBudget <= 0)
            throw new IllegalStateException("risk scan is not pending");
        instrument = runtime.instrument(identities.symbol(initial.symbolId()));
        mark = runtime.markPrice(initial.symbolId());
        if (instrument == null || mark == null || mark.priceSequence() != initial.priceSequence())
            throw new IllegalStateException("risk scan input is missing");
        settleAssetId = identities.assetId(instrument.settleAsset());
        List<RiskLaneProgress> cursors = initial.laneProgress();
        if (!cursors.isEmpty() && cursors.size() != inputs.length)
            throw new IllegalStateException("risk scan Lane topology differs");
        laneMask = creationMask = 0;
        work = 0;
        int participants = 0;
        for (int lane = 0; lane < inputs.length; lane++) {
            RiskLaneProgress cursor = cursors.isEmpty() ? initialCursor(initial, lane) : cursors.get(lane);
            if (!cursor.complete() && cursor.userId() == 0
                    && !hasNextIndexedUser(lane, cursor.lastUserId()))
                cursor = new RiskLaneProgress(cursor.lastUserId(), true, 0, 0, "-", 0, 0, 0, 0, 0);
            inputs[lane] = laneInput(initial, lane, cursor);
            results[lane] = null;
            allocations[lane] = 0;
            if (!cursor.complete()) participants++;
        }
        if (participants == 0) { nextLane = initial.accountLaneId(); return; }
        int selectedCount = Math.min(participants, sliceBudget);
        int assigned = 0;
        nextLane = initial.accountLaneId();
        for (int offset = 0; offset < inputs.length && assigned < selectedCount; offset++) {
            int lane = (initial.accountLaneId() + offset) % inputs.length;
            if (inputs[lane].riskComplete()) continue;
            allocations[lane] = sliceBudget / selectedCount + (assigned < sliceBudget % selectedCount ? 1 : 0);
            laneMask |= 1L << lane;
            nextLane = (lane + 1) % inputs.length;
            assigned++;
        }
    }

    private boolean hasNextIndexedUser(int lane, long cursor) {
        if (positionUsers != null) return positionUsers.higherUserId(instrument.symbol(), lane, cursor) != 0;
        if (!(indexedUserIds instanceof java.util.NavigableSet<?>))
            throw new IllegalStateException("risk user index must be ordered for online scanning");
        @SuppressWarnings("unchecked")
        var users = (java.util.NavigableSet<Long>) indexedUserIds;
        for (Long next = users.higher(cursor); next != null; next = users.higher(next))
            if (runtime.topology().accountLaneId(next) == lane) return true;
        return false;
    }

    private RiskLaneProcessor.Page scanLane(int lane) {
        return RiskLaneProcessor.scan(runtime, inputs[lane], positionUsers, indexedUserIds,
                instrument, mark, settleAssetId, initial.symbolId(), allocations[lane], identities);
    }

    private Object applyLiquidations(int lane) {
        results[lane].creations().apply(runtime);
        return null;
    }

    private void assignLiquidationIds() {
        nextLiquidationId = runtime.nextLiquidationId();
        if (laneMask == 0) work = 1;
        // 固定 Lane 顺序及 Lane 内用户/持仓顺序；不使用线程完成顺序。
        for (int lane = 0; lane < results.length; lane++) {
            if ((laneMask & (1L << lane)) == 0) continue;
            RiskLaneProcessor.Page result = results[lane];
            work += Math.max(1, result.workUnits());
            if (result.creations().size() == 0) continue;
            nextLiquidationId = result.creations().assign(nextLiquidationId);
            creationMask |= 1L << lane;
        }
    }

    private void finish() {
        List<RiskLaneProgress> cursors = new ArrayList<>(inputs.length);
        boolean complete = true;
        for (int lane = 0; lane < inputs.length; lane++) {
            RiskLaneProcessor.Page page = results[lane];
            RiskScanRuntime scan = page == null ? inputs[lane] : page.scan();
            boolean done = page == null ? scan.riskComplete() : page.complete();
            cursors.add(cursor(scan, done));
            complete &= done;
        }
        if (nextLiquidationId != runtime.nextLiquidationId()) runtime.setNextLiquidationId(nextLiquidationId);
        if (complete) nextLane = inputs.length - 1;
        if (!complete) {
            while (cursors.get(nextLane).complete()) nextLane = (nextLane + 1) % inputs.length;
        }
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

    /** 尚未分派的扫描由起点生成 Lane 游标，不读取账户数据。 */
    private static RiskLaneProgress initialCursor(RiskScanRuntime scan, int lane) {
        if (lane == scan.accountLaneId()) return cursor(scan, scan.riskComplete());
        return new RiskLaneProgress(0, lane < scan.accountLaneId() || scan.riskComplete(),
                0, 0, "-", 0, 0, 0, 0, 0);
    }

    private static RiskLaneProgress cursor(RiskScanRuntime scan, boolean complete) {
        return new RiskLaneProgress(scan.lastUserId(), complete, scan.riskUserId(), scan.riskPhase(),
                scan.riskPositionCursor(), scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(),
                scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits());
    }

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
