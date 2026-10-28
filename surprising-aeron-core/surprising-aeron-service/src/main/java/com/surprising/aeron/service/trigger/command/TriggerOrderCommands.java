package com.surprising.aeron.service.trigger.command;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.risk.RiskScanRuntime;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 触发业务命令及续步入口：条件登记后由价格预算扫描选候选，OCO、追踪、过期和重试沿用原状态转换，再把子单交现有准入、撮合和结算。Owner 管页与交接，账户 Lane 改触发和资金，必要的市场未平仓量由唯一账本读取；bool 完成值只针对当前页或当前交接。
 */
public final class TriggerOrderCommands {
    /**
     * 直接控制槽使用的触发单变更种类，各项对应原触发状态转换。
     */
    public enum Mutation {
        /** 撤销触发单。 */
        CANCEL,
        /** 按触发行情认领执行。 */
        CLAIM,
        /** 登记子单执行成功或失败结果。 */
        COMPLETE,
        /** 更新追踪最高价、最低价及激活时间。 */
        TRAILING,
        /** 按业务时间检查并写入过期。 */
        EXPIRE,
        /** 按陈旧界限恢复允许重试的触发状态。 */
        RETRY
    }

    /** 本产品线交易 Owner，串行推进本类命令并管理提交边界。 */
    private final TradingCoreRuntime owner;

    /**  Owner 复用的已平仓关联触发 ID 缓冲，后续复制入既有提交事件，下一命令前可重用。 借出 Owner 当前复用的清仓触发单 ID 缓冲，调用方在下次清空或重新选择前消费。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final PrimitiveLongChangeSet closingTriggerIds = new PrimitiveLongChangeSet();
    /** 无扫描或不再待执行时共享的立即完成回调，不承载真实扫描进度。 */
    private static final java.util.function.BooleanSupplier COMPLETE = () -> true;
    /** Owner 串行触发扫描复用的一页续步，不为每次价格更新新建整个捕获图。 */
    private final PendingTriggerScan reusableTriggerScan = new PendingTriggerScan();
    /** 触发子单串行执行复用的账户操作与 Owner 续步。 */
    private final TriggerExecutionContinuation reusableTriggerExecution = new TriggerExecutionContinuation();

    /**
     * 清缓冲后选出本命令已平仓对应的待触发单，核对各单取消修订可增加，再检查全局修订加总不溢出；返回的是待取消 ID，不在这里直接撤销。
     */
    public PrimitiveLongChangeSet collectClosingTriggerIds() {
        closingTriggerIds.clear();
        selectClosingTriggers(/* 本次遍历或回调选中的触发单 ID */ id -> {
            if (!owner.triggerOrderIndex().route(id).cancelRevisionAvailable())
                throw new ArithmeticException("trigger cancellation revision overflow");
            closingTriggerIds.add(id);
        });
        Math.addExact(owner.runtimeState().revision(), closingTriggerIds.size());
        return closingTriggerIds;
    }

    /**
     * 保存非空交易 Owner，复用扫描与子单续步；本构造不启动任何工作线程。
     * @param owner 本产品线唯一交易 Owner，提供账户运行时、索引和提交入口
     */
    public TriggerOrderCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /**
     * 仅在现有风险扫描与本价格序列一致时，固定当前最大待触发 ID、标记价及报价时间，重置触发阶段和 OCO 续步；无候选时标记触发扫描完成。
     * @param command 本次已解码的标记价、价格序列及生成时间
     */
    public void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        // 本标的解析得到的内部整数身份，find 入口没找到时可为空
        Integer symbolId = owner.identities().findSymbolId(command.instrumentId());
        // 该标的已有风险与触发扫描进度，存在且价格序列对应时才继续
        RiskScanRuntime scan = symbolId == null ? null : owner.runtimeState().riskScan(symbolId);
        if (scan == null || scan.priceSequence() != command.priceSequence()) return;
        // 本价格序列开始时固定的最大待触发 ID，后续新建更大 ID 不纳入这次页范围
        long upperId = owner.triggerOrderIndex().maxPendingId(command.instrumentId());
        owner.runtimeState().replaceRiskScan(
                scan.withTriggerProgress(upperId == 0, TriggerOrderIndex.PHASE_GREATER_OR_EQUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, upperId, command.markPriceTicks(),
                command.generatedAtEpochMillis()).withTriggerOcoProgress(0, 0));
    }

    /**
     * 没有扫描或触发部分已完成时返回共享完成回调；否则准备一页可续步工作，页预算取请求与配置的较小者。返回真只表示本次工作结束，不代表三种价格来源均扫完。
     * @param instrumentId 本次扫描的规范数字标的 ID 文本，准备时解析为整数
     * @param maxWork 本次允许使用的候选页及 OCO 处理预算，另受配置批量上限约束
     */
    public java.util.function.BooleanSupplier pendingTriggerScan(String instrumentId, int maxWork) {
        // 本标的解析得到的内部整数身份，find 入口没找到时可为空
        Integer symbolId = owner.identities().findSymbolId(instrumentId);
        // 该标的已有风险与触发扫描进度，存在且价格序列对应时才继续
        RiskScanRuntime scan = symbolId == null ? null : owner.runtimeState().riskScan(symbolId);
        if (scan == null || scan.triggerComplete()) return COMPLETE;
        return reusableTriggerScan.prepare(instrumentId, scan,
                Math.min(maxWork, owner.defaultTriggerScanBatchSize()));
    }

    /**
     * 优先把本命令收集到的非空触发新值转成路由；返回空新值时仍回退 Owner 已发布索引，本方法不单独区分删除与没有新值。
     * @param id 需要查询的触发单业务 ID
     */
    private TriggerOrderIndex.TriggerRoute currentTriggerRoute(long id) {
        // 本命令收集到的触发状态新值，非空时优先于已发布 Owner 路由
        var receipt = owner.runtimeState().changedTriggerOrder(id);
        return receipt == null ? owner.triggerOrderIndex().route(id) : TriggerOrderIndex.TriggerRoute.from(receipt);
    }

    /** Owner 串行复用的一页触发扫描续步，保留价格与 OCO 游标并等待账户变更或子单准入。 构造约定：创建供 Owner 串行复用的页续步，具体输入由 prepare 填入。 */
    @NoArgsConstructor(access = AccessLevel.PACKAGE)
    private final class PendingTriggerScan implements java.util.function.BooleanSupplier {
        /** 当前扫描标的的规范整数身份。 */
        private int instrumentId;
        /** 本扫描合成或原执行请求传入的命令 UUID，供子单身份和撮合上下文使用。 */
        private UUID commandId;
        /** 本页固定并随 OCO 续步调整的风险与触发扫描进度值。 */
        private RiskScanRuntime scan;
        /** 当前页预算，同时约束候选页大小和 OCO 段工作；逐候选本身不递减，兄弟段完成时扣量。 */
        private int remaining;
        /** 当前价格来源已取出的候选页，处理完后保存续步并结束本次扫描工作。 */
        private TriggerOrderIndex.TriggerCandidatePage page;
        /** 当前候选在 page 中的下标，追踪状态变更后可留在同一候选重新检查。 */
        private int candidate;
        /**
         * ocoComplete：当前候选的 OCO 兄弟撤销已扫完，允许继续认领与下子单。
         * finished：本次页或 OCO 段已结束，区别于运行时 scan.triggerComplete 的整体扫描完成。
         */
        private boolean ocoComplete, finished;
        /** 已经派发且尚待轮询完成的账户变更或子单执行回调。 */
        private java.util.function.BooleanSupplier pending;
        /** 当前页在同一用户账户 Lane 复用的过期、追踪或同组撤销任务。 */
        private final ScanMutation reusableMutation = new ScanMutation();

        /**
         * 固定标的整数身份、扫描值和页预算，用产品线、标的文本及价格序列合成可重建命令 UUID，清上次页和等待状态后返回自身。
         * @param instrumentId 本次扫描的规范数字标的 ID 文本，准备时解析为整数
         * @param scan 当前需要保存或接续的风险及触发扫描进度
         * @param remaining 本次扫描页及 OCO 撤销可用的预算
         */
        PendingTriggerScan prepare(String instrumentId, RiskScanRuntime scan, int remaining) {
            this.instrumentId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            this.scan = scan;
            this.remaining = remaining;
            // 自动触发 UUID 高半部，由产品线、标的文本哈希及固定标记组合
            long msb = ((long) owner.productLine().ordinal() << 48)
                    ^ (instrumentId == null ? 0L : ((long) instrumentId.hashCode() << 16))
                    ^ 0x4d41524b50524943L; // 固定文本标记 MARKPRIC 的整数编码
            // 自动触发 UUID 低半部，固定采用本轮价格序列
            long lsb = scan.priceSequence();
            commandId = new UUID(msb, lsb);
            candidate = 0;
            page = null;
            pending = null;
            ocoComplete = false;
            finished = false;
            return this;
        }

        /**
         * 先续完已派发工作，优先恢复 OCO 撤单，再取当前价格来源的一页候选；逐个处理过期、追踪更新、条件与同组撤销后发起子单。候选页处理完就保存下一阶段或游标并结束本次工作，账户未完成则返回假让 Owner 稍后再轮询。remaining 用于页大小和同组撤单预算，不逐候选递减。
         */
        @Override public boolean getAsBoolean() {
            for (;;) {
                if (pending != null) {
                    if (!pending.getAsBoolean()) return false;
                    pending = null;
                }
                if (finished) return true;
                if (page == null) {
                    if (scan.triggerOcoOrderId() != 0) {
                        // 本次候选对应的触发状态或路由，先确认状态再处理
                        var trigger = currentTriggerRoute(scan.triggerOcoOrderId());
                        if (trigger != null && trigger.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                            cancelSiblings(trigger, scan.triggerOcoCursor() == 0 ? Long.MAX_VALUE : scan.triggerOcoCursor(), true);
                            continue;
                        }
                        scan = scan.withTriggerOcoProgress(0, 0);
                        replaceRiskScan(scan);
                    }
                    if (remaining <= 0) return true;
                    // 整个触发阶段除以单来源阶段数得到的行情来源枚举下标
                    int sourceIndex = scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE;
                    // 当前扫描采用的 MARK、LAST 或 INDEX 行情来源
                    var source = com.surprising.aeron.protocol.CoreTriggerPriceSource.values()[sourceIndex];
                    // 本标的当前行情状态，LAST 与 INDEX 还依赖该价格序列
                    var mark = owner.runtimeState().markPrice(scan.symbolId());
                    // 取候选页所用价格；MARK 固定在扫描值中，其他来源要求当前行情仍匹配该序列
                    long sourcePrice = source == com.surprising.aeron.protocol.CoreTriggerPriceSource.MARK
                            ? scan.triggerMarkPriceTicks()
                            : mark == null || mark.priceSequence() != scan.priceSequence() ? 0
                            : source == com.surprising.aeron.protocol.CoreTriggerPriceSource.LAST
                                    ? mark.lastPriceTicks() : mark.indexPriceTicks();
                    page = owner.triggerOrderIndex().candidatesPage(instrumentId, source, sourcePrice,
                            scan.triggerPhase() % TriggerOrderIndex.PHASE_COMPLETE,
                            scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(), remaining);
                }
                if (candidate == page.ids().size()) {
                    // 本页结束后保存的全来源扫描阶段，来源编号和来源内阶段一起编码
                    int nextPhase = page.complete()
                            ? ((scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE) + 1)
                                    * TriggerOrderIndex.PHASE_COMPLETE
                            : (scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE)
                                    * TriggerOrderIndex.PHASE_COMPLETE + page.nextPhase();
                    replaceRiskScan(scan.withTriggerProgress(nextPhase == 3 * TriggerOrderIndex.PHASE_COMPLETE,
                            nextPhase, page.complete() ? Long.MAX_VALUE : page.nextPriceCursor(),
                            page.complete() ? Long.MAX_VALUE : page.nextOrderCursor(),
                            scan.triggerUpperId(), scan.triggerMarkPriceTicks(),
                            scan.triggerGeneratedAtEpochMillis()).withTriggerOcoProgress(0, 0));
                    finished = true;
                    return true;
                }
                // 本次遍历或回调选中的触发单 ID
                long id = page.idAt(candidate);
                // 本次候选对应的触发状态或路由，先确认状态再处理
                var trigger = currentTriggerRoute(id);
                if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                    candidate++;
                    continue;
                }
                // 当前扫描采用的 MARK、LAST 或 INDEX 行情来源
                var source = com.surprising.aeron.protocol.CoreTriggerPriceSource.values()[
                        scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE];
                // 本标的当前行情状态，LAST 与 INDEX 还依赖该价格序列
                var mark = owner.runtimeState().markPrice(scan.symbolId());
                // 当前候选实际采用的所选来源价格 tick
                long price = source == com.surprising.aeron.protocol.CoreTriggerPriceSource.MARK
                        ? scan.triggerMarkPriceTicks()
                        : source == com.surprising.aeron.protocol.CoreTriggerPriceSource.LAST
                                ? mark.lastPriceTicks() : mark.indexPriceTicks();
                // 本次扫描固定的报价生成时间，用于过期、激活与触发时间
                long at = scan.triggerGeneratedAtEpochMillis();
                if (trigger.expiresAtEpochMillis() > 0 && at > 0 && trigger.expiresAtEpochMillis() <= at) {
                    candidate++;
                    mutateExpire(trigger.userId(), id, at);
                    continue;
                }
                if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
                    // 追踪单是否卖出方向，卖单跟最高价、买单跟最低价
                    boolean sell = trigger.side() == CoreOrderSide.SELL;
                    if (trigger.activationPriceTicks() > 0 && ((sell && price < trigger.activationPriceTicks())
                            || (!sell && price > trigger.activationPriceTicks()))) {
                        candidate++;
                        continue;
                    }
                    // 按当前行情更新后的追踪最高价，只有卖出追踪单更新它
                    long highest = sell ? Math.max(trigger.highestPriceTicks(), price) : trigger.highestPriceTicks();
                    // 按当前行情更新后的追踪最低价，只有买入追踪单更新它
                    long lowest = sell ? trigger.lowestPriceTicks()
                            : trigger.lowestPriceTicks() == 0 ? price : Math.min(trigger.lowestPriceTicks(), price);
                    // 已有激活时间保留，未激活则使用本次报价时间
                    long activated = trigger.activatedAtEpochMillis() == 0 ? at : trigger.activatedAtEpochMillis();
                    if (highest != trigger.highestPriceTicks() || lowest != trigger.lowestPriceTicks()
                            || activated != trigger.activatedAtEpochMillis()) {
                        mutateTrailing(trigger.userId(), id, highest, lowest, activated);
                        continue;
                    }
                }
                if (!isTriggerConditionSatisfied(trigger, price)) {
                    candidate++;
                    continue;
                }
                if (!ocoComplete) {
                    if (remaining <= 0) {
                        replaceRiskScan(scan.withTriggerOcoProgress(id, Long.MAX_VALUE));
                        finished = true;
                        return true;
                    }
                    cancelSiblings(trigger, Long.MAX_VALUE, false);
                    continue;
                }
                ocoComplete = false;
                candidate++;
                pending = beginTriggerExecution(id, scan.priceSequence(), price, at, commandId, false);
            }
        }

        /**
         * 从稳定 Owner 索引借用同组倒序 ID，只选排除当前单且小于游标的一段，按剩余预算定位下一界；有工作则派给账户 Lane，无工作直接推进 OCO 续步状态。
         * @param trigger 当前 OCO 主触发单路由，同组撤销范围排除它自身
         * @param cursor 本段 OCO 撤销的排他起始触发 ID
         * @param resumed 是否接续此前保存在扫描状态中的 OCO 撤销
         */
        private void cancelSiblings(TriggerOrderIndex.TriggerRoute trigger,
                                    long cursor, boolean resumed) {
            // 控制边界内 Owner 索引保持稳定直到收集 Lane 发布结果，账户任务借用有界 ID 范围，无须复制兄弟清单。
            // 从 Owner 索引借入的同组倒序 ID 集，控制边界内保持稳定
            var siblings = owner.triggerOrderIndex().ocoSiblingsDescending(trigger);
            // 本 OCO 页已选的兄弟 ID 数量，不是成功取消数量
            int count = 0;
            // 本页最近选中的最后一个兄弟 ID，供保存下一排他游标
            long last = cursor;
            // 是否在页预算之外还看到了其他符合范围的兄弟 ID
            boolean more = false;
            for (/* 当前借入同组倒序集合的 primitive ID 迭代器 */ var siblingIds = siblings.iterator(); siblingIds.hasNext();) {
                // 本次遍历或回调选中的触发单 ID
                long id = siblingIds.nextLong();
                if (id == trigger.triggerOrderId() || id >= cursor) continue;
                if (count == remaining) { more = true; break; }
                count++;
                last = id;
            }
            // 本段选择已到同组末尾，不再需要下一 OCO 页
            boolean complete = !more;
            // 本 OCO 页选中的兄弟 ID 个数，由账户结果收集后扣预算
            int work = count;
            // 本页选中的最后 ID，账户任务把它作为包含的下界
            long next = last;
            if (work == 0) {
                reusableMutation.completeSiblings(trigger, next, work, complete, resumed);
                return;
            }
            reusableMutation.prepareSiblings(trigger, cursor, next, work, complete, resumed, siblings);
            submitMutation(trigger.userId());
        }

        /**
         * 准备当前触发单过期操作，再按所属用户派发同一个永久账户任务。
         * @param userId 触发单所属用户，决定账户访问与派发 Lane
         * @param triggerId 本次扫描变更或执行的触发单 ID
         * @param at 本次过期检查采用的业务毫秒时间
         */
        private void mutateExpire(long userId, long triggerId, long at) {
            reusableMutation.prepareExpire(triggerId, at);
            submitMutation(userId);
        }

        /**
         * 准备追踪单高低价及激活时间更新，再按所属用户派发账户任务；同一候选稍后会用新路由重新判断。
         * @param userId 触发单所属用户，决定账户访问与派发 Lane
         * @param triggerId 本次扫描变更或执行的触发单 ID
         * @param highest 追踪止损目前记录的最高价格 tick
         * @param lowest 追踪止损目前记录的最低价格 tick
         * @param activated 追踪止损激活的毫秒时间戳
         */
        private void mutateTrailing(long userId, long triggerId, long highest, long lowest, long activated) {
            reusableMutation.prepareTrailing(triggerId, highest, lowest, activated);
            submitMutation(userId);
        }

        /**
         * 异步时记住同一个待完成任务并派发用户 Lane。
         * @param userId 触发单所属用户，决定账户访问与派发 Lane
         */
        private void submitMutation(long userId) {
            reusableMutation.userId = userId;
            pending = reusableMutation;
            owner.runtimeState().dispatchControlLanes(
                    1L << owner.runtimeState().topology().accountLaneId(userId), reusableMutation);
        }

        /**
         * 扫描页复用的账户任务：同一对象分别承担 Lane 操作和 Owner 完成轮询，使用前明确准备一种任务。
         */
        private final class ScanMutation implements java.util.function.BooleanSupplier,
                java.util.function.IntFunction<Object> {
            /**
             * EXPIRE：扫描任务内部类型一：检查并写入过期状态。
             * TRAILING：扫描任务内部类型二：更新追踪高低价和激活时间。
             * SIBLINGS：扫描任务内部类型三：处理已选 OCO 兄弟范围。
             */
            private static final byte EXPIRE = 1, TRAILING = 2, SIBLINGS = 3;
            /** 本次复用扫描任务要执行的内部类型，准备前重置为零。 */
            private byte kind;
            /**
             * userId：本次账户扫描任务所属用户，用于派发和收集对应 Lane 结果。
             * triggerId：本次要变更或执行的触发单业务 ID。
             * arg1：过期任务存检查时间；追踪任务存最高价。
             * arg2：追踪任务存最低价，其他类型不使用。
             * arg3：追踪任务存激活时间，其他类型不使用。
             * cursor：本 OCO 页的排他起始 ID，跳过大于等于它的兄弟。
             * next：本 OCO 页已选的最后 ID，账户处理范围包含该 ID。
             */
            private long userId, triggerId, arg1, arg2, arg3, cursor, next;
            /** 本 OCO 页选中遍历的兄弟 ID 数，不等同实际取消成功数。 */
            private int work;
            /**
             * complete：本 OCO 页后是否已没有更多兄弟 ID 待遍历。
             * resumed：本次 OCO 页是否从保存的扫描续步恢复，而非当前候选首次检查。
             */
            private boolean complete, resumed;
            /** OCO 当前主触发单路由，借它确定同用户和要排除的主 ID。 */
            private TriggerOrderIndex.TriggerRoute trigger;
            /** 借用 Owner 索引的稳定同组倒序集合，下次 prepare 前清理，不在任务准备时复制。 */
            private it.unimi.dsi.fastutil.longs.LongSortedSet siblings;

            /**
             * 清上次任务数据，保存过期类型、触发单 ID 及过期检查时间。
             * @param triggerId 本次扫描变更或执行的触发单 ID
             * @param at 本次过期检查采用的业务毫秒时间
             */
            void prepareExpire(long triggerId, long at) {
                clear(); kind = EXPIRE; this.triggerId = triggerId; arg1 = at;
            }

            /**
             * 清上次任务数据，保存追踪类型、触发单 ID 和高价、低价、激活时间。
             * @param triggerId 本次扫描变更或执行的触发单 ID
             * @param highest 追踪止损目前记录的最高价格 tick
             * @param lowest 追踪止损目前记录的最低价格 tick
             * @param activated 追踪止损激活的毫秒时间戳
             */
            void prepareTrailing(long triggerId, long highest, long lowest, long activated) {
                clear(); kind = TRAILING; this.triggerId = triggerId;
                arg1 = highest; arg2 = lowest; arg3 = activated;
            }

            /**
             * 清上次任务数据，借入稳定同组 ID 集和当前页范围、工作量及是否恢复标志；集合不复制，外层控制边界保证使用期间索引稳定。
             * @param trigger 当前 OCO 主触发单路由，保存它的身份及所属用户
             * @param cursor 本段 OCO 撤销的排他起始触发 ID
             * @param next 本 OCO 段最后要处理的 ID，范围包含该 ID
             * @param work 本段选中遍历的兄弟 ID 数量，用于扣减页预算
             * @param complete 本 OCO 段后是否没有更多需要遍历的兄弟 ID
             * @param resumed 是否接续此前保存在扫描状态中的 OCO 撤销
             * @param siblings Owner 索引中稳定借入的同组倒序 ID 集，账户任务只处理选中范围
             */
            void prepareSiblings(TriggerOrderIndex.TriggerRoute trigger,
                                 long cursor, long next, int work, boolean complete, boolean resumed,
                                 it.unimi.dsi.fastutil.longs.LongSortedSet siblings) {
                clear(); kind = SIBLINGS; this.trigger = trigger; this.cursor = cursor; this.next = next;
                this.work = work; this.complete = complete; this.resumed = resumed; this.siblings = siblings;
            }

            /**
             * 没有实际账户工作时直接保存本页结果并推进扫描续步，不派发空 Lane 操作。
             * @param trigger 当前 OCO 主触发单路由，供记录恢复游标
             * @param next 本 OCO 段最后要处理的 ID，范围包含该 ID
             * @param work 本段选中遍历的兄弟 ID 数量，用于扣减页预算
             * @param complete 本 OCO 段后是否没有更多需要遍历的兄弟 ID
             * @param resumed 是否接续此前保存在扫描状态中的 OCO 撤销
             */
            void completeSiblings(TriggerOrderIndex.TriggerRoute trigger,
                                  long next, int work, boolean complete, boolean resumed) {
                this.trigger = trigger; this.next = next; this.work = work;
                this.complete = complete; this.resumed = resumed; finishSiblings();
            }

            /**
             * 在用户账户访问范围内按类型修改过期或追踪状态；同组撤单只遍历已选倒序范围并取消仍待触发的单，返回是否有变化，未知类型明确报错。
             */
            private Object applyMutation() {
                return switch (kind) {
                    case EXPIRE -> owner.runtimeState().expireTriggerOrder(triggerId, arg1);
                    case TRAILING -> owner.runtimeState().updateTrailingTriggerOrder(triggerId,
                            arg1, arg2, arg3);
                    case SIBLINGS -> {
                        // 本段 OCO 兄弟中至少一个待触发单实际被取消的累计标记
                        boolean changed = false;
                        for (/* 当前借入同组倒序集合的 primitive ID 迭代器 */ var siblingIds = siblings.iterator(); siblingIds.hasNext();) {
                            // 本次遍历或回调选中的触发单 ID
                            long id = siblingIds.nextLong();
                            if (id == trigger.triggerOrderId() || id >= cursor) continue;
                            if (id < next) break;
                            // 当前兄弟在账户 Lane 的真实状态，只取消仍为 PENDING 的单
                            var sibling = owner.runtimeState().triggerOrder(id);
                            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                                changed |= owner.runtimeState().cancelTriggerOrder(
                                        trigger.userId(), id);
                        }
                        yield changed;
                    }
                    default -> throw new IllegalStateException("unknown trigger scan mutation");
                };
            }

            /**
             * 扣除本段遍历工作量；未撤完保存目标单和下一游标并结束本页，恢复段撤完则清 OCO 游标，普通段撤完则允许当前候选执行。
             */
            private void finishSiblings() {
                remaining -= work;
                if (!complete) {
                    scan = scan.withTriggerOcoProgress(trigger.triggerOrderId(), next);
                    replaceRiskScan(scan);
                    finished = true;
                } else if (resumed) {
                    scan = scan.withTriggerOcoProgress(0, 0);
                    replaceRiskScan(scan);
                } else ocoComplete = true;
            }

            /**
             * 下次准备前清数值、标志和借用的触发路由与同组集合；它不是所有完成分支都会自动调用的尾部清理。
             */
            void clear() {
                kind = 0; userId = triggerId = arg1 = arg2 = arg3 = cursor = next = 0;
                work = 0; complete = resumed = false; trigger = null; siblings = null;
            }

            /**
             * 账户 Lane 回调统一执行本次变更；Lane 编号由外层派发决定，此参数当前不读取。
             * @param ignoredLaneId 外层派发提供的账户 Lane 编号，本回调当前不读取
             */
            @Override public Object apply(int ignoredLaneId) { return applyMutation(); }

            /**
             * Owner 等待控制 Lane 结果，未齐返回假；真实变化请求发布，再推进 OCO 页状态并返回本次任务完成。
             */
            @Override public boolean getAsBoolean() {
                if (!owner.runtimeState().pollControlLanes()) return false;
                // 控制 Lane 收集的变更返回值，为 Boolean.TRUE 才请求发布
                Object result = owner.runtimeState().controlLaneResult(
                        owner.runtimeState().topology().accountLaneId(userId));
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
                if (kind == SIBLINGS) finishSiblings();
                return true;
            }
        }
    }

    /**
     * 把触发扫描游标与 OCO 进度接回原风险状态转换，然后请求命令提交发布。
     * @param scan 当前需要保存或接续的风险及触发扫描进度
     */
    void replaceRiskScan(RiskScanRuntime scan) {
        owner.runtimeState().replaceRiskScan(scan);
        owner.requestCommitPublication();
    }

    /**
     * 基价或回调率非正返回零；否则精确相乘后按百万分之一向下取整得到追踪回撤价差，中间乘法溢出明确失败。
     * @param base 追踪卖单最高价或买单最低价，作为回撤价差计算基数
     * @param callbackRatePpm 追踪回调比例，单位为百万分之一
     */
    static long trailingDelta(long base, long callbackRatePpm) {
        if (base <= 0 || callbackRatePpm <= 0) return 0;
        return Math.floorDiv(Math.multiplyExact(base, callbackRatePpm), 1_000_000L);
    }

    /**
     * 从完整触发单判断条件：普通单比较固定阈值，追踪卖单按最高价回落、买单按最低价反弹；追踪基价或激活时间未准备好时不满足。此方法不检查行情序列、过期或任务状态。
     * @param trigger 本次判断、构造子单或 OCO 撤销所用的触发状态、路由或视图
     * @param priceTicks 本次所选行情来源的价格 tick
     */
    static boolean isTriggerConditionSatisfied(
            com.surprising.aeron.model.CoreTriggerOrderState trigger, long priceTicks) {
        if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
            // 追踪单是否卖出方向，卖单跟最高价、买单跟最低价
            boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
            // 追踪卖单记录的最高价或买单记录的最低价，作为回调阈值基数
            long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
            if (base <= 0 || trigger.activatedAtEpochMillis() <= 0) return false;
            // 基价按百万分之一回调比例向下取整得到的价差
            long delta = trailingDelta(base, trigger.callbackRatePpm());
            // 卖单基价减回调价差、买单基价加回调价差得到的触发阈值
            long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
            return sell ? priceTicks <= threshold : priceTicks >= threshold;
        }
        return trigger.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                ? priceTicks >= trigger.triggerPriceTicks() : priceTicks <= trigger.triggerPriceTicks();
    }

    /**
     * 只用 Owner 路由判断同一套普通或追踪价格条件，供扫描筛选；是否还能执行仍由后续完整业务检查决定。
     * @param trigger 本次判断、构造子单或 OCO 撤销所用的触发状态、路由或视图
     * @param priceTicks 本次所选行情来源的价格 tick
     */
    static boolean isTriggerConditionSatisfied(
            TriggerOrderIndex.TriggerRoute trigger, long priceTicks) {
        if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
            // 追踪单是否卖出方向，卖单跟最高价、买单跟最低价
            boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
            // 追踪卖单记录的最高价或买单记录的最低价，作为回调阈值基数
            long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
            if (base <= 0 || trigger.activatedAtEpochMillis() <= 0) return false;
            // 基价按百万分之一回调比例向下取整得到的价差
            long delta = trailingDelta(base, trigger.callbackRatePpm());
            // 卖单基价减回调价差、买单基价加回调价差得到的触发阈值
            long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
            return sell ? priceTicks <= threshold : priceTicks >= threshold;
        }
        return trigger.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                ? priceTicks >= trigger.triggerPriceTicks() : priceTicks <= trigger.triggerPriceTicks();
    }



    /**
     * 不存在时报业务拒绝，非待触发状态直接返回空；从该单选择的 MARK、LAST 或 INDEX 取当前价格，核对正数、行情序列、指定价和触发条件，满足才返回原单。
     * @param trigger 本次判断、构造子单或 OCO 撤销所用的触发状态、路由或视图
     * @param sequence 可执行性校验必须匹配的行情价格序列
     * @param price 选定来源价格或子单默认限价，单位为价格 tick
     * @param symbolId 触发标的内部整数索引，可以为空以表示尚未解析到标的
     */
    private com.surprising.aeron.model.CoreTriggerOrderState executableTrigger(
            com.surprising.aeron.model.CoreTriggerOrderState trigger, long sequence, long price, Integer symbolId) {
        if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) return null;
        // 本标的当前行情状态，LAST 与 INDEX 还依赖该价格序列
        var mark = symbolId == null ? null : owner.runtimeState().markPrice(symbolId);
        // 从当前行情按触发单价格来源取出的实际校验价，没有行情时为零
        long selectedPrice = mark == null ? 0 : switch (trigger.priceSource()) {
            case MARK -> mark.markPriceTicks();
            case LAST -> mark.lastPriceTicks();
            case INDEX -> mark.indexPriceTicks();
        };
        if (mark == null || selectedPrice <= 0 || mark.priceSequence() != sequence
                || selectedPrice != price || !isTriggerConditionSatisfied(trigger, price))
            throw new CoreStateRejectedException("TRIGGER_CONDITION_NOT_MET", "trigger price is not executable");
        return trigger;
    }

    /**
     * 限价单优先用配置价，没有配置价时用触发价；市价单限价零。衍生品子单强制只减仓、现货不只减仓，客户端 ID 固定由触发 ID 派生。
     * @param trigger 本次判断、构造子单或 OCO 撤销所用的触发状态、路由或视图
     * @param price 选定来源价格或子单默认限价，单位为价格 tick
     * @param instrument 触发单已有标的配置，用来判断现货还是衍生品
     * @param orderId 已确定未占用的正奇数子订单 ID
     */
    private PlaceOrderCommand childOrder(com.surprising.aeron.model.CoreTriggerOrderState trigger,
            long price, com.surprising.aeron.service.instrument.CoreInstrument instrument, long orderId) {
        // 子单限价：限价意图优先配置价否则触发价，市价意图固定零
        long limit = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (trigger.priceTicks() > 0 ? trigger.priceTicks() : price) : 0;
        return new PlaceOrderCommand(orderId,
                trigger.instrumentId(), trigger.side(), limit, trigger.quantitySteps(),
                instrument.contractType() != com.surprising.instrument.api.model.ContractType.SPOT,
                trigger.marginMode(), trigger.positionSide(), trigger.orderType(), trigger.timeInForce(), false,
                "TRIGGER:" + trigger.triggerOrderId());
    }



    /**
     * 要求同用户、产品、币对、方向、资金模式及数量，组标识相同且非空，订单和客户编号不同；第一腿为止盈、第二腿为止损，卖出止盈价高于止损价，买入则相反。
     */
    public static void validateOcoPair(CoreTriggerOrderStateView first, CoreTriggerOrderStateView second) {
        if (first == null || second == null || first.ocoGroupId().isBlank()
                || !first.ocoGroupId().equals(second.ocoGroupId()) || first.userId() != second.userId()
                || first.productLine() != second.productLine() || !first.instrumentId().equals(second.instrumentId())
                || first.side() != second.side() || first.marginMode() != second.marginMode()
                || first.positionSide() != second.positionSide() || first.quantitySteps() != second.quantitySteps()
                || first.triggerOrderId() == second.triggerOrderId()
                || first.clientTriggerOrderId().equals(second.clientTriggerOrderId())
                || first.triggerType() != CoreTriggerOrderType.TAKE_PROFIT
                || second.triggerType() != CoreTriggerOrderType.STOP_LOSS
                || (first.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL
                    ? first.triggerPriceTicks() <= second.triggerPriceTicks()
                    : first.triggerPriceTicks() >= second.triggerPriceTicks())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "invalid take-profit / stop-loss OCO pair");
        }
    }

    /**
     * 按当前产品线、用户和触发视图，通过原身份表准备对应持仓键；不在这里修改真实持仓。
     * @param userId 触发单所属用户，决定账户访问与派发 Lane
     * @param trigger 本次判断、构造子单或 OCO 撤销所用的触发状态、路由或视图
     */
    long preparedTriggerPositionKey(
            long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger) {
        if (!owner.productLine().isDerivative()) return 0;
        return owner.identities().positionKey(userId,
                com.surprising.aeron.service.order.state.OrderReservation.requireInstrumentId(trigger.instrumentId()),
                trigger.positionSide());
    }

    /**
     * 从触发 ID 映射到正奇数候选，碰到占用就按二递增并在最大值后回到一；转一整圈仍无可用值才拒绝，不把移位当成普通有符号乘法。
     * @param triggerOrderId 当前触发单业务 ID
     */
    private long triggerChildOrderId(long triggerOrderId) {
        // 网关触发 ID 来自 UUID，可覆盖正 long 范围；用移位和掩码选正奇数子单空间，避免普通有符号乘法溢出。
        // 由触发 ID 映射并逐个检查占用的正奇数子订单候选
        long candidate = ((triggerOrderId << 1) | 1L) & Long.MAX_VALUE;
        // 首次子单候选值，用于判断奇数空间是否已回绕一整圈
        long first = candidate;
        while (owner.orderIdInUse(candidate)) {
            candidate = candidate == Long.MAX_VALUE ? 1 : candidate + 2;
            if (candidate == first) throw new CoreStateRejectedException("INVALID_COMMAND", "trigger child order IDs exhausted");
        }
        return candidate;
    }

    /**
     * 解码并用业务时间物化算法视图，异步路径另核用户并准备标的索引，交原控制槽，冷路径直接更新并请求发布。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     * @param clusterTimestamp 本次复制命令的业务毫秒时间，用于物化创建视图的时间字段
     */
    public void executeUpsertAlgoOrder(CoreMessage message, long clusterTimestamp) {
        var algo = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeCommand(message.payloadUnsafe());
        var current = owner.runtimeState().algoRoute(algo.algoOrderId());
        long userId = message.header().userId();
        if (current != null && current.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        int symbolId = -1;
        if (algo instanceof com.surprising.aeron.protocol.CoreAlgoOrderCommand.Create create) {
            symbolId = owner.identities().symbolId(create.instrumentId());
        } else if (current != null) symbolId = owner.identities().symbolId(current.instrumentId());
        owner.directCommand.deferAlgoUpsertControl(userId, algo, symbolId, clusterTimestamp);
    }

    /**
     * 解码延时全撤控制并应用原用户状态转换，正常返回后请求发布；此入口不读取额外传入的 clusterTimestamp。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeUpdateCancelAllAfter(CoreMessage message) {
        owner.runtimeState().updateCancelAllAfter(message.header().userId(),
                com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }

    /**
     * 要求恰好两条并按业务时间建立视图，核对配对和交易开关，再准备标的、持仓键和已结算标记；交控制槽异步处理。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     * @param clusterTimestamp 本次复制命令的业务毫秒时间，用于物化创建视图的时间字段
     */
    public void executePlaceTriggerOcoPair(CoreMessage message, long clusterTimestamp) {
        // OCO 请求解出的两腿创建视图列表，数量必须恰好二
        var views = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodePlacementList(message.payloadUnsafe());
        if (views.size() != 2) throw new CoreStateRejectedException("INVALID_COMMAND", "OCO requires two legs");
        // 按本次业务时间物化的 OCO 第一腿
        var first = views.get(0).materialize(message.header().productLine(), message.header().userId(), clusterTimestamp);
        // 按当前业务时间物化的 OCO 第二腿
        var second = views.get(1).materialize(message.header().productLine(), message.header().userId(), clusterTimestamp);
        validateOcoPair(first, second);
        // 本触发单已有标的配置，供交易开关或子单产品规则检查
        var instrument = owner.runtimeState().instrument(first.instrumentId());
        if (instrument != null) {
            instrument.requireOrderEnabled(first.orderType(), first.timeInForce(), false, false);
            instrument.requireOrderEnabled(second.orderType(), second.timeInForce(), false, false);
        }
        // 本标的解析得到的内部整数身份，find 入口没找到时可为空
        int symbolId = owner.identities().symbolId(first.instrumentId());
        // 本触发视图或本次持仓变化所对应的内部持仓键
        long positionKey = preparedTriggerPositionKey(message.header().userId(), first);
        // 该标的财库是否已经登记生命周期结算，传给账户准入作为拒绝依据
        boolean settled = owner.runtimeState().treasury().lifecycleSettlement(symbolId) != 0;
        owner.directCommand.deferTriggerOcoPairControl(message.header().userId(), first, second, symbolId, positionKey, settled);
    }

    /**
     * 解码并建立带业务时间的视图，检查标的开关，再准备标的与持仓输入；交控制槽异步处理。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     * @param clusterTimestamp 本次复制命令的业务毫秒时间，用于物化创建视图的时间字段
     */
    public void executePlaceTriggerOrder(CoreMessage message, long clusterTimestamp) {
        // 按本次业务时间物化的触发单创建或更新视图
        var trigger = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodePlacement(message.payloadUnsafe())
                .materialize(message.header().productLine(), message.header().userId(), clusterTimestamp);
        // 触发单所属的已注册标的，存在时先检查当前交易状态及订单开关
        var maintenanceInstrument = owner.runtimeState().instrument(trigger.instrumentId());
        if (maintenanceInstrument != null) {
            maintenanceInstrument.requireOrderEnabled(trigger.orderType(), trigger.timeInForce(), false, false);
        }
        // 本标的解析得到的内部整数身份，find 入口没找到时可为空
        int symbolId = owner.identities().symbolId(trigger.instrumentId());
        // 本触发视图或本次持仓变化所对应的内部持仓键
        long positionKey = preparedTriggerPositionKey(message.header().userId(), trigger);
        // 当前标的是否已结算，交给账户转换防止过期合约新建触发单
        boolean instrumentSettled = owner.runtimeState().treasury().lifecycleSettlement(symbolId) != 0;
        owner.directCommand.deferTriggerUpsertControl(message.header().userId(), trigger, symbolId, positionKey,
                instrumentSettled);
    }

    /**
     * 解码触发 ID，交取消类型控制任务异步处理。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeCancelTriggerOrder(CoreMessage message) {
        // 本次协议解出或平仓关联遍历到的触发单 ID
        long triggerOrderId = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(message.header().userId(), Mutation.CANCEL,
                triggerOrderId, 0, 0, 0, false, null);
    }

    /**
     * 解码认领参数，按触发单真实所属用户交异步控制任务。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeClaimTriggerOrder(CoreMessage message) {
        // 协议解出的认领数组，依次为 ID、行情序列、触发价格和时间
        long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(claim[0]), Mutation.CLAIM,
                claim[0], claim[1], claim[2], claim[3], false, null);
    }

    /**
     * 解码成功标记、子单 ID 和完成时间，按真实所属用户完成触发单。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeCompleteTriggerOrder(CoreMessage message) {
        // 协议解出的完成数组，依次为 ID、成功标记、关联子单 ID 和完成时间
        long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(complete[0]), Mutation.COMPLETE,
                complete[0], complete[2], complete[3], 0, complete[1] == 1, "");
    }

    /**
     * 解码追踪高低价与激活时间，按真实用户交异步控制任务。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeUpdateTriggerTrailing(CoreMessage message) {
        // 协议解出的追踪数组，依次为 ID、最高价、最低价及激活时间
        long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(trailing[0]), Mutation.TRAILING,
                trailing[0], trailing[1], trailing[2], trailing[3], false, null);
    }

    /**
     * 解码触发 ID 与过期检查时间，按真实用户交过期控制任务。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeExpireTriggerOrder(CoreMessage message) {
        // 协议解出的触发单 ID 与过期检查时间或重试陈旧界限
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(lifecycle[0]), Mutation.EXPIRE,
                lifecycle[0], lifecycle[1], 0, 0, false, null);
    }

    /**
     * 解码触发 ID 和陈旧界限，以请求头提交时间作为重试时间，按真实用户交重试控制任务。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeRetryTriggerOrder(CoreMessage message) {
        // 协议解出的触发单 ID 与过期检查时间或重试陈旧界限
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(lifecycle[0]), Mutation.RETRY,
                lifecycle[0], lifecycle[1], message.header().submittedAtEpochMillis(), 0, false, null);
    }

    /**
     * 显式执行请求：解码并把子单续步交直接控制槽等待。
     * @param message 当前借用的原命令头和载荷，使用期间保持缓冲有效
     */
    public void executeTriggerOrder(CoreMessage message) {
        // 协议解出的执行数组，依次为触发 ID、行情序列、价格和触发时间。
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
        long triggerId = execute[0], triggerSequence = execute[1], price = execute[2], triggeredAt = execute[3];
        owner.directCommand.deferControl(beginTriggerExecution(triggerId, triggerSequence, price, triggeredAt,
                message.header().commandId(), true));
    }

    /**
     * 在 Owner 索引中找原路由，不存在则拒绝，非待触发直接返回完成；其余先选子单 ID，再准备复用执行续步，真正账户校验稍后执行。
     * @param triggerId 本次扫描变更或执行的触发单 ID
     * @param triggerSequence 本次执行或认领要求匹配的行情价格序列
     * @param price 选定来源价格或子单默认限价，单位为价格 tick
     * @param triggeredAt 本次触发的业务毫秒时间
     * @param commandId 原请求或自动扫描合成的命令 UUID，沿用到子单准入和撮合
     * @param cancelOco 本次是否还需要先撤同组兄弟，自动扫描已撤完时为假
     */
    private java.util.function.BooleanSupplier beginTriggerExecution(long triggerId, long triggerSequence,
            long price, long triggeredAt, UUID commandId, boolean cancelOco) {
        // Owner 索引中原触发单路由，用于开始异步子单准备
        var route = owner.triggerOrderIndex().route(triggerId);
        if (route == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (route.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) return COMPLETE;
        // 已经选定并用于本次子单的未占用正奇数订单 ID
        long childOrderId = triggerChildOrderId(triggerId);
        return reusableTriggerExecution.prepare(route, triggerId, triggerSequence, price, triggeredAt,
                commandId, cancelOco, childOrderId);
    }

    /**
     * 触发子单准入的复用续步：必要时先查询唯一账本，再派发所属账户 Lane 完成触发认领和冻结，Owner 收集回执后交撮合；不承担后续成交终态。
     */
    private final class TriggerExecutionContinuation
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        /** 账户 Lane 复核并认领后的完整触发单，Owner 收集回执后清引用。 */
        private com.surprising.aeron.model.CoreTriggerOrderState trigger;
        /**
         * triggerId：本次要变更或执行的触发单业务 ID。
         * triggerSequence：本次执行要求匹配的行情价格序列。
         * price：本次执行要求匹配的所选来源价格 tick。
         * triggeredAt：本次触发时间，传给认领和完成状态转换。
         * childSequence：子单准入及后续撮合使用的预先算定 Core 顺序。
         * childOrderId：Owner 在派发账户前选定的未占用正奇数子订单 ID。
         */
        private long triggerId, triggerSequence, price, triggeredAt, childSequence, childOrderId;
        /** 本扫描合成或原执行请求传入的命令 UUID，供子单身份和撮合上下文使用。 */
        private UUID commandId;
        /** 触发单所属用户账户 Lane，由 Owner 预先定位。 */
        private int laneId;
        /**
         * baseAssetId：标的基础资产的内部整数身份。
         * quoteAssetId：标的报价资产的内部整数身份。
         * settleAssetId：标的结算资产的内部整数身份。
         */
        private int baseAssetId, quoteAssetId, settleAssetId;
        /** Owner 固定的订单决策上下文，账户 Lane 据此解析子单，交接完成后清引用。 */
        private com.surprising.aeron.service.order.admission.RuntimeOrderAdmission.Context decision;
        /** 唯一账本提供的市场未平仓量，只有标的额度需要它时才查询。 */
        private long openInterest;
        /** 子单账户工作是否已派发，防止 Owner 轮询时重复派同一任务。 */
        private boolean accountDispatched;
        /** 本次可选借入的同组倒序集合，准备时固定来源、Owner 收集后清引用。 */
        private it.unimi.dsi.fastutil.longs.LongSortedSet siblings;

        /**
         * 固定子单 ID 和本次触发输入，准备订单决策、资产整数身份及原命令序号；需要市场未平仓量时就地读取，借入可选 OCO 集合并确定账户 Lane，不在这里冻结资金。
         * @param route Owner 索引中的原触发路由，只用于定位账户与准备决策输入
         * @param triggerId 本次扫描变更或执行的触发单 ID
         * @param triggerSequence 本次执行或认领要求匹配的行情价格序列
         * @param price 选定来源价格或子单默认限价，单位为价格 tick
         * @param triggeredAt 本次触发的业务毫秒时间
         * @param commandId 原请求或自动扫描合成的命令 UUID，沿用到子单准入和撮合
         * @param cancelOco 本次是否还需要先撤同组兄弟，自动扫描已撤完时为假
         * @param childOrderId Owner 已选定、需要由账户准入的子订单 ID
         */
        java.util.function.BooleanSupplier prepare(TriggerOrderIndex.TriggerRoute route,
                long triggerId, long triggerSequence, long price, long triggeredAt, UUID commandId,
                boolean cancelOco, long childOrderId) {
            this.childOrderId = childOrderId;
            trigger = null;
            this.triggerId = triggerId; this.triggerSequence = triggerSequence;
            this.price = price; this.triggeredAt = triggeredAt; this.commandId = commandId;
            decision = com.surprising.aeron.service.order.admission.RuntimeOrderAdmission.context(
                    owner.runtimeState(), owner.identities(), route.instrumentId(), owner.currentClusterTimestamp());
            baseAssetId = owner.identities().assetId(decision.instrument().baseAsset());
            quoteAssetId = owner.identities().assetId(decision.instrument().quoteAsset());
            settleAssetId = owner.identities().assetId(decision.instrument().settleAsset());
            if (decision.instrument().usesOpenInterestForUserLimit()) {
                openInterest = owner.runtimeState().ledgerAdmissionOpenInterest(decision.instrument().instrumentId());
            } else {
                openInterest = 0;
            }
            accountDispatched = false;
            childSequence = Math.addExact(Math.addExact(owner.appliedCommandCount(), 2), owner.queuedMatchingCount());
            siblings = cancelOco ? owner.triggerOrderIndex().ocoSiblingsDescending(route)
                    : it.unimi.dsi.fastutil.longs.LongSortedSets.EMPTY_SET;
            laneId = owner.runtimeState().topology().accountLaneId(route.userId());
            return this;
        }

        /**
         * 账户 Lane 先用完整触发单复核行情及条件，解析子单准入；业务拒绝先记原因，随后处理同组撤销并认领，再记触发失败或冻结子单。客户端身份失败时只在这里撤回新准备的身份；技术异常继续传播，不扩大为本方法回滚所有业务状态。
         * @param ignoredLaneId 外层派发提供的账户 Lane 编号，本回调当前不读取
         */
        @Override public Object apply(int ignoredLaneId) {
            trigger = executableTrigger(owner.runtimeState().triggerOrder(triggerId), triggerSequence, price, decision.symbolId());
            if (trigger == null) return null;
            com.surprising.aeron.service.order.admission.ResolvedPlaceOrder order = null;
            String failureReason = "";
            try {
                order = com.surprising.aeron.service.order.admission.RuntimeOrderAdmission.resolve(
                        decision, trigger.userId(), childOrder(trigger, price, trigger.instrument(), childOrderId));
            } catch (/* 子单决策或冻结中遇到的业务拒绝，记成触发失败而非技术故障 */ CoreStateRejectedException rejected) { failureReason = rejected.code(); }
            int assetId = order == null ? 0 : order.reservationAsset().equals(decision.instrument().baseAsset()) ? baseAssetId
                    : order.reservationAsset().equals(decision.instrument().quoteAsset()) ? quoteAssetId : settleAssetId;
            for (/* 当前借入同组倒序集合的 primitive ID 迭代器 */ var siblingIds = siblings.iterator(); siblingIds.hasNext();) {
                // 本页当前选择或处理的 OCO 兄弟触发 ID
                long siblingId = siblingIds.nextLong();
                if (siblingId == triggerId) continue;
                // 当前兄弟在账户 Lane 的真实状态，只取消仍为 PENDING 的单
                var sibling = owner.runtimeState().triggerOrder(siblingId);
                if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                    owner.runtimeState().cancelTriggerOrder(trigger.userId(), siblingId);
            }
            owner.runtimeState().claimTriggerOrder(triggerId, triggerSequence, price, triggeredAt);
            trigger = owner.runtimeState().triggerOrder(triggerId);
            if (!failureReason.isEmpty()) {
                owner.runtimeState().completeTriggerOrder(triggerId, false, 0,
                        failureReason, triggeredAt);
                return null;
            }
            // 当前用户 Lane 预备的客户端身份令牌，成功后交 Owner 统计，失败时按需撤回
            var key = owner.identities().prepareClientKeyInCurrentLane(trigger.userId(), order.clientOrderId());
            try {
                // 子单资金准入成功后的运行时订单回执，交给 Owner 收集预留并排入撮合
                var receipt = owner.runtimeState().placeTriggerChildInLane(trigger.userId(), order,
                        commandId, childSequence, openInterest, owner.identities(),
                        com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry.clientKeyValue(key), assetId,
                        decision.lifecycleSettled(), decision.fundingInProgress());
                return receipt;
            } catch (/* 子单决策或冻结中遇到的业务拒绝，记成触发失败而非技术故障 */ CoreStateRejectedException rejected) {
                if (key != 0) owner.identities().rollbackClientKeyInCurrentLane(trigger.userId(),
                        order.clientOrderId(), key);
                owner.runtimeState().completeTriggerOrder(triggerId, false, 0,
                        rejected.code(), triggeredAt);
                return null;
            } catch (/* 子单冻结过程中遇到的技术异常或错误，只撤回新预备客户端身份后继续传播 */ RuntimeException | Error failure) {
                if (key != 0) owner.identities().rollbackClientKeyInCurrentLane(trigger.userId(),
                        order.clientOrderId(), key);
                throw failure;
            }
        }

        /**
         * Owner 派发一次账户工作；收集结果登记新客户端身份及变化，有子单回执才把预留接回并排入撮合，最后清大对象引用。本次返回真是准入交接完成，撮合与成交终态仍由后续链路处理。
         */
        @Override public boolean getAsBoolean() {
            if (!accountDispatched) {
                owner.runtimeState().dispatchControlLanes(1L << laneId, this);
                accountDispatched = true;
            }
            if (!owner.runtimeState().pollControlLanes()) return false;
            // 账户子单准入的回执，空表示本次没有成功接纳子单
            var result = owner.runtimeState().controlLaneResult(laneId);
            owner.requestCommitPublication();
            if (result != null) {
                // 账户返回的子单订单回执，先收集冻结预留再排入撮合
                var receipt = (com.surprising.aeron.service.order.state.OrderRuntime) result;
                owner.runtimeState().pendingReservations().collectControlReservation(receipt, childSequence);
                owner.resultBuilder.markUserChanged(trigger.userId());
                owner.resultBuilder.markOrderChanged(receipt.orderId());
                owner.queueTriggerMatching(trigger, triggerSequence, price, triggeredAt, commandId, receipt);
            }
            trigger = null;
            decision = null;
            commandId = null;
            siblings = null;
            return true;
        }
    }



    /**
     * 先合入本命令变化，只检查原非零且本次已经归零或删除的持仓；从该用户 Owner 索引筛选相同标的、持仓方向及保证金模式的待触发单，交调用方消费。
     * @param selected 消费选定触发 ID 的回调，调用期间在 Owner 流程内立即执行
     */
    private void selectClosingTriggers(java.util.function.LongConsumer selected) {
        owner.seedChangeAccumulators();
        if (!owner.runtimeState().hasChangedPositions()) return;
        // 本命令已登记变化的 primitive 持仓键迭代器
        org.eclipse.collections.api.iterator.LongIterator changedPositions =
                owner.runtimeState().changedPositionIterator();
        while (changedPositions.hasNext()) {
            // 本触发视图或本次持仓变化所对应的内部持仓键
            long positionKey = changedPositions.next();
            // 本命令变更前的持仓，只有原数量非零才检查关联触发
            var previous = owner.runtimeState().currentPatchPositionBefore(positionKey);
            if (previous == null || previous.signedQuantitySteps() == 0) continue;
            // 本命令变更后的持仓值，为空或数量零才需要清理原持仓关联触发
            var current = owner.runtimeState().changedPositionValue(positionKey);
            if (current != null && current.signedQuantitySteps() != 0) continue;
            // 已平仓原持仓的标的规范整数身份
            int instrumentId = previous.instrument().instrumentIdValue();
            // 原持仓全仓或逐仓模式，选关联触发时必须一致
            var previousMarginMode = previous.marginMode();
            for (/* 该用户 Owner 触发索引的 ID 迭代器，逐项再核对本命令新路由 */ var triggerIds = owner.triggerOrderIndex().ids(previous.userId()).iterator(); triggerIds.hasNext();) {
                // 本次协议解出或平仓关联遍历到的触发单 ID
                long triggerOrderId = triggerIds.nextLong();
                // 本次候选对应的触发状态或路由，先确认状态再处理
                var trigger = currentTriggerRoute(triggerOrderId);
                if (trigger == null || trigger.status()
                        != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) continue;
                if (instrumentId != trigger.instrumentId() || trigger.positionSide() != previous.positionSide()
                        || trigger.marginMode() != previousMarginMode) continue;
                selected.accept(triggerOrderId);
            }
        }
    }

    /**
     * 按本命令新路由优先、已发布索引次之查所属用户，找不到触发单则明确拒绝。
     * @param triggerOrderId 当前触发单业务 ID
     */
    long requireTriggerOwner(long triggerOrderId) {
        // 本次候选对应的触发状态或路由，先确认状态再处理
        var trigger = currentTriggerRoute(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order does not exist");
        }
        return trigger.userId();
    }
}
