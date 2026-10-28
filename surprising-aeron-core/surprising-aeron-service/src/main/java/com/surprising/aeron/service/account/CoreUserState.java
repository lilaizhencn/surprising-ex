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
        validateLocks(balances, reservations, positions);
        this.productLine = productLine;
        this.userId = userId;
        this.revision = revision;
        this.balances = StateMapSupport.freezeSorted(balances);
        this.reservations = StateMapSupport.freezeSorted(reservations);
        this.positions = StateMapSupport.freezeSorted(positions);
        this.positionMode = positionMode;
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

    /** 从已校验的不可变预留和持仓精确汇总锁定额，再核对真实冻结余额。 */
    private static void validateLocks(Map<String, AssetBalance> balances,
                                      Map<Long, OrderReservation> reservations,
                                      Map<String, CorePositionState> positions) {
        if (reservations.isEmpty() && positions.isEmpty()) return;
        Map<String, Long> locks = new HashMap<>();
        reservations.values().forEach(reservation -> {
            long units = reservation.remainingUnits();
            if (units != 0) locks.merge(reservation.asset(), units, Math::addExact);
        });
        positions.values().forEach(position -> {
            long units = position.positionMarginUnits();
            if (units != 0) locks.merge(position.marginAsset(), units, Math::addExact);
        });
        locks.forEach((asset, units) -> {
            AssetBalance balance = balances.get(asset);
            if (balance == null || units > balance.lockedUnits()) {
                throw new IllegalStateException("reservation locks exceed balance asset=" + asset);
            }
        });
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
