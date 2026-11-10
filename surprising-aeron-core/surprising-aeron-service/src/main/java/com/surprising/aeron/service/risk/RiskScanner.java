package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class RiskScanner {
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
    private final Page[] results;
    /** 账本为各参与 Lane 分配的本片工作量，Lane 估值时读取。 */
    private final int[] allocations;
    /** 每个 Lane 复用的有界用户页，由账本清空填充，发布就绪后交给所属 Lane 读取。 */
    private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] userPages;
    /** 当前币对片段最多可分给各 Lane 的工作预算。 */
    private int sliceBudget;
    /** Owner 选中的本片原扫描记录，含行情序号及各 Lane 上次进度。 */
    private RiskScanner.State initial;
    /** Owner 捕获的币对配置引用，Lane 估值只读使用，不在这里深复制配置。 */
    private CoreInstrument instrument;
    /** Owner 捕获且与扫描序号一致的行情记录，交 Lane 估值。 */
    private MarkPrice mark;
    /** 当前币对结算资产的整数编号，用于合并同资产风险。 */
    private int settleAssetId;
    /** laneMask：账本准备的参与估值 Lane 位集合，Owner 在路由发布后据此派发；creationMask：Owner 根据估值回执整理的新增强平写入 Lane 位集合；nextLiquidationId：Owner 给当前片段分配完需求后得到的下一强平编号。 */
    private long laneMask, creationMask, nextLiquidationId;
    /** nextLane：下一轮从哪个 Lane 开始分配预算，Owner 在本片结束时提交为轮转位置；work：当前片段实际扣减的预算，参与 Lane 或空访问至少各计一个单位。 */
    private int nextLane, work;
    /** 0 捕获本片输入，1 等 Lane 估值，2 等 Lane 写入新强平，3 命令完成。 */
    private int phase;

    /**
     * 创建使用持仓用户索引的风险续扫对象，初始化一次各 Lane 缓冲区，再按本命令设置预算。
     * @param maxWork 调用方允许的工作量上限，范围为 1 到 4096，实际还受扫描控制配置限制
     * @param positionUsers 按币对和 Lane 定位有持仓用户的索引，避免为每轮风险扫描遍历全体账户
     * @param runtime 本次命令操作的交易运行时，Owner、账户 Lane 和账本各自遵守状态所有权
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和持仓
     */
    public RiskScanner(int maxWork, PositionUserIndex positionUsers, TradingAccountRuntime runtime,
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
        results = new Page[lanes];
        allocations = new int[lanes];
        userPages = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[lanes];
        for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < lanes; lane++) userPages[lane] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(9);
        resetForCommand(maxWork);
    }

    /**
     * Owner 为下一命令重新设置预算和阶段，复用各 Lane 数组，丢弃旧命令完成或失败回滚后的残留片段。
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
            RiskScanner.State selected = remaining == 0 ? null : runtime.firstRiskIncompleteScan();
            if (selected == null) { phase = 3; return true; }
            prepare(selected, Math.min(8, remaining));
            runtime.publishRiskPositionInputs();
            prepareRoutes();
            if (laneMask != 0) runtime.dispatchRiskLanes(laneMask, lane ->
                    scan(runtime, lane, inputs[lane], userPages[lane],
                            instrument, mark, settleAssetId, initial.symbolId(), allocations[lane], identities));
            phase = 1;
        }
        if (laneMask != 0 && !runtime.pollControlLanes()) return false;
        if (phase == 1) {
            for (/* 本轮正在初始化、收集、执行或合并的账户 Lane 下标。 */ int lane = 0; lane < inputs.length; lane++)
                if ((laneMask & (1L << lane)) != 0)
                    results[lane] = (Page) runtime.controlLaneResult(lane);
            assignLiquidationIds();
            if (creationMask != 0) {
                runtime.dispatchRiskLanes(creationMask, lane -> {
                    results[lane].creations().apply(runtime);
                    return null;
                });
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
    private void prepare(RiskScanner.State selected, int sliceBudget) {
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
    private void prepareRoutes() {
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
        if (participants == 0) { nextLane = initial.accountLaneId(); return; }
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
            Page result = results[lane];
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
            Page page = results[lane];
            RiskLaneProgress cursor = page == null ? inputs[lane] : page.progress();
            cursors.add(cursor);
            complete &= cursor.complete();
        }
        if (nextLiquidationId != runtime.nextLiquidationId()) runtime.setNextLiquidationId(nextLiquidationId);
        if (complete) nextLane = inputs.length - 1;
        if (!complete) {
            while (cursors.get(nextLane).complete()) nextLane = (nextLane + 1) % inputs.length;
        }
        long scheduledRevision = Math.incrementExact(runtime.revision());
        RiskScanner.State next;
        if (complete && initial.scanStartPriceSequence() != initial.priceSequence()) {
            next = new RiskScanner.State(initial.symbolId(), 0, initial.priceSequence(), initial.priceSequence(),
                    false, initial.triggerComplete(), initial.triggerPhase(), initial.triggerPriceCursor(),
                    initial.triggerOrderCursor(), initial.triggerUpperId(), initial.triggerMarkPriceTicks(),
                    initial.triggerGeneratedAtEpochMillis(), 0, 0, scheduledRevision, List.of());
        } else {
            next = new RiskScanner.State(initial.symbolId(), nextLane, initial.priceSequence(),
                    initial.scanStartPriceSequence(), complete, initial.triggerComplete(), initial.triggerPhase(),
                    initial.triggerPriceCursor(), initial.triggerOrderCursor(), initial.triggerUpperId(),
                    initial.triggerMarkPriceTicks(), initial.triggerGeneratedAtEpochMillis(),
                    initial.triggerOcoOrderId(), initial.triggerOcoCursor(), scheduledRevision, cursors);
        }
        runtime.putRiskScan(next);
    }


    static Page scan(TradingAccountRuntime runtime, int laneId, RiskLaneProgress initial,
                                        LongArrayList indexedUserIds,
                                        CoreInstrument changedInstrument, MarkPrice changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RuntimeIdentityRegistry identities) {
        // 当前 Lane 可复用的本批强平需求容器，容量受本次工作预算限制。
        RiskScanner.LiquidationBatch creations = runtime.riskLiquidationBatch(maxWork);
        // 本批正在推进的扫描游标，每处理完一个用户片段就接收新进度。
        RiskLaneProgress progress = initial;
        // 本批还剩多少工作预算，任何用户处理至少扣一个单位。
        int remaining = maxWork;
        // 当前用户这份累计结果所依据的最新修订号，随页回执交给 Owner 保存。
        long userRevision = initial.userRevision();
        long expectedMarketRevision = initial.marketRevision();
        while (remaining > 0) {
            // 沿旧用户游标继续或从有序页选出的用户，记录不存在时本 Lane 这一页结束。
            UserAccount user = progress.userId() == 0
                    ? nextUser(runtime, indexedUserIds, progress.lastUserId())
                    : runtime.user(progress.userId());
            if (user == null) {
                return finishPage(progress, maxWork - remaining, true, creations);
            }
            if (progress.userId() == 0 || user.revision() != userRevision
                    || expectedMarketRevision != runtime.marketRevision()) {
                progress = new RiskLaneProgress(progress.lastUserId(), false, user.userId(), 0, "-", 0,
                        0, 0, 0, 0, user.revision(), runtime.marketRevision());
            }
            userRevision = user.revision();
            expectedMarketRevision = runtime.marketRevision();
            // 本次处理一个用户得到的游标、消耗工作量及是否完成的回执。
            UserPage page = processUser(runtime, laneId, progress, user, changedInstrument, changedMark,
                    settleAssetId, changedSymbolId, remaining, creations, identities);
            progress = page.progress();
            // 本次从预算中扣除的工作量，最少为 1，避免空记录使循环无法推进。
            int consumed = Math.max(1, page.workUnits());
            remaining -= consumed;
        }
        // 没有未完成用户且有序页中也无下一用户时，才表明本 Lane 当前遍历已结束。
        boolean complete = progress.userId() == 0
                && nextUser(runtime, indexedUserIds, progress.lastUserId()) == null;
        return finishPage(progress, maxWork - remaining, complete, creations);
    }

    /**
     * 按三个阶段继续当前用户：0 累计同资产持仓风险并重算变化合约的逐仓风险，1 累计逐仓委托冻结，2 用共享权益写入各全仓持仓风险；预算耗尽时保存完整游标。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param laneId 当前任务所属账户 Lane
     * @param scan 本次分批处理的风险游标及账户累计值
     * @param user 正在估值的当前 Lane 用户记录
     * @param changedInstrument 本轮价格变化的合约配置，逐仓只重算该合约，全仓按结算资产合并估值
     * @param changedMark 本轮变化合约对应的固定行情输入，逐仓估值使用它
     * @param settleAssetId 本轮需要合并全仓风险的结算资产整数编号
     * @param changedSymbolId 本轮价格变化的币对整数编号，用来限定逐仓重算范围
     * @param maxWork 本批最多处理的工作项数量，用户遍历至少消耗一个单位以保证能退出
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     */
    private static UserPage processUser(TradingAccountRuntime runtime, int laneId,
                                        RiskLaneProgress scan, UserAccount user,
                                        CoreInstrument changedInstrument, MarkPrice changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RiskScanner.LiquidationBatch creations,
                                        RuntimeIdentityRegistry identities) {
        // 用户估值所处阶段：0 扫持仓，1 扫逐仓委托冻结，2 写全仓风险。
        int phase = scan.phase();
        // 当前阶段最后处理的持仓业务键，换阶段时重置为短横线。
        String positionCursor = scan.positionCursor();
        // 当前用户最后处理的冻结委托编号，便于预算用完后继续。
        long reservationCursor = scan.reservationCursor();
        // 同一结算资产下已累计的全仓权益变化，期货用浮盈亏，期权用市值。
        long unrealized = scan.unrealizedPnlUnits();
        // 同一结算资产下已累计的全仓维持保证金，后续用它判断共享风险。
        long maintenance = scan.maintenanceMarginUnits();
        // 该资产已累计的逐仓持仓保证金，不能再次计入全仓共享钱包。
        long isolatedMargin = scan.isolatedMarginUnits();
        // 该资产已累计的逐仓委托冻结，计算全仓钱包时还要扣掉这部分。
        long isolatedReservation = scan.isolatedReservationUnits();
        // 本页按总余额扣逐仓占用后得到的全仓钱包；最小 long 表示尚未计算。
        long wallet = Long.MIN_VALUE;
        // 当前账户 Lane 复用的估值暂存区，读取单个持仓后覆盖计算结果。
        PositionRiskScratch positionRisk = runtime.accountLane(laneId).positionRiskScratch();
        // 同一条常驻 Lane 线程可能在旧扫描失效后继续处理新价格。
        // 把小型行情缓存绑定到当前市场修订，避免继续使用旧标记价。
        positionRisk.scanMarketRevision = runtime.marketRevision();
        // 本用户片段已经处理的持仓或冻结记录数量，达到上限就保存继续游标。
        int work = 0;
        while (work < maxWork) {
            if (phase == 0) {
                // 游标后下一份持仓的运行时整数键，0 表示当前持仓遍历到末尾。
                long positionIdentity = runtime.nextRiskPositionKey(user.userId(), positionCursor, identities);
                if (positionIdentity == 0) {
                    phase = 1;
                    positionCursor = "-";
                    continue;
                }
                positionCursor = identities.positionKey(user.userId(), positionIdentity);
                // 本轮要累加或写风险的 Lane 持仓，空仓和不属于本结算资产的记录按阶段跳过。
                Position position = runtime.position(positionIdentity);
                work++;
                if (position.signedQuantitySteps() == 0) continue;
                if (position.marginMode() == CoreMarginMode.ISOLATED) {
                    if (position.assetId() == settleAssetId) {
                        isolatedMargin = Math.addExact(isolatedMargin, position.positionMarginUnits());
                    }
                    if (position.symbolId() == changedSymbolId) {
                        updateIsolated(runtime, user.userId(), positionCursor, position,
                                changedInstrument, changedMark, creations, identities);
                    }
                    continue;
                }
                if (position.assetId() != settleAssetId) continue;
                if (!risk(runtime, position, identities, positionRisk)) continue;
                unrealized = Math.addExact(unrealized, positionRisk.equityDelta);
                maintenance = Math.addExact(maintenance, positionRisk.maintenance);
                continue;
            }
            if (phase == 1) {
                // 游标后的下一份冻结委托，遍历结束后才能进入全仓风险写入阶段。
                OrderFundsReservation reservation = nextReservation(runtime, user.userId(), reservationCursor);
                if (reservation == null) {
                    if (maintenance == 0) {
                        return new UserPage(new RiskLaneProgress(user.userId(), false, 0, 0, "-", 0, 0, 0, 0, 0),
                                work);
                    }
                    phase = 2;
                    positionCursor = "-";
                    continue;
                }
                reservationCursor = reservation.orderId();
                work++;
                // 冻结委托对应的订单，读取它的保证金模式判断是否属于逐仓占用。
                Order order = runtime.order(reservation.orderId());
                if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                        && reservation.assetId() == settleAssetId) {
                    isolatedReservation = Math.addExact(isolatedReservation, reservation.reservedUnits());
                }
                continue;
            }
            // 游标后下一份持仓的运行时整数键，0 表示当前持仓遍历到末尾。
            long positionIdentity = runtime.nextRiskPositionKey(user.userId(), positionCursor, identities);
            if (positionIdentity == 0) {
                return new UserPage(new RiskLaneProgress(user.userId(), false, 0, 0, "-", 0, 0, 0, 0, 0),
                        work);
            }
            positionCursor = identities.positionKey(user.userId(), positionIdentity);
            // 本轮要累加或写风险的 Lane 持仓，空仓和不属于本结算资产的记录按阶段跳过。
            Position position = runtime.position(positionIdentity);
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || position.assetId() != settleAssetId) continue;
            if (!risk(runtime, position, identities, positionRisk)) continue;
            // 当前 Lane 处理这一页时，账户余额和逐仓占用保持在同一所有权范围。
            // 每页只计算一次全仓钱包，供该用户后续全仓持仓复用。
            if (wallet == Long.MIN_VALUE) {
                // 当前用户在本结算资产的账户余额，用可用加冻结得出钱包总额。
                AccountBalance balance = runtime.balance(user.userId(), settleAssetId);
                // 该资产的可用与冻结余额之和，尚未扣除逐仓持仓和逐仓委托占用。
                long total = balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
                wallet = Math.subtractExact(Math.subtractExact(total, isolatedMargin), isolatedReservation);
                if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            }
            // 全仓钱包加同资产全仓持仓估值变化后的共享权益。
            long equity = Math.addExact(wallet, unrealized);
            // 本次维持保证金相对权益的 ppm 风险占比，用于正常、预警或强平判级。
            long ratio = riskRatio(maintenance, equity);
            putRiskAndLiquidation(runtime, user.userId(), positionCursor, position,
                    positionRisk.instrument, positionRisk.priceSequence, equity, positionRisk.unrealized,
                    positionRisk.maintenance, ratio, creations, identities);
        }
        return new UserPage(new RiskLaneProgress(scan.lastUserId(), false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.userRevision(), scan.marketRevision()), work);
    }

    /**
     * 仅用这份逐仓持仓的保证金和估值变化计算权益，再写风险记录及强平需求；期货使用浮盈亏，期权使用带方向市值。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param userId 当前被估值或需要定位持仓、冻结记录的用户编号
     * @param positionKey 用户持仓的业务键，净持仓用合约编号，双向持仓还区分持仓腿
     * @param position 当前 Lane 持有的目标持仓对象，数量及金额按该对象最新值计算
     * @param instrument 当前持仓所属合约的类型、精度和保证金配置
     * @param mark 当前持仓估值采用的行情，包括标记价、指数价、远期价和价格序号
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     */
    private static void updateIsolated(TradingAccountRuntime runtime, long userId, String positionKey,
                                       Position position, CoreInstrument instrument,
                                       MarkPrice mark, RiskScanner.LiquidationBatch creations,
                                       RuntimeIdentityRegistry identities) {
        // 当前逐仓持仓在新标记价下的浮盈亏，写风险记录时单独保留。
        long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks(), instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
        // 按当前数量、风险档位和行情算出的逐仓维持保证金。
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        // 逐仓权益的估值变化，期货使用浮盈亏，期权使用带方向市值。
        long equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        // 这份逐仓持仓自己的保证金加估值变化，不混入其他账户资金。
        long equity = Math.addExact(position.positionMarginUnits(), equityDelta);
        // 本次维持保证金相对权益的 ppm 风险占比，用于正常、预警或强平判级。
        long ratio = riskRatio(maintenance, equity);
        putRiskAndLiquidation(runtime, userId, positionKey, position, instrument, mark.priceSequence(), equity,
                unrealized, maintenance, ratio, creations, identities);
    }

    /**
     * 风险事实有变化才写新估值；结算或关闭中的合约不新建强平。其余持仓不再危险时取消尚未执行的计划，仍危险时刷新计划，尚无计划时加入待编号需求。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param userId 当前被估值或需要定位持仓、冻结记录的用户编号
     * @param positionKey 用户持仓的业务键，净持仓用合约编号，双向持仓还区分持仓腿
     * @param position 当前 Lane 持有的目标持仓对象，数量及金额按该对象最新值计算
     * @param instrument 当前持仓所属合约的类型、精度和保证金配置
     * @param priceSequence 本次风险结果对应的行情序号，也作为强平计划的触发价格版本
     * @param equity 本次风险比较采用的权益金额，单位为结算资产最小单位
     * @param unrealized 该持仓在当前标记价下的未实现盈亏，单位为结算资产最小单位
     * @param maintenance 维持持仓所需的保证金，单位为结算资产最小单位
     * @param ratio 维持保证金相对权益的风险占比，按一百万倍保存
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     */
    private static void putRiskAndLiquidation(TradingAccountRuntime runtime, long userId, String positionKey,
                                              Position position, CoreInstrument instrument,
                                              long priceSequence, long equity, long unrealized, long maintenance,
                                              long ratio, RiskScanner.LiquidationBatch creations,
                                              RuntimeIdentityRegistry identities) {
        // 根据风险占比选出的正常、预警或强平结论。
        CoreRiskStatus status = RiskMarginCalculator.status(ratio);
        // 当前持仓的币对整数编号，定位已有强平及行情缓存时使用。
        int symbolId = position.symbolId();
        // 该用户、币对和持仓腿对应的风险记录整数键。
        long riskKey = identities.preparedPositionKey(userId, positionKey);
        // 这份持仓上一次发布的风险记录，所有风险事实相同就继续复用它。
        RiskMarginCalculator.Snapshot previousSnapshot = runtime.riskSnapshot(riskKey);
        // 分批继续或新一轮扫描可能再次遇到没有变化的持仓。
        // 风险事实相同就保留原风险记录，省去新记录和变更缓冲区写入。
        if (previousSnapshot == null || previousSnapshot.priceSequence() != priceSequence
                || previousSnapshot.equityUnits() != equity
                || previousSnapshot.unrealizedPnlUnits() != unrealized
                || previousSnapshot.maintenanceMarginUnits() != maintenance
                || previousSnapshot.marginRatioPpm() != ratio
                || previousSnapshot.positionSide() != position.positionSide()) {
            runtime.putRiskSnapshot(riskKey, new RiskMarginCalculator.Snapshot(userId, symbolId, position.positionSide(),
                    priceSequence, equity, unrealized, maintenance, ratio));
        }
        // 到期结算流程按已批准价格处理剩余持仓。这里仍保留风险估值，
        // 但结算分批期间不再为同一持仓创建竞争的强平流程。
        if (instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                || instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
            return;
        }
        // 该用户当前持仓腿已有的活动强平记录，已进入执行的流程不在这里重建。
        RiskScanner.LiquidationOrder active = runtime.activeLiquidation(userId, symbolId, position.positionSide());
        if (status != CoreRiskStatus.LIQUIDATION
                || !RiskMarginCalculator.canLiquidate(instrument.contractType(), position.signedQuantitySteps())) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new RiskScanner.LiquidationOrder(active.liquidationId(), active.userId(),
                        active.symbolId(), active.marginMode(), active.positionSide(), active.instrument(),
                        active.triggerPriceSequence(), active.signedQuantitySteps(), active.closeQuantitySteps(),
                        0, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
            }
            return;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                // 刷新尚未执行计划时读取的最新带方向持仓步数。
                long quantity = position.signedQuantitySteps();
                if (active.userId() != userId || active.symbolId() != symbolId
                        || active.marginMode() != position.marginMode()
                        || active.positionSide() != position.positionSide()
                        || active.instrument() != instrument
                        || active.triggerPriceSequence() != priceSequence
                        || active.signedQuantitySteps() != quantity
                        || active.closeQuantitySteps() != Math.absExact(quantity)) {
                    runtime.replaceLiquidation(new RiskScanner.LiquidationOrder(active.liquidationId(), userId, symbolId,
                            position.marginMode(), position.positionSide(), instrument, priceSequence,
                            quantity, Math.absExact(quantity), 0, 0, 0, 0,
                            CoreLiquidationState.Status.PLANNED, 0));
                }
            }
            return;
        }
        creations.add(position, priceSequence);
    }

    /**
     * 取得当前持仓的配置和行情，必要时刷新 Lane 内市场缓存，再算浮盈亏、权益变化和维持保证金；缺少配置或行情时返回 false，调用方跳过该持仓。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param position 当前 Lane 持有的目标持仓对象，数量及金额按该对象最新值计算
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     * @param result 本 Lane 复用的持仓估值暂存区，计算成功后覆盖其中的市场与金额字段
     */
    private static boolean risk(TradingAccountRuntime runtime, Position position,
                                RuntimeIdentityRegistry identities, PositionRiskScratch result) {
        // 当前持仓的币对整数编号，定位已有强平及行情缓存时使用。
        int symbolId = position.symbolId();
        // 暂存区内的配置引用；币对、市场修订或运行时变化时重新取得配置。
        CoreInstrument instrument = result.instrument;
        // 暂存区内的行情引用；缓存失效后与配置一起从当前运行时重新读取。
        MarkPrice mark = result.mark;
        if (instrument == null || mark == null || result.symbolId != symbolId
                || instrument != position.instrument()
                || result.marketRevision != result.scanMarketRevision
                || result.runtime != runtime) {
            instrument = runtime.instrument(identities.instrumentId(symbolId));
            mark = runtime.markPrice(symbolId);
            result.symbolId = symbolId;
            result.instrument = instrument;
            result.mark = mark;
            result.marketRevision = result.scanMarketRevision;
            result.runtime = runtime;
        }
        if (instrument == null || mark == null) return false;
        // 本持仓在有效行情标记价下的未实现盈亏，随后写入暂存区供调用方使用。
        long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks(), instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
        result.instrument = instrument;
        result.priceSequence = mark.priceSequence();
        result.unrealized = unrealized;
        result.equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        result.maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        return true;
    }

    /**
     * 把维持保证金除以权益后放大一百万倍；不需维持保证金时为 0，权益不为正或中间乘法溢出时用最大 long 表示极高风险。
     * @param maintenance 维持持仓所需的保证金，单位为结算资产最小单位
     * @param equity 本次风险比较采用的权益金额，单位为结算资产最小单位
     */
    private static long riskRatio(long maintenance, long equity) {
        if (maintenance <= 0) return 0;
        if (equity <= 0) return Long.MAX_VALUE;
        try {
            return Math.multiplyExact(maintenance, 1_000_000L) / equity;
        } catch (/* 风险占比放大一百万倍时发生整数溢出，按极高风险返回最大 long。 */ ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 在有序用户页中二分定位游标，只返回其后的下一用户；页尾没有后续用户时返回 null。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param users 按用户编号升序排列的当前 Lane 候选用户页
     * @param cursor 最后处理的用户或记录位置，查找时只返回它后面的下一项
     */
    private static UserAccount nextUser(TradingAccountRuntime runtime, LongArrayList users, long cursor) {
        // 用户游标在有序页中的位置，命中时向后一项，未命中时取插入点。
        int slot = users.binarySearch(cursor);
        slot = slot >= 0 ? slot + 1 : -slot - 1;
        return slot == users.size() ? null : runtime.user(users.get(slot));
    }

    /**
     * 沿用户冻结委托索引寻找游标后的下一笔，0 编号代表没有更多记录并转换为 null。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param userId 当前被估值或需要定位持仓、冻结记录的用户编号
     * @param cursor 最后处理的冻结委托编号，0 表示尚未开始
     */
    private static OrderFundsReservation nextReservation(TradingAccountRuntime runtime, long userId, long cursor) {
        // 下一份冻结委托的编号，0 表示已没有后续冻结记录。
        long id = runtime.nextRiskReservationId(userId, cursor);
        return id == 0 ? null : runtime.reservation(id);
    }

    /**
     * 一个账户 Lane 复用的单持仓估值暂存区，处理下一持仓时覆盖；只缓存当前币对的配置和行情，跨市场修订或跨运行时不能沿用旧缓存。
     */
    public static final class PositionRiskScratch {
        /** 缓存币对的配置引用，用来换算价格、数量和保证金。 */
        private CoreInstrument instrument;
        /** 缓存币对本次采用的行情引用，不能跨失效的市场修订继续用。 */
        private MarkPrice mark;
        /** 缓存币对的整数编号，-1 表示尚未缓存任何币对。 */
        private int symbolId = -1;
        /** 缓存行情实际对应的市场修订号，初始最小 long 表示尚未建立缓存。 */
        private long marketRevision = Long.MIN_VALUE;
        /** 本次扫描要求的市场修订号，进入用户处理时刷新，用来识别旧行情缓存。 */
        private long scanMarketRevision = Long.MIN_VALUE;
        /** 缓存所对应的运行时引用，防止同一暂存区误用另一运行时的市场数据。 */
        private TradingAccountRuntime runtime;
        /** 当前成功估值使用的行情序号，写风险记录和强平计划时带上它。 */
        private long priceSequence;
        /** 当前持仓的未实现盈亏，单位为结算资产最小整数单位。 */
        private long unrealized;
        /** 当前持仓所需维持保证金，单位为结算资产最小整数单位。 */
        private long maintenance;
        /** 当前持仓计入权益的变化额，期货是未实现盈亏，期权是带方向市值。 */
        private long equityDelta;
    }

    /** 将本批完成标记和估值版本写回唯一的 Lane 进度；没有未完成用户时不保留失效版本。 */
    private static Page finishPage(RiskLaneProgress progress, int workUnits, boolean complete,
            RiskScanner.LiquidationBatch creations) {
        if (progress.complete() == complete) return new Page(progress, workUnits, creations);
        var result = new RiskLaneProgress(progress.lastUserId(), complete, progress.userId(), progress.phase(),
                progress.positionCursor(), progress.reservationCursor(), progress.unrealizedPnlUnits(),
                progress.maintenanceMarginUnits(), progress.isolatedMarginUnits(), progress.isolatedReservationUnits(),
                progress.userRevision(), progress.marketRevision());
        return new Page(result, workUnits, creations);
    }

    /**
     * 一个账户 Lane 本批扫描的回执，Owner 收集后提交各 Lane 游标，并按固定顺序给新增强平需求分配编号。
     * @param progress 本次分批处理的风险游标及账户累计值
     * @param workUnits 本批实际处理的工作项数量，Owner 用它扣减共享预算
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     */
    record Page(RiskLaneProgress progress, int workUnits, RiskScanner.LiquidationBatch creations) { }
    /**
     * 单个用户在本批内的估值进度及工作量，用来区分用户已经算完与预算耗尽后还要继续两种情况。
     * @param progress 本次分批处理的风险游标及账户累计值
     * @param workUnits 本批实际处理的工作项数量，Owner 用它扣减共享预算
     */
    private record UserPage(RiskLaneProgress progress, int workUnits) { }


    public record State(
        int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
        boolean riskComplete, boolean triggerComplete, int triggerPhase,
        long triggerPriceCursor, long triggerOrderCursor, long triggerUpperId,
        long triggerMarkPriceTicks, long triggerGeneratedAtEpochMillis,
        long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision,
        List<RiskLaneProgress> laneProgress) {
    public State {
        laneProgress = List.copyOf(laneProgress);
        if (laneProgress.size() > Long.SIZE || symbolId < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                || !laneProgress.isEmpty() && accountLaneId >= laneProgress.size()
                || priceSequence < 0 || scanStartPriceSequence < 0 || scanStartPriceSequence > priceSequence
                || triggerPhase < 0 || triggerPriceCursor < 0 || triggerOrderCursor < 0
                || triggerUpperId < 0 || triggerMarkPriceTicks < 0 || triggerGeneratedAtEpochMillis < 0
                || triggerOcoOrderId < 0 || triggerOcoCursor < 0 || lastScheduledRevision < 0) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
    }

    /** 批次续扫的确切用户位置来自当前轮转 Lane；尚未分派时从用户 0 开始。 */
    public long lastUserId() {
        return laneProgress.isEmpty() ? 0 : laneProgress.get(accountLaneId).lastUserId();
    }

    public boolean complete() {
        return riskComplete && triggerComplete;
    }

    public State withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
            long upperId, long markPriceTicks, long generatedAtEpochMillis) {
        return new State(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                riskComplete, complete, phase, priceCursor, orderCursor, upperId, markPriceTicks,
                generatedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, laneProgress);
    }

    public State withTriggerOcoProgress(long orderId, long cursor) {
        return new State(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                riskComplete, triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor,
                triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, orderId, cursor,
                lastScheduledRevision, laneProgress);
    }
}


    public record LiquidationOrder(long liquidationId, long userId, int symbolId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, CoreInstrument instrument,
                                 long triggerPriceSequence, long signedQuantitySteps, long closeQuantitySteps,
                                 long deficitUnits, long executionPriceTicks, long liquidationFeeRatePpm,
                                 long liquidationFeeUnits, CoreLiquidationState.Status status,
                                 long nextCancelOrderId) {
    /**
     * 检查强平身份、配置、持仓方向数量、费用与亏空等基本范围；这里不额外检查不同强平阶段之间的状态转换。
     */
    public LiquidationOrder {
        if (liquidationId <= 0 || userId <= 0 || symbolId < 0 || marginMode == null || positionSide == null
                || instrument == null || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                || closeQuantitySteps <= 0 || closeQuantitySteps > Math.absExact(signedQuantitySteps)
                || deficitUnits < 0 || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                || nextCancelOrderId < 0) {
            throw new IllegalArgumentException("invalid runtime liquidation");
        }
    }
}


    @RequiredArgsConstructor
public static final class LiquidationBatch {
    /**  本片段工作上限；最多一个工作单元生成一条清算。  返回该批次允许保存的最大需求数量，复用时仍受此容量限制。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int capacity;
    /** 借用的 Lane 持仓引用，不在这里制作持仓副本；仅有新增清算时才分配有界数组。 */
    private Position[] positions;
    /** 与 positions 同下标的触发价格序号；只有下标小于 count 的部分属于本批有效内容。 */
    private long[] priceSequences;
    /** Lane 写入数量，完成发布后由 owner 读取。 */
    private int count;
    /** owner 唯一写入的起始编号，派发后由 Lane 读取。 */
    private long firstId;

    /** 返回本批新增的清算需求数，Owner 在 Lane 结果发布后据此预留连续编号。 */
    int size() { return count; }

    /** 开始下一批时清零有效数量和编号起点，复用原数组；旧数组内容不清除，超出新 count 的部分不会被应用。 */
    public void reset() {
        count = 0;
        firstId = 0;
    }

    /**
     * Lane 保存需要新建强平计划的持仓引用及触发价格序号；超过工作预算立即报错，不扩大数组。
     * @param position 风险判断时的持仓记录，稍后用于生成完整强平计划
     * @param priceSequence 形成这份需求的行情序号
     */
    void add(Position position, long priceSequence) {
        if (positions == null) {
            positions = new Position[capacity];
            priceSequences = new long[capacity];
        }
        if (count == capacity) throw new IllegalStateException("risk creation budget exceeded");
        positions[count] = position;
        priceSequences[count++] = priceSequence;
    }

    /**
     * Owner 为本批预留从 nextId 开始的连续编号，返回下一批可用的起点；整数溢出直接报错。
     * @param nextId 全局当前尚未分配的强平编号起点
     */
    long assign(long nextId) {
        // 本批编号范围之后的第一个可用编号，用于 Owner 继续分配后续 Lane 的需求。
        long end = Math.addExact(nextId, count);
        firstId = nextId;
        return end;
    }

    /**
     * 原账户 Lane 使用 Owner 已分配的编号，把本批需求逐条落为 PLANNED 强平记录；每条先计划平掉对应持仓的全部绝对数量。
     * @param runtime 接收强平计划的运行时，调用方必须在正确的状态所有权范围内执行
     */
    void apply(TradingAccountRuntime runtime) {
        if (firstId <= 0) throw new IllegalStateException("risk liquidation IDs not assigned");
        for (/* 当前需要落地的需求下标，同时决定它相对本批起点的连续强平编号。 */ int i = 0; i < count; i++) {
            // 风险扫描时保存的持仓记录，提供用户、币对、保证金模式和带方向数量。
            Position p = positions[i];
            runtime.putLiquidation(new RiskScanner.LiquidationOrder(firstId + i, p.userId(), p.symbolId(), p.marginMode(),
                    p.positionSide(), p.instrument(), priceSequences[i], p.signedQuantitySteps(),
                    Math.absExact(p.signedQuantitySteps()), 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0));
        }
    }
}

}
