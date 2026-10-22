package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * Owner 强平路由及未结束任务索引。完整持仓和资金正文仍在账户 Lane，此处按用户、标的文本及持仓方向定位最早未结束 ID；未结束还包括保险和 ADL 阶段，和仅 PLANNED/ORDERED 可执行标志分别保存。
 */
public final class LiquidationIndex {

    /** 按用户、标的文本及持仓方向分组的未结束 ID 升序桶。 */
    private final Map<LiquidationKey, NavigableSet<Long>> activeIds = new TreeMap<>();
    /** 全部未结束强平 ID 的原可变升序集合，与分组桶一并维护。 */
    private final NavigableSet<Long> allActiveIds = new TreeSet<>();
    /** 全部保留强平身份到轻量路由的反向表，终态路由也可存在。 */
    private final LongObjectHashMap<LiquidationRoute> keysById = new LongObjectHashMap<>();

    /**
     * 从已提交冷状态重建强平路由及未结束 ID 分组。
     * @param state 索引重建使用的本产品线已提交冷状态
     */
    public LiquidationIndex(TradingCoreState state) {
        rebuild(state);
    }

    /**
     * 按用户、标的及持仓方向取未结束 ID 的最小值，分组不包含保证金模式；没有返回零，不保证同组只有一个任务。
     * @param userId 该强平任务所属用户
     * @param instrumentId 规范数字标的文本，分组比较仍采用文本字典序；hasUnresolved 可空表示全部
     * @param positionSide 该任务对应净仓、多头或空头持仓腿
     */
    public long activeId(long userId, String instrumentId, CorePositionSide positionSide) {
        // 当前查询或移除的分组 ID 升序桶
        NavigableSet<Long> ids = activeIds.get(new LiquidationKey(userId, instrumentId, positionSide));
        return ids == null || ids.isEmpty() ? 0 : ids.first();
    }

    /**
     * 借出原可变的全局未结束 ID 升序集合；调用方沿用 Owner 访问边界，直接改集合会使分组索引不一致。
     */
    public NavigableSet<Long> activeIds() {
        return allActiveIds;
    }

    /**
     * 强平路由的不可变用户、标的、方向及未结束/可执行标志，不保留资金和持仓正文。
     * @param userId 该强平任务所属用户
     * @param instrumentId 规范数字标的文本，分组比较仍采用文本字典序；hasUnresolved 可空表示全部
     * @param positionSide 该任务对应净仓、多头或空头持仓腿
     * @param unresolved 是否尚未完成或取消，保险和 ADL 阶段也为真
     * @param executable 是否处于 PLANNED 或 ORDERED 这两个可执行阶段
     */
    public record LiquidationRoute(long userId, String instrumentId, CorePositionSide positionSide,
                                   boolean unresolved, boolean executable) {
        /**
         * 从轻量路由生成用户、标的及持仓方向分组键。
         */
        LiquidationKey key() { return new LiquidationKey(userId, instrumentId, positionSide); }
    }
    /**
     * 按强平 ID 读取最近应用的轻量路由，包括保留的终态路由。
     * @param id 需要读取路由的强平业务 ID
     */
    public LiquidationRoute route(long id) { return keysById.get(id); }

    /**
     * 遍历路由找未结束任务；标的非空时只看该文本，空值表示检查全部标的。
     * @param instrumentId 规范数字标的文本，分组比较仍采用文本字典序；hasUnresolved 可空表示全部
     */
    public boolean hasUnresolved(String instrumentId) {
        for (LiquidationRoute /* 当前检查是否未结束且匹配指定标的的轻量路由 */ route : keysById.values())
            if (route.unresolved() && (instrumentId == null || route.instrumentId().equals(instrumentId))) return true;
        return false;
    }

    /**
     * 先按旧路由撤掉未结束集合，再从账户运行时状态建立新路由；新状态非空保留身份，未结束时加入全局和分组集合，空值删除。
     * @param liquidationId 需要新增、更新或移除的强平业务 ID
     * @param after 账户已变更的真实强平运行时状态，空表示删除
     * @param identities 把账户内部标的索引还原为协议数字文本的原身份表
     */
    public void apply(long liquidationId, LiquidationRuntime after, RuntimeIdentityRegistry identities) {
        // 该 ID 原路由，未结束时先撤掉旧集合成员
        LiquidationRoute previous = keysById.remove(liquidationId);
        if (previous != null && previous.unresolved()) remove(liquidationId, previous.key());
        if (after != null) {
            // 按新账户强平状态和整数身份表生成的路由
            LiquidationRoute current = new LiquidationRoute(after.userId(), identities.instrumentId(after.symbolId()),
                    after.positionSide(), isActive(after), after.status() == CoreLiquidationState.Status.PLANNED
                    || after.status() == CoreLiquidationState.Status.ORDERED);
            keysById.put(liquidationId, current);
            if (current.unresolved()) add(liquidationId, current.key());
        }
    }

    /**
     * 清全局、分组及反向路由表，再从冷状态风险分量的强平逐个重建。
     * @param state 索引重建使用的本产品线已提交冷状态
     */
    public void rebuild(TradingCoreState state) {
        activeIds.clear();
        allActiveIds.clear();
        keysById.clear();
        state.riskState().liquidations().values().forEach(this::add);
    }

    /**
     * 冷强平状态非空且既未 COMPLETED 也未 CANCELED 即视为未结束，包括保险及 ADL 待处理阶段。
     * @param value 需要判断未结束、增加或移除的强平状态/原路由
     */
    private static boolean isActive(CoreLiquidationState value) {
        return value != null && value.status() != CoreLiquidationState.Status.COMPLETED
                && value.status() != CoreLiquidationState.Status.CANCELED;
    }

    /**
     * 对运行时强平使用同样的未结束判断，不把它等同于可执行减仓。
     * @param value 需要判断未结束、增加或移除的强平状态/原路由
     */
    private static boolean isActive(LiquidationRuntime value) {
        return value != null && value.status() != CoreLiquidationState.Status.COMPLETED
                && value.status() != CoreLiquidationState.Status.CANCELED;
    }

    /**
     * 从冷强平值登记路由；所有状态保留路由，未结束状态另加入分组和全局集合。
     * @param value 需要判断未结束、增加或移除的强平状态/原路由
     */
    private void add(CoreLiquidationState value) {
        // 从冷强平值生成的用户、标的及持仓方向分组键
        LiquidationKey key = new LiquidationKey(value.userId(), value.instrumentId(), value.positionSide());
        keysById.put(value.liquidationId(), new LiquidationRoute(value.userId(), value.instrumentId(),
                value.positionSide(), isActive(value), value.status() == CoreLiquidationState.Status.PLANNED
                || value.status() == CoreLiquidationState.Status.ORDERED));
        if (isActive(value)) add(value.liquidationId(), key);
    }

    /**
     * 把未结束强平 ID 加入全局升序集合和对应分组升序桶。
     * @param liquidationId 需要新增、更新或移除的强平业务 ID
     * @param key 用户、标的文本及持仓方向组成的原分组键
     */
    private void add(long liquidationId, LiquidationKey key) {
        allActiveIds.add(liquidationId);
        activeIds.computeIfAbsent(key, /* 已经确定的强平分组键，此回调只创建空升序 ID 桶 */ ignored -> new TreeSet<>()).add(liquidationId);
    }

    /**
     * 从冷强平状态还原旧分组，移除该路由及其未结束集合成员。
     * @param value 需要判断未结束、增加或移除的强平状态/原路由
     */
    private void remove(CoreLiquidationState value) {
        // 从冷强平值生成的用户、标的及持仓方向分组键
        LiquidationKey key = new LiquidationKey(value.userId(), value.instrumentId(), value.positionSide());
        keysById.remove(value.liquidationId());
        remove(value.liquidationId(), key);
    }

    /**
     * 从全局和指定分组移除 ID，分组变空时删键。
     * @param liquidationId 需要新增、更新或移除的强平业务 ID
     * @param key 用户、标的文本及持仓方向组成的原分组键
     */
    private void remove(long liquidationId, LiquidationKey key) {
        allActiveIds.remove(liquidationId);
        // 当前查询或移除的分组 ID 升序桶
        NavigableSet<Long> ids = activeIds.get(key);
        if (ids == null) return;
        ids.remove(liquidationId);
        if (ids.isEmpty()) activeIds.remove(key);
    }

    /**
     * 未结束强平的用户、标的和持仓方向分组键；不包含保证金模式。
     * @param userId 该强平任务所属用户
     * @param instrumentId 规范数字标的文本，分组比较仍采用文本字典序；hasUnresolved 可空表示全部
     * @param positionSide 该任务对应净仓、多头或空头持仓腿
     */
    private record LiquidationKey(long userId, String instrumentId, CorePositionSide positionSide)
            implements Comparable<LiquidationKey> {
        /**
         * 要求标的文本符合规范数字身份，其他字段按既有调用约定提供。
         */
        private LiquidationKey {
            instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        }

        /**
         * 按用户 ID、标的文本字典序、持仓方向枚举序号比较，不把标的文本当作数值大小排序。
         * @param other 按同一分组规则需要比较的另一键
         */
        @Override
        public int compareTo(LiquidationKey other) {
            // 两分组用户 ID 的比较结果，非零时直接返回
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            // 同用户下标的文本字典序比较结果
            int symbolCompare = instrumentId.compareTo(other.instrumentId);
            return symbolCompare != 0 ? symbolCompare : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
