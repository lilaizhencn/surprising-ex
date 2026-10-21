package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.order.OrderReservation;
import com.surprising.aeron.service.state.snapshot.StateMapSupport;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 物化后的完整用户业务状态，保存余额、订单冻结、持仓及模式。完整构造核对键和锁定金额，增量转换要求原映射血缘并只更新变化项，最终冻结有序映射；在线权威实体仍由 Account Lane 持有。
 */
public final class CoreUserState {

    /** 完整用户业务状态所属的独立产品线。 */
    private final ProductLine productLine;
    /** 该状态对应的正数用户编号。 */
    private final long userId;
    /** 用户业务版本，允许初始值为零。 */
    private final long revision;
    /** 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表。 */
    private final Map<String, AssetBalance> balances;
    /** 按订单编号保存的冻结有序预留记录映射。 */
    private final Map<Long, OrderReservation> reservations;
    /** 按持仓业务键保存的冻结有序持仓映射。 */
    private final Map<String, CorePositionState> positions;
    /** 用户的单向或双向持仓模式。 */
    private final CorePositionMode positionMode;
    /** 校验用派生金额：各资产订单剩余冻结加持仓保证金，随本快照更新，不是可再次支出的余额。 */
    private final Map<String, Long> explainedLocks;

    /**
     * 按给定模式构造完整用户状态，先从全部冻结及持仓重算可解释锁定额，再校验并冻结映射。
     * @param productLine 完整用户业务状态所属的独立产品线
     * @param userId 该状态对应的正数用户编号
     * @param revision 用户业务版本，允许初始值为零
     * @param balances 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表
     * @param reservations 按订单编号保存的冻结有序预留记录映射
     * @param positions 按持仓业务键保存的冻结有序持仓映射
     * @param positionMode 用户的单向或双向持仓模式
     */
    public CoreUserState(ProductLine productLine,
                         long userId,
                         long revision,
                         Map<String, AssetBalance> balances,
                         Map<Long, OrderReservation> reservations,
                         Map<String, CorePositionState> positions,
                         CorePositionMode positionMode) {
        this(productLine, userId, revision, balances, reservations, positions, positionMode,
                calculateExplainedLocks(reservations, positions), false);
    }

    /**
     * 未显式指定模式时采用单向持仓，其余走完整构造和锁定额校验。
     * @param productLine 完整用户业务状态所属的独立产品线
     * @param userId 该状态对应的正数用户编号
     * @param revision 用户业务版本，允许初始值为零
     * @param balances 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表
     * @param reservations 按订单编号保存的冻结有序预留记录映射
     * @param positions 按持仓业务键保存的冻结有序持仓映射
     */
    public CoreUserState(ProductLine productLine,
                         long userId,
                         long revision,
                         Map<String, AssetBalance> balances,
                         Map<Long, OrderReservation> reservations,
                         Map<String, CorePositionState> positions) {
        this(productLine, userId, revision, balances, reservations, positions, CorePositionMode.ONE_WAY);
    }

    /**
     * 校验基础字段、映射键及锁定上界，再冻结余额、预留、持仓和派生金额；incremental 决定只校验变化键还是完整来源。
     * @param productLine 完整用户业务状态所属的独立产品线
     * @param userId 该状态对应的正数用户编号
     * @param revision 用户业务版本，允许初始值为零
     * @param balances 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表
     * @param reservations 按订单编号保存的冻结有序预留记录映射
     * @param positions 按持仓业务键保存的冻结有序持仓映射
     * @param positionMode 用户的单向或双向持仓模式
     * @param explainedLocks 校验用派生金额：各资产订单剩余冻结加持仓保证金，随本快照更新，不是可再次支出的余额
     * @param incremental 是否按已保留的映射增量血缘只检查变化键
     */
    private CoreUserState(ProductLine productLine,
                          long userId,
                          long revision,
                          Map<String, AssetBalance> balances,
                          Map<Long, OrderReservation> reservations,
                          Map<String, CorePositionState> positions,
                          CorePositionMode positionMode,
                          Map<String, Long> explainedLocks,
                          boolean incremental) {
        if (productLine == null || userId <= 0 || revision < 0 || balances == null || reservations == null
                || positions == null || positionMode == null || explainedLocks == null) {
            throw new IllegalArgumentException("invalid user state");
        }
        validateEntries(balances, reservations, positions, incremental);
        validateLocks(balances, explainedLocks, incremental);
        this.productLine = productLine;
        this.userId = userId;
        this.revision = revision;
        this.balances = StateMapSupport.freezeSorted(balances);
        this.reservations = StateMapSupport.freezeSorted(reservations);
        this.positions = StateMapSupport.freezeSorted(positions);
        this.positionMode = positionMode;
        this.explainedLocks = StateMapSupport.freezeSorted(explainedLocks);
    }

    /**
     * 创建指定产品用户的零版本空状态，默认单向持仓，没有余额、订单冻结或持仓。
     * @param productLine 完整用户业务状态所属的独立产品线
     * @param userId 该状态对应的正数用户编号
     */
    public static CoreUserState empty(ProductLine productLine, long userId) {
        return new CoreUserState(productLine, userId, 0, Map.of(), Map.of(), Map.of(),
                CorePositionMode.ONE_WAY);
    }

    /**
     * 要求三张新业务映射是原映射的直接增量，从派生锁定额扣除变化项前值、加后值，再以给定版本和模式构造增量后状态；不扫描全部未变实体。
     * @param nextRevision 调用方提供的后状态非负版本，递增规则由上游业务入口负责
     * @param nextBalances 基于当前余额映射建立的直接增量，包含本次余额变化
     * @param nextReservations 基于当前预留映射建立的直接增量，包含新增、变更和删除
     * @param nextPositions 基于当前持仓映射建立的直接增量，包含本次持仓变化
     * @param nextPositionMode 本次后状态采用的非空持仓模式，业务切换限制由上游检查
     */
    CoreUserState transition(long nextRevision,
                             Map<String, AssetBalance> nextBalances,
                             Map<Long, OrderReservation> nextReservations,
                             Map<String, CorePositionState> nextPositions,
                             CorePositionMode nextPositionMode) {
        requireLineage("balances", balances, nextBalances);
        requireLineage("reservations", reservations, nextReservations);
        requireLineage("positions", positions, nextPositions);
        // 基于原派生金额建立的本次直接增量，只对预留及持仓变化项作差更新。
        Map<String, Long> nextExplainedLocks = StateMapSupport.delta(explainedLocks);
        for (Long /* 当前变化的预留订单编号，用它扣原剩余冻结并加新剩余冻结。 */ orderId : StateMapSupport.changedKeys(nextReservations)) {
            // 变化订单原来的冻结记录，存在时从派生锁定额扣去其剩余额度。
            OrderReservation previous = reservations.get(orderId);
            // 变化订单新的冻结记录，存在时把其剩余额度加回派生锁定额。
            OrderReservation current = nextReservations.get(orderId);
            if (previous != null) adjustExplainedLock(nextExplainedLocks, previous.asset(), -previous.remainingUnits());
            if (current != null) adjustExplainedLock(nextExplainedLocks, current.asset(), current.remainingUnits());
        }
        for (String /* 当前变化的持仓业务键，前后保证金差额进入派生锁定额。 */ positionKey : StateMapSupport.changedKeys(nextPositions)) {
            // 当前变化持仓的前值，存在时先扣掉旧持仓保证金。
            CorePositionState previous = positions.get(positionKey);
            // 当前变化持仓的后值，存在时增加其新持仓保证金。
            CorePositionState current = nextPositions.get(positionKey);
            if (previous != null) {
                adjustExplainedLock(nextExplainedLocks, previous.marginAsset(), -previous.positionMarginUnits());
            }
            if (current != null) {
                adjustExplainedLock(nextExplainedLocks, current.marginAsset(), current.positionMarginUnits());
            }
        }
        return new CoreUserState(productLine, userId, nextRevision, nextBalances, nextReservations, nextPositions,
                nextPositionMode, nextExplainedLocks, true);
    }

    /**
     * 前值为空或同一对象时无需比较，否则要求四张映射都保留到给定前状态的增量祖先关系，丢失血缘立即失败。
     * @param before 用来比较变更键或映射血缘的前用户业务状态
     */
    void requireIncrementalLineage(CoreUserState before) {
        if (before == null || before == this) return;
        requireDescendantLineage("user balances", before.balances, balances);
        requireDescendantLineage("user reservations", before.reservations, reservations);
        requireDescendantLineage("user positions", before.positions, positions);
        requireDescendantLineage("user explained locks", before.explainedLocks, explainedLocks);
    }

    /**
     * 返回完整用户业务状态所属的独立产品线。
     */
    public ProductLine productLine() {
        return productLine;
    }

    /**
     * 返回该状态对应的正数用户编号。
     */
    public long userId() {
        return userId;
    }

    /**
     * 返回用户业务版本，允许初始值为零。
     */
    public long revision() {
        return revision;
    }

    /**
     * 返回按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表。
     */
    public Map<String, AssetBalance> balances() {
        return balances;
    }

    /**
     * 返回按订单编号保存的冻结有序预留记录映射。
     */
    public Map<Long, OrderReservation> reservations() {
        return reservations;
    }

    /**
     * 返回按持仓业务键保存的冻结有序持仓映射。
     */
    public Map<String, CorePositionState> positions() {
        return positions;
    }

    /**
     * 返回用户的单向或双向持仓模式。
     */
    public CorePositionMode positionMode() {
        return positionMode;
    }

    /**
     * 查询从给定前状态到当前的余额资产变更键；没有前状态时返回当前全部资产键。
     * @param before 用来比较变更键或映射血缘的前用户业务状态
     */
    public Set<String> changedBalanceAssetsSince(CoreUserState before) {
        return before == null ? balances.keySet() : StateMapSupport.changedKeys(before.balances, balances);
    }

    /**
     * 查询从前状态到当前的预留订单变更键；没有前状态时返回当前全部预留编号。
     * @param before 用来比较变更键或映射血缘的前用户业务状态
     */
    public Set<Long> changedReservationIdsSince(CoreUserState before) {
        return before == null ? reservations.keySet() : StateMapSupport.changedKeys(before.reservations, reservations);
    }

    /**
     * 查询从前状态到当前的持仓变更键；没有前状态时返回当前全部持仓键。
     * @param before 用来比较变更键或映射血缘的前用户业务状态
     */
    public Set<String> changedPositionKeysSince(CoreUserState before) {
        return before == null ? positions.keySet() : StateMapSupport.changedKeys(before.positions, positions);
    }

    /**
     * 规范化资产代码并读取可用加锁定总额，没有该资产余额就返回 0。
     * @param asset 要查询或核对的资产代码，金额更新和总额查询会先规范化
     */
    public long totalUnits(String asset) {
        // 当前核对或查询的资产余额，缺失资产总额为零，但正数可解释锁定不能缺余额。
        AssetBalance balance = balances.get(AssetBalance.normalizeAsset(asset));
        return balance == null ? 0 : balance.totalUnits();
    }

    /**
     * 按完整或变化键范围核对资产、订单编号和持仓键与其记录身份一致，已删除的空后值跳过。
     * @param balances 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表
     * @param reservations 按订单编号保存的冻结有序预留记录映射
     * @param positions 按持仓业务键保存的冻结有序持仓映射
     * @param incremental 是否按已保留的映射增量血缘只检查变化键
     */
    private static void validateEntries(Map<String, AssetBalance> balances,
                                        Map<Long, OrderReservation> reservations,
                                        Map<String, CorePositionState> positions,
                                        boolean incremental) {
        keysToValidate(balances, incremental).forEach(/* 当前要核对映射身份或锁定上界的资产代码。 */ asset -> {
            // 当前核对或查询的资产余额，缺失资产总额为零，但正数可解释锁定不能缺余额。
            AssetBalance balance = balances.get(asset);
            if (balance != null && !asset.equals(balance.asset())) {
                throw new IllegalArgumentException("balance key does not match asset");
            }
        });
        keysToValidate(reservations, incremental).forEach(/* 当前核对的预留映射键，必须等于非空记录的订单编号。 */ orderId -> {
            // 当前预留记录，身份校验看订单编号，派生金额计算看其剩余冻结。
            OrderReservation reservation = reservations.get(orderId);
            if (reservation != null && orderId != reservation.orderId()) {
                throw new IllegalArgumentException("reservation key does not match orderId");
            }
        });
        keysToValidate(positions, incremental).forEach(/* 当前核对的持仓映射键，必须与持仓自身业务身份一致。 */ key -> {
            // 当前持仓记录，身份校验看持仓键，派生金额计算看保证金资产和金额。
            CorePositionState position = positions.get(key);
            if (position != null && !key.equals(position.key())) {
                throw new IllegalArgumentException("position key does not match position identity");
            }
        });
    }

    /**
     * 完整构造检查所有有可解释锁定的资产；增量构造只检查余额或派生锁定变化的资产，并避免重复核对同一资产。
     * @param balances 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表
     * @param explainedLocks 校验用派生金额：各资产订单剩余冻结加持仓保证金，随本快照更新，不是可再次支出的余额
     * @param incremental 是否按已保留的映射增量血缘只检查变化键
     */
    private static void validateLocks(Map<String, AssetBalance> balances,
                                      Map<String, Long> explainedLocks,
                                      boolean incremental) {
        if (!incremental) {
            for (String /* 当前要核对映射身份或锁定上界的资产代码。 */ asset : explainedLocks.keySet()) validateLock(balances, explainedLocks, asset);
            return;
        }
        // 本次余额增量中变化的资产键，先检查这些资产的锁定上界。
        Set<String> changedBalances = StateMapSupport.changedKeys(balances);
        // 本次派生锁定额增量中变化的资产键，跳过已经因余额变化检查过的项。
        Set<String> changedLocks = StateMapSupport.changedKeys(explainedLocks);
        for (String /* 当前要核对映射身份或锁定上界的资产代码。 */ asset : changedBalances) validateLock(balances, explainedLocks, asset);
        for (String /* 当前要核对映射身份或锁定上界的资产代码。 */ asset : changedLocks) {
            if (!changedBalances.contains(asset)) validateLock(balances, explainedLocks, asset);
        }
    }

    /**
     * 可解释锁定不能为负；正数时要求资产余额存在且其锁定额足够，允许锁定额大于当前可解释部分，不要求两者相等。
     * @param balances 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表
     * @param explainedLocks 校验用派生金额：各资产订单剩余冻结加持仓保证金，随本快照更新，不是可再次支出的余额
     * @param asset 要查询或核对的资产代码，金额更新和总额查询会先规范化
     */
    private static void validateLock(Map<String, AssetBalance> balances,
                                     Map<String, Long> explainedLocks,
                                     String asset) {
        // 当前资产可解释锁定总额，默认零，不能超过真实余额锁定额。
        long units = explainedLocks.getOrDefault(asset, 0L);
        // 当前核对或查询的资产余额，缺失资产总额为零，但正数可解释锁定不能缺余额。
        AssetBalance balance = balances.get(asset);
        if (units < 0 || (units > 0 && (balance == null || units > balance.lockedUnits()))) {
            throw new IllegalStateException("reservation locks exceed balance asset=" + asset);
        }
    }

    /**
     * 从订单剩余预留及持仓保证金按资产累加派生锁定额；没有来源或来源全空返回空映射，非空映射继续交构造入口冻结。
     * @param reservations 按订单编号保存的冻结有序预留记录映射
     * @param positions 按持仓业务键保存的冻结有序持仓映射
     */
    private static Map<String, Long> calculateExplainedLocks(Map<Long, OrderReservation> reservations,
                                                              Map<String, CorePositionState> positions) {
        if (reservations == null || positions == null || (reservations.isEmpty() && positions.isEmpty())) {
            return Map.of();
        }
        // 完整构造时临时累计的按资产派生锁定额，完成后交给状态冻结。
        Map<String, Long> result = new HashMap<>();
        reservations.values().forEach(/* 当前预留记录，身份校验看订单编号，派生金额计算看其剩余冻结。 */ reservation ->
                adjustExplainedLock(result, reservation.asset(), reservation.remainingUnits()));
        positions.values().forEach(/* 当前持仓记录，身份校验看持仓键，派生金额计算看保证金资产和金额。 */ position ->
                adjustExplainedLock(result, position.marginAsset(), position.positionMarginUnits()));
        return result.isEmpty() ? Map.of() : result;
    }

    /**
     * 规范化资产代码并精确累加正负变化，结果不能为负，归零删除该项，否则写入新值。
     * @param locks 需要应用本次资产变化的派生锁定额映射
     * @param asset 要查询或核对的资产代码，金额更新和总额查询会先规范化
     * @param delta 该资产应增加或减少的可解释锁定额，单位与余额相同
     */
    private static void adjustExplainedLock(Map<String, Long> locks, String asset, long delta) {
        // 本次金额变化采用的规范资产代码，防止大小写或空白形成不同键。
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        // 原派生锁定额加本次变化的精确结果，负数报错，零值移除。
        long next = Math.addExact(locks.getOrDefault(normalizedAsset, 0L), delta);
        if (next < 0) throw new IllegalStateException("negative explained lock asset=" + normalizedAsset);
        if (next == 0) locks.remove(normalizedAsset);
        else locks.put(normalizedAsset, next);
    }

    /**
     * 完整入口返回所有键，增量入口只返回本次映射的变化键，供统一身份检查复用。
     * @param <K> 当前业务映射的键类型，资产和持仓为文本，订单为整数编号
     * @param values 本次需要检查身份一致性的业务映射
     * @param incremental 是否按已保留的映射增量血缘只检查变化键
     */
    private static <K> Set<K> keysToValidate(Map<K, ?> values, boolean incremental) {
        return incremental ? StateMapSupport.changedKeys(values) : values.keySet();
    }

    /**
     * 要求后映射是前映射的直接增量，避免增量校验看不到完整变化来源。
     * @param name 出错时标明余额、预留或持仓等映射名称
     * @param before 本次直接增量应基于的前映射
     * @param after 需要核对其直接或后代增量关系的后映射
     */
    private static void requireLineage(String name, Map<?, ?> before, Map<?, ?> after) {
        if (!StateMapSupport.isDirectDeltaOf(before, after)) {
            throw new IllegalStateException(name + " lineage is unavailable");
        }
    }

    /**
     * 允许跨多个增量版本，但后映射必须仍是指定前映射的后代，不能用无关新 Map 替代血缘。
     * @param name 出错时标明余额、预留或持仓等映射名称
     * @param before 需要保留为祖先的指定前映射
     * @param after 需要核对其直接或后代增量关系的后映射
     */
    private static void requireDescendantLineage(String name, Map<?, ?> before, Map<?, ?> after) {
        if (!StateMapSupport.isDeltaDescendantOf(before, after)) {
            throw new IllegalStateException(name + " lineage is unavailable");
        }
    }

    /**
     * 比较产品、用户、版本、三张业务映射和模式；派生锁定额及映射血缘不作为额外业务差异。
     * @param value 需要进行完整用户业务值比较的任意对象
     */
    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof CoreUserState /* 已经确认类型的待比较用户状态，只比较显式业务字段。 */ other)) return false;
        return userId == other.userId && revision == other.revision && productLine == other.productLine
                && balances.equals(other.balances) && reservations.equals(other.reservations)
                && positions.equals(other.positions) && positionMode == other.positionMode;
    }

    /**
     * 按与 equals 相同的显式业务字段组合哈希值。
     */
    @Override
    public int hashCode() {
        return Objects.hash(productLine, userId, revision, balances, reservations, positions, positionMode);
    }

    /**
     * 输出用户、版本、产品及业务映射，供快照差异和测试断言诊断。
     */
    @Override
    public String toString() {
        return "CoreUserState[productLine=" + productLine + ", userId=" + userId + ", revision=" + revision
                + ", balances=" + balances + ", reservations=" + reservations + ", positions=" + positions
                + ", positionMode=" + positionMode + ']';
    }
}
