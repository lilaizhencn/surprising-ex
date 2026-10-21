package com.surprising.aeron.service.state.account;

import com.surprising.aeron.service.state.CoreStateTestFixtures;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 核对待完成预留的用户计数和分资产金额聚合，在资产迁移、金额溢出和逆向替换时保持其他预留不变。
 */
class AccountLanePendingReservationTest {
    /**
     * 以接近 long 上限的多个用户反复完成两个资产预留，要求用户项归零移除、资产容器保持原对象并最终没有待完成条目。
     */
    @Test
    void completedUsersLeaveNoEntriesAndAssetsReuseTheirTables() throws Exception {
        // 当前线程持有的独立测试 Lane，直接维护预留权威表及待完成索引。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 读取分资产待完成金额表的反射字段。
        var field = AccountLaneState.class.getDeclaredField("pendingReservedUnitsByAsset");
        field.setAccessible(true);
        // 资产编号到用户金额表的实际内部索引。
        @SuppressWarnings("unchecked")
        var assets = (IntObjectHashMap<LongLongHashMap>) field.get(lane);
        // 首轮保存的资产零用户金额表，用于验证后续不更换容器。
        LongLongHashMap first = null;
        for (/* 当前周转的近 long 上限用户编号。 */ long user = Long.MAX_VALUE - 2048; user < Long.MAX_VALUE; user++) {
            for (/* 本轮预留使用的资产编号，依次为零和一。 */ int asset = 0; asset < 2; asset++) {
                // 本资产对应的临时预留订单编号，完成后复用编号。
                long id = asset + 1;
                lane.reservations.put(id, CoreStateTestFixtures.reservation(id, user, asset, 19));
                lane.markPendingReservation(id, 1);
                assertThat(lane.pendingReservedUnits(user, asset)).isEqualTo(19);
                lane.completePendingReservation(id, 1);
                lane.reservations.remove(id);
                assertThat(lane.pendingReservationCount(user)).isZero();
                assertThat(lane.pendingReservedUnits(user, asset)).isZero();
            }
            if (first == null) first = assets.get(0);
            assertThat(assets.get(0)).isSameAs(first);
            assertThat(assets.size()).isEqualTo(2);
            assertThat(assets.get(0).isEmpty()).isTrue();
            assertThat(assets.get(1).isEmpty()).isTrue();
        }
        assertThat(lane.pendingReservationCount()).isZero();
    }

    /**
     * 让目标资产接近 long 上限，确认溢出替换不改两边金额，再执行恰好到上限的迁移及逆向替换并完成清理。
     */
    @Test
    void assetMigrationOverflowAndReverseReplacementPreserveOtherReservations() {
        // 当前线程持有的独立测试 Lane，直接维护预留权威表及待完成索引。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 用户七在资产零上待完成的四十单位预留。
        var previous = CoreStateTestFixtures.reservation(1, 7, 0, 40);
        // 同用户资产一上的其他大额预留，迁移时必须保留其金额。
        var other = CoreStateTestFixtures.reservation(2, 7, 1, Long.MAX_VALUE - 10);
        lane.reservations.put(1, previous);
        lane.reservations.put(2, other);
        lane.markPendingReservation(1, 1);
        lane.markPendingReservation(2, 1);
        assertThatThrownBy(() -> lane.replacePendingReservation(previous,
                CoreStateTestFixtures.reservation(1, 7, 1, 11))).isInstanceOf(ArithmeticException.class);
        assertThat(lane.pendingReservedUnits(7, 0)).isEqualTo(40);
        assertThat(lane.pendingReservedUnits(7, 1)).isEqualTo(Long.MAX_VALUE - 10);
        // 迁移到资产一、金额为十的替换预留，合计恰为 long 上限。
        var replacement = CoreStateTestFixtures.reservation(1, 7, 1, 10);
        lane.replacePendingReservation(previous, replacement);
        lane.reservations.put(1, replacement);
        assertThat(lane.pendingReservedUnits(7, 0)).isZero();
        assertThat(lane.pendingReservedUnits(7, 1)).isEqualTo(Long.MAX_VALUE);
        lane.replacePendingReservation(replacement, previous);
        lane.reservations.put(1, previous);
        lane.completePendingReservation(1, 1);
        lane.completePendingReservation(2, 1);
        assertThat(lane.pendingReservedUnits(7, 0)).isZero();
        assertThat(lane.pendingReservedUnits(7, 1)).isZero();
        assertThat(lane.pendingReservationCount()).isZero();
    }
}
