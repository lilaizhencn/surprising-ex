package com.surprising.aeron.service.core.change;

import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskSnapshotRuntime;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 一个命令在一个账户 Lane 上的变化收据。Lane 保存值或待冻结活引用、终态订单身份及本边界持仓计数，发布完成后 Owner 才读取和接管。订单与持仓专用缓冲可整体交换，用户和预留元数据按各自入口收集；本类不重新计算成交或资金。
 */
public final class LaneCommitDelta {
    /** 是否已冻结本收据订单预留并准备终态身份，终态提交要求此标记。 */
    private boolean publicationPrepared;
    /** Lane 已收集的终态订单编号列，只读取 terminalOrderCount 之前的项。 */
    private long[] terminalOrderIds = new long[4];
    /** 与终态编号同下标的用户编号列。 */
    private long[] terminalOrderUsers = new long[4];
    /** 与终态编号同下标的客户号文本列，清理时释放有效引用。 */
    private String[] terminalOrderClients = new String[4];
    /**  本收据已保存的有效终态身份数量。 返回本收据已收集的终态身份有效数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int terminalOrderCount;
    /** 本边界 Lane 持仓记录条数，-1 表示未采集或已发布，包含表中保留的零仓位记录。 */
    private int positionCount = -1;
    /**
     *  Lane 独立写入并在完成位之后交给 Owner 的待准入完成数。
     * 返回该 Lane 本收据记录的已完成待准入数量。
     * 保存 Lane 已完成的待准入数量回执，不在此修改预留权威表。
     * @param completedPending 本 Lane 在该边界完成的待准入条数
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int completedPending;

    /** 等待消费者归还 Lane 的已退休订单引用数组。 */
    private OrderRuntime[] retiredOrders = new OrderRuntime[4];
    /** 待归还订单引用数组的有效长度。 */
    private int retiredOrderCount;

    /**
     * 追加已脱离业务表的订单回收引用，需要时增长数组；是否跨过最终退休水位由后续 Lane 回收池判断。
     * @param order 已经脱离业务表、准备由本收据暂存回收引用的订单
     */
    public void retiredOrder(OrderRuntime order) {
        if (retiredOrderCount == retiredOrders.length)
            retiredOrders = java.util.Arrays.copyOf(retiredOrders, retiredOrders.length * 2);
        retiredOrders[retiredOrderCount++] = order;
    }

    /**
     * 把本收据暂存的订单引用交回所属 Lane 回收队列并清空有效引用，后续复用仍要等待订单退休水位。
     * @param lane 实际拥有这些账户状态、负责捕获或接收回收对象的 Lane
     */
    public void returnRetiredOrders(AccountLaneState lane) {
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < retiredOrderCount; index++) {
            lane.returnRetiredOrder(retiredOrders[index]);
            retiredOrders[index] = null;
        }
        retiredOrderCount = 0;
    }

    /**
     * 按二次幂预留终态订单编号、用户和客户号数组容量，不改变当前有效终态数量。
     * @param expectedCount 预期需要保存的终态订单身份数量
     */
    public void ensureTerminalCapacity(int expectedCount) {
        if (expectedCount <= terminalOrderIds.length) return;
        // 终态身份三列需要共同扩展到的二次幂容量。
        int capacity = terminalOrderIds.length;
        while (capacity < expectedCount) capacity = Math.multiplyExact(capacity, 2);
        terminalOrderIds = java.util.Arrays.copyOf(terminalOrderIds, capacity);
        terminalOrderUsers = java.util.Arrays.copyOf(terminalOrderUsers, capacity);
        terminalOrderClients = java.util.Arrays.copyOf(terminalOrderClients, capacity);
    }

    /**
     * 把订单编号、用户编号和客户号文本复制到本收据的身份列，全部写完再增加有效计数，不保存整份终态订单副本。
     * @param value 要摘取身份信息的非空订单，终态判断由调用方负责
     */
    public void recordTerminalOrder(OrderRuntime value) {
        if (terminalOrderCount == terminalOrderIds.length) {
            ensureTerminalCapacity(Math.addExact(terminalOrderCount, 1));
        }
        terminalOrderIds[terminalOrderCount] = value.orderId();
        terminalOrderUsers[terminalOrderCount] = value.userId();
        terminalOrderClients[terminalOrderCount] = value.clientOrderId();
        terminalOrderCount++;
    }

    /**
     * 拒绝重复准备；只有尚无终态身份记录时才从变化中补收终态标识，再冻结订单和预留值，最后设置已准备标记。
     */
    public void preparePublication() {
        if (publicationPrepared) throw new IllegalStateException("Lane publication already prepared");
        if (terminalOrderCount == 0) {
            orders.forEach((/* 当前订单或预留变化对应的订单编号。 */ orderId, /* 当前订单变化值，只有非空且终态才补记终态身份。 */ order) -> {
                if (order != null && order.status().terminal()) recordTerminalOrder(order);
            });
        }
        orders.freezeValues();
        reservations.freezeValues();
        publicationPrepared = true;
    }

    /**
     * 读取有效终态身份槽的订单编号，调用方应以终态计数限制下标。
     * @param index 终态身份有效列中的下标
     */
    public long terminalOrderId(int index) { return terminalOrderIds[index]; }
    /**
     * 读取有效终态身份槽的用户编号。
     * @param index 终态身份有效列中的下标
     */
    public long terminalOrderUser(int index) { return terminalOrderUsers[index]; }
    /**
     * 读取有效终态身份槽的客户号文本，不从后续活订单重新取值。
     * @param index 终态身份有效列中的下标
     */
    public String terminalOrderClient(int index) { return terminalOrderClients[index]; }

    /** 按需分配的触发单变化列，null 表示此收据尚未使用该列。 */
    RuntimeChangeBuffer<CoreTriggerOrderState> triggers;
    /** 按需分配的算法单变化列。 */
    RuntimeChangeBuffer<com.surprising.aeron.model.CoreAlgoOrderState> algos;
    /**
     *  本边界实际关闭的触发记录条数，Owner 收集后增加产品修订。
     * 返回收据保存的关闭触发单数量。
     * 记录此收据实际关闭触发单的条数，后续 Owner 据此增加修订。
     * @param closedTriggerCount 本收据实际关闭的触发记录条数
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int closedTriggerCount;
    /** 本收据独立复制的用户命令值缓冲。 */
    final RuntimeUserChangeBuffer users = new RuntimeUserChangeBuffer();
    /**  Lane 活引用在完成位前冻结的订单变化缓冲，可整体交给 Owner。 返回当前收据订单专用变化缓冲，不能在已交接后继续读写旧租约。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeOrderChangeBuffer orders = new RuntimeOrderChangeBuffer();
    /** Lane 活引用在完成位前冻结的预留后值缓冲。 */
    final RuntimeReservationChangeBuffer reservations = new RuntimeReservationChangeBuffer();
    /**  持仓发布值与预计算索引的专用变化缓冲。 返回当前收据持仓专用变化缓冲，仍受 Lane 到 Owner 的租约约束。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimePositionChangeBuffer positions = new RuntimePositionChangeBuffer();
    /** 本边界强平值变化，null 代表删除。 */
    final RuntimeChangeBuffer<LiquidationRuntime> liquidations = new RuntimeChangeBuffer<>();
    /** 本次需要发布的风险快照变化。 */
    /** 风险后值与可选布尔删除证明，证明由本 Lane 结束时读取持仓存在性获得。 */
    final RuntimeIndexedChangeBuffer<RiskSnapshotRuntime, Boolean> riskSnapshots = new RuntimeIndexedChangeBuffer<>();
    /** 下游需要知道的已移除订单路由编号集合。 */
    final LongHashSet removedOrderRoutes = new LongHashSet();
    /** 本边界记录的预留路由移除编号集合。 */
    final LongHashSet removedReservationRoutes = new LongHashSet();

    /**
     * 为单用户批量准入预留用户、订单及预留变化容量。
     * @param expectedOrders 本边界预计涉及的订单数量
     */
    public void ensureAdmissionCapacity(int expectedOrders) {
        users.ensureCapacity(1);
        orders.ensureCapacity(expectedOrders);
        reservations.ensureCapacity(expectedOrders);
    }

    /**
     * 按预期订单数量预留用户、订单、预留和持仓变化容量，提前准备容器容量但不创建业务实体。
     * @param expectedOrders 本边界预计涉及的订单数量
     */
    public void ensureOrderCapacity(int expectedOrders) {
        users.ensureCapacity(expectedOrders * 2);
        orders.ensureCapacity(expectedOrders);
        reservations.ensureCapacity(expectedOrders);
        positions.ensureCapacity(expectedOrders * 2);
    }

    /**
     * 把当前用户值复制到本收据独占的命令槽，避免后续原位用户变更覆盖较早回执。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putUser(long key, UserRuntime value) {
        users.put(key, value);
    }

    /**
     * 先登记 Lane 活订单引用，完成发布前由准备或控制冻结入口复制成命令值。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putOrder(long key, OrderRuntime value) {
        orders.putLive(key, value);
    }

    /**
     * 先登记 Lane 活预留引用，完成发布前再冻结为本收据发布值。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putReservation(long key, ReservationRuntime value) {
        reservations.putLive(key, value);
    }

    /**
     * 由账户工作线程在发布控制完成位之前冻结订单和预留后值。
     */
    public void freezeControlValues() { orders.freezeValues(); reservations.freezeValues(); }

    /**
     * 立即把持仓复制为本物理变化缓冲的发布值。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putPosition(long key, PositionRuntime value) {
        positions.put(key, value);
    }

    /**
     * 记录当前强平后值引用或 null 删除变化，不在本入口执行强平。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putLiquidation(long key, LiquidationRuntime value) {
        liquidations.put(key, value);
    }

    /**
     * 记录风险后值并使该键旧预计算列失效，删除证明在 Lane 命令结束时采集。
     * @param key 当前用户、订单、预留、持仓、强平或风险实体的原语键，具体含义由当前入口固定
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putRiskSnapshot(long key, RiskSnapshotRuntime value) {
        riskSnapshots.put(key, value);
    }

    /**
     * 要求所属 Lane 线程，捕获此收据边界的持仓记录数；对风险删除项保存此时持仓是否也不存在的证明，供后续身份回收。
     * @param lane 实际拥有这些账户状态、负责捕获或接收回收对象的 Lane
     */
    public void preparePositionRetirement(AccountLaneState lane) {
        lane.assertOwner();
        positionCount = lane.positionCount();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++) {
            if (riskSnapshots.valueAt(index) != null) continue;
            // 当前风险变化对应的运行时持仓键。
            long key = riskSnapshots.keyAt(index);
            riskSnapshots.putPrepared(key, lane.position(key) == null);
        }
    }

    /**
     * 登记需要交给下游输出的订单路由移除编号，不直接操作全局路由表。
     * @param orderId 需要报告移除路由的订单编号
     */
    public void removeOrderRoute(long orderId) { removedOrderRoutes.add(orderId); }
    /**
     * 登记本边界预留路由移除编号，后续收集路径消费并清理该集合。
     * @param orderId 需要报告移除路由的订单编号
     */
    public void removeReservationRoute(long orderId) { removedReservationRoutes.add(orderId); }

    /**
     * 收集本方法覆盖的用户及订单变化键，清理相关业务缓冲，并更新风险持仓身份集合；不等同于完整收据发布或 clear，触发变化和终态身份另有边界。
     * @param laneId 接口保留的 Lane 编号，本方法实现不读取此参数
     * @param state 接收本边界发布变化及计数的 Owner 运行时
     * @param changedUsers 可选的本命令变化用户键集合，不需要时为 null
     * @param changedOrders 可选的本命令变化订单键集合，不需要时为 null
     */
    public void drainTo(int laneId,
                 TradingRuntimeState state,
                 com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
                 com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        if (changedUsers != null || changedOrders != null) {
            users.forEach((/* 本收据用户变化对应的用户编号。 */ userId, /* 本次遍历只收集业务键，不读取回调传入的值。 */ ignored) -> {
                if (changedUsers != null) changedUsers.add(userId);
            });
            orders.forEach((/* 当前订单或预留变化对应的订单编号。 */ orderId, /* 本次遍历只收集业务键，不读取回调传入的值。 */ ignored) -> {
                if (changedOrders != null) changedOrders.add(orderId);
            });
            reservations.forEach((/* 当前订单或预留变化对应的订单编号。 */ orderId, /* 本收据预留后值，非空时可从它得到用户编号。 */ reservation) -> {
                if (changedOrders != null) changedOrders.add(orderId);
                if (changedUsers != null && reservation != null) changedUsers.add(reservation.userId());
            });
            positions.forEach((/* 当前持仓变化的原语键，收集用户键时本回调不读取此键。 */ positionKey, /* 本收据的持仓发布值，非空时用于收集所属用户。 */ position) -> {
                if (changedUsers != null && position != null) changedUsers.add(position.userId());
            });
            if (changedOrders != null) removedOrderRoutes.forEach(changedOrders::add);
        }
        users.clear();
        orders.clear();
        reservations.clear();
        positions.clear();
        liquidations.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++) {
            // 当前风险变化对应的运行时持仓键。
            long key = riskSnapshots.keyAt(index);
            if (riskSnapshots.valueAt(index) == null) state.removeRiskPositionIdentity(key);
            else state.addRiskPositionIdentity(key);
        }
        riskSnapshots.clear();
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    /**
     * 先发布触发及关闭计数，要求终态准备完成，再收集持仓计数、用户及预留元数据和终态身份，传递强平风险变化，最后接管订单与持仓物理缓冲。
     * @param state 接收本边界发布变化及计数的 Owner 运行时
     * @param laneId 当前收据实际所属的账户 Lane 编号
     * @param terminalOrderSink 可选的终态身份批量消费者，读取本收据已保存的原语身份列
     * @param coreSequence 本次提交结果所属的 Core 命令序号
     * @param changedUsers 可选的本命令变化用户键集合，不需要时为 null
     * @param changedOrders 可选的本命令变化订单键集合，不需要时为 null
     */
    public void commitTerminalToOwner(TradingRuntimeState state, int laneId,
                               TradingRuntimeState.TerminalOrderSink terminalOrderSink, long coreSequence,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        publishTriggersToOwner(state);
        if (closedTriggerCount != 0) {
            state.restoreRevision(Math.addExact(state.revision(), closedTriggerCount));
            closedTriggerCount = 0;
        }
        if (!publicationPrepared) throw new IllegalStateException("Lane terminal publication is not prepared");
        publishPositionCountToOwner(state, laneId);
        publishPreparedToOwner(state, changedUsers, changedOrders);
        if (terminalOrderSink != null && terminalOrderCount != 0)
            terminalOrderSink.acceptBatch(this, coreSequence);
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations().put(liquidations.keyAt(index), liquidations.valueAt(index));
        liquidations.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++) {
            // 当前风险变化对应的运行时持仓键。
            long key = riskSnapshots.keyAt(index);
            // 当前强平、预留、持仓或风险变化值，null 保留删除含义。
            RiskSnapshotRuntime value = riskSnapshots.valueAt(index);
            state.changedRiskSnapshots().putIndexed(key, value, riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
            if (value == null) state.removeRiskPositionIdentity(key);
            else state.addRiskPositionIdentity(key);
        }
        riskSnapshots.clear();
        state.orderChanges().adopt(laneId, orders);
        state.positionChanges().adopt(laneId, positions);
    }

    /**
     * 记录用户及预留输出和可选变化键，清理已消费的用户预留及路由移除集合，并解除准备标记；订单持仓仍留待后续接管。
     * @param state 接收本边界发布变化及计数的 Owner 运行时
     * @param changedUsers 可选的本命令变化用户键集合，不需要时为 null
     * @param changedOrders 可选的本命令变化订单键集合，不需要时为 null
     */
    private void publishPreparedToOwner(
            TradingRuntimeState state,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        // 这里只收集本命令变化键及用户、预留元数据，订单和持仓另走物理缓冲交接。
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < users.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = users.keyAt(index);
            if (changedUsers != null) changedUsers.add(id);
            state.changedUser(id);
            state.captureUserMetadata(users.valueAt(index));
        }
        users.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < orders.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = orders.keyAt(index);
            if (changedOrders != null) changedOrders.add(id);
        }
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < reservations.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = reservations.keyAt(index);
            // 当前强平、预留、持仓或风险变化值，null 保留删除含义。
            ReservationRuntime value = reservations.valueAt(index);
            if (changedOrders != null) changedOrders.add(id);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
            state.changedReservation(id);
            state.captureReservation(value);
        }
        reservations.clear();
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < positions.size(); index++) {
            // 当前用户、订单、预留或持仓列中的业务键。
            long id = positions.keyAt(index);
            // 当前强平、预留、持仓或风险变化值，null 保留删除含义。
            PositionRuntime value = positions.valueAt(index);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
        }
        if (!removedOrderRoutes.isEmpty()) {
            if (changedOrders != null) removedOrderRoutes.forEach(changedOrders::add);
            removedOrderRoutes.clear();
        }
        if (!removedReservationRoutes.isEmpty()) {
            removedReservationRoutes.clear();
        }
        publicationPrepared = false;
    }

    /**
     * 把已准备的批量准入变化、触发及本边界持仓数交给 Owner，并接管订单和持仓缓冲；部分源列仍由外层释放时 clear，本方法不自行冻结活引用。
     * @param state 接收本边界发布变化及计数的 Owner 运行时
     * @param laneId 当前收据实际所属的账户 Lane 编号
     */
    public void commitBatchAdmissionToOwner(TradingRuntimeState state, int laneId) {
        if (state == null || laneId < 0 || laneId >= state.accountLaneCount()) {
            throw new IllegalArgumentException("invalid batch admission owner handoff");
        }
        publishPositionCountToOwner(state, laneId);
        publishTriggersToOwner(state);
        if (closedTriggerCount != 0) {
            state.restoreRevision(Math.addExact(state.revision(), closedTriggerCount));
            closedTriggerCount = 0;
        }
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < users.size(); index++) state.changedUser(users.keyAt(index));
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < reservations.size(); index++) state.changedReservation(reservations.keyAt(index));
        state.orderChanges().adopt(laneId, orders);
        state.positionChanges().adopt(laneId, positions);
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations().put(liquidations.keyAt(index), liquidations.valueAt(index));
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++)
            state.changedRiskSnapshots().putIndexed(riskSnapshots.keyAt(index), riskSnapshots.valueAt(index),
                    riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    /**
     * 按需创建算法单变化列并记录最新后值，null 表示删除。
     * @param id 算法或触发记录的业务编号
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putAlgo(long id, com.surprising.aeron.model.CoreAlgoOrderState value) {
        if (algos == null) algos = new RuntimeChangeBuffer<>();
        algos.put(id, value);
    }

    /**
     * 按需创建触发变化列并记录最新后值，null 表示删除。
     * @param id 算法或触发记录的业务编号
     * @param value 当前入口要记录的业务后值，null 表示删除；是否立即复制由该列缓冲决定
     */
    public void putTrigger(long id, CoreTriggerOrderState value) {
        if (triggers == null) triggers = new RuntimeChangeBuffer<>();
        triggers.put(id, value);
    }

    /**
     * 把算法与触发变化交入 Owner 变化集合，再清空源列，保持原命令顺序。
     * @param state 接收本边界发布变化及计数的 Owner 运行时
     */
    public void publishTriggersToOwner(TradingRuntimeState state) {
        if (algos != null) algos.drainTo(state.changedAlgoOrders()::put);
        if (triggers == null || triggers.isEmpty()) return;
        triggers.forEach((/* 当前用户、订单、预留或持仓列中的业务键。 */ id, /* 当前强平、预留、持仓或风险变化值，null 保留删除含义。 */ value) -> {
            state.changedTriggerOrders().put(id, value);
        });
        triggers.clear();
    }

    /**
     * 收集控制任务的本边界计数、用户及预留元数据，接管已冻结的订单持仓变化并传递强平风险后值；剩余源列生命周期由外层 clear 管理。
     * @param state 接收本边界发布变化及计数的 Owner 运行时
     * @param laneId 当前收据实际所属的账户 Lane 编号
     */
    public void recordControlChanges(TradingRuntimeState state, int laneId) {
        publishPositionCountToOwner(state, laneId);
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < users.size(); index++) {
            state.changedUser(users.keyAt(index));
            state.captureUserMetadata(users.valueAt(index));
        }
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < reservations.size(); index++) {
            state.changedReservation(reservations.keyAt(index));
            state.captureReservation(reservations.valueAt(index));
        }
        state.orderChanges().adopt(laneId, orders);
        state.positionChanges().adopt(laneId, positions);
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations().put(liquidations.keyAt(index), liquidations.valueAt(index));
        for (/* 当前正在消费的收据连续有效槽。 */ int index = 0; index < riskSnapshots.size(); index++)
            state.changedRiskSnapshots().putIndexed(riskSnapshots.keyAt(index), riskSnapshots.valueAt(index),
                    riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
    }

    /**
     * 仅在已采集计数时向 Owner 发布此 Lane 的边界条数，并重设为 -1，避免同收据再次发布旧计数。
     * @param state 接收本边界发布变化及计数的 Owner 运行时
     * @param laneId 当前收据实际所属的账户 Lane 编号
     */
    private void publishPositionCountToOwner(TradingRuntimeState state, int laneId) {
        if (positionCount < 0) return;
        state.publishLanePositionCount(laneId, positionCount);
        positionCount = -1;
    }

    /**
     * 重置计数、终态身份和变化列，释放有效引用并保留容量；待回收订单只是解除引用，不会自动调用 returnRetiredOrders。
     */
    public void clear() {
        completedPending = 0;
        positionCount = -1;
        closedTriggerCount = 0;
        java.util.Arrays.fill(retiredOrders, 0, retiredOrderCount, null);
        retiredOrderCount = 0;
        if (triggers != null) triggers.clear();
        if (algos != null) algos.clear();
        publicationPrepared = false;
        java.util.Arrays.fill(terminalOrderClients, 0, terminalOrderCount, null);
        // 原语列不持有引用；count 是唯一有效边界，recordTerminalOrder 先完整覆盖再递增。
        terminalOrderCount = 0;
        users.clear();
        orders.clear();
        reservations.clear();
        positions.clear();
        liquidations.clear();
        riskSnapshots.clear();
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

}
