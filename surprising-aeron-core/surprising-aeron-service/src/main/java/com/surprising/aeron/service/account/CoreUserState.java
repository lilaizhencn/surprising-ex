package com.surprising.aeron.service.account;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.snapshot.state.StateMapSupport;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 物化后的完整用户业务状态，保存余额、订单冻结、持仓及模式。构造时核对全部键和锁定金额，最终冻结有序映射；在线权威实体仍由 Account Lane 持有。
 */
public final class CoreUserState {

    /** 完整用户业务状态所属的独立产品线。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final ProductLine productLine;
    /** 该状态对应的正数用户编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long userId;
    /** 用户业务版本，允许初始值为零。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long revision;
    /** 按资产代码保存的冻结有序余额映射，属于业务快照而非在线 Lane 表。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final Map<String, AssetBalance> balances;
    /** 按订单编号保存的冻结有序预留记录映射。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final Map<Long, OrderReservation> reservations;
    /** 按持仓业务键保存的冻结有序持仓映射。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final Map<String, CorePositionState> positions;
    /** 用户的单向或双向持仓模式。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final CorePositionMode positionMode;

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
        if (productLine == null || userId <= 0 || revision < 0 || balances == null || reservations == null
                || positions == null || positionMode == null) {
            throw new IllegalArgumentException("invalid user state");
        }
        validateEntries(balances, reservations, positions);
        validateLocks(balances, calculateExplainedLocks(reservations, positions));
        this.productLine = productLine;
        this.userId = userId;
        this.revision = revision;
        this.balances = StateMapSupport.freezeSorted(balances);
        this.reservations = StateMapSupport.freezeSorted(reservations);
        this.positions = StateMapSupport.freezeSorted(positions);
        this.positionMode = positionMode;
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
     * 创建指定产品用户的零版本空状态，默认单向持仓，没有余额、订单冻结或持仓。
     * @param productLine 完整用户业务状态所属的独立产品线
     * @param userId 该状态对应的正数用户编号
     */
    public static CoreUserState empty(ProductLine productLine, long userId) {
        return new CoreUserState(productLine, userId, 0, Map.of(), Map.of(), Map.of(),
                CorePositionMode.ONE_WAY);
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

    /** 检查完整快照中余额、预留和持仓的键与业务身份一致。 */
    private static void validateEntries(Map<String, AssetBalance> balances,
                                        Map<Long, OrderReservation> reservations,
                                        Map<String, CorePositionState> positions) {
        balances.forEach((asset, balance) -> {
            if (balance != null && !asset.equals(balance.asset())) {
                throw new IllegalArgumentException("balance key does not match asset");
            }
        });
        reservations.forEach((orderId, reservation) -> {
            if (reservation != null && orderId != reservation.orderId()) {
                throw new IllegalArgumentException("reservation key does not match orderId");
            }
        });
        positions.forEach((key, position) -> {
            if (position != null && !key.equals(position.key())) {
                throw new IllegalArgumentException("position key does not match position identity");
            }
        });
    }

    /** 核对所有订单剩余预留和持仓保证金，不得超过对应资产冻结余额。 */
    private static void validateLocks(Map<String, AssetBalance> balances, Map<String, Long> explainedLocks) {
        for (String asset : explainedLocks.keySet()) validateLock(balances, explainedLocks, asset);
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
     * 比较产品、用户、版本、三张业务映射和模式；校验用派生锁定额不作为额外业务差异。
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
