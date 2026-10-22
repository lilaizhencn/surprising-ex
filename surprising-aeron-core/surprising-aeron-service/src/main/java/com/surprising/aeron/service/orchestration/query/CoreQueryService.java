package com.surprising.aeron.service.orchestration.query;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.matching.BookBootstrapSnapshot;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.ledger.InsuranceAllocationPolicy;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService;
import com.surprising.aeron.service.state.query.RuntimeOperationalQueryService;
import com.surprising.aeron.service.state.query.RuntimeStateQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.surprising.aeron.service.orchestration.TradingCoreRuntime.*;

/** 核心内部查询的异步编排入口。Owner 解析条件并登记编号，账户 Lane、Matcher 或系统账本在各自所有权内读取；结果回到这里供 Owner 消费，不能为查询复制一份在线权威账户。 构造约定：绑定所属交易运行时，查询的投递、身份路由和结果计数均沿用该运行时。 参数 owner：所属交易运行时，提供查询投递、路由、身份和提交水位入口 */
@RequiredArgsConstructor
public final class CoreQueryService {
    /** 提供业务路由、线程投递和提交水位的所属运行时，查询仍遵守实际状态所有权。 */
    final TradingCoreRuntime owner;


    /** 读取线程写入、Owner 消费后移除的并发完成结果表，键为内部查询编号。 */
    final Map<Long, CompletedBookQuery> completedBookQueries
            = new ConcurrentHashMap<>();

    /** Matcher 异步盘口读取失败的并发标记表，Owner 消费时转换为拒绝响应。 */
    final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();

    /** 仅由 Owner 管理的待账户投递风险查询，账本路由就绪且 Lane 邮件投递成功后移除。 */
    private final Map<Long, RiskAccountQuery> pendingRiskQueries = new java.util.HashMap<>();

    /** 外部命令 UUID 到内部异步编号的并发关联表，最终结果消费或关闭时清除。 */
    final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();

    /** Owner 持有的有界盘口初始化会话，按插入顺序淘汰最早会话，各页共用固定快照。 */
    final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();

    /** 从 Long.MIN_VALUE 起递增的内部查询编号，不使用交易命令序号。 */
    long nextAsyncQueryId = Long.MIN_VALUE;

    /**
     * 判断协议类型是否属于普通账户、订单、挂单或算法父单查询，用于选择账户 Lane 读取入口。
     * @param type 本次查询的协议消息类型，用来选择账户、账本或编码分支
     */
    public static boolean accountQuery(CoreMessageType type) {
        return type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY
                || type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                || type == CoreMessageType.CLIENT_ORDER_STATE_QUERY || type == CoreMessageType.USER_OPEN_ORDERS_QUERY
                || type == CoreMessageType.ALGO_ORDER_QUERY;
    }

    /**
     * 识别风险快照与 ADL 候选两类查询，交给需要先经账本选路由的风险查询任务。
     * @param type 本次查询的协议消息类型，用来选择账户、账本或编码分支
     */
    public static boolean riskAccountQuery(CoreMessageType type) {
        return type == CoreMessageType.RISK_STATE_QUERY || type == CoreMessageType.ADL_CANDIDATE_QUERY;
    }

    /**
     * 从 Owner 的待完成划转路由取一页，空页直接返回；有数据则登记内部编号并投递对应 Lane，投递失败清除关联，成功后完成本次路由页选择并返回待完成。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     * @param limit 本次待完成划转查询最多选择的路由条数
     */
    public CoreResponse beginPendingTransferQuery(CoreMessage message, int limit) {
        // 待完成划转索引选出的当前页，含对齐编号、用户和 Lane 位集合。
        var page = owner.runtimeState().pendingTransferRoutes(limit);
        if (page.ids().length == 0) {
            owner.runtimeState().completePendingTransferPage(page);
            return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encode(List.of()));
        }
        // 为本次异步读取新分配的内部查询编号，与外部命令 UUID 另行关联。
        long id = nextAsyncQueryId++;
        // 保存这次划转路由及 DTO 槽的账户查询任务，空页不创建任务。
        var query = new AccountQuery(id, owner.appliedCommandCount(), CoreMessageType.PENDING_TRANSFER_QUERY,
                0, 0, null, page.laneMask(), null, null);
        query.transferPage = new PendingTransferQueryPage(page.ids(), page.users(),
                new com.surprising.aeron.protocol.CorePendingTransferView[page.ids().length]);
        queryIds.put(message.header().commandId(), id);
        try {
            if (!owner.runtimeState().dispatchAccountQuery(page.laneMask(), query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除外部命令与查询编号的关联，再继续上抛。 */ RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        owner.runtimeState().completePendingTransferPage(page);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount());
    }

    /**
     * 解码下单意图并检查用户和订单身份，固定币对决策输入；必要时先投递账本准备未平仓量，再投递用户 Lane 做只读预检，失败时移除查询关联。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     * @param timestamp 本次预检采用的集群业务时间，用于订单规则和到期校验
     */
    public CoreResponse beginPreflightQuery(CoreMessage message, long timestamp) {
        // 从载荷解出的预检下单意图，尚未生成真实订单或冻结。
        var command = TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
        // 预检消息头指定的用户，必须为正数才能定位账户。
        long userId = message.header().userId();
        if (userId <= 0) throw new IllegalArgumentException("preflight user is required");
        owner.requireOrderIdentityAvailable(userId, command);
        if (owner.orderIdInUse(command.orderId())) throw new com.surprising.aeron.service.exception.CoreStateRejectedException(
                "DUPLICATE_ORDER_ID", "orderId already exists");
        // Owner 为当前预检取得的币对决策输入，避免 Lane 任意回读变化中的产品元数据。
        var context = com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.queryContext(
                owner.runtimeState(), owner.identities(), userId, command.instrumentId(), timestamp);
        // 为本次异步读取新分配的内部查询编号，与外部命令 UUID 另行关联。
        long id = nextAsyncQueryId++;
        // 可等待账本 OI 输入的预检任务，之后只读用户账户计算所需预留。
        var query = new PreflightQuery(id, owner.appliedCommandCount(), userId, command, context);
        queryIds.put(message.header().commandId(), id);
        try {
            if (!query.accountReady() && !owner.runtimeState().dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
            if (!owner.runtimeState().dispatchAccountQuery(owner.runtimeState().topology().accountLaneMask(userId), query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除外部命令与查询编号的关联，再继续上抛。 */ RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount());
    }

    /**
     * 一次下单预检的查询任务：必要时先由账本提供未平仓量，再由用户所属 Lane 检查订单和所需预留，只返回估算结果，不创建真实订单或冻结。
     */
    private final class PreflightQuery implements SettlementLaneWorker.Command,
            java.util.function.Function<AccountLaneState, CoreResponse>, SystemLedgerLane.Entry {
        /**
         * id：本次任务的内部查询编号，用于保存最终响应和完成外部关联消费。
         * commandCount：任务创建时固定的已应用命令计数，响应沿用此值。
         * userId：必须存在的预检用户编号，只在其所属 Lane 读取账户。
         */
        private final long id, commandCount, userId;
        /** 账本准备的准入未平仓量，在 inputReady 发布后供账户预检使用。 */
        private long openInterest;
        /** 预检的 OI 输入就绪标记；不依赖 OI 的订单创建时即为 true，否则由账本写值后发布。 */
        private volatile boolean inputReady;
        /** 只读预检采用的下单意图，不作为真实撮合命令提交。 */
        private final com.surprising.aeron.protocol.PlaceOrderCommand command;
        /** Owner 固定的预检币对、标记价、费率及结算状态输入。 */
        private final com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.Context context;

        /**
         * 保存本次预检的订单和固定决策输入；产品不依赖未平仓量或为只减仓单时可直接读账户，否则等待账本发布输入就绪。
         * @param id 本次异步查询的内部编号，完成响应用它写入结果表
         * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
         * @param userId 查询目标用户编号，具体查询类型决定是否允许用 0 表示未指定
         * @param command 已解码的普通下单意图，本次只做只读预检，不提交真实订单
         * @param context Owner 固定的币对、价格、费率和生命周期决策输入
         */
        private PreflightQuery(long id, long commandCount, long userId, com.surprising.aeron.protocol.PlaceOrderCommand command,
                com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.Context context) {
            this.id = id; this.commandCount = commandCount; this.userId = userId;
            this.command = command; this.context = context;
            inputReady = !context.instrument().usesOpenInterestForUserLimit() || command.reduceOnly();
        }

        /**
         * 返回预检所需的账本输入是否已就绪，账户消费者据此决定是否可以执行。
         */
        @Override public boolean accountReady() { return inputReady; }

        /**
         * 在账本线程取得该币对的准入未平仓量，先写数值再发布 volatile 就绪位。
         * @param ignored 账本接口传入的 TreasuryRuntime，预检从运行时账本索引取 OI，不直接使用此参数
         */
        @Override public void apply(TreasuryRuntime ignored) {
            openInterest = owner.runtimeState().ledgerAdmissionOpenInterest(command.instrumentId());
            inputReady = true;
        }

        /**
         * 进入所属账户的合法只读范围执行预检，把业务拒绝、算术溢出或非法参数转换为查询响应，然后保存结果并通知 Owner。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public void execute(AccountLaneState lane) {
            // 当前账户 Lane 执行预检后的成功结果或明确业务拒绝响应。
            CoreResponse response;
            try { response = owner.runtimeState().readAccountLane(lane, this); }
            catch (/* 订单规则或资金检查产生的明确业务拒绝，使用原拒绝码编码响应。 */ com.surprising.aeron.service.exception.CoreStateRejectedException failure) {
                response = rejected(CoreResultCode.fromRejectionCode(failure.code()));
            } catch (/* 预检金额计算发生算术溢出，返回 ARITHMETIC_OVERFLOW。 */ ArithmeticException failure) { response = rejected(CoreResultCode.ARITHMETIC_OVERFLOW); }
            catch (/* 预检输入非法，返回 INVALID_COMMAND，不使查询一直等待。 */ IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
            completedBookQueries.put(id, CompletedBookQuery.encoded(response));
            owner.runtimeState().signalOwnerCompletion();
        }

        /**
         * 按固定输入解析订单规则，在当前 Lane 取得客户及持仓身份，计算所需预留并编码资产和金额；只检查，不写真实订单或冻结。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public CoreResponse apply(AccountLaneState lane) {
            // 按固定市场和规则输入解析出的预检订单参数。
            var order = com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.resolve(context, userId, command);
            // 当前用户的客户端订单身份、币对、持仓键和结算状态，交给账户预留检查。
            var identity = new com.surprising.aeron.service.state.admission.AdmissionIdentity(
                    owner.identities().findClientKeyValue(userId, command.clientOrderId()), context.symbolId(),
                    owner.identities().findPositionKeyValueInLane(lane, userId, context.instrument(), command.positionSide()),
                    context.settlementStatus());
            // 本次只读预检算出的所需冻结金额，只编码返回而不真正冻结。
            long reserved = com.surprising.aeron.service.state.admission.RuntimeOrderAdmission.requiredReservationForQuery(
                    owner.runtimeState(), lane, userId, order, openInterest, identity);
            return new CoreResponse(ResponseStatus.OK, commandCount, com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(
                    new com.surprising.aeron.protocol.CoreOrderPreflightView(order.reservationAsset(), reserved)));
        }

        /**
         * 按本次预检固定的命令计数和业务错误码构造拒绝响应。
         * @param code 本次查询要返回的明确业务拒绝码
         */
        private CoreResponse rejected(CoreResultCode code) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
        }
    }

    /**
     * 创建风险查询对象并先投递账本选路由，登记外部命令与内部编号；投递成功后保留待账户交接对象，Owner 消费查询时继续投递 Lane。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    public CoreResponse beginRiskAccountQuery(CoreMessage message) {
        // 为本次异步读取新分配的内部查询编号，与外部命令 UUID 另行关联。
        long id = nextAsyncQueryId++;
        // Owner 创建的风险或 ADL 查询交接对象，先让账本选择路由。
        var query = RiskAccountQuery.prepare(owner, message, id);
        queryIds.put(message.header().commandId(), id);
        try {
            if (!owner.runtimeState().dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除外部命令与查询编号的关联，再继续上抛。 */ RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        pendingRiskQueries.put(id, query);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount());
    }

    /**
     * 按查询类型解析用户、订单或客户端条件；挂单先选固定编号页，普通订单无活跃路由时让各 Lane 探测保留终态，随后登记并投递账户查询。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    public CoreResponse beginAccountQuery(CoreMessage message) {
        // 当前账户查询类型，决定解码普通订单、客户端订单或挂单条件。
        var type = message.header().messageType();
        if (type == CoreMessageType.ALGO_ORDER_QUERY) return beginAlgoAccountQuery(message);
        // 查询头中的用户条件，按订单编号或全局挂单查询时可为 0。
        long userId = message.header().userId();
        // 从普通订单详情或哈希载荷解出的订单编号，其他分支为 0。
        long orderId = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                ? TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()) : 0;
        // 客户端订单查询的标识文本，其他查询不需要。
        String clientName = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                ? CoreStateQueryCodec.decodeClientOrderStateQuery(message.payloadUnsafe()) : null;
        if (type == CoreMessageType.CLIENT_ORDER_STATE_QUERY && userId <= 0)
            throw new IllegalArgumentException("userId must be positive");
        // 按挂单索引页顺序固定的订单编号数组，非挂单查询保持 null。
        long[] openOrderIds = null;
        if (type == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            // 已解码的挂单页币对、排他订单游标和条数条件。
            var pageQuery = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payloadUnsafe());
            // 规范化后的排他编号游标，协议的 0 转成 Long.MAX_VALUE 作为首页起点。
            long before = pageQuery.beforeOrderId() == 0 ? Long.MAX_VALUE : pageQuery.beforeOrderId();
            // 活动订单索引选出的有界挂单页，空页直接编码空列表。
            var page = owner.activeOrderIndex().page(userId, pageQuery.instrumentId(), before, pageQuery.limit());
            if (page.orderIds().isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                    CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(List.of())));
            openOrderIds = new long[page.orderIds().size()];
            for (/* 把选中订单编号按页顺序复制到固定数组的下标。 */ int index = 0; index < openOrderIds.length; index++) openOrderIds[index] = page.orderIds().get(index);
        }
        // 当前是否为只带普通订单编号的详情或哈希查询，决定是否需要跨 Lane 探测。
        boolean orderQuery = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY;
        if (!orderQuery && openOrderIds == null && userId <= 0) return missingAccountResponse(type, owner.appliedCommandCount());
        // 累计本次所选记录所属的账户 Lane 位，投递后必须等待这些 Lane 全部完成。
        long laneMask;
        if (openOrderIds != null) {
            laneMask = 0;
            for (long /* 当前挂单页中的订单编号，用其活跃路由计算目标 Lane。 */ selected : openOrderIds) {
                // 当前索引记录的身份与归属路由，用来取得所属用户并定位账户 Lane。
                var route = owner.activeOrderIndex().activeOrderRoute(selected);
                if (route == null) throw new IllegalStateException("active order query route is missing");
                laneMask |= owner.runtimeState().topology().accountLaneMask(route.userId());
            }
        } else if (orderQuery) {
            // 当前索引记录的身份与归属路由，用来取得所属用户并定位账户 Lane。
            var route = owner.activeOrderIndex().activeOrderRoute(orderId);
            // 只有orderId的内部协议也支持保留的终态订单；无活跃路由时由各Lane原位探测。
            int count = owner.runtimeState().topology().accountLaneCount();
            laneMask = route != null ? owner.runtimeState().topology().accountLaneMask(route.userId())
                    : count == Long.SIZE ? -1L : (1L << count) - 1;
        } else laneMask = owner.runtimeState().topology().accountLaneMask(userId);
        // 为本次异步读取新分配的内部查询编号，与外部命令 UUID 另行关联。
        long id = nextAsyncQueryId++;
        // 已经确定身份条件和目标 Lane 的账户查询任务，负责所属线程读取与编码。
        var query = new AccountQuery(id, owner.appliedCommandCount(), type, userId, orderId, clientName, laneMask, openOrderIds, null);
        return dispatchAccountQuery(message, id, laneMask, query);
    }

    /**
     * 按过期时间、状态、用户或币对选择触发索引来源，再施加精确编号、游标及活动状态筛选；受扫描和结果条数双重约束，固定路由后由所属 Lane 读取详情。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    public CoreResponse beginTriggerQuery(CoreMessage message) {
        // 触发单查询的币对、状态、编号、游标、过期时间和结果上限条件。
        var requested = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payloadUnsafe());
        // 币对筛选的永久整数编号，空币对条件用 0 表示不限定。
        int requestedInstrument = requested.instrumentId().isEmpty() ? 0
                : com.surprising.product.api.utils.InstrumentIds.parse(requested.instrumentId());
        // 规范化后的排他编号游标，协议的 0 转成 Long.MAX_VALUE 作为首页起点。
        long before = requested.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : requested.beforeTriggerOrderId();
        // 按过期时间、状态、用户或币对选择的触发索引遍历来源，后面继续核对组合条件。
        Iterable<Long> source = requested.expiresBeforeEpochMillis() > 0
                ? owner.triggerOrderIndex().expired(requested.expiresBeforeEpochMillis(), requested.limit())
                : requested.instrumentId().isEmpty()
                ? requested.status() != null ? owner.triggerOrderIndex().ids(requested.status())
                : message.header().userId() == 0 ? requested.beforeTriggerOrderId() == 0
                        ? owner.triggerOrderIndex().ids() : owner.triggerOrderIndex().idsBefore(before)
                        : owner.triggerOrderIndex().ids(message.header().userId())
                : requested.status() == null ? owner.triggerOrderIndex().ids(requested.instrumentId())
                : owner.triggerOrderIndex().ids(requested.instrumentId(), requested.status());
        // 已通过组合条件的触发单编号，按索引遍历顺序保留。
        var selected = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        // 与选中触发单编号对齐的用户路由，供各账户 Lane 只填自己的结果槽。
        var users = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        // 累计本次所选记录所属的账户 Lane 位，投递后必须等待这些 Lane 全部完成。
        long laneMask = 0;
        // 已经遍历的索引项数量，包含被筛掉的项，超过全局扫描预算立即拒绝。
        int scanned = 0;
        for (long /* 当前遍历的触发单编号，与精确编号及排他游标条件核对。 */ id : source) {
            if (++scanned > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            // 当前索引记录的身份与归属路由，用来取得所属用户并定位账户 Lane。
            var route = owner.triggerOrderIndex().route(id);
            if (route == null || requested.beforeTriggerOrderId() != 0 && id >= before || requested.triggerOrderId() != 0 && requested.triggerOrderId() != id
                    || message.header().userId() != 0 && message.header().userId() != route.userId()
                    || requestedInstrument != 0 && requestedInstrument != route.instrumentId()
                    || requested.status() != null && requested.status() != route.status()
                    || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY && !route.status().open()) continue;
            selected.add(id); users.add(route.userId());
            laneMask |= owner.runtimeState().topology().accountLaneMask(route.userId());
            if (selected.size() == Math.min(requested.limit(), RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)) break;
        }
        if (selected.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(List.of()));
        // 本次查询的内部编号，用于登记异步完成、失败或后续消费。
        long queryId = nextAsyncQueryId++;
        // 保存固定触发单路由及 DTO 槽的账户查询任务。
        var query = new AccountQuery(queryId, owner.appliedCommandCount(), message.header().messageType(),
                0, 0, null, laneMask, null, null);
        query.triggerPage = new TriggerQueryPage(selected.toArray(), users.toArray(),
                new com.surprising.aeron.protocol.CoreTriggerOrderStateView[selected.size()]);
        return dispatchAccountQuery(message, queryId, laneMask, query);
    }

    /**
     * 按算法索引选父单，或精确选择仍保留的父单编号，固定用户路由及原顺序，再交给所属 Lane 汇总子单进度。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    private CoreResponse beginAlgoAccountQuery(CoreMessage message) {
        // 已解码的算法父单用户、币对、调度截止时间、精确编号和条数条件。
        var requested = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payloadUnsafe());
        // 算法索引按筛选得到的父单编号，精确查询也允许保留的终态父单。
        var ids = requested.algoOrderId() == 0
                ? owner.algoOrderIndex().query(requested.userId(), requested.instrumentId(), requested.dueAtEpochMillis(),
                        requested.limit())
                : owner.algoOrderIndex().value(requested.algoOrderId()) == null ? List.<Long>of() : List.of(requested.algoOrderId());
        if (ids.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(List.of()));
        // 与父单编号数组逐项对齐的所属用户编号。
        // 按算法查询原顺序固定的父单编号数组，结果槽保持相同顺序。
        long[] selectedIds = new long[ids.size()], users = new long[ids.size()];
        // 累计本次所选记录所属的账户 Lane 位，投递后必须等待这些 Lane 全部完成。
        long laneMask = 0;
        for (/* 正在填入父单编号和所属用户两个对齐数组的下标。 */ int index = 0; index < ids.size(); index++) {
            // 本轮从算法索引取出的父单编号，用于读取所属用户路由。
            long selected = ids.get(index);
            // 当前索引记录的身份与归属路由，用来取得所属用户并定位账户 Lane。
            var route = owner.algoOrderIndex().value(selected);
            if (route == null) throw new IllegalStateException("algo query route is missing");
            selectedIds[index] = selected; users[index] = route.userId();
            laneMask |= owner.runtimeState().topology().accountLaneMask(route.userId());
        }
        // 为本次异步读取新分配的内部查询编号，与外部命令 UUID 另行关联。
        long id = nextAsyncQueryId++;
        // 已经固定父单及用户路由的本次查询页，各 Lane 后续填入汇总视图。
        var page = new AlgoQueryPage(selectedIds, users,
                new com.surprising.aeron.protocol.CoreAlgoOrderView[selectedIds.length]);
        // 携带算法父单页的账户查询任务，不在 Owner 汇总真实账户子订单。
        var query = new AccountQuery(id, owner.appliedCommandCount(), CoreMessageType.ALGO_ORDER_QUERY,
                0, 0, null, laneMask, null, page);
        return dispatchAccountQuery(message, id, laneMask, query);
    }

    /**
     * 登记外部命令 UUID 到内部查询编号并投递目标 Lane；邮箱拒收或投递异常先清除关联，成功只返回待完成状态。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     * @param id 本次异步查询的内部编号，完成响应用它写入结果表
     * @param laneMask 本次查询需要访问的账户 Lane 位集合，全部完成后才归并
     * @param query 已经固定身份、结果槽和目标 Lane 的账户查询任务
     */
    private CoreResponse dispatchAccountQuery(CoreMessage message, long id, long laneMask, AccountQuery query) {
        queryIds.put(message.header().commandId(), id);
        try {
            if (!owner.runtimeState().dispatchAccountQuery(laneMask, query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除外部命令与查询编号的关联，再继续上抛。 */ RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount());
    }

    /**
     * 本次触发单页的固定编号、用户路由和响应槽；各 Lane 只填自己的下标，全部完成后按原选择顺序编码。
     * @param ids 按原索引顺序选择的业务记录编号，与用户和响应槽逐项对齐
     * @param users 各业务记录所属的用户编号，用于决定由哪个账户 Lane 填结果
     * @param views 与编号逐项对齐的查询 DTO 槽，各 Lane 只填写自己拥有的记录
     */
    private record TriggerQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreTriggerOrderStateView[] views) { }

    /**
     * 本次算法父单页的固定路由与响应槽，各 Lane 汇总自己的父单及保留子单，最后合并时再检查全局子单扫描量。
     * @param ids 按原索引顺序选择的业务记录编号，与用户和响应槽逐项对齐
     * @param users 各业务记录所属的用户编号，用于决定由哪个账户 Lane 填结果
     * @param views 与编号逐项对齐的查询 DTO 槽，各 Lane 只填写自己拥有的记录
     */
    private record AlgoQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreAlgoOrderView[] views) { }

    /**
     * 本次待完成划转页的固定编号、用户路由和 DTO 槽，用于各 Lane 原位读取，不保存第二份划转状态。
     * @param ids 按原索引顺序选择的业务记录编号，与用户和响应槽逐项对齐
     * @param users 各业务记录所属的用户编号，用于决定由哪个账户 Lane 填结果
     * @param views 与编号逐项对齐的查询 DTO 槽，各 Lane 只填写自己拥有的记录
     */
    private record PendingTransferQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CorePendingTransferView[] views) { }

    /**
     * 保留缺失实体的协议差异：用户或订单哈希返回成功且哈希为零，详情查询返回 ENTITY_NOT_FOUND。
     * @param type 本次查询的协议消息类型，用来选择账户、账本或编码分支
     * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
     */
    private static CoreResponse missingAccountResponse(CoreMessageType type, long commandCount) {
        if (type == CoreMessageType.USER_STATE_HASH_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY)
            return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.ENTITY_NOT_FOUND, commandCount);
    }

    /**
     * 一笔普通账户或业务列表查询的跨 Lane 任务，保存本次响应槽和原子完成位；所属 Lane 读实体并编码，最后完成者归并结果，消费或关闭后释放引用。
     */
    private final class AccountQuery implements SettlementLaneWorker.Command,
            java.util.function.Function<AccountLaneState, CoreResponse> {
        /**
         * id：本次任务的内部查询编号，用于保存最终响应和完成外部关联消费。
         * commandCount：任务创建时固定的已应用命令计数，响应沿用此值。
         * userId：单用户查询的用户编号，列表或仅订单编号查询可为 0。
         * orderId：按普通订单编号读取的目标编号，其他查询分支可为 0。
         * laneMask：本次任务全部目标账户 Lane 的位集合，用于投递和完成条件核对。
         */
        private final long id, commandCount, userId, orderId, laneMask;
        /** 客户端订单标识，配合用户编号在所属 Lane 查询订单。 */
        private final String clientName;
        /** 任务固定的协议查询类型，决定读取对象和响应编码。 */
        private final CoreMessageType type;
        /** 按 Lane 编号存放的单 Lane 查询响应，全部完成后由最后完成者选择或归并。 */
        private final CoreResponse[] responses;
        /** 各 Lane 是否命中单实体的标记，合并订单查询时检查只能有一个所有者命中。 */
        private final boolean[] found;
        /** Owner 按挂单索引选出的固定顺序编号数组，非挂单页为 null。 */
        private final long[] openOrderIds;
        /** 与挂单编号对齐的详情 DTO 槽，只保留实际读取时仍为 OPEN 的订单。 */
        private final com.surprising.aeron.protocol.CoreOrderStateView[] openOrders;
        /** 算法父单的固定路由与结果槽，非算法查询为 null。 */
        private final AlgoQueryPage algoPage;
        /** 触发单页的固定路由与结果槽，投递前由 Owner 设置。 */
        private TriggerQueryPage triggerPage;
        /** 待完成划转页的固定路由与结果槽，投递前由 Owner 设置。 */
        private PendingTransferQueryPage transferPage;
        /** 原子累计完成 Lane 位，并检测同一账户查询 Lane 重复完成。 */
        private final AtomicLong completedLaneMask = new AtomicLong();

        /**
         * 固定查询类型、身份和目标 Lane 集合，按拓扑创建各 Lane 响应槽，挂单页另外分配与所选编号对齐的 DTO 槽。
         * @param id 本次异步查询的内部编号，完成响应用它写入结果表
         * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
         * @param type 本次查询的协议消息类型，用来选择账户、账本或编码分支
         * @param userId 查询目标用户编号，具体查询类型决定是否允许用 0 表示未指定
         * @param orderId 精确查询的普通订单编号；不按普通订单编号查询的分支为 0
         * @param clientName 客户端订单标识，仅客户端订单详情查询使用
         * @param laneMask 本次查询需要访问的账户 Lane 位集合，全部完成后才归并
         * @param openOrderIds 按索引页顺序固定的活动订单编号，非挂单页为 null
         * @param algoPage 本次算法父单查询的固定路由及结果槽，其他查询为 null
         */
        private AccountQuery(long id, long commandCount, CoreMessageType type, long userId,
                             long orderId, String clientName, long laneMask, long[] openOrderIds, AlgoQueryPage algoPage) {
            this.id = id; this.commandCount = commandCount; this.type = type;
            this.userId = userId; this.orderId = orderId; this.clientName = clientName; this.laneMask = laneMask;
            // 当前拓扑的账户 Lane 总数，按此分配响应与命中槽。
            int count = owner.runtimeState().topology().accountLaneCount();
            responses = new CoreResponse[count]; found = new boolean[count];
            this.openOrderIds = openOrderIds;
            this.algoPage = algoPage;
            openOrders = openOrderIds == null ? null : new com.surprising.aeron.protocol.CoreOrderStateView[openOrderIds.length];
        }

        /**
         * 在目标 Lane 建立只读范围并记录本 Lane 响应；原子累计完成位并拒绝重复完成，最后一个 Lane 归并和保存结果，通知 Owner。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public void execute(AccountLaneState lane) {
            // 当前 Lane 的读取响应，列表分支可为 null，因为实际 DTO 已写入页槽。
            CoreResponse response;
            try { response = owner.runtimeState().readAccountLane(lane, this); }
            catch (/* 当前读取或页组装超过查询规模上限，按所在分支记录或返回查询过大。 */ RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            }
            catch (/* 账户读取或最终归并发现非法参数，转换为 INVALID_COMMAND。 */ IllegalArgumentException invalid) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            responses[lane.laneId()] = response;
            // 当前 Lane 的完成位，用于原子合并及检测重复完成。
            long bit = 1L << lane.laneId();
            // 把当前 Lane 合入后的完成集合，等于目标集合才可归并最终结果。
            // 本轮 CAS 前已完成的 Lane 集合，更新冲突时重新读取。
            long previous, complete;
            do {
                previous = completedLaneMask.get(); complete = previous | bit;
                if ((previous & bit) != 0) throw new IllegalStateException("account query Lane completed twice");
            } while (!completedLaneMask.compareAndSet(previous, complete));
            if (complete != laneMask) return;
            // 最后完成的 Lane 归并出的整笔查询响应，写入完成表供 Owner 消费。
            CoreResponse completed;
            try { completed = completedResponse(); }
            catch (/* 账户读取或最终归并发现非法参数，转换为 INVALID_COMMAND。 */ IllegalArgumentException invalid) {
                completed = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            completedBookQueries.put(id, CompletedBookQuery.encoded(completed));
            owner.runtimeState().signalOwnerCompletion();
        }

        /**
         * 在当前 Lane 原位读取所选划转、触发、算法、挂单或单实体详情；列表按固定下标填 DTO，单实体按协议返回详情、哈希或缺失结果。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public CoreResponse apply(AccountLaneState lane) {
            if (transferPage != null) {
                for (/* 当前页编号和 DTO 数组的共同下标，所属 Lane 只写它实际拥有的记录。 */ int index = 0; index < transferPage.ids.length; index++) {
                    if (owner.runtimeState().topology().accountLaneId(transferPage.users[index]) != lane.laneId()) continue;
                    // 所属 Lane 读出的待完成划转，用户身份必须与固定路由一致。
                    var value = owner.runtimeState().pendingTransfer(transferPage.ids[index]);
                    if (value == null || value.userId() != transferPage.users[index])
                        throw new IllegalArgumentException("pending transfer routing differs from Lane identity");
                    transferPage.views[index] = new com.surprising.aeron.protocol.CorePendingTransferView(value.userId(), value.command());
                }
                return null;
            }
            if (triggerPage != null) {
                for (/* 当前页编号和 DTO 数组的共同下标，所属 Lane 只写它实际拥有的记录。 */ int index = 0; index < triggerPage.ids.length; index++) {
                    if (owner.runtimeState().topology().accountLaneId(triggerPage.users[index]) != lane.laneId()) continue;
                    // 所属 Lane 当前仍保留的触发单，存在时才填入对应视图槽。
                    var value = owner.runtimeState().triggerOrder(triggerPage.ids[index]);
                    if (value != null) triggerPage.views[index] = value.view();
                }
                return null;
            }
            if (algoPage != null) {
                for (/* 当前页编号和 DTO 数组的共同下标，所属 Lane 只写它实际拥有的记录。 */ int index = 0; index < algoPage.ids.length; index++) {
                    if (owner.runtimeState().topology().accountLaneId(algoPage.users[index]) != lane.laneId()) continue;
                    // 当前单个算法父单及保留子订单汇总后的结果列表，非空时取其唯一首项。
                    var views = RuntimeOperationalQueryService.algoOrders(owner.runtimeState(), List.of(algoPage.ids[index]));
                    if (!views.isEmpty()) algoPage.views[index] = views.getFirst();
                }
                return null;
            }
            if (openOrderIds != null) {
                for (/* 当前页编号和 DTO 数组的共同下标，所属 Lane 只写它实际拥有的记录。 */ int index = 0; index < openOrderIds.length; index++) {
                    // 当前 Lane 对某个挂单编号的详情探测结果，只把命中且仍 OPEN 的订单写入页槽。
                    var query = RuntimeStateQueryService.orderState(owner.runtimeState(), lane, owner.identities(), openOrderIds[index]);
                    if (query.found() && "OPEN".equals(query.view().status())) openOrders[index] = query.view();
                }
                return null;
            }
            if (type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY) {
                // 当前用户的完整状态与哈希查询结果，超限标记优先转换为拒绝。
                var query = RuntimeStateQueryService.userState(owner.runtimeState(), lane, owner.identities(), userId);
                if (query.tooLarge()) return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
                found[lane.laneId()] = query.found();
                if (type == CoreMessageType.USER_STATE_HASH_QUERY)
                    return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(query.stateHash()));
                return query.found() ? new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeUserState(query.view()))
                        : missingAccountResponse(type, commandCount);
            }
            // 按用户客户端标识或普通订单编号读取的结果，未命中时按原协议返回缺失。
            var query = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                    ? RuntimeStateQueryService.clientOrderState(owner.runtimeState(), lane, owner.identities(), userId, clientName)
                    : orderId == 0 ? null : RuntimeStateQueryService.orderState(owner.runtimeState(), lane, owner.identities(), orderId);
            found[lane.laneId()] = query != null && query.found();
            if (!found[lane.laneId()]) return missingAccountResponse(type, commandCount);
            // 当前查询已经编码的协议字节，随后由对应响应或盘口上限检查使用。
            byte[] encoded = type == CoreMessageType.ORDER_STATE_HASH_QUERY
                    ? CoreStateQueryCodec.encodeStateHash(query.stateHash()) : CoreStateQueryCodec.encodeOrderState(query.view());
            return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
        }

        /**
         * 先检查所选 Lane 的参数或超限失败，再按原索引顺序合并列表；算法父单额外核对全局子单条数，单订单必须只有一个 Lane 命中。
         */
        private CoreResponse completedResponse() {
            // 尚待检查错误的目标 Lane 位集合，每轮清除最低一位。
            long pending = laneMask;
            while (pending != 0) {
                // 当前最低有效位对应的账户 Lane 编号，用于读取固定响应或命中槽。
                int laneId = Long.numberOfTrailingZeros(pending); pending &= pending - 1;
                // 当前目标 Lane 的响应，非法参数和超限拒绝要优先于部分列表结果返回。
                var response = responses[laneId];
                if (response != null && (response.resultCode() == CoreResultCode.INVALID_COMMAND
                        || response.resultCode() == CoreResultCode.QUERY_RESPONSE_TOO_LARGE)) return response;
            }
            if (transferPage != null) return new CoreResponse(ResponseStatus.OK, commandCount,
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encode(java.util.Arrays.asList(transferPage.views)));
            if (triggerPage != null) {
                // 按触发索引原顺序过滤空槽后的触发单 DTO 列表。
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreTriggerOrderStateView>(triggerPage.views.length);
                for (var /* 当前触发单页槽的 DTO，已删除或未读取到的项为空并跳过。 */ view : triggerPage.views) if (view != null) selected.add(view);
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(selected));
            }
            if (algoPage != null) {
                // 按父单选择顺序合并的算法 DTO 列表，追加前核对合计子单扫描量。
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreAlgoOrderView>(algoPage.views.length);
                // 所有已合并父单的子单引用总数，不能仅按每个 Lane 独立放宽扫描预算。
                int children = 0;
                for (var /* 当前算法父单的汇总视图，其子单列表长度计入全局扫描预算。 */ view : algoPage.views) if (view != null) {
                    children = Math.addExact(children, view.childOrderIds().size());
                    if (children > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
                        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                                CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
                    selected.add(view);
                }
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(selected));
            }
            if (openOrders != null) {
                // 按挂单索引页原顺序过滤空槽后的 OPEN 订单详情列表。
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreOrderStateView>(openOrders.length);
                for (var /* 当前挂单页槽中的活动订单 DTO，不存在时不进入结果。 */ order : openOrders) if (order != null) selected.add(order);
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(selected)));
            }
            // 单实体查询唯一命中 Lane 的响应，再次命中说明账户身份分布错误。
            CoreResponse selected = null;
            // 尚待检查单实体命中的 Lane 位集合，每轮只访问一个目标 Lane。
            long lanes = laneMask;
            while (lanes != 0) {
                // 当前最低有效位对应的账户 Lane 编号，用于读取固定响应或命中槽。
                int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
                if (!found[laneId]) continue;
                if (selected != null) throw new IllegalStateException("order identity is present in multiple Account Lanes");
                selected = responses[laneId];
            }
            return selected == null ? responses[Long.numberOfTrailingZeros(laneMask)] : selected;
        }
    }

    /**
     * 沿用账本查询的登记和投递流程，Owner 返回待完成，财库内容由账本线程读取及编码。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    public CoreResponse beginTreasuryQuery(CoreMessage message) { return beginLedgerQuery(message); }

    /** 账本邮箱暂满时保留的唯一强平工作查询，Owner 在消费入口重试投递，成功后清空。 */
    private LiquidationWorkQuery deferredLiquidationWorkQuery;

    /**
     * 选择强平工作候选，保险页保留游标外计划以计算全局分配；先投递 Lane 取事实，再投递账本等回执，账本邮箱满时只保留一个延迟投递查询。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     * @param requested 强平工作类型、排他游标、页条数及编码字节预算
     */
    public CoreResponse beginLiquidationWorkQuery(CoreMessage message, CoreLiquidationWorkView.Query requested) {
        if (deferredLiquidationWorkQuery != null) return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        // 本次强平扫描的有序候选；保险查询包含页外计划，其他类型从排他游标后开始。
        var candidates = requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                ? owner.liquidationIndex().activeIds()
                : owner.liquidationIndex().activeIds().tailSet(requested.afterLiquidationId(), false);
        if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                && candidates.size() > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        // 本次查询的内部编号，用于登记异步完成、失败或后续消费。
        long queryId = nextAsyncQueryId++;
        // 已经固定候选、市场输入和结果槽的工作页查询，先投账户再等账本归并。
        var query = new LiquidationWorkQuery(queryId, owner.appliedCommandCount(), requested, candidates);
        queryIds.put(message.header().commandId(), queryId);
        if (query.laneMask != 0 && !owner.runtimeState().dispatchAccountQuery(query.laneMask, query)) {
            queryIds.remove(message.header().commandId());
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        if (!owner.runtimeState().dispatchLedgerQuery(query)) deferredLiquidationWorkQuery = query;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount());
    }

    /**
     * 一次强平执行、保险或 ADL 工作页的交接任务。Owner 固定候选和市场输入，Lane 读取计划事实，账本等待所有 Lane 后计算保险建议并编码有界页，不实际执行强平或赔付。
     */
    private final class LiquidationWorkQuery implements SettlementLaneWorker.Command, SystemLedgerLane.Entry {
        /**
         * id：本次任务的内部查询编号，用于保存最终响应和完成外部关联消费。
         * commandCount：任务创建时固定的已应用命令计数，响应沿用此值。
         * laneMask：本次任务全部目标账户 Lane 的位集合，用于投递和完成条件核对。
         */
        private final long id, commandCount, laneMask;
        /** 固定的强平工作类型、游标、条数和字节预算，Lane 与账本共用。 */
        private final CoreLiquidationWorkView.Query requested;
        /** 按强平编号顺序选择的本次扫描候选，保险分支包括最终返回页以外的相关计划。 */
        private final long[] ids;
        /**
         * lanes：与候选编号对齐的账户 Lane 编号，各消费者只处理自己的项。
         * assetIds：与候选编号对齐的结算资产编号，供保险请求准备和分配使用。
         */
        private final int[] lanes, assetIds;
        /** Owner 为各候选固定的规范币对对象，Lane 读取计划时核对身份。 */
        private final com.surprising.aeron.service.state.instrument.CoreInstrument[] instruments;
        /** 各 Lane 填写的保险或 ADL 缺口 DTO 槽，无合格计划时保持 null。 */
        private final CoreLiquidationWorkView.Resolution[] receipts;
        /** 各 Lane 填写的强平执行动作 DTO 槽，未满足状态与价格序号筛选时保持 null。 */
        private final com.surprising.aeron.protocol.CoreLiquidationActionView[] actions;
        /** Owner 为各候选固定的标记价输入，执行页用它核对触发价格序号。 */
        private final com.surprising.aeron.service.state.market.MarkPriceRuntime[] marks;
        /** Owner 固定的风险续扫信息，与本次工作页一起返回。 */
        private final com.surprising.aeron.protocol.CoreRiskScanContinuation continuation;
        /** 本次扫描上限内是否已经包含全部候选来源，不等于最终返回页已经结束。 */
        private final boolean sourceComplete;
        /** 各账户 Lane 的失败码，账本先检查失败再编码工作页，避免返回部分成功。 */
        private final CoreResultCode[] failures;
        /** 保险查询各 Lane 准备的相关请求输入，账本合并后按原基金余额计算建议。 */
        private final InsuranceAllocationPolicy.AllocationInputs[] claims;
        /** 原子累计强平查询的账户完成位，账本 ready 仅在所有目标 Lane 齐全时通过。 */
        private final java.util.concurrent.atomic.AtomicLong completed = new java.util.concurrent.atomic.AtomicLong();
        /**
         * 按扫描上限固定候选编号、所属 Lane、币对、结算资产和标记价，并记录是否已选完来源及风险续扫信息；各 Lane 的结果槽只属于本次查询。
         * @param id 本次异步查询的内部编号，完成响应用它写入结果表
         * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
         * @param requested 强平工作类型、排他游标、页条数及编码字节预算
         * @param candidates 已按强平编号排序的候选集合，保险分支含当前页游标之外的相关计划
         */
        LiquidationWorkQuery(long id, long commandCount, CoreLiquidationWorkView.Query requested, java.util.NavigableSet<Long> candidates) {
            this.id = id; this.commandCount = commandCount; this.requested = requested;
            // 本次实际选择的候选数量，取来源数量和全局扫描上限的较小值。
            int count = Math.min(candidates.size(), RuntimeOperationalQueryService.MAX_INDEX_SCAN);
            sourceComplete = count == candidates.size();
            continuation = RuntimeLiquidationQueryService.continuation(owner.runtimeState(), owner.identities(), requested.purpose());
            ids = new long[count]; lanes = new int[ids.length]; assetIds = new int[ids.length];
            instruments = new com.surprising.aeron.service.state.instrument.CoreInstrument[ids.length];
            receipts = new CoreLiquidationWorkView.Resolution[ids.length];
            actions = new com.surprising.aeron.protocol.CoreLiquidationActionView[ids.length];
            marks = new com.surprising.aeron.service.state.market.MarkPriceRuntime[ids.length];
            failures = new CoreResultCode[owner.runtimeState().topology().accountLaneCount()];
            claims = new InsuranceAllocationPolicy.AllocationInputs[owner.runtimeState().topology().accountLaneCount()];
            // 逐项累计候选所属的账户 Lane 位，构造结束后固定为该任务的目标集合。
            long mask = 0;
            // 写入候选编号、Lane、资产、币对和标记价多个对齐数组的共同下标。
            int index = 0;
            for (long /* 当前遍历的候选强平编号，超过本次固定数量后停止取路由。 */ liquidationId : candidates) {
                if (index == count) break;
                // 当前索引记录的身份与归属路由，用来取得所属用户并定位账户 Lane。
                var route = owner.liquidationIndex().route(liquidationId);
                if (route == null) throw new IllegalStateException("liquidation query route is missing");
                instruments[index] = owner.runtimeState().instrument(route.instrumentId());
                assetIds[index] = owner.identities().assetId(instruments[index].settleAsset());
                marks[index] = owner.runtimeState().markPrice(owner.identities().findSymbolId(route.instrumentId()));
                ids[index] = liquidationId; lanes[index] = owner.runtimeState().topology().accountLaneId(route.userId());
                mask |= 1L << lanes[index++];
            }
            laneMask = mask;
        }
        /**
         * 当前 Lane 读取其候选计划，按工作类型和固定市场输入筛选执行动作或缺口回执；保险页还准备本 Lane 的全部相关请求，记录错误后发布完成位并通知 Owner。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public void execute(AccountLaneState lane) {
            try { owner.runtimeState().readAccountLane(lane, /* 当前已建立合法只读范围的账户 Lane，只处理分配给它的强平候选。 */ owned -> {
                for (/* 当前检查的固定候选槽，下标同时定位所属 Lane、计划编号和市场输入。 */ int index = 0; index < ids.length; index++) {
                    if (lanes[index] != owned.laneId()) continue;
                    // 所属账户当前保留的强平计划，已经不存在时跳过该候选。
                    var value = owner.runtimeState().liquidation(ids[index]);
                    if (value == null) continue;
                    // 工作页类型，决定构造执行动作、保险缺口还是 ADL 缺口。
                    var purpose = requested.purpose();
                    if (purpose == CoreLiquidationWorkView.Purpose.EXECUTION) {
                        // 查询进入时固定的标记价，执行页要求它与计划的触发价格序号相同。
                        var mark = marks[index];
                        if ((value.status() != com.surprising.aeron.service.state.model.CoreLiquidationState.Status.PLANNED
                                && value.status() != com.surprising.aeron.service.state.model.CoreLiquidationState.Status.ORDERED)
                                || mark == null || mark.priceSequence() != value.triggerPriceSequence()) continue;
                        actions[index] = new com.surprising.aeron.protocol.CoreLiquidationActionView(value.liquidationId(),
                                value.userId(), instruments[index].instrumentId(), value.marginMode(), value.positionSide(),
                                value.triggerPriceSequence(), value.signedQuantitySteps(), value.closeQuantitySteps(),
                                mark.markPriceTicks(), value.status().name(),
                                value.status() == com.surprising.aeron.service.state.model.CoreLiquidationState.Status.ORDERED
                                        ? value.nextCancelOrderId() : 0);
                        continue;
                    }
                    // 保险或 ADL 缺口页要求的计划状态，不能把其他阶段混入该页。
                    var required = purpose == CoreLiquidationWorkView.Purpose.INSURANCE
                            ? com.surprising.aeron.service.state.model.CoreLiquidationState.Status.INSURANCE_REQUIRED
                            : com.surprising.aeron.service.state.model.CoreLiquidationState.Status.ADL_REQUIRED;
                    if (value.status() != required) continue;
                    // Owner 为当前候选固定的规范币对，需与账户计划持有对象和产品线一致。
                    var instrument = instruments[index];
                    if (instrument == null || instrument != value.instrument() || instrument.contractType().productLine() != owner.productLine()) continue;
                    receipts[index] = new CoreLiquidationWorkView.Resolution(value.liquidationId(), value.userId(),
                            instrument.instrumentId(), instrument.settleAsset(), value.marginMode(), value.positionSide(),
                            value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(), 0, purpose);
                }
                if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE)
                    claims[owned.laneId()] = InsuranceAllocationPolicy.prepareLaneAllocations(owner.runtimeState(), ids, assetIds, instruments);
                return null;
            }); }
            catch (/* 当前读取或页组装超过查询规模上限，按所在分支记录或返回查询过大。 */ RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
            } catch (/* 读取计划事实时发现参数或算术错误，记入当前 Lane 的 INVALID_COMMAND 失败槽。 */ IllegalArgumentException | ArithmeticException invalid) {
                failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
            }
            completed.getAndUpdate(/* 原子更新前已完成的 Lane 位集合，本次只合入当前 Lane 的位。 */ mask -> mask | (1L << lane.laneId()));
            owner.runtimeState().signalOwnerCompletion();
        }
        /**
         * 只有所有目标 Lane 的完成位齐全，账本才可读取回执；空路由的零位集合直接就绪。
         */
        @Override public boolean ready() { return completed.get() == laneMask; }
        /**
         * 在账本线程等待 Lane 已完成后，先返回失败或组装有界工作页；保险页依据全部请求和原基金余额计算建议金额，编码结果并通知 Owner，不执行资金支付。
         * @param ledger 当前账本线程持有的原财库，用于只读查询或保险建议计算
         */
        @Override public void apply(TreasuryRuntime ledger) {
            // 账本归并、保险建议计算及编码后产生的最终工作页响应。
            CoreResponse response;
            try {
                for (CoreResultCode /* 按 Lane 编号检查到的失败码，有失败就立即返回拒绝，不生成部分成功页。 */ failure : failures) {
                    if (failure == null) continue;
                    completedBookQueries.put(id, CompletedBookQuery.encoded(new CoreResponse(
                            ResponseStatus.REJECTED, ResponseStatus.REJECTED, failure, commandCount)));
                    owner.runtimeState().signalOwnerCompletion();
                    return;
                }
                // 按固定候选和双重预算组装的工作页，保险分支再补建议金额。
                var result = RuntimeLiquidationQueryService.workPage(owner.productLine(), requested, ids, actions,
                        receipts, continuation, sourceComplete);
                if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE) {
                    // 账本依据各 Lane 全部相关请求和原保险余额算出的建议分配，不是实际支付。
                    var allocations = InsuranceAllocationPolicy.allocations(ledger, InsuranceAllocationPolicy.mergeLaneClaims(claims));
                    result = RuntimeLiquidationQueryService.insuranceRecommendations(result, allocations);
                }
                response = new CoreResponse(ResponseStatus.OK, commandCount, CoreLiquidationWorkCodec.encodeWork(result));
            } catch (/* 当前读取或页组装超过查询规模上限，按所在分支记录或返回查询过大。 */ RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            } catch (/* 工作页或保险建议计算发生非法参数或算术错误，转换为 INVALID_COMMAND。 */ IllegalArgumentException | ArithmeticException invalid) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            completedBookQueries.put(id, CompletedBookQuery.encoded(response));
            owner.runtimeState().signalOwnerCompletion();
        }
    }

    /**
     * 把未平仓量或合约维护页查询交给所属账本线程，投递失败清除编号，成功返回待完成。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     * @param page 合约编号、排他用户游标和返回条数条件
     * @param maintenance 查询进入时取得的合约维护状态，随用户页一起编码返回
     */
    public CoreResponse beginPositionIndexQuery(CoreMessage message,
            com.surprising.aeron.protocol.CoreMaintenanceCodec.Query page,
            com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        // 为本次异步读取新分配的内部查询编号，与外部命令 UUID 另行关联。
        long id = nextAsyncQueryId++;
        queryIds.put(message.header().commandId(), id);
        // 在账本所属索引上读取 OI 或合约维护用户页的任务。
        var query = new LedgerQuery(id, owner.appliedCommandCount(), message.header().messageType(), page, maintenance);
        try {
            if (!owner.runtimeState().dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除外部命令与查询编号的关联，再继续上抛。 */ RuntimeException failure) { queryIds.remove(message.header().commandId()); throw failure; }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount());
    }

    /**
     * 为财库读取分配内部编号并投递账本，邮箱满时拒绝本次查询，投递异常不留下外部命令关联。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    private CoreResponse beginLedgerQuery(CoreMessage message) {
        // 为本次异步读取新分配的内部查询编号，与外部命令 UUID 另行关联。
        long id = nextAsyncQueryId++;
        queryIds.put(message.header().commandId(), id);
        // 只读原财库并编码响应的账本任务，与账户查询使用同一结果登记表。
        LedgerQuery query = new LedgerQuery(id, owner.appliedCommandCount());
        try {
            if (!owner.runtimeState().dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除外部命令与查询编号的关联，再继续上抛。 */ RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount());
    }

    /**
     * 在系统账本线程读取财库、未平仓量或合约维护用户页的单次任务，直接消费原账本或其索引，编码后通知 Owner。
     * 构造约定：固定本次账本查询的协议类型，以及可选的合约维护分页条件和维护状态。
     * 参数 id：本次异步查询的内部编号，完成响应用它写入结果表
     * 参数 commandCount：查询进入时固定的已应用命令计数，作为响应计数
     * 参数 type：本次查询的协议消息类型，用来选择账户、账本或编码分支
     * 参数 page：可选的合约维护用户分页条件，财库或 OI 查询不使用
     * 参数 maintenance：查询进入时取得的合约维护状态，随用户页一起编码返回
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private final class LedgerQuery implements SystemLedgerLane.Entry {
        /** 本次任务的内部查询编号，用于保存最终响应和完成外部关联消费。 */
        private final long id;
        /** 任务创建时固定的已应用命令计数，响应沿用此值。 */
        private final long commandCount;
        /** 任务固定的协议查询类型，决定读取对象和响应编码。 */
        private final CoreMessageType type;
        /** 账本查询可选的合约用户分页条件，财库和 OI 分支不使用。 */
        private final com.surprising.aeron.protocol.CoreMaintenanceCodec.Query page;
        /** 随合约维护用户页一起返回的固定维护状态，不在账本查询中修改。 */
        private final com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance;
        /**
         * 创建只读取财库的账本任务，页条件与合约维护信息均不需要。
         * @param id 本次异步查询的内部编号，完成响应用它写入结果表
         * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
         */
        private LedgerQuery(long id, long commandCount) {
            this(id, commandCount, CoreMessageType.TREASURY_STATE_QUERY, null, null);
        }
        /**
         * 在账本线程读取 OI 索引、按用户游标的合约维护页或财库资产，检查响应规模并编码；参数或规模错误只拒绝本次查询，随后保存响应通知 Owner。
         * @param ledger 当前账本线程持有的原财库，用于只读查询或保险建议计算
         */
        @Override public void apply(TreasuryRuntime ledger) {
            // 原账本或索引读取后生成的成功或业务拒绝响应。
            CoreResponse response;
            try {
                // 当前查询已经编码的协议字节，随后由对应响应或盘口上限检查使用。
                byte[] encoded;
                if (type == CoreMessageType.OPEN_INTEREST_QUERY) {
                    // OI 索引导出的各币对多空数量汇总，先检查规模再构造查询视图。
                    var totals = owner.openInterestIndex().totals();
                    if (totals.size() > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                        throw new RuntimeOperationalQueryService.QueryTooLargeException();
                    // 与各币对多空汇总一一对应的 OI 协议 DTO 列表。
                    var views = new ArrayList<com.surprising.aeron.protocol.CoreOpenInterestView>(totals.size());
                    for (var /* 当前币对及其多空持仓数量汇总项，转成 OI 查询 DTO。 */ entry : totals.entrySet()) {
                        views.add(new com.surprising.aeron.protocol.CoreOpenInterestView(
                                entry.getKey(), entry.getValue().longQuantity(), entry.getValue().shortQuantity()));
                    }
                    encoded = com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views);
                } else if (type == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
                    // 合约维护页内按用户编号升序选择的有仓用户编号，数量不超过页上限。
                    var users = new ArrayList<Long>(page.limit());
                    // 已经发现页上限之后还有用户，供维护页指示后续仍有数据。
                    boolean more = false;
                    // 持仓用户索引中严格大于当前游标的下一用户编号，0 表示已无后继。
                    long next = owner.positionUserIndex().higherUserId(page.instrumentId(), page.afterUserId());
                    while (next != 0) {
                        if (users.size() == page.limit()) { more = true; break; }
                        users.add(next);
                        next = owner.positionUserIndex().higherUserId(page.instrumentId(), next);
                    }
                    encoded = com.surprising.aeron.protocol.CoreMaintenanceCodec.encodePage(
                            new com.surprising.aeron.protocol.CoreMaintenanceCodec.Page(maintenance, users, more));
                } else encoded = CoreStateQueryCodec.encodeTreasuryState(
                        RuntimeOperationalQueryService.treasuryAssets(ledger, owner.identities()));
                response = new CoreResponse(ResponseStatus.OK, commandCount, encoded);
            } catch (/* 财库或 OI 返回数量超限，只把本次查询转换为响应过大。 */ RuntimeOperationalQueryService.QueryTooLargeException failure) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            } catch (/* 账本查询条件或编码参数非法，转换为 INVALID_COMMAND。 */ IllegalArgumentException failure) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, commandCount);
            }
            completedBookQueries.put(id, CompletedBookQuery.encoded(response));
            owner.runtimeState().signalOwnerCompletion();
        }
    }

    /**
     * 解码单币对盘口查询，在对应 Matcher 提交栅栏之后读取层级；异步完成或失败分别登记，Owner 先返回待完成。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    public CoreResponse beginBookQuery(CoreMessage message) {
        if (message.payloadLength() == 0) {
            throw new IllegalArgumentException("single-instrumentId book query payload is required");
        }
        // 已解码的单币对编号和盘口深度条件，必须提供非空载荷。
        var query = CoreStateQueryCodec.decodeOrderBookQuery(message.payloadUnsafe());
        // 本次查询的内部编号，用于登记异步完成、失败或后续消费。
        long queryId = nextAsyncQueryId++;
        try {
            owner.matcherPipeline.readAtSubmissionFence(owner.matchingAdapter().matcherShardId(query.instrumentId()),
                    () -> owner.matchingAdapter().orderBookLevelsAsync(query.instrumentId(), query.depth()).join())
                    .whenComplete((/* Matcher 在对应提交栅栏后读取出的盘口层级，包装后交给 Owner 编码。 */ levels, /* Matcher 盘口读取或投递失败，登记内部失败标记，随后由 Owner 消费为拒绝响应。 */ failure) -> {
                        if (failure != null) failedQueries.put(queryId, true);
                        else completedBookQueries.put(queryId, CompletedBookQuery.single(levels));
                    });
        } catch (/* Matcher 盘口读取或投递失败，登记内部失败标记，随后由 Owner 消费为拒绝响应。 */ RuntimeException failure) {
            failedQueries.put(queryId, true);
        }
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                owner.appliedCommandCount());
    }

    /**
     * 已有快照编号时校验会话和深度并续页；新请求异步读取一个或多个 Matcher 的初始化快照，合并后交 Owner 建立有界分页会话。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    public CoreResponse beginBookBootstrapQuery(CoreMessage message) {
        // 初始化盘口请求，含快照标识、币对游标、深度和每页币对上限。
        CoreOrderBookBootstrapQuery query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(message.payloadUnsafe());
        if (!query.snapshotId().isEmpty()) {
            // 按快照标识找到的现有会话，必须存在且深度一致才能继续分页。
            BookBootstrapSession session = bookBootstrapSessions.get(query.snapshotId());
            if (session == null || session.depth() != query.depth()) {
                return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
            }
            return bootstrapPageResponse(session, query);
        }
        // 本次查询的内部编号，用于登记异步完成、失败或后续消费。
        long queryId = nextAsyncQueryId++;
        try {
            // 异步取得的初始化快照凭据；单 Matcher 读栅栏，多 Matcher 分别读取后合并。
            var snapshot = owner.matchingAdapter().topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline.readAtSubmissionFence(0,
                    () -> owner.matchingAdapter().orderBookBootstrapAsync(query.depth()).join())
                    : owner.matcherPipeline.readEachAsync(
                    /* 当前要读取初始化盘口的 Matcher 分片编号。 */ shardId -> owner.matchingAdapter().orderBookBootstrapShard(shardId, query.depth()))
                    .thenApply(owner.matchingAdapter()::mergeBookBootstrapShards);
            snapshot.whenComplete((/* 所有 Matcher 初始化读取及合并完成的快照内容，先包装完成结果，Owner 稍后创建会话。 */ value, /* 初始化读取、分片合并或任务投递失败，登记标记供 Owner 消费时返回拒绝。 */ failure) -> {
                if (failure != null) failedQueries.put(queryId, true);
                else completedBookQueries.put(queryId, CompletedBookQuery.bootstrap(
                        message.header().commandId().toString(), query, value));
            });
        } catch (/* 初始化读取、分片合并或任务投递失败，登记标记供 Owner 消费时返回拒绝。 */ RuntimeException failure) {
            failedQueries.put(queryId, true);
        }
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                owner.appliedCommandCount());
    }

    /**
     * 按外部命令到内部编号的关联表判断是否还有未消费的查询。
     */
    public boolean hasPendingQueries() {
        return !queryIds.isEmpty();
    }

    /**
     * 按外部命令 UUID 查找内部异步编号，没有登记时返回 0。
     * @param queryId 外部查询请求的命令 UUID，用来查找内部异步编号
     */
    public long querySequence(UUID queryId) {
        return queryIds.getOrDefault(queryId, 0L);
    }

    /**
     * 在账户和财务读取者已经停止后清理完成结果、失败标记、编号关联及待投递引用，防止工作线程在清理后又写回结果。
     */
    public void clearPendingQueries() {
        completedBookQueries.clear();
        failedQueries.clear();
        queryIds.clear();
        deferredLiquidationWorkQuery = null;
        pendingRiskQueries.clear();
    }

    /**
     * 由 Owner 继续尝试风险账户交接和延迟账本投递，再消费一次性结果及编号关联；已编码响应直接返回，盘口原始结果在此编码或建立有界初始化会话。尚未完成返回 null。
     * @param queryId 需要继续投递并尝试消费结果的内部异步编号
     */
    public CoreResponse takeQueryResult(long queryId) {
        // 该内部编号仍等待账本路由或账户投递的风险查询，尝试投递成功后从待交接表移除。
        var riskQuery = pendingRiskQueries.get(queryId);
        if (riskQuery != null && riskQuery.dispatchAccounts()) pendingRiskQueries.remove(queryId);
        if (deferredLiquidationWorkQuery != null && deferredLiquidationWorkQuery.id == queryId
                && owner.runtimeState().dispatchLedgerQuery(deferredLiquidationWorkQuery)) deferredLiquidationWorkQuery = null;
        if (failedQueries.remove(queryId) != null) {
            queryIds.values().removeIf(/* 关联表当前保存的内部编号，与正在消费的编号一致时删除该关联。 */ value -> value == queryId);
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        // 按内部编号一次性移出的完成结果，尚未产生结果时为 null。
        CompletedBookQuery completed = completedBookQueries.remove(queryId);
        if (completed == null) return null;
        queryIds.values().removeIf(/* 关联表当前保存的内部编号，与正在消费的编号一致时删除该关联。 */ value -> value == queryId);
        if (completed.encodedResponse() != null) return completed.encodedResponse();
        // Owner 此刻已发布的实时序号，包装盘口响应或初始化会话时固定。
        long exportSequence = owner.commits.publication.publishedSequence();
        if (completed.bootstrapSnapshot() == null) {
            // 把当前发布水位与单币对层级组合后的盘口协议视图。
            var view = new com.surprising.aeron.protocol.CoreOrderBookView(exportSequence, completed.levels());
            // 当前查询已经编码的协议字节，随后由对应响应或盘口上限检查使用。
            byte[] encoded = CoreStateQueryCodec.encodeOrderBookView(view);
            return boundedBookResponse(completed.levels().size(), encoded);
        }
        // 由已完成快照建立的固定分页会话，插入前按容量淘汰最早会话。
        BookBootstrapSession session = BookBootstrapSession.create(completed.snapshotId(), exportSequence,
                completed.bootstrapQuery().depth(), completed.bootstrapSnapshot());
        while (bookBootstrapSessions.size() >= TradingCoreRuntime.MAX_BOOK_BOOTSTRAP_SNAPSHOTS) {
            bookBootstrapSessions.remove(bookBootstrapSessions.keySet().iterator().next());
        }
        bookBootstrapSessions.put(session.snapshotId(), session);
        return bootstrapPageResponse(session, completed.bootstrapQuery());
    }

    /**
     * 校验币对游标属于固定会话，按币对文本顺序选择其后最多 limit 个币对并汇总层级，计算续页游标，编码后检查条数与字节上限。
     * @param session 已经固定盘口内容、导出水位和深度的初始化分页会话
     * @param query 当前页使用的快照编号、币对游标、深度和币对数量上限
     */
    CoreResponse bootstrapPageResponse(BookBootstrapSession session, CoreOrderBookBootstrapQuery query) {
        if (!query.symbolCursor().isEmpty() && !session.symbols().containsKey(query.symbolCursor())) {
            return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
        }
        // 固定会话中严格位于请求币对游标之后的有序币对键视图。
        var tailSymbols = session.symbols().tailMap(query.symbolCursor(), false).keySet();
        // 本页按字典序选出的币对编号列表，按币对条数上限分页而非按层级数截断。
        List<String> symbols = new ArrayList<>(Math.min(query.limit(), tailSymbols.size()));
        for (String /* 当前从排他游标之后选入本页的币对编号。 */ symbol : tailSymbols) {
            symbols.add(symbol);
            if (symbols.size() >= query.limit()) break;
        }
        // 本页全部币对的层级总数，先用精确加法累计再准备响应列表容量。
        int expectedLevels = 0;
        for (String /* 当前本页币对编号，先统计其层级数，再按同一顺序并入结果。 */ instrumentId : symbols) {
            expectedLevels = Math.addExact(expectedLevels, session.symbols().get(instrumentId).size());
        }
        // 本页所选币对的全部盘口层级，编码后还要检查全局层级与字节预算。
        List<com.surprising.aeron.protocol.CoreBookLevelView> levels = new ArrayList<>(expectedLevels);
        for (String /* 当前本页币对编号，先统计其层级数，再按同一顺序并入结果。 */ instrumentId : symbols) levels.addAll(session.symbols().get(instrumentId));
        // 当前页已无币对，或最后一个币对之后再无后继，表示会话分页结束。
        boolean complete = symbols.isEmpty()
                || session.symbols().higherKey(symbols.getLast()) == null;
        // 未结束时为本页最后一个币对编号，结束时使用空文本。
        String nextCursor = complete ? "" : symbols.getLast();
        // 带固定快照标识、发布序号、后续游标及层级的初始化页。
        CoreOrderBookBootstrapPage page = new CoreOrderBookBootstrapPage(session.snapshotId(),
                session.exportSequence(), nextCursor, complete, levels);
        // 当前查询已经编码的协议字节，随后由对应响应或盘口上限检查使用。
        byte[] encoded = CoreStateQueryCodec.encodeOrderBookBootstrapPage(page);
        return boundedBookResponse(levels.size(), encoded);
    }

    /**
     * 统一检查盘口层级数量和编码字节上限，超限返回专用错误，否则按当前已应用命令计数构造成功响应。
     * @param levelCount 本次编码包含的全部盘口层级数，不是币对数量
     * @param encoded 已经完成的盘口响应字节，用其实际长度检查响应上限
     */
    CoreResponse boundedBookResponse(int levelCount, byte[] encoded) {
        if (levelCount > TradingCoreRuntime.MAX_BOOK_RESPONSE_LEVELS || encoded.length > TradingCoreRuntime.MAX_BOOK_RESPONSE_BYTES) {
            return owner.rejected(CoreResultCode.BOOK_QUERY_RESPONSE_TOO_LARGE);
        }
        return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(), encoded);
    }

    /**
     * 已完成查询的交接结果：可直接携带已编码响应，也可携带单币对盘口层级或多币对初始化快照，Owner 消费时选择对应协议。
     * @param levels 单币对盘口查询返回的层级列表，初始化分支使用独立快照
     * @param snapshotId 初始化快照及分页会话的稳定标识，用于后续游标校验
     * @param bootstrapQuery 多币对盘口初始化请求，普通层级或已编码响应分支为 null
     * @param bootstrapSnapshot 从 Matcher 取得的多币对初始化快照，普通或已编码响应分支为 null
     * @param encodedResponse 读取线程已经构造的最终查询响应，盘口原始结果分支为 null
     */
    record CompletedBookQuery(
            List<com.surprising.aeron.protocol.CoreBookLevelView> levels,
            String snapshotId,
            CoreOrderBookBootstrapQuery bootstrapQuery,
            BookBootstrapSnapshot bootstrapSnapshot,
            CoreResponse encodedResponse) {

        /**
         * 保存尚待 Owner 包装的盘口查询结果，已编码响应分支设为 null。
         * @param levels 单币对盘口查询返回的层级列表，初始化分支使用独立快照
         * @param snapshotId 初始化快照及分页会话的稳定标识，用于后续游标校验
         * @param bootstrapQuery 多币对盘口初始化请求，普通层级或已编码响应分支为 null
         * @param bootstrapSnapshot 从 Matcher 取得的多币对初始化快照，普通或已编码响应分支为 null
         */
        CompletedBookQuery(List<com.surprising.aeron.protocol.CoreBookLevelView> levels, String snapshotId,
                           CoreOrderBookBootstrapQuery bootstrapQuery, BookBootstrapSnapshot bootstrapSnapshot) {
            this(levels, snapshotId, bootstrapQuery, bootstrapSnapshot, null);
        }

        /**
         * 把所属读取线程已经编码的响应包装为可一次消费的完成结果，无须 Owner 再读实体。
         * @param response 所属读取线程已经完成编码的最终查询响应
         */
        static CompletedBookQuery encoded(CoreResponse response) { return new CompletedBookQuery(List.of(), "", null, null, response); }

        /**
         * 复制单币对盘口层级列表作为不可修改的查询交接内容，Owner 稍后补导出水位并编码。
         * @param levels 单币对盘口查询返回的层级列表，初始化分支使用独立快照
         */
        static CompletedBookQuery single(
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels) {
            return new CompletedBookQuery(List.copyOf(levels), "", null, null);
        }

        /**
         * 保留多币对初始化查询条件及 Matcher 快照，Owner 消费后创建分页会话。
         * @param snapshotId 初始化快照及分页会话的稳定标识，用于后续游标校验
         * @param query 创建这份快照的原始初始化请求，Owner 据此返回第一页
         * @param snapshot Matcher 读取及合并后的初始化快照，包含币对集合和对应层级
         */
        static CompletedBookQuery bootstrap(
                String snapshotId,
                CoreOrderBookBootstrapQuery query,
                BookBootstrapSnapshot snapshot) {
            return new CompletedBookQuery(List.of(), snapshotId, query, snapshot);
        }
    }

    /**
     * 多币对盘口初始化的分页会话，固定快照编号、导出水位、深度和按币对文本排序的只读层级映射，后续页不能混入另一份快照。
     * @param snapshotId 初始化快照及分页会话的稳定标识，用于后续游标校验
     * @param exportSequence Owner 消费结果时固定的实时发布序号，后续各页共用
     * @param depth 该初始化会话的每侧盘口深度，后续页请求必须一致
     * @param symbols 按币对文本排序的只读盘口分组，列表也保持不可修改
     */
    record BookBootstrapSession(
            String snapshotId,
            long exportSequence,
            int depth,
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> symbols) {

        /**
         * 按币对文本建立有序分组，把层级归入已有币对并拒绝未知引用；最后冻结每组列表和映射，后续分页共用同一快照内容。
         * @param snapshotId 初始化快照及分页会话的稳定标识，用于后续游标校验
         * @param exportSequence Owner 消费结果时固定的实时发布序号，后续各页共用
         * @param depth 该初始化会话的每侧盘口深度，后续页请求必须一致
         * @param snapshot Matcher 读取及合并后的初始化快照，包含币对集合和对应层级
         */
        static BookBootstrapSession create(
                String snapshotId,
                long exportSequence,
                int depth,
                BookBootstrapSnapshot snapshot) {
            // 按币对文本排序的临时分组表，组装完成后冻结映射及各列表。
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> grouped = new TreeMap<>();
            for (String /* 快照声明存在的币对编号，先建立空列表，使空盘口币对也能参与分页。 */ instrumentId : snapshot.symbols()) grouped.put(instrumentId, new ArrayList<>());
            for (com.surprising.aeron.protocol.CoreBookLevelView /* 快照中的一个盘口层级，只能归入快照已声明的币对分组。 */ level : snapshot.levels()) {
                // 当前层级对应币对的分组列表，找不到说明快照引用了未知币对。
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels = grouped.get(level.instrumentId());
                if (levels == null) throw new IllegalStateException("bootstrap level references unknown instrumentId");
                levels.add(level);
            }
            grouped.replaceAll((/* 正在冻结列表的币对键，冻结动作只需要同项的列表内容。 */ instrumentId, /* 本币对已完成分组的层级列表，复制为不可修改列表后交给固定会话。 */ levels) -> List.copyOf(levels));
            return new BookBootstrapSession(snapshotId, exportSequence, depth,
                    Collections.unmodifiableNavigableMap(grouped));
        }
    }
}
