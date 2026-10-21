package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.command.ImmutableLongArrayList;

import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import com.surprising.product.api.utils.InstrumentIds;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import it.unimi.dsi.fastutil.longs.LongSortedSets;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.CoreTriggerPriceSource;

/**
 * Owner 持有的触发单派生索引。完整业务正文由账户 Lane 保存，此处按提交结果维护路由、身份、用户、持仓腿、OCO、到期和价格候选；查询多为借用只读活视图，使用期间须遵守现有 Owner 稳定边界。候选价格命中不等于已通过执行校验。
 */
public final class TriggerOrderIndex {

    // 这些都是 Owner 派生索引，apply、remove 与 rebuild 跟随已经提交的账户 Lane 状态。
    /** 按标的整数身份组织的倒序触发 ID 桶，含所有保留状态。 */
    private final Int2ObjectOpenHashMap<LongSortedSet> idsBySymbol = new Int2ObjectOpenHashMap<>();
    /** 按标的再按状态组织的倒序 ID 桶，用于筛选及固定最大待触发 ID。 */
    private final Int2ObjectOpenHashMap<Map<CoreTriggerOrderStatus, LongSortedSet>> idsBySymbolStatus = new Int2ObjectOpenHashMap<>();
    /** 跨标的按状态组织的倒序 ID 桶。 */
    private final Map<CoreTriggerOrderStatus, LongSortedSet> idsByStatus = new java.util.EnumMap<>(CoreTriggerOrderStatus.class);
    /** 按用户组织的倒序 ID 桶，供用户查询和已平仓关联触发筛选。 */
    private final Long2ObjectOpenHashMap<LongSortedSet> idsByUser = new Long2ObjectOpenHashMap<>();
    /** 全部已索引触发 ID 的倒序集合，支持查询游标从指定位置开始。 */
    private final LongSortedSet allIds = descendingIds();
    /** 用户与客户端触发 ID 到业务 ID 的派生查重表，随保留状态的 apply/remove/rebuild 维护。 */
    private final Object2LongOpenHashMap<ClientTriggerKey> idsByClient = new Object2LongOpenHashMap<>();
    /** 用户、标的、保证金模式及方向到自然升序 ID 集，包含非待触发状态。 */
    private final Map<TriggerPositionKey, LongSortedSet> idsByPosition = new java.util.HashMap<>();
    /** 相同用户及持仓腿 OCO 组的 PENDING 倒序 ID 集，包含组内主单自身。 */
    private final Map<TriggerOcoKey, LongSortedSet> idsByOco = new java.util.HashMap<>();
    /** 标的与价格来源到五类候选价格桶，只索引待触发状态。 */
    private final Map<PriceKey, TriggerPriceBuckets> idsByPrice = new java.util.HashMap<>();
    /** 正到期时间到自然升序 ID 桶，外层时间键自然升序，只含待触发单。 */
    private final Long2ObjectAVLTreeMap<LongSortedSet> idsByExpiry = new Long2ObjectAVLTreeMap<>();
    /** Owner 已应用的轻量触发路由，反向移除旧桶依赖它；真实账户执行正文仍在 Lane。 */
    private final Long2ObjectOpenHashMap<TriggerRoute> valuesById = new Long2ObjectOpenHashMap<>();

    /**
     * 创建按 long 数值倒序排列的 primitive ID 集合，供大部分查询和同组遍历使用。
     */
    private static LongSortedSet descendingIds() {
        return new LongAVLTreeSet(LongComparators.OPPOSITE_COMPARATOR);
    }

    /**
     * 不可变候选路由，保存身份、状态、价格与追踪条件及取消修订可用标志；数量、费用和完整执行正文仍由账户 Lane 保管。
     * @param triggerOrderId 触发单业务 ID，使用原 long 身份
     * @param userId 触发单所属用户，零筛选语义由具体查询入口决定
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param clientTriggerOrderId 用户范围内的客户端触发 ID，空值不进入客户端查重表
     * @param ocoGroupId 该单所属 OCO 文本组 ID，空文本表示不分组
     * @param marginMode 该触发关联持仓的全仓或逐仓模式
     * @param positionSide 该触发关联的净仓、多头或空头持仓腿
     * @param status 需要索引或筛选的触发状态，只有 PENDING 进入候选桶
     * @param expiresAtEpochMillis 触发单到期毫秒时间戳，正数才登记到期桶
     * @param priceSource 触发阈值判断使用的 MARK、LAST 或 INDEX 行情来源
     * @param triggerType 普通止盈止损或追踪止损类型
     * @param triggerCondition 普通阈值的大于等于或小于等于方向
     * @param triggerPriceTicks 普通触发单价格阈值，单位为价格 tick
     * @param side 触发子单买卖方向，追踪单据此选择高点回落或低点反弹
     * @param activationPriceTicks 追踪激活价格，零表示没有额外激活价条件
     * @param callbackRatePpm 追踪回调比例，单位为百万分之一
     * @param highestPriceTicks 追踪单已记录的最高价格 tick
     * @param lowestPriceTicks 追踪单已记录的最低价格 tick
     * @param activatedAtEpochMillis 追踪单激活毫秒时间，非正视为尚未激活
     * @param cancelRevisionAvailable 路由生成时触发修订是否小于 long 最大值，可否再加一取消
     */
    public record TriggerRoute(long triggerOrderId, long userId, int instrumentId,
            String clientTriggerOrderId, String ocoGroupId, CoreMarginMode marginMode,
            CorePositionSide positionSide, CoreTriggerOrderStatus status, long expiresAtEpochMillis,
            CoreTriggerPriceSource priceSource, CoreTriggerOrderType triggerType,
            com.surprising.aeron.protocol.CoreTriggerCondition triggerCondition, long triggerPriceTicks,
            com.surprising.aeron.protocol.CoreOrderSide side, long activationPriceTicks,
            long callbackRatePpm, long highestPriceTicks, long lowestPriceTicks, long activatedAtEpochMillis,
            boolean cancelRevisionAvailable) {
        /**
         * 从完整触发单复制候选定位所需字段，并把修订是否还能加一压成取消可用标志；不搬运下单数量、费率或完整执行正文。
         * @param value 需要提取路由字段的完整触发状态，既可来自提交值也可来自当前命令新值
         */
        public static TriggerRoute from(CoreTriggerOrderState value) {
            return new TriggerRoute(value.triggerOrderId(), value.userId(), value.instrument().instrumentIdValue(),
                    value.clientTriggerOrderId(), value.ocoGroupId(), value.marginMode(), value.positionSide(),
                    value.status(), value.expiresAtEpochMillis(), value.priceSource(), value.triggerType(),
                    value.triggerCondition(), value.triggerPriceTicks(), value.side(), value.activationPriceTicks(),
                    value.callbackRatePpm(), value.highestPriceTicks(), value.lowestPriceTicks(), value.activatedAtEpochMillis(),
                    value.revision() < Long.MAX_VALUE);
        }
    }

    /**
     * 按触发 ID 借出最近已应用的不可变路由，不访问账户 Lane 正文。
     * @param id 当前需要查询、更新或移除的触发业务 ID
     */
    public TriggerRoute route(long id) { return valuesById.get(id); }

    /**
     * 从已提交冷状态重建所有派生索引；后续由 Owner 随真实账户提交结果增量维护。
     * @param state 用来重建索引的本产品线已提交冷状态
     */
    public TriggerOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    /**
     * 按标的返回 ID 倒序的只读活视图，没有该标的时返回共享空集合。
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     */
    public Set<Long> ids(String instrumentId) {
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = idsBySymbol.get(InstrumentIds.parse(instrumentId));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    /**
     * 返回全部已索引触发 ID 的倒序只读活视图，包括非待触发状态。
     */
    public Set<Long> ids() {
        return LongSortedSets.unmodifiable(allIds);
    }

    /**
     * 返回倒序集合从 before 开始向更小 ID 的只读活视图，包含等于游标的 ID；需要排除相等项的调用方自行过滤。
     * @param before 倒序查询的起始 ID，该范围本身包含相等项
     */
    public Set<Long> idsBefore(long before) {
        return LongSortedSets.unmodifiable(allIds.tailSet(before));
    }

    /**
     * 按状态返回倒序 ID 只读活视图，没有该状态时返回共享空集合。
     * @param status 需要索引或筛选的触发状态，只有 PENDING 进入候选桶
     */
    public Set<Long> ids(CoreTriggerOrderStatus status) {
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = idsByStatus.get(status);
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    /**
     * 同时按标的和状态返回倒序 ID 只读活视图。
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param status 需要索引或筛选的触发状态，只有 PENDING 进入候选桶
     */
    public Set<Long> ids(String instrumentId, CoreTriggerOrderStatus status) {
        // 该标的按状态组织的二级桶，没有该标的时为空
        Map<CoreTriggerOrderStatus, LongSortedSet> byStatus = idsBySymbolStatus.get(
                InstrumentIds.parse(instrumentId));
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = byStatus == null ? null : byStatus.get(status);
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    /**
     * 按用户返回倒序 primitive ID 只读活视图，调用期间仍依赖 Owner 的索引稳定边界。
     * @param userId 触发单所属用户，零筛选语义由具体查询入口决定
     */
    public LongSortedSet ids(long userId) {
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = idsByUser.get(userId);
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    /**
     * 读取该标的 PENDING 桶的第一个倒序 ID，即当前最大待触发身份；没有候选返回零。
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     */
    public long maxPendingId(String instrumentId) {
        // 该标的按状态组织的二级桶，没有该标的时为空
        Map<CoreTriggerOrderStatus, LongSortedSet> byStatus = idsBySymbolStatus.get(
                InstrumentIds.parse(instrumentId));
        // 该标的 PENDING 倒序 ID 桶，首个就是最大候选身份
        LongSortedSet pending = byStatus == null ? null : byStatus.get(CoreTriggerOrderStatus.PENDING);
        return pending == null || pending.isEmpty() ? 0 : pending.firstLong();
    }

    /**
     * 以 MARK 价格来源进入同一套分阶段候选分页。
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param markPriceTicks 当前所选来源的实际价格 tick；保留参数名 mark，但可用于其他来源
     * @param phase 单价格来源内的候选遍历阶段，零起、五表示完成
     * @param priceCursor 上页保存的价格桶游标，与阶段和 ID 游标一起恢复
     * @param orderCursor 当前桶内排他的上页最后 ID，追加时只取严格更小的 ID
     * @param upperTriggerId 本轮固定的最大待触发身份，排除之后新建的更大 ID
     * @param limit 候选页最多返回的 ID 数或到期选择上限，具体入口分别校验或直接返回空
     */
    public TriggerCandidatePage candidatesPage(String instrumentId, long markPriceTicks,
                                                int phase, long priceCursor, long orderCursor,
                                                long upperTriggerId, int limit) {
        return candidatesPage(instrumentId, CoreTriggerPriceSource.MARK, markPriceTicks, phase,
                priceCursor, orderCursor, upperTriggerId, limit);
    }

    /**
     * 把规范数字标的文本解析成整数，再按指定价格来源分页。
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param source 当前查询的行情来源枚举
     * @param markPriceTicks 当前所选来源的实际价格 tick；保留参数名 mark，但可用于其他来源
     * @param phase 单价格来源内的候选遍历阶段，零起、五表示完成
     * @param priceCursor 上页保存的价格桶游标，与阶段和 ID 游标一起恢复
     * @param orderCursor 当前桶内排他的上页最后 ID，追加时只取严格更小的 ID
     * @param upperTriggerId 本轮固定的最大待触发身份，排除之后新建的更大 ID
     * @param limit 候选页最多返回的 ID 数或到期选择上限，具体入口分别校验或直接返回空
     */
    public TriggerCandidatePage candidatesPage(String instrumentId, CoreTriggerPriceSource source,
                                                long markPriceTicks, int phase, long priceCursor,
                                                long orderCursor, long upperTriggerId, int limit) {
        return candidatesPage(InstrumentIds.parse(instrumentId), source, markPriceTicks, phase,
                priceCursor, orderCursor, upperTriggerId, limit);
    }

    /**
     * 校验页上限一至四千零九十六，按普通大于等于、普通小于等于、两类追踪阈值和无条件追踪阶段遍历候选桶；每桶 ID 倒序并受固定上界与排他 ID 游标约束。达到页上限先返回未完成，可能需空尾页才确认结束；这里只筛选候选，不执行完整触发校验。
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param source 当前查询的行情来源枚举
     * @param markPriceTicks 当前所选来源的实际价格 tick；保留参数名 mark，但可用于其他来源
     * @param phase 单价格来源内的候选遍历阶段，零起、五表示完成
     * @param priceCursor 上页保存的价格桶游标，与阶段和 ID 游标一起恢复
     * @param orderCursor 当前桶内排他的上页最后 ID，追加时只取严格更小的 ID
     * @param upperTriggerId 本轮固定的最大待触发身份，排除之后新建的更大 ID
     * @param limit 候选页最多返回的 ID 数或到期选择上限，具体入口分别校验或直接返回空
     */
    public TriggerCandidatePage candidatesPage(int instrumentId, CoreTriggerPriceSource source,
                                                long markPriceTicks, int phase, long priceCursor,
                                                long orderCursor, long upperTriggerId, int limit) {
        if (limit <= 0 || limit > 4_096) {
            throw new IllegalArgumentException("candidate page limit must be in [1,4096]");
        }
        // 当前标的及来源的五类候选桶，追踪和普通单分别维护
        TriggerPriceBuckets price = idsByPrice.get(new PriceKey(instrumentId, source));
        if (price == null || markPriceTicks <= 0 || upperTriggerId <= 0 || phase >= PHASE_COMPLETE) {
            return TriggerCandidatePage.emptyPage();
        }
        // 本页正在处理并准备返回的单来源阶段
        int nextPhase = Math.max(phase, PHASE_GREATER_OR_EQUAL);
        // 本页价格游标，阶段结束后重置，页满时保存实际桶价
        long nextPrice = priceCursor;
        // 当前价格桶的排他 ID 游标，换桶后重置为 long 最大值
        long nextOrder = orderCursor;
        // 候选页在价格更新热路径使用，直接收集 primitive long，避免每页先装箱再做第二次防御复制。
        // 本次候选页的 primitive long 构造器，避免先建装箱列表再复制
        ImmutableLongArrayList.Builder result = new ImmutableLongArrayList.Builder(limit);
        for (; nextPhase < PHASE_TRAILING_ALWAYS; nextPhase++) {
            // 当前候选阶段选用的普通或追踪价格树
            Long2ObjectAVLTreeMap<LongSortedSet> buckets = switch (nextPhase) {
                case PHASE_GREATER_OR_EQUAL -> price.greaterOrEqual;
                case PHASE_LESS_OR_EQUAL -> price.lessOrEqual;
                case PHASE_TRAILING_GREATER_OR_EQUAL -> price.trailingGreaterOrEqual;
                default -> price.trailingLessOrEqual;
            };
            // 本阶段是否按价格升序走；另一方向用同一双向迭代器向前驱走
            boolean ascending = nextPhase == PHASE_LESS_OR_EQUAL || nextPhase == PHASE_TRAILING_GREATER_OR_EQUAL;
            // 已定位本阶段可用价格区间的双向 primitive 迭代器
            LongBidirectionalIterator prices = priceIterator(buckets, markPriceTicks, nextPhase, nextPrice, nextOrder);
            while (ascending ? prices.hasNext() : prices.hasPrevious()) {
                // 当前遍历的价格阈值，用于页满时保存续步位置
                long bucketPrice = ascending ? prices.nextLong() : prices.previousLong();
                // 本桶本次最后追加的 ID，没有追加则为零
                long last = appendBucket(result, buckets.get(bucketPrice), upperTriggerId, nextOrder, limit);
                if (result.size() >= limit) return page(result, nextPhase, bucketPrice, last, false);
                nextOrder = Long.MAX_VALUE;
            }
            nextPrice = 0;
            nextOrder = Long.MAX_VALUE;
        }
        if (nextPhase == PHASE_TRAILING_ALWAYS) {
            // 本桶本次最后追加的 ID，没有追加则为零
            long last = appendBucket(result, price.trailingAlways, upperTriggerId, nextOrder, limit);
            if (result.size() >= limit) return page(result, PHASE_TRAILING_ALWAYS, 0, last, false);
        }
        return page(result, PHASE_COMPLETE, 0, 0, true);
    }

    /** 单来源阶段零：普通 price 大于等于触发阈值的候选。 */
    public static final int PHASE_GREATER_OR_EQUAL = 0;
    /** 单来源阶段一：普通 price 小于等于触发阈值的候选。 */
    public static final int PHASE_LESS_OR_EQUAL = 1;
    /** 单来源阶段二：需要沿较大阈值区间查找的追踪候选，包含已激活卖出回落或未激活买入条件。 */
    public static final int PHASE_TRAILING_GREATER_OR_EQUAL = 2;
    /** 单来源阶段三：需要沿较小阈值区间查找的追踪候选，包含已激活买入反弹或未激活卖出条件。 */
    public static final int PHASE_TRAILING_LESS_OR_EQUAL = 3;
    /** 单来源阶段四：没有激活阈值、需要每轮进入判断的追踪候选。 */
    public static final int PHASE_TRAILING_ALWAYS = 4;
    /** 单价格来源阶段五表示该来源候选遍历完成，命令扫描还用它编码来源编号。 */
    public static final int PHASE_COMPLETE = 5;

    /**
     * 每阶段只定位一次价格范围，再沿合适方向走符合当前价的桶；有未耗尽 ID 游标时保留当前价格桶续读，已耗尽桶则按游标跳过。
     * @param buckets 本阶段采用的有序价格到倒序 ID 桶映射
     * @param mark 当前所选来源的实际价格 tick，供限定价格区间
     * @param phase 单价格来源内的候选遍历阶段，零起、五表示完成
     * @param cursor 本阶段上次价格桶位置，和 orderCursor 一起决定是否续读该桶
     * @param orderCursor 当前桶内排他的上页最后 ID，追加时只取严格更小的 ID
     */
    private static LongBidirectionalIterator priceIterator(Long2ObjectAVLTreeMap<LongSortedSet> buckets,
            long mark, int phase, long cursor, long orderCursor) {
        // 本阶段是否按价格升序走；另一方向用同一双向迭代器向前驱走
        boolean ascending = phase == PHASE_LESS_OR_EQUAL || phase == PHASE_TRAILING_GREATER_OR_EQUAL;
        // 该阶段的初始价格游标哨兵，大于等于两类为 long 最大值，其余为零
        long initialCursor = phase == PHASE_GREATER_OR_EQUAL || phase == PHASE_TRAILING_GREATER_OR_EQUAL
                ? Long.MAX_VALUE : 0;
        // 价格和 ID 游标是否仍是本阶段初始哨兵组合
        boolean initial = cursor == initialCursor && orderCursor == Long.MAX_VALUE;
        if (ascending) {
            if (initial) return buckets.tailMap(mark).keySet().iterator();
            if (orderCursor != Long.MAX_VALUE) return buckets.tailMap(Math.max(mark, cursor)).keySet().iterator();
            return buckets.tailMap(mark).keySet().iterator(cursor);
        }
        // 倒向价格遍历采用的不超过当前价和已保存游标的上界
        long upper = initial ? mark : Math.min(mark, cursor);
        // 定位后的价格或 ID primitive 迭代器，按所在方法继续遍历
        LongBidirectionalIterator iterator = buckets.keySet().iterator(upper);
        if (!initial && orderCursor == Long.MAX_VALUE && upper == cursor && buckets.containsKey(cursor))
            iterator.previousLong();
        return iterator;
    }

    /**
     * 向 primitive 页追加桶内倒序 ID，只接受不超过固定上界且严格小于 orderCursor 的 ID；返回最后追加值，没有追加返回零。初始游标若为 long 最大值，也会排除等于该值的 ID。
     * @param result 本页 primitive ID 构造器，追加后冻结交出
     * @param ids 候选页 ID 列表或当前待追加桶的倒序 ID 集，保持已有顺序
     * @param upperTriggerId 本轮固定的最大待触发身份，排除之后新建的更大 ID
     * @param orderCursor 当前桶内排他的上页最后 ID，追加时只取严格更小的 ID
     * @param limit 候选页最多返回的 ID 数或到期选择上限，具体入口分别校验或直接返回空
     */
    private static long appendBucket(ImmutableLongArrayList.Builder result, LongSortedSet ids,
                                     long upperTriggerId, long orderCursor, int limit) {
        if (orderCursor <= 0) return 0;
        // 桶内可选最大 ID，取固定上界与排他游标减一的较小者
        long start = Math.min(upperTriggerId, orderCursor - 1);
        // 定位后的价格或 ID primitive 迭代器，按所在方法继续遍历
        var iterator = ids.tailSet(start).iterator();
        // 本桶本次最后追加的 ID，没有追加则为零
        long last = 0;
        while (iterator.hasNext() && result.size() < limit) {
            last = iterator.nextLong();
            result.add(last);
        }
        return last;
    }

    /**
     * 冻结当前 primitive 构造器并附上阶段、价格及 ID 游标，形成可交出的候选页。
     * @param result 本页 primitive ID 构造器，追加后冻结交出
     * @param nextPhase 下一次继续使用的单来源阶段，完成时为五
     * @param nextPriceCursor 下一次恢复所需的价格桶游标
     * @param nextOrderCursor 下一次恢复所需的排他 ID 游标
     * @param complete 该价格来源候选是否已经遍历到结尾
     */
    private static TriggerCandidatePage page(ImmutableLongArrayList.Builder result, int nextPhase,
                                              long nextPriceCursor, long nextOrderCursor,
                                              boolean complete) {
        return new TriggerCandidatePage(result.freeze(), nextPhase, nextPriceCursor, nextOrderCursor, complete);
    }

    /**
     * 一页固定 ID 与单来源续步游标；只代表索引筛出的候选和是否遍历完，不是触发执行结果。
     * @param ids 候选页 ID 列表或当前待追加桶的倒序 ID 集，保持已有顺序
     * @param nextPhase 下一次继续使用的单来源阶段，完成时为五
     * @param nextPriceCursor 下一次恢复所需的价格桶游标
     * @param nextOrderCursor 下一次恢复所需的排他 ID 游标
     * @param complete 该价格来源候选是否已经遍历到结尾
     */
    public record TriggerCandidatePage(List<Long> ids, int nextPhase, long nextPriceCursor,
                                       long nextOrderCursor, boolean complete) {
        /**
         * 空列表统一为空页内容，primitive 不可变列表直接复用，其他列表做只读复制；仅校验阶段在零到完成阶段内，不在此验证全部游标组合。
         */
        public TriggerCandidatePage {
            ids = ids == null || ids.isEmpty() ? List.of()
                    : ids instanceof ImmutableLongArrayList ? ids : List.copyOf(ids);
            if (nextPhase < PHASE_GREATER_OR_EQUAL || nextPhase > PHASE_COMPLETE) {
                throw new IllegalArgumentException("invalid trigger candidate cursor");
            }
        }

        /**
         * 优先从 primitive 列表直接读 long，其他列表按原下标访问，供热路径避免额外装箱。
         * @param index 当前页要读取的 ID 下标
         */
        public long idAt(int index) {
            if (ids instanceof ImmutableLongArrayList /* 已经是不可变 primitive long 列表的页内容，可直接按下标读 long */ primitive) return primitive.valueAt(index);
            return ids.get(index);
        }

        /**
         * 创建已完成的空候选页，阶段为完成、两个游标为零。
         */
        public static TriggerCandidatePage emptyPage() {
            return new TriggerCandidatePage(List.of(), PHASE_COMPLETE, 0, 0, true);
        }
    }

    /**
     * 按到期时间从早到晚选至给定时间为止的待触发 ID，最多 limit 个；输出集合按 ID 自然升序，选中顺序和输出顺序不是同一排序。非正时间或上限返回空。
     * @param epochMillis 到期时间查询的包含上界，单位为毫秒
     * @param limit 候选页最多返回的 ID 数或到期选择上限，具体入口分别校验或直接返回空
     */
    public Set<Long> expired(long epochMillis, int limit) {
        if (epochMillis <= 0 || limit <= 0) return Set.of();
        // 按自然 ID 升序保存已选到期触发单的结果集
        LongSortedSet result = new LongAVLTreeSet();
        // 不超过到期查询时间的升序时间桶范围，最大 long 时间时直接使用全树
        var buckets = epochMillis == Long.MAX_VALUE ? idsByExpiry : idsByExpiry.headMap(epochMillis + 1);
        for (LongSortedSet /* 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空 */ ids : buckets.values()) {
            // 定位后的价格或 ID primitive 迭代器，按所在方法继续遍历
            var iterator = ids.iterator();
            while (iterator.hasNext()) {
                result.add(iterator.nextLong());
                if (result.size() >= limit) return LongSortedSets.unmodifiable(result);
            }
        }
        return LongSortedSets.unmodifiable(result);
    }

    /**
     * 按用户、标的、保证金模式和持仓方向返回自然升序 ID 的只读活视图；该桶包含所有已索引状态，调用方再判断是否待触发。
     * @param userId 触发单所属用户，零筛选语义由具体查询入口决定
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param marginMode 该触发关联持仓的全仓或逐仓模式
     * @param positionSide 该触发关联的净仓、多头或空头持仓腿
     */
    public Set<Long> ids(long userId, String instrumentId, CoreMarginMode marginMode, CorePositionSide positionSide) {
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = idsByPosition.get(new TriggerPositionKey(userId,
                InstrumentIds.parse(instrumentId), marginMode, positionSide));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    /**
     * 从完整触发单提取同用户、标的、保证金模式、持仓方向和 OCO 组，返回待触发兄弟的倒序只读活视图，包含主单自身。
     * @param order 当前需要提取路由、归桶或移除旧桶的触发单或路由
     */
    public LongSortedSet ocoSiblingsDescending(CoreTriggerOrderState order) {
        if (order.ocoGroupId().isEmpty()) return LongSortedSets.EMPTY_SET;
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = idsByOco.get(new TriggerOcoKey(order.userId(),
                order.instrument().instrumentIdValue(), order.marginMode(), order.positionSide(),
                order.ocoGroupId()));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    /**
     * 从轻量路由提取相同 OCO 分组键，不读取账户正文；空组直接返回空集合。
     * @param order 当前需要提取路由、归桶或移除旧桶的触发单或路由
     */
    public LongSortedSet ocoSiblingsDescending(TriggerRoute order) {
        if (order.ocoGroupId().isEmpty()) return LongSortedSets.EMPTY_SET;
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = idsByOco.get(new TriggerOcoKey(order.userId(),
                order.instrumentId(), order.marginMode(), order.positionSide(),
                order.ocoGroupId()));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    /**
     * 在用户范围检查非空客户端触发 ID 是否已经索引，包含仍保留在本索引中的终态身份；不在此处理外部终态墓碑。
     * @param userId 触发单所属用户，零筛选语义由具体查询入口决定
     * @param clientTriggerOrderId 用户范围内的客户端触发 ID，空值不进入客户端查重表
     */
    public boolean containsClient(long userId, String clientTriggerOrderId) {
        return clientTriggerOrderId != null && !clientTriggerOrderId.isEmpty()
                && idsByClient.containsKey(new ClientTriggerKey(userId, clientTriggerOrderId));
    }

    /**
     * 先撤下旧路由的全部候选和身份键，再按非空当前值加入新路由；空值表示删除。调用方必须保证 id 与当前触发单自身身份一致，本方法不另校验两者。
     * @param id 当前需要查询、更新或移除的触发业务 ID
     * @param current 该 ID 提交后的完整触发状态，空表示删除；调用方保证与 id 同身份
     */
    public void apply(long id, CoreTriggerOrderState current) {
        // 该 ID 原轻量路由，先据它移除全部旧分组及客户端身份
        TriggerRoute previous = valuesById.remove(id);
        if (previous != null) {
            remove(previous, id);
            if (!previous.clientTriggerOrderId().isEmpty()) {
                idsByClient.removeLong(new ClientTriggerKey(previous.userId(), previous.clientTriggerOrderId()));
            }
        }
        if (current != null) {
            add(current);
            if (!current.clientTriggerOrderId().isEmpty()) {
                idsByClient.put(new ClientTriggerKey(current.userId(), current.clientTriggerOrderId()), id);
            }
        }
    }

    /**
     * 清空全部桶和反向路由，从冷状态触发单逐一重建，并登记非空客户端身份。
     * @param state 用来重建索引的本产品线已提交冷状态
     */
    public void rebuild(TradingCoreState state) {
        idsBySymbol.clear();
        idsBySymbolStatus.clear();
        idsByStatus.clear();
        idsByUser.clear();
        allIds.clear();
        idsByClient.clear();
        idsByPosition.clear();
        idsByOco.clear();
        idsByPrice.clear();
        idsByExpiry.clear();
        valuesById.clear();
        state.triggerOrders().values().forEach(/* 重建时当前一条完整触发状态，按其真实字段重建候选和身份桶 */ order -> {
            add(order);
            if (!order.clientTriggerOrderId().isEmpty()) {
                idsByClient.put(new ClientTriggerKey(order.userId(), order.clientTriggerOrderId()), order.triggerOrderId());
            }
        });
    }

    /**
     * 把完整触发单转成路由，再加入共同的派生索引；客户端身份由外层 apply 或 rebuild 单独维护。
     * @param order 当前需要提取路由、归桶或移除旧桶的触发单或路由
     */
    private void add(CoreTriggerOrderState order) { add(TriggerRoute.from(order)); }

    /**
     * 所有状态都加入基础身份/用户/标的/状态/持仓桶，只有 PENDING 加入 OCO、到期和候选价格桶，追踪单按当前激活或回调阈值单独归桶。
     * @param order 当前需要提取路由、归桶或移除旧桶的触发单或路由
     */
    private void add(TriggerRoute order) {
        // 当前加入或移除的路由所属用户
        long userId = order.userId();
        // 当前路由标的的规范整数身份
        int instrumentId = order.instrumentId();
        // 当前加入索引的触发单业务 ID
        long id = order.triggerOrderId();
        valuesById.put(id, order);
        allIds.add(id);
        idsBySymbol.computeIfAbsent(instrumentId, /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
        idsBySymbolStatus.computeIfAbsent(instrumentId, /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> new java.util.EnumMap<>(CoreTriggerOrderStatus.class))
                .computeIfAbsent(order.status(), /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
        idsByStatus.computeIfAbsent(order.status(), /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
        idsByUser.computeIfAbsent(userId, /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
        idsByPosition.computeIfAbsent(new TriggerPositionKey(userId,
                instrumentId, order.marginMode(), order.positionSide()),
                /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> new LongAVLTreeSet()).add(id);
        if (order.status() == CoreTriggerOrderStatus.PENDING && !order.ocoGroupId().isEmpty()) {
            idsByOco.computeIfAbsent(new TriggerOcoKey(userId, instrumentId,
                    order.marginMode(), order.positionSide(), order.ocoGroupId()), /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
        }
        if (order.status() == CoreTriggerOrderStatus.PENDING) {
            if (order.expiresAtEpochMillis() > 0) {
                idsByExpiry.computeIfAbsent(order.expiresAtEpochMillis(), /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> new LongAVLTreeSet()).add(id);
            }
            // 当前标的及来源的五类候选桶，追踪和普通单分别维护
            TriggerPriceBuckets price = idsByPrice.computeIfAbsent(new PriceKey(instrumentId, order.priceSource()),
                    /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> new TriggerPriceBuckets());
            if (order.triggerType() == CoreTriggerOrderType.TRAILING_STOP) {
                indexTrailing(price, order);
            } else if (order.triggerPriceTicks() > 0) {
                if (order.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL) {
                    price.greaterOrEqual.computeIfAbsent(order.triggerPriceTicks(), /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
                } else {
                    price.lessOrEqual.computeIfAbsent(order.triggerPriceTicks(), /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
                }
            }
        }
    }

    /**
     * 按旧路由从全部相关桶移除该 ID，空桶立即删除，最后删除空的价格分组；客户端身份仍由外层 apply 单独处理。
     * @param order 当前需要提取路由、归桶或移除旧桶的触发单或路由
     * @param id 当前需要查询、更新或移除的触发业务 ID
     */
    private void remove(TriggerRoute order, long id) {
        valuesById.remove(id);
        // 当前加入或移除的路由所属用户
        long userId = order.userId();
        // 当前路由标的的规范整数身份
        int instrumentId = order.instrumentId();
        allIds.remove(id);
        // 原用户倒序 ID 集，移除后为空则删除用户桶
        LongSortedSet userIds = idsByUser.get(userId);
        if (userIds != null) {
            userIds.remove(id);
            if (userIds.isEmpty()) idsByUser.remove(userId);
        }
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = idsBySymbol.get(instrumentId);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) idsBySymbol.remove(instrumentId);
        }

        // 该标的按状态组织的二级桶，没有该标的时为空
        Map<CoreTriggerOrderStatus, LongSortedSet> byStatus = idsBySymbolStatus.get(
                instrumentId);
        if (byStatus != null) {
            // 原标的或全局状态对应的倒序 ID 桶，移除后检查是否需要删桶
            LongSortedSet statusIds = byStatus.get(order.status());
            if (statusIds != null) {
                statusIds.remove(id);
                if (statusIds.isEmpty()) byStatus.remove(order.status());
            }
            if (byStatus.isEmpty()) idsBySymbolStatus.remove(instrumentId);
        }
        // 原标的或全局状态对应的倒序 ID 桶，移除后检查是否需要删桶
        LongSortedSet statusIds = idsByStatus.get(order.status());
        if (statusIds != null) {
            statusIds.remove(id);
            if (statusIds.isEmpty()) idsByStatus.remove(order.status());
        }

        // 用户、整数标的、保证金模式及持仓方向组成的原持仓分组键
        TriggerPositionKey positionKey = new TriggerPositionKey(userId,
                instrumentId, order.marginMode(), order.positionSide());
        // 原持仓分组的自然升序 ID 集
        LongSortedSet positionIds = idsByPosition.get(positionKey);
        if (positionIds != null) {
            positionIds.remove(id);
            if (positionIds.isEmpty()) idsByPosition.remove(positionKey);
        }
        if (!order.ocoGroupId().isEmpty()) {
            // 旧路由的同用户、标的、持仓腿及组 ID 组合键
            TriggerOcoKey ocoKey = new TriggerOcoKey(userId, instrumentId,
                    order.marginMode(), order.positionSide(), order.ocoGroupId());
            // 旧 OCO 分组的待触发倒序 ID 桶，空后删除组键
            LongSortedSet ocoIds = idsByOco.get(ocoKey);
            if (ocoIds != null) {
                ocoIds.remove(id);
                if (ocoIds.isEmpty()) idsByOco.remove(ocoKey);
            }
        }
        if (order.status() == CoreTriggerOrderStatus.PENDING && order.expiresAtEpochMillis() > 0) {
            // 旧到期时间的自然升序 ID 桶，空后删除时间键
            LongSortedSet expiryIds = idsByExpiry.get(order.expiresAtEpochMillis());
            if (expiryIds != null) {
                expiryIds.remove(id);
                if (expiryIds.isEmpty()) idsByExpiry.remove(order.expiresAtEpochMillis());
            }
        }
        // 旧路由的整数标的与行情来源组合键
        PriceKey priceKey = new PriceKey(instrumentId, order.priceSource());
        // 当前标的及来源的五类候选桶，追踪和普通单分别维护
        TriggerPriceBuckets price = idsByPrice.get(priceKey);
        if (price == null) return;
        removeTrailing(price, order, id);
        if (order.triggerPriceTicks() > 0) {
            // 普通阈值按大于等于或小于等于选择的旧价格树
            Long2ObjectAVLTreeMap<LongSortedSet> map = order.triggerCondition()
                    == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                    ? price.greaterOrEqual : price.lessOrEqual;
            // 旧普通阈值的倒序 ID 桶，空后删除该价格键
            LongSortedSet priceIds = map.get(order.triggerPriceTicks());
            if (priceIds != null) {
                priceIds.remove(id);
                if (priceIds.isEmpty()) map.remove(order.triggerPriceTicks());
            }
        }
        if (price.greaterOrEqual.isEmpty() && price.lessOrEqual.isEmpty()
                && price.trailingGreaterOrEqual.isEmpty() && price.trailingLessOrEqual.isEmpty()
                && price.trailingAlways.isEmpty()) {
            idsByPrice.remove(priceKey);
        }
    }

    /**
     * 同一标的及行情来源的五类候选桶，价格树自然升序、每个桶内 ID 倒序，和已应用路由保持一致。
     */
    private static final class TriggerPriceBuckets {
        /** 普通大于等于条件的阈值升序树，桶内 ID 倒序。 */
        private final Long2ObjectAVLTreeMap<LongSortedSet> greaterOrEqual = new Long2ObjectAVLTreeMap<>();
        /** 普通小于等于条件的阈值升序树，桶内 ID 倒序。 */
        private final Long2ObjectAVLTreeMap<LongSortedSet> lessOrEqual = new Long2ObjectAVLTreeMap<>();
        /** 已激活卖出回撤阈值或未激活买入激活价树，候选按本阶段区间选择。 */
        private final Long2ObjectAVLTreeMap<LongSortedSet> trailingGreaterOrEqual = new Long2ObjectAVLTreeMap<>();
        /** 已激活买入反弹阈值或未激活卖出激活价树，候选按本阶段区间选择。 */
        private final Long2ObjectAVLTreeMap<LongSortedSet> trailingLessOrEqual = new Long2ObjectAVLTreeMap<>();
        /** 没有激活价且尚无可用回调阈值的追踪 ID 倒序集合。 */
        private final LongSortedSet trailingAlways = descendingIds();
    }

    /**
     * 追踪已激活且基价回调率有效时按当前回调阈值归桶；否则有激活价按买卖方向放入激活范围桶，没有激活价则每轮作为候选。
     * @param price 追踪分组的价格桶，或单个阈值价格，具体由方法签名区分
     * @param order 当前需要提取路由、归桶或移除旧桶的触发单或路由
     */
    private static void indexTrailing(TriggerPriceBuckets price, TriggerRoute order) {
        // 当前追踪单是否卖出，卖出跟最高价、买入跟最低价
        boolean sell = order.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
        if (order.activatedAtEpochMillis() > 0) {
            // 已经激活的追踪卖出高点或买入低点
            long base = sell ? order.highestPriceTicks() : order.lowestPriceTicks();
            if (base > 0 && order.callbackRatePpm() > 0) {
                // 基价乘回调比例后按百万分之一向下取整的价差
                long delta = Math.floorDiv(Math.multiplyExact(base, order.callbackRatePpm()), 1_000_000L);
                // 追踪卖出高点减价差或买入低点加价差的当前回调阈值
                long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
                addPriceId(sell ? price.trailingGreaterOrEqual : price.trailingLessOrEqual, threshold,
                        order.triggerOrderId());
                return;
            }
        }
        if (order.activationPriceTicks() > 0) {
            addPriceId(sell ? price.trailingLessOrEqual : price.trailingGreaterOrEqual,
                    order.activationPriceTicks(), order.triggerOrderId());
            return;
        }
        price.trailingAlways.add(order.triggerOrderId());
    }

    /**
     * 依据旧路由重复同一套追踪归桶规则，移除旧阈值或无条件候选中的 ID，供后续更新重新归桶。
     * @param price 追踪分组的价格桶，或单个阈值价格，具体由方法签名区分
     * @param order 当前需要提取路由、归桶或移除旧桶的触发单或路由
     * @param id 当前需要查询、更新或移除的触发业务 ID
     */
    private static void removeTrailing(TriggerPriceBuckets price, TriggerRoute order, long id) {
        // 当前追踪单是否卖出，卖出跟最高价、买入跟最低价
        boolean sell = order.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
        if (order.activatedAtEpochMillis() > 0) {
            // 已经激活的追踪卖出高点或买入低点
            long base = sell ? order.highestPriceTicks() : order.lowestPriceTicks();
            if (base > 0 && order.callbackRatePpm() > 0) {
                // 基价乘回调比例后按百万分之一向下取整的价差
                long delta = Math.floorDiv(Math.multiplyExact(base, order.callbackRatePpm()), 1_000_000L);
                // 追踪卖出高点减价差或买入低点加价差的当前回调阈值
                long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
                removePriceId(sell ? price.trailingGreaterOrEqual : price.trailingLessOrEqual, threshold, id);
                return;
            }
        }
        if (order.activationPriceTicks() > 0) {
            removePriceId(sell ? price.trailingLessOrEqual : price.trailingGreaterOrEqual,
                    order.activationPriceTicks(), id);
            return;
        }
        price.trailingAlways.remove(id);
    }

    /**
     * 取得或创建指定阈值的倒序 ID 桶并加入触发身份。
     * @param map 需要加入或移除指定价格阈值的 ID 桶映射
     * @param price 追踪分组的价格桶，或单个阈值价格，具体由方法签名区分
     * @param id 当前需要查询、更新或移除的触发业务 ID
     */
    private static void addPriceId(Long2ObjectAVLTreeMap<LongSortedSet> map, long price, long id) {
        map.computeIfAbsent(price, /* computeIfAbsent 提供的已确定分组键，本回调只需要创建空桶 */ ignored -> descendingIds()).add(id);
    }

    /**
     * 移除指定阈值桶中的 ID，桶变空时连价格键一起删掉。
     * @param map 需要加入或移除指定价格阈值的 ID 桶映射
     * @param price 追踪分组的价格桶，或单个阈值价格，具体由方法签名区分
     * @param id 当前需要查询、更新或移除的触发业务 ID
     */
    private static void removePriceId(Long2ObjectAVLTreeMap<LongSortedSet> map, long price, long id) {
        // 本次找到的原 ID 桶，返回时包装只读活视图或在移除后检查是否为空
        LongSortedSet ids = map.get(price);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) map.remove(price);
    }

    /**
     * 精确定位用户同标的、保证金模式及持仓方向的触发集合键。
     * @param userId 触发单所属用户，零筛选语义由具体查询入口决定
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param marginMode 该触发关联持仓的全仓或逐仓模式
     * @param positionSide 该触发关联的净仓、多头或空头持仓腿
     */
    private record TriggerPositionKey(long userId, int instrumentId, CoreMarginMode marginMode,
                                      CorePositionSide positionSide) {
    }

    /**
     * OCO 分组键，同组只在相同用户、标的及持仓腿内联动。
     * @param userId 触发单所属用户，零筛选语义由具体查询入口决定
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param marginMode 该触发关联持仓的全仓或逐仓模式
     * @param positionSide 该触发关联的净仓、多头或空头持仓腿
     * @param groupId 同用户、标的及持仓腿内的 OCO 文本组身份
     */
    private record TriggerOcoKey(long userId, int instrumentId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, String groupId) {
    }

    /**
     * 用户范围内的客户端触发 ID 身份键。
     * @param userId 触发单所属用户，零筛选语义由具体查询入口决定
     * @param clientTriggerOrderId 用户范围内的客户端触发 ID，空值不进入客户端查重表
     */
    private record ClientTriggerKey(long userId, String clientTriggerOrderId) {
    }

    /**
     * 标的整数身份与行情来源组合，防止不同价格来源共用候选树。
     * @param instrumentId 标的身份，文本入口使用规范数字 ID，索引键和路由保存其整数值
     * @param source 当前查询的行情来源枚举
     */
    private record PriceKey(int instrumentId, CoreTriggerPriceSource source) {
    }
}
