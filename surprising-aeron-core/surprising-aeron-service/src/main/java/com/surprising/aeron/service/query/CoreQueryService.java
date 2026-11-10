package com.surprising.aeron.service.query;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLeverageView;
import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRiskScanContinuation;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTreasuryAssetView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.matcher.OrderBookDepthSnapshot;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.snapshot.state.CoreStateHash;
import com.surprising.aeron.service.treasury.InsuranceAllocationPolicy;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import static com.surprising.aeron.service.core.TradingCoreRuntime.*;

/** 核心内部查询的异步编排入口。Owner 解析条件并登记编号，账户 Lane、Matcher 或系统账本在各自所有权内读取；结果回到这里供 Owner 消费，不能为查询复制一份在线权威账户。 构造约定：绑定所属交易运行时，查询的投递、身份路由和结果计数均沿用该运行时。 参数 owner：所属交易运行时，提供查询投递、路由、身份和提交水位入口 */
@RequiredArgsConstructor
public final class CoreQueryService {
    /** 提供业务路由、线程投递和提交水位的所属运行时，查询仍遵守实际状态所有权。 */
    final TradingCoreRuntime owner;

    /** 只有 Owner 访问关联表；工作线程只完成请求自己的 Future。 */
    private final Map<UUID, CompletableFuture<QueryResult>> pendingQueries = new java.util.HashMap<>();

    /** Owner 持有的有界盘口初始化会话，按插入顺序淘汰最早会话，各页共用固定快照。 */
    final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();

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
     * 从 Owner 的待完成划转路由取一页，空页直接返回；有数据则登记请求完成状态并投递对应 Lane，投递失败清除关联，成功后完成本次路由页选择并返回待完成。
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
        // 保存这次划转路由及 DTO 槽的账户查询任务，空页不创建任务。
        var query = new AccountQuery(owner.appliedCommandCount(), CoreMessageType.PENDING_TRANSFER_QUERY,
                0, 0, null, page.laneMask(), null, null);
        query.transferPage = new PendingTransferQueryPage(page.ids(), page.users(),
                new com.surprising.aeron.protocol.TransferFundsCommand[page.ids().length]);
        registerQuery(message, query);
        try {
            if (!owner.runtimeState().dispatchAccountQuery(page.laneMask(), query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除请求关联，再继续上抛。 */ RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        owner.runtimeState().completePendingTransferPage(page);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
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
        if (owner.orderIdInUse(command.orderId())) throw new com.surprising.aeron.exception.CoreStateRejectedException(
                "DUPLICATE_ORDER_ID", "orderId already exists");
        // Owner 为当前预检取得的币对决策输入，避免 Lane 任意回读变化中的产品元数据。
        var context = com.surprising.aeron.service.order.OrderAdmissionService.queryContext(
                owner.runtimeState(), owner.identities(), command.instrumentId(), timestamp);
        var query = new PreflightQuery(owner.appliedCommandCount(), userId, command, context);
        registerQuery(message, query);
        try {
            if (!owner.runtimeState().dispatchAccountQuery(owner.runtimeState().topology().accountLaneMask(userId), query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除请求关联，再继续上抛。 */ RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /**
     * 一次下单预检的查询任务：由用户所属 Lane 检查订单和所需预留，只返回估算结果，不创建真实订单或冻结。
     */
    private final class PreflightQuery extends CompletableFuture<QueryResult> implements SettlementLaneWorker.Command,
            java.util.function.Function<AccountPartition, CoreResponse> {
        /**
         * commandCount：任务创建时固定的已应用命令计数，响应沿用此值。
         * userId：必须存在的预检用户编号，只在其所属 Lane 读取账户。
         */
        private final long commandCount, userId;
        /** 准入未平仓量，供账户预检使用。 */
        private final long openInterest;
        /** 只读预检采用的下单意图，不作为真实撮合命令提交。 */
        private final com.surprising.aeron.protocol.PlaceOrderCommand command;
        /** Owner 固定的预检币对、标记价、费率及结算状态输入。 */
        private final com.surprising.aeron.service.order.OrderAdmissionService.Context context;

        /**
         * 保存本次预检的订单和固定决策输入；若标的需要则同步读取未平仓量。
         * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
         * @param userId 查询目标用户编号，具体查询类型决定是否允许用 0 表示未指定
         * @param command 已解码的普通下单意图，本次只做只读预检，不提交真实订单
         * @param context Owner 固定的币对、价格、费率和生命周期决策输入
         */
        private PreflightQuery(long commandCount, long userId, com.surprising.aeron.protocol.PlaceOrderCommand command,
                com.surprising.aeron.service.order.OrderAdmissionService.Context context) {
            this.commandCount = commandCount; this.userId = userId;
            this.command = command; this.context = context;
            if (context.instrument().usesOpenInterestForUserLimit() && !command.reduceOnly()) {
                this.openInterest = owner.runtimeState().ledgerAdmissionOpenInterest(command.instrumentId());
            } else {
                this.openInterest = 0;
            }
        }

        /**
         * 进入所属账户的合法只读范围执行预检，把业务拒绝、算术溢出或非法参数转换为查询响应，然后保存结果并通知 Owner。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public void execute(AccountPartition lane) {
            // 当前账户 Lane 执行预检后的成功结果或明确业务拒绝响应。
            CoreResponse response;
            try { response = owner.runtimeState().readAccountLane(lane, this); }
            catch (/* 订单规则或资金检查产生的明确业务拒绝，使用原拒绝码编码响应。 */ com.surprising.aeron.exception.CoreStateRejectedException failure) {
                response = rejected(CoreResultCode.fromRejectionCode(failure.code()));
            } catch (/* 预检金额计算发生算术溢出，返回 ARITHMETIC_OVERFLOW。 */ ArithmeticException failure) { response = rejected(CoreResultCode.ARITHMETIC_OVERFLOW); }
            catch (/* 预检输入非法，返回 INVALID_COMMAND，不使查询一直等待。 */ IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
            complete(QueryResult.encoded(response));
        }

        /**
         * 按固定输入解析订单规则，在当前 Lane 取得客户及持仓身份，计算所需预留并编码资产和金额；只检查，不写真实订单或冻结。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public CoreResponse apply(AccountPartition lane) {
            // 按固定市场和规则输入解析出的预检订单参数。
            var order = com.surprising.aeron.service.order.OrderAdmissionService.resolve(context, userId, command);
            // 本次只读预检算出的所需冻结金额，只编码返回而不真正冻结。
            long reserved = com.surprising.aeron.service.order.OrderAdmissionService.requiredReservationForQuery(
                    owner.runtimeState(), lane, userId, order, openInterest, context.symbolId(),
                    owner.identities().findClientKeyValue(userId, command.clientOrderId()),
                    owner.identities().findPositionKeyValueInLane(lane, userId, context.instrument(), command.positionSide()),
                    context.settlementStatus());
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
     * 创建风险查询对象，登记外部命令与请求完成状态，并投递到相应 Lane。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     */
    public CoreResponse beginRiskAccountQuery(CoreMessage message) {
        var query = RiskAccountQuery.prepare(owner, message);
        registerQuery(message, query);
        query.dispatchAccounts();
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
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
        com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds = null;
        if (type == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            // 已解码的挂单页币对、排他订单游标和条数条件。
            var pageQuery = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payloadUnsafe());
            // 规范化后的排他编号游标，协议的 0 转成 Long.MAX_VALUE 作为首页起点。
            long before = pageQuery.beforeOrderId() == 0 ? Long.MAX_VALUE : pageQuery.beforeOrderId();
            // 活动订单索引选出的有界挂单页，空页直接编码空列表。
            var page = owner.activeOrderIndex().page(userId, pageQuery.instrumentId(), before, pageQuery.limit());
            if (page.orderIds().isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                    CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(List.of())));
            openOrderIds = page.orderIds();
        }
        // 当前是否为只带普通订单编号的详情或哈希查询，决定是否需要跨 Lane 探测。
        boolean orderQuery = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY;
        if (!orderQuery && openOrderIds == null && userId <= 0) return missingAccountResponse(type, owner.appliedCommandCount());
        // 累计本次所选记录所属的账户 Lane 位，投递后必须等待这些 Lane 全部完成。
        long laneMask;
        if (openOrderIds != null) {
            laneMask = 0;
            for (int index = 0; index < openOrderIds.size(); index++) {
                long selected = openOrderIds.valueAt(index);
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
        // 已经确定身份条件和目标 Lane 的账户查询任务，负责所属线程读取与编码。
        var query = new AccountQuery(owner.appliedCommandCount(), type, userId, orderId, clientName, laneMask, openOrderIds, null);
        return dispatchAccountQuery(message, laneMask, query);
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
            if (++scanned > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN)
                throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
            // 当前索引记录的身份与归属路由，用来取得所属用户并定位账户 Lane。
            var route = owner.triggerOrderIndex().route(id);
            if (route == null || requested.beforeTriggerOrderId() != 0 && id >= before || requested.triggerOrderId() != 0 && requested.triggerOrderId() != id
                    || message.header().userId() != 0 && message.header().userId() != route.userId()
                    || requestedInstrument != 0 && requestedInstrument != route.instrumentId()
                    || requested.status() != null && requested.status() != route.status()
                    || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY && !route.status().open()) continue;
            selected.add(id); users.add(route.userId());
            laneMask |= owner.runtimeState().topology().accountLaneMask(route.userId());
            if (selected.size() == Math.min(requested.limit(), CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)) break;
        }
        if (selected.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(List.of()));
        // 保存固定触发单路由及 DTO 槽的账户查询任务。
        var query = new AccountQuery(owner.appliedCommandCount(), message.header().messageType(),
                0, 0, null, laneMask, null, null);
        query.triggerPage = new TriggerQueryPage(selected.toArray(), users.toArray(),
                new com.surprising.aeron.protocol.CoreTriggerOrderStateView[selected.size()]);
        return dispatchAccountQuery(message, laneMask, query);
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
            long selected = ids instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList primitive
                    ? primitive.valueAt(index) : ids.get(index);
            // 当前索引记录的身份与归属路由，用来取得所属用户并定位账户 Lane。
            var route = owner.algoOrderIndex().value(selected);
            if (route == null) throw new IllegalStateException("algo query route is missing");
            selectedIds[index] = selected; users[index] = route.userId();
            laneMask |= owner.runtimeState().topology().accountLaneMask(route.userId());
        }
        // 已经固定父单及用户路由的本次查询页，各 Lane 后续填入汇总视图。
        var page = new AlgoQueryPage(selectedIds, users,
                new com.surprising.aeron.protocol.CoreAlgoOrderView[selectedIds.length]);
        // 携带算法父单页的账户查询任务，不在 Owner 汇总真实账户子订单。
        var query = new AccountQuery(owner.appliedCommandCount(), CoreMessageType.ALGO_ORDER_QUERY,
                0, 0, null, laneMask, null, page);
        return dispatchAccountQuery(message, laneMask, query);
    }

    /**
     * 按请求 UUID 登记查询并投递目标 Lane；邮箱拒收或投递异常先清除关联，成功只返回待完成状态。
     * @param message 已经通过核心消息入口的查询请求，携带类型、外部命令 UUID、用户及编码载荷
     * @param laneMask 本次查询需要访问的账户 Lane 位集合，全部完成后才归并
     * @param query 已经固定身份、结果槽和目标 Lane 的账户查询任务
     */
    private CoreResponse dispatchAccountQuery(CoreMessage message, long laneMask, AccountQuery query) {
        registerQuery(message, query);
        try {
            if (!owner.runtimeState().dispatchAccountQuery(laneMask, query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (/* 投递任务时发生的原始异常，先移除请求关联，再继续上抛。 */ RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
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
    private record PendingTransferQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.TransferFundsCommand[] views) { }

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
    private final class AccountQuery extends CompletableFuture<QueryResult> implements SettlementLaneWorker.Command,
            java.util.function.Function<AccountPartition, CoreResponse> {
        /**
         * commandCount：任务创建时固定的已应用命令计数，响应沿用此值。
         * userId：单用户查询的用户编号，列表或仅订单编号查询可为 0。
         * orderId：按普通订单编号读取的目标编号，其他查询分支可为 0。
         * laneMask：本次任务全部目标账户 Lane 的位集合，用于投递和完成条件核对。
         */
        private final long commandCount, userId, orderId, laneMask;
        /** 客户端订单标识，配合用户编号在所属 Lane 查询订单。 */
        private final String clientName;
        /** 任务固定的协议查询类型，决定读取对象和响应编码。 */
        private final CoreMessageType type;
        /** 按 Lane 编号存放的单 Lane 查询响应，全部完成后由最后完成者选择或归并。 */
        private final CoreResponse[] responses;
        /** 各 Lane 是否命中单实体的标记，合并订单查询时检查只能有一个所有者命中。 */
        private final boolean[] found;
        /** Owner 按挂单索引选出的固定顺序编号数组，非挂单页为 null。 */
        private final com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds;
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
         * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
         * @param type 本次查询的协议消息类型，用来选择账户、账本或编码分支
         * @param userId 查询目标用户编号，具体查询类型决定是否允许用 0 表示未指定
         * @param orderId 精确查询的普通订单编号；不按普通订单编号查询的分支为 0
         * @param clientName 客户端订单标识，仅客户端订单详情查询使用
         * @param laneMask 本次查询需要访问的账户 Lane 位集合，全部完成后才归并
         * @param openOrderIds 按索引页顺序固定的活动订单编号，非挂单页为 null
         * @param algoPage 本次算法父单查询的固定路由及结果槽，其他查询为 null
         */
        private AccountQuery(long commandCount, CoreMessageType type, long userId,
                             long orderId, String clientName, long laneMask, com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds, AlgoQueryPage algoPage) {
            this.commandCount = commandCount; this.type = type;
            this.userId = userId; this.orderId = orderId; this.clientName = clientName; this.laneMask = laneMask;
            // 当前拓扑的账户 Lane 总数，按此分配响应与命中槽。
            int count = owner.runtimeState().topology().accountLaneCount();
            responses = new CoreResponse[count]; found = new boolean[count];
            this.openOrderIds = openOrderIds;
            this.algoPage = algoPage;
            openOrders = openOrderIds == null ? null : new com.surprising.aeron.protocol.CoreOrderStateView[openOrderIds.size()];
        }

        /**
         * 在目标 Lane 建立只读范围并记录本 Lane 响应；原子累计完成位并拒绝重复完成，最后一个 Lane 归并和保存结果，通知 Owner。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public void execute(AccountPartition lane) {
            // 当前 Lane 的读取响应，列表分支可为 null，因为实际 DTO 已写入页槽。
            CoreResponse response;
            try { response = owner.runtimeState().readAccountLane(lane, this); }
            catch (/* 当前读取或页组装超过查询规模上限，按所在分支记录或返回查询过大。 */ CoreQueryService.OperationalQueries.QueryTooLargeException tooLarge) {
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
            complete(QueryResult.encoded(completed));
        }

        /**
         * 在当前 Lane 原位读取所选划转、触发、算法、挂单或单实体详情；列表按固定下标填 DTO，单实体按协议返回详情、哈希或缺失结果。
         * @param lane 当前执行读取的账户 Lane，必须由其所属线程持有只读权限
         */
        @Override public CoreResponse apply(AccountPartition lane) {
            if (transferPage != null) {
                for (/* 当前页编号和 DTO 数组的共同下标，所属 Lane 只写它实际拥有的记录。 */ int index = 0; index < transferPage.ids.length; index++) {
                    if (owner.runtimeState().topology().accountLaneId(transferPage.users[index]) != lane.laneId()) continue;
                    // 所属 Lane 读出的待完成划转，用户身份必须与固定路由一致。
                    var value = owner.runtimeState().pendingTransfer(transferPage.ids[index]);
                    if (value == null || value.sourceUserId() != transferPage.users[index])
                        throw new IllegalArgumentException("pending transfer routing differs from Lane identity");
                    transferPage.views[index] = value;
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
                    algoPage.views[index] = CoreQueryService.OperationalQueries.algoOrder(
                            owner.runtimeState(), algoPage.ids[index]);
                }
                return null;
            }
            if (openOrderIds != null) {
                for (/* 当前页编号和 DTO 数组的共同下标，所属 Lane 只写它实际拥有的记录。 */ int index = 0; index < openOrderIds.size(); index++) {
                    // 当前 Lane 对某个挂单编号的详情探测结果，只把命中且仍 OPEN 的订单写入页槽。
                    var query = CoreQueryService.StateQueries.orderState(owner.runtimeState(), lane, owner.identities(), openOrderIds.valueAt(index), false);
                    if (query.found() && "OPEN".equals(query.view().status())) openOrders[index] = query.view();
                }
                return null;
            }
            if (type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY) {
                // 当前用户的完整状态与哈希查询结果，超限标记优先转换为拒绝。
                var query = CoreQueryService.StateQueries.userState(owner.runtimeState(), lane, owner.identities(), userId,
                        type == CoreMessageType.USER_STATE_HASH_QUERY);
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
                    ? CoreQueryService.StateQueries.clientOrderState(owner.runtimeState(), lane, owner.identities(), userId, clientName)
                    : orderId == 0 ? null : CoreQueryService.StateQueries.orderState(owner.runtimeState(), lane, owner.identities(), orderId,
                            type == CoreMessageType.ORDER_STATE_HASH_QUERY);
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
                    if (children > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN)
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
                && candidates.size() > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN)
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
        // 已经固定候选、市场输入和结果槽的工作页查询，先投账户再等账本归并。
        var query = new LiquidationWorkQuery(owner.appliedCommandCount(), requested, candidates);
        registerQuery(message, query);
        if (query.laneMask != 0 && !owner.runtimeState().dispatchAccountQuery(query.laneMask, query)) {
            pendingQueries.remove(message.header().commandId());
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        if (query.ready()) query.apply(owner.runtimeState().treasury());
        else deferredLiquidationWorkQuery = query;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /**
     * 一次强平执行、保险或 ADL 工作页的交接任务。Owner 固定候选和市场输入，Lane 读取计划事实，等待所有 Lane 后计算保险建议并编码有界页，不实际执行强平或赔付。
     */
    private final class LiquidationWorkQuery extends CompletableFuture<QueryResult> implements SettlementLaneWorker.Command {
        /**
         * commandCount：任务创建时固定的已应用命令计数，响应沿用此值。
         * laneMask：本次任务全部目标账户 Lane 的位集合，用于投递和完成条件核对。
         */
        private final long commandCount, laneMask;
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
        private final com.surprising.aeron.service.instrument.CoreInstrument[] instruments;
        /** 各 Lane 填写的保险或 ADL 缺口 DTO 槽，无合格计划时保持 null。 */
        private final CoreLiquidationWorkView.Resolution[] receipts;
        /** 各 Lane 填写的强平执行动作 DTO 槽，未满足状态与价格序号筛选时保持 null。 */
        private final com.surprising.aeron.protocol.CoreLiquidationActionView[] actions;
        /** Owner 为各候选固定的标记价输入，执行页用它核对触发价格序号。 */
        private final com.surprising.aeron.service.market.MarkPrice[] marks;
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
         * @param commandCount 查询进入时固定的已应用命令计数，作为响应计数
         * @param requested 强平工作类型、排他游标、页条数及编码字节预算
         * @param candidates 已按强平编号排序的候选集合，保险分支含当前页游标之外的相关计划
         */
        LiquidationWorkQuery(long commandCount, CoreLiquidationWorkView.Query requested, java.util.NavigableSet<Long> candidates) {
            this.commandCount = commandCount; this.requested = requested;
            // 本次实际选择的候选数量，取来源数量和全局扫描上限的较小值。
            int count = Math.min(candidates.size(), CoreQueryService.OperationalQueries.MAX_INDEX_SCAN);
            sourceComplete = count == candidates.size();
            continuation = CoreQueryService.LiquidationQueries.continuation(owner.runtimeState(), owner.identities(), requested.purpose());
            ids = new long[count]; lanes = new int[ids.length]; assetIds = new int[ids.length];
            instruments = new com.surprising.aeron.service.instrument.CoreInstrument[ids.length];
            receipts = new CoreLiquidationWorkView.Resolution[ids.length];
            actions = new com.surprising.aeron.protocol.CoreLiquidationActionView[ids.length];
            marks = new com.surprising.aeron.service.market.MarkPrice[ids.length];
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
        @Override public void execute(AccountPartition lane) {
            try { owner.runtimeState().readAccountLane(lane, /* 当前已建立合法只读范围的账户 Lane，只处理分配给它的强平候选。 */ owned -> {
                for (/* 当前检查的固定候选槽，下标同时定位所属 Lane、计划编号和市场输入。 */ int index = 0; index < ids.length; index++) {
                    if (lanes[index] != owned.laneId()) continue;
                    // 所属账户当前保留的强平计划，已经不存在时跳过该候选。
                    var value = owned.liquidation(ids[index]);
                    if (value == null) continue;
                    // 工作页类型，决定构造执行动作、保险缺口还是 ADL 缺口。
                    var purpose = requested.purpose();
                    if (purpose == CoreLiquidationWorkView.Purpose.EXECUTION) {
                        // 查询进入时固定的标记价，执行页要求它与计划的触发价格序号相同。
                        var mark = marks[index];
                        if ((value.status() != com.surprising.aeron.model.CoreLiquidationState.Status.PLANNED
                                && value.status() != com.surprising.aeron.model.CoreLiquidationState.Status.ORDERED)
                                || mark == null || mark.priceSequence() != value.triggerPriceSequence()) continue;
                        actions[index] = new com.surprising.aeron.protocol.CoreLiquidationActionView(value.liquidationId(),
                                value.userId(), instruments[index].instrumentId(), value.marginMode(), value.positionSide(),
                                value.triggerPriceSequence(), value.signedQuantitySteps(), value.closeQuantitySteps(),
                                mark.markPriceTicks(), value.status().name(),
                                value.status() == com.surprising.aeron.model.CoreLiquidationState.Status.ORDERED
                                        ? value.nextCancelOrderId() : 0);
                        continue;
                    }
                    // 保险或 ADL 缺口页要求的计划状态，不能把其他阶段混入该页。
                    var required = purpose == CoreLiquidationWorkView.Purpose.INSURANCE
                            ? com.surprising.aeron.model.CoreLiquidationState.Status.INSURANCE_REQUIRED
                            : com.surprising.aeron.model.CoreLiquidationState.Status.ADL_REQUIRED;
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
            catch (/* 当前读取或页组装超过查询规模上限，按所在分支记录或返回查询过大。 */ CoreQueryService.OperationalQueries.QueryTooLargeException tooLarge) {
                failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
            } catch (/* 读取计划事实时发现参数或算术错误，记入当前 Lane 的 INVALID_COMMAND 失败槽。 */ IllegalArgumentException | ArithmeticException invalid) {
                failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
            }
            completed.getAndUpdate(/* 原子更新前已完成的 Lane 位集合，本次只合入当前 Lane 的位。 */ mask -> mask | (1L << lane.laneId()));
        }
        /**
         * 只有所有目标 Lane 的完成位齐全，才可读取回执；空路由的零位集合直接就绪。
         */
        public boolean ready() { return completed.get() == laneMask; }
        /**
         * 等待 Lane 已完成后，先返回失败或组装有界工作页；保险页依据全部请求和原基金余额计算建议金额，编码结果并通知 Owner，不执行资金支付。
         * @param ledger 当前原财库，用于只读查询或保险建议计算
         */
        public void apply(TreasuryRuntime ledger) {
            // 账本归并、保险建议计算及编码后产生的最终工作页响应。
            CoreResponse response;
            try {
                for (CoreResultCode /* 按 Lane 编号检查到的失败码，有失败就立即返回拒绝，不生成部分成功页。 */ failure : failures) {
                    if (failure == null) continue;
                    complete(QueryResult.encoded(new CoreResponse(
                            ResponseStatus.REJECTED, ResponseStatus.REJECTED, failure, commandCount)));
                    return;
                }
                // 按固定候选和双重预算组装的工作页，保险分支再补建议金额。
                var result = CoreQueryService.LiquidationQueries.workPage(owner.productLine(), requested, ids, actions,
                        receipts, continuation, sourceComplete);
                if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE) {
                    // 账本依据各 Lane 全部相关请求和原保险余额算出的建议分配，不是实际支付。
                    var allocations = InsuranceAllocationPolicy.allocations(ledger, claims);
                    result = CoreQueryService.LiquidationQueries.insuranceRecommendations(result, allocations);
                }
                response = new CoreResponse(ResponseStatus.OK, commandCount, CoreLiquidationWorkCodec.encodeWork(result));
            } catch (/* 当前读取或页组装超过查询规模上限，按所在分支记录或返回查询过大。 */ CoreQueryService.OperationalQueries.QueryTooLargeException tooLarge) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            } catch (/* 工作页或保险建议计算发生非法参数或算术错误，转换为 INVALID_COMMAND。 */ IllegalArgumentException | ArithmeticException invalid) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            complete(QueryResult.encoded(response));
        }
    }

    /** 在 Owner 上同步读取财库、未平仓量或合约维护页，沿用查询预算和业务拒绝码。 */
    public CoreResponse queryLedger(CoreMessageType type,
            com.surprising.aeron.protocol.CoreMaintenanceCodec.Query page,
            com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        long commandCount = owner.appliedCommandCount();
        TreasuryRuntime ledger = owner.runtimeState().treasury();
        // 原账本或索引读取后生成的成功或业务拒绝响应。
        CoreResponse response;
        try {
            // 当前查询已经编码的协议字节，随后由对应响应或盘口上限检查使用。
            byte[] encoded;
            if (type == CoreMessageType.OPEN_INTEREST_QUERY) {
                // OI 索引导出的各币对多空数量汇总，先检查规模再构造查询视图。
                var totals = owner.openInterestIndex().totals();
                if (totals.size() > CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                    throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
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
                    CoreQueryService.OperationalQueries.treasuryAssets(ledger, owner.identities()));
            response = new CoreResponse(ResponseStatus.OK, commandCount, encoded);
        } catch (/* 财库或 OI 返回数量超限，只把本次查询转换为响应过大。 */ CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
        } catch (/* 账本查询条件或编码参数非法，转换为 INVALID_COMMAND。 */ IllegalArgumentException failure) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, commandCount);
        }
        return response;
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
        var result = new CompletableFuture<QueryResult>();
        registerQuery(message, result);
        try {
            owner.matcherPipeline().readAtSubmissionFence(owner.matchingAdapter().matcherShardId(query.instrumentId()),
                    () -> owner.matchingAdapter().orderBookLevels(query.instrumentId(), query.depth()))
                    .whenComplete((/* Matcher 在对应提交栅栏后读取出的盘口层级，包装后交给 Owner 编码。 */ levels, /* Matcher 盘口读取或投递失败，登记内部失败标记，随后由 Owner 消费为拒绝响应。 */ failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else result.complete(QueryResult.single(levels));
                    });
        } catch (/* Matcher 盘口读取或投递失败，登记内部失败标记，随后由 Owner 消费为拒绝响应。 */ RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING,
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
        var result = new CompletableFuture<QueryResult>();
        String snapshotId = message.header().commandId().toString();
        registerQuery(message, result);
        try {
            // 异步取得的初始化快照凭据；单 Matcher 读栅栏，多 Matcher 分别读取后合并。
            var snapshot = owner.matchingAdapter().topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline().readAtSubmissionFence(0,
                    () -> owner.matchingAdapter().orderBookBootstrapShard(0, query.depth()))
                    : owner.matcherPipeline().readEachAsync(
                    /* 当前要读取初始化盘口的 Matcher 分片编号。 */ shardId -> owner.matchingAdapter().orderBookBootstrapShard(shardId, query.depth()))
                    .thenApply(owner.matchingAdapter()::mergeBookBootstrapShards);
            snapshot.whenComplete((/* 所有 Matcher 初始化读取及合并完成的快照内容，先包装完成结果，Owner 稍后创建会话。 */ value, /* 初始化读取、分片合并或任务投递失败，登记标记供 Owner 消费时返回拒绝。 */ failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(QueryResult.bootstrap(
                        snapshotId, query, value));
            });
        } catch (/* 初始化读取、分片合并或任务投递失败，登记标记供 Owner 消费时返回拒绝。 */ RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING,
                owner.appliedCommandCount());
    }

    private void registerQuery(CoreMessage message, CompletableFuture<QueryResult> query) {
        if (pendingQueries.putIfAbsent(message.header().commandId(), query) != null)
            throw new IllegalArgumentException("query is already pending");
    }

    public boolean hasPendingQueries() { return !pendingQueries.isEmpty(); }

    public boolean hasPendingQuery(UUID commandId) { return pendingQueries.containsKey(commandId); }

    /** 消费者停止后解除 Owner 对查询任务的引用。 */
    public void clearPendingQueries() {
        pendingQueries.clear();
        deferredLiquidationWorkQuery = null;
    }

    /** Owner 继续必要投递，并按外部请求 UUID 一次性消费结果。 */
    public CoreResponse takeQueryResult(UUID commandId) {
        var query = pendingQueries.get(commandId);
        if (query == null) return null;
        if (query instanceof RiskAccountQuery riskQuery) riskQuery.dispatchAccounts();
        if (query == deferredLiquidationWorkQuery && deferredLiquidationWorkQuery.ready()) {
            deferredLiquidationWorkQuery.apply(owner.runtimeState().treasury());
            deferredLiquidationWorkQuery = null;
        }
        if (!query.isDone()) return null;
        pendingQueries.remove(commandId);
        QueryResult completed;
        try {
            completed = query.join();
        } catch (java.util.concurrent.CompletionException failure) {
            if (failure.getCause() instanceof Error fatal) throw fatal;
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
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
        while (bookBootstrapSessions.size() >= TradingCoreRuntime.Limits.MAX_BOOK_BOOTSTRAP_SNAPSHOTS) {
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
        if (levelCount > TradingCoreRuntime.Limits.MAX_BOOK_RESPONSE_LEVELS || encoded.length > TradingCoreRuntime.Limits.MAX_BOOK_RESPONSE_BYTES) {
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
    record QueryResult(
            List<com.surprising.aeron.protocol.CoreBookLevelView> levels,
            String snapshotId,
            CoreOrderBookBootstrapQuery bootstrapQuery,
            OrderBookDepthSnapshot bootstrapSnapshot,
            CoreResponse encodedResponse) {

        /**
         * 保存尚待 Owner 包装的盘口查询结果，已编码响应分支设为 null。
         * @param levels 单币对盘口查询返回的层级列表，初始化分支使用独立快照
         * @param snapshotId 初始化快照及分页会话的稳定标识，用于后续游标校验
         * @param bootstrapQuery 多币对盘口初始化请求，普通层级或已编码响应分支为 null
         * @param bootstrapSnapshot 从 Matcher 取得的多币对初始化快照，普通或已编码响应分支为 null
         */
        QueryResult(List<com.surprising.aeron.protocol.CoreBookLevelView> levels, String snapshotId,
                           CoreOrderBookBootstrapQuery bootstrapQuery, OrderBookDepthSnapshot bootstrapSnapshot) {
            this(levels, snapshotId, bootstrapQuery, bootstrapSnapshot, null);
        }

        /**
         * 把所属读取线程已经编码的响应包装为可一次消费的完成结果，无须 Owner 再读实体。
         * @param response 所属读取线程已经完成编码的最终查询响应
         */
        static QueryResult encoded(CoreResponse response) { return new QueryResult(List.of(), "", null, null, response); }

        /**
         * 复制单币对盘口层级列表作为不可修改的查询交接内容，Owner 稍后补导出水位并编码。
         * @param levels 单币对盘口查询返回的层级列表，初始化分支使用独立快照
         */
        static QueryResult single(
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels) {
            return new QueryResult(List.copyOf(levels), "", null, null);
        }

        /**
         * 保留多币对初始化查询条件及 Matcher 快照，Owner 消费后创建分页会话。
         * @param snapshotId 初始化快照及分页会话的稳定标识，用于后续游标校验
         * @param query 创建这份快照的原始初始化请求，Owner 据此返回第一页
         * @param snapshot Matcher 读取及合并后的初始化快照，包含币对集合和对应层级
         */
        static QueryResult bootstrap(
                String snapshotId,
                CoreOrderBookBootstrapQuery query,
                OrderBookDepthSnapshot snapshot) {
            return new QueryResult(List.of(), snapshotId, query, snapshot);
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
                OrderBookDepthSnapshot snapshot) {
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


/**
 * 一次风险快照或 ADL 候选查询的线程交接对象：Owner 固定市场输入，Ledger 选择有界路由，各账户 Lane 读取自己的状态，最后完成的 Lane 合并并编码响应。只保留本次路由和查询 DTO，不建立账户镜像。
 */
static final class RiskAccountQuery extends java.util.concurrent.CompletableFuture<CoreQueryService.QueryResult> implements SettlementLaneWorker.Command, Function<AccountPartition, Void> {
    /** 提供账本、账户投递及查询结果登记的所属运行时，不代表任意线程都能直接访问其账户。 */
    private final TradingCoreRuntime owner;
    /**
     * commandCount：进入查询时固定的已应用命令计数，作为返回响应的计数边界。
     * requestedUser：风险查询指定的用户编号，0 表示按持仓成员选择多个用户。
     */
    private final long commandCount, requestedUser;
    /** 账本路由阶段计算的目标账户 Lane 位集合，账户只在该集合内执行。 */
    private long laneMask;
    /**
     * users：账本选出的用户编号数组；风险路由按用户选择，ADL 中每个位置对应一份候选持仓，用户可重复。
     * positionKeys：与 users 对齐的 ADL 持仓身份数组；null 表示按用户生成风险快照。
     */
    private long[] users, positionKeys;
    private boolean accountsDispatched;
    /** ADL 查询的规范结算资产，风险快照查询用 null 区分分支。 */
    private final String asset;
    /** 每个 Lane 保留及最终合并的 ADL 候选上限，不放宽索引扫描规模限制。 */
    private final int limit;
    /** 进入查询时固定的市场输入，不含余额或持仓副本。 */
    private final CoreQueryService.RiskQueries.MarketInputs markets;
    /** 按 Lane 编号存放的风险快照 DTO 列表，各 Lane 只写自己的列表，全部完成后统一归并。 */
    private final List<ArrayList<CoreRiskSnapshotView>> riskResults;
    /** 各 Lane 独写的有界 ADL 候选列表，最后再按统一优先级合并。 */
    private final List<ArrayList<CoreAdlCandidateView>> adlResults;
    /** 每个 Lane 的业务失败码槽；全部完成后按 Lane 编号选择先遇到的失败。 */
    private final CoreResultCode[] failures;
    /** 原子累计已完成的账户 Lane 位，保证只有最后一个完成者编码响应，并检测重复回执。 */
    private final AtomicLong completedLaneMask = new AtomicLong();

    /**
     * 在 Owner 解码查询类型及 ADL 条件，固定当前命令计数和市场输入，创建稍后交给 Ledger 选择账户路由的查询对象。
     * @param owner 所属交易运行时，提供索引路由、查询登记和线程交接入口
     * @param message 风险状态或 ADL 候选查询消息，用户条件取自消息头，ADL 条件取自载荷
     */
    static RiskAccountQuery prepare(TradingCoreRuntime owner, CoreMessage message) {
        // 本次 ADL 资产筛选条件，风险快照查询保持 null。
        String asset = null;
        // 从 ADL 载荷读取的返回条数上限，风险查询保持零。
        int limit = 0;
        if (message.header().messageType() != CoreMessageType.RISK_STATE_QUERY) {
            // 已解码的 ADL 资产与候选数量条件，用于校验并规范化资产文本。
            var query = CoreAdlQueryCodec.decodeQuery(message.payloadUnsafe());
            asset = AssetBalance.normalizeAsset(query.asset());
            limit = query.limit();
        }
        return new RiskAccountQuery(owner, owner.appliedCommandCount(), message.header().userId(), asset, limit,
                CoreQueryService.RiskQueries.MarketInputs.capture(owner.runtimeState(), owner.identities(), owner.runtimeState().instrumentIdsForQuery()));
    }

    /**
     * 保存本次查询条件，并按原有 Lane 数量分配各 Lane 独写的风险结果、ADL 候选及失败槽，等待账本填好路由。
     * @param owner 所属交易运行时，提供索引路由、查询登记和线程交接入口
     * @param commandCount 查询进入时固定的已应用命令计数，完成响应沿用此值
     * @param requestedUser 风险查询的用户条件，0 表示按持仓成员查询全部用户
     * @param asset 规范化的 ADL 结算资产；null 表示本次为风险快照查询
     * @param limit ADL 最多返回的候选数量，风险快照查询不使用此限制
     * @param markets Owner 在进入查询时固定的币对配置与标记价输入，后续账户读取共用它
     */
    private RiskAccountQuery(TradingCoreRuntime owner, long commandCount,
            long requestedUser, String asset, int limit, CoreQueryService.RiskQueries.MarketInputs markets) {
        this.owner = owner; this.commandCount = commandCount; this.requestedUser = requestedUser;
        this.asset = asset; this.limit = limit; this.markets = markets;
        // 当前账户 Lane 总数，决定结果和错误槽的固定布局。
        int count = owner.runtimeState().topology().accountLaneCount();
        failures = new CoreResultCode[count];
        riskResults = new ArrayList<>(count); adlResults = new ArrayList<>(count);
        for (/* 正在初始化查询结果列表的账户 Lane 编号。 */ int lane = 0; lane < count; lane++) {
            riskResults.add(new ArrayList<>());
            adlResults.add(new ArrayList<>(Math.min(limit, 64)));
        }
        resolveRoutes();
    }

    /**
     * 按风险用户索引或 ADL 持仓索引选择有界路由，计算需要访问的账户 Lane；无路由或参数、规模错误就直接保存响应。
     */
    private void resolveRoutes() {
        try {
            if (asset == null) {
                // 按指定用户或全部持仓用户取得的风险路由，已受索引扫描上限约束。
                users = owner.positionUserIndex().riskQueryUserIds(requestedUser,
                        CoreQueryService.OperationalQueries.MAX_INDEX_SCAN);
            } else {
                // 指定结算资产下的非零持仓候选路由，每项都要保留用户及持仓身份。
                var routes = owner.adlPositionIndex().positions(asset, CoreQueryService.OperationalQueries.MAX_INDEX_SCAN);
                users = new long[routes.size()]; positionKeys = new long[routes.size()];
                // 写入 ADL 用户数组和持仓身份数组的共同下标，保证两数组对齐。
                int index = 0;
                for (var /* 当前 ADL 候选的用户、币对及持仓腿路由，用于构造精确持仓键。 */ route : routes) {
                    users[index] = route.userId();
                    positionKeys[index++] = com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry.positionIdentityKey(
                            route.userId(), route.instrumentId(), route.positionSide());
                }
            }
            for (long /* 当前已选路由的用户编号，用来累计其所属账户 Lane 位。 */ user : users) laneMask |= owner.runtimeState().topology().accountLaneMask(user);
            if (laneMask == 0) {
                complete(CoreQueryService.QueryResult.encoded(completedResponse()));
            }
        } catch (/* 账本选择路由时发现扫描规模超限，转换为查询响应过大。 */ CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            failRoutes(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (/* 账本选择路由时遇到的非法查询条件，转换为 INVALID_COMMAND。 */ IllegalArgumentException failure) { failRoutes(CoreResultCode.INVALID_COMMAND); }
    }

    /**
     * 路由阶段已经失败，无须再投递账户查询；保存带业务错误码的已编码响应，让 Owner 按普通查询结果消费。
     * @param code 需要返回的参数错误或查询规模超限等业务错误码
     */
    private void failRoutes(CoreResultCode code) {
        complete(CoreQueryService.QueryResult.encoded(rejected(code)));
    }

    /**
     * 由 Owner 把查询投到所选账户 Lane；已直接回复则无需投递，邮箱暂不能接受时返回 false 供后续继续尝试。
     */
    boolean dispatchAccounts() {
        if (accountsDispatched) return true;
        accountsDispatched = isDone() || owner.runtimeState().dispatchAccountQuery(laneMask, this);
        return accountsDispatched;
    }

    /**
     * 在所属 Lane 的合法只读范围执行查询，把可预期的参数或规模错误记入本 Lane 槽；原子标记完成，只有最后完成的 Lane 合并编码并通知 Owner，重复完成视为内部错误。
     * @param lane 当前执行查询的账户 Lane，只能读取属于它的用户及持仓
     */
    @Override public void execute(AccountPartition lane) {
        try { owner.runtimeState().readAccountLane(lane, this); }
        catch (/* 当前 Lane 生成风险结果时超过实体数量限制，记录在本 Lane 的失败槽。 */ CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
        } catch (/* 当前 Lane 估值时发现非法参数，记录在本 Lane 的失败槽。 */ IllegalArgumentException failure) {
            failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
        }
        // 当前完成 Lane 的唯一位标记，不能重复发布同一位。
        long bit = 1L << lane.laneId();
        // 本轮原子更新前已完成的 Lane 集合；CAS 失败就重新读取。
        // 本轮把当前 Lane 合入后的集合，等于目标集合时才可归并最终结果。
        long previous, complete;
        do {
            previous = completedLaneMask.get(); complete = previous | bit;
            if ((previous & bit) != 0) throw new IllegalStateException("risk query Lane completed twice");
        } while (!completedLaneMask.compareAndSet(previous, complete));
        if (complete != laneMask) return;
        // 最后完成的 Lane 生成的成功或业务拒绝响应，存入异步结果表。
        CoreResponse response;
        try { response = completedResponse(); }
        catch (/* 最终归并时发现总实体数超限，返回 QUERY_RESPONSE_TOO_LARGE。 */ CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            response = rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (/* 最终编码或合并时遇到非法参数，返回 INVALID_COMMAND。 */ IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
        complete(CoreQueryService.QueryResult.encoded(response));
    }

    /**
     * 在账户只读范围内过滤属于当前 Lane 的用户或持仓：风险查询重算用户快照，ADL 查询估值并保留有界候选；这里只写本 Lane 的 DTO 列表，不改账户。
     * @param lane 当前执行查询的账户 Lane，只能读取属于它的用户及持仓
     */
    @Override public Void apply(AccountPartition lane) {
        // 用户到 Lane 的既定拓扑，保证当前消费者只访问自己的账户。
        var topology = owner.runtimeState().topology();
        if (positionKeys == null) {
            // 当前 Lane 独写的风险快照列表，逐用户追加前检查本 Lane 上限。
            var result = riskResults.get(lane.laneId());
            for (long /* 风险查询路由中的用户，属于其他 Lane 时直接跳过。 */ user : users) {
                if (topology.accountLaneId(user) != lane.laneId()) continue;
                // 对当前用户和固定市场输入重新估值后得到的风险快照 DTO。
                var rows = CoreQueryService.RiskQueries.userSnapshots(owner.runtimeState(), owner.identities(), user, markets);
                if (result.size() + rows.size() > CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                    throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
                result.addAll(rows);
            }
        } else {
            // 当前 Lane 独写的 ADL 优先候选列表，最多保留本次 limit 条。
            var result = adlResults.get(lane.laneId());
            for (/* 逐项读取对齐用户与持仓键的 ADL 路由下标。 */ int index = 0; index < users.length; index++) {
                if (topology.accountLaneId(users[index]) != lane.laneId()) continue;
                // 当前账户持仓估值后的 ADL 候选；不满足候选条件时为 null。
                var candidate = CoreQueryService.RiskQueries.adlCandidate(owner.runtimeState(), owner.identities(),
                        users[index], positionKeys[index], asset, markets);
                if (candidate != null) CoreQueryService.RiskQueries.addAdlCandidate(result, candidate, limit);
            }
        }
        return null;
    }

    /**
     * 按 Lane 编号优先返回已有失败；否则风险快照先检查合并数量，再按用户、币对文本及持仓腿排序编码，ADL 则归并各 Lane 的有界候选。空路由也使用相同响应协议。
     */
    CoreResponse completedResponse() {
        for (CoreResultCode /* 按 Lane 编号检查到的失败码，有失败就不返回部分成功列表。 */ failure : failures) if (failure != null) return rejected(failure);
        // 最终风险快照列表或 ADL 候选列表的协议编码字节。
        byte[] encoded;
        if (positionKeys == null) {
            // 各 Lane 风险 DTO 总数，先核对全局上限再分配合并列表。
            int count = 0;
            for (var /* 当前统计数量的 Lane 风险列表，不读取其账户权威对象。 */ rows : riskResults) count = Math.addExact(count, rows.size());
            if (count > CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
            // 容量已按总数准备的合并风险列表，仅用于排序及响应编码。
            var result = new ArrayList<CoreRiskSnapshotView>(count);
            for (var /* 正在按 Lane 顺序并入最终结果的风险 DTO 列表。 */ rows : riskResults) result.addAll(rows);
            result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                    .thenComparing(CoreRiskSnapshotView::instrumentId)
                    .thenComparingInt(/* 用户及币对相同时用于比较持仓腿枚举顺序的风险快照项。 */ value -> value.positionSide().ordinal()));
            encoded = CoreRiskQueryCodec.encode(result);
        } else encoded = CoreAdlQueryCodec.encodeCandidates(CoreQueryService.RiskQueries.mergeAdlCandidates(adlResults, limit));
        return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
    }

    /**
     * 以查询进入时的命令计数构造业务拒绝响应，不修改交易命令的结果账本或账户状态。
     * @param code 需要返回的参数错误或查询规模超限等业务错误码
     */
    private CoreResponse rejected(CoreResultCode code) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
    }
}

/** 强平待办工作查询。接收所属 Lane 已筛选的执行、保险补亏和 ADL 回执，在查询边界用条数和编码字节预算分页，并携带尚未完成风险扫描的继续信息；这里不执行平仓或修改亏空。 构造约定：只组装强平只读查询结果，不持有另一份账户或强平状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static final class LiquidationQueries {

    /**
     * 把账户 Lane 已捕获的只读动作和补亏回执归并成一页。空回执也消费对应编号，未纳入的有效项保留给下页；同时保持来源是否完整、条数及编码字节预算语义。
     * @param productLine 这页强平工作的产品线，补亏筛选时还核对合约产品归属
     * @param query 本次用途、起始强平游标、最大条数及最大编码字节数
     * @param ids 与两个回执数组按相同下标对应的候选编号，顺序与分页遍历一致
     * @param actionReceipts 各账户 Lane 捕获的执行动作回执，不符合用途或失效时该位置为空
     * @param resolutionReceipts 与编号同下标的保险或 ADL 补亏回执，没有可展示项时为空
     * @param continuation 执行用途附带的风险扫描继续信息，没有待扫任务时可为空
     * @param sourceComplete 上游候选编号读取是否已到末尾，分页自己也可能因条数或字节上限变为未完
     */
    public static CoreLiquidationWorkView workPage(ProductLine productLine,
            CoreLiquidationWorkView.Query query, long[] ids, CoreLiquidationActionView[] actionReceipts,
            CoreLiquidationWorkView.Resolution[] resolutionReceipts, CoreRiskScanContinuation continuation,
            boolean sourceComplete) {
        // 本页已经接纳的强平执行动作，超过字节预算的最后一项不保留。
        ArrayList<CoreLiquidationActionView> actions = new ArrayList<>();
        // 本页补亏待办列表，保险推荐填充方法中则表示替换推荐额后的新列表。
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>();
        // 回执合页已消费到的强平编号，空回执也推进，有效项超限时留在前一项。
        long cursor = query.afterLiquidationId();
        // 该页是否已消费完来源范围，条数、扫描或字节边界使它变为 false。
        boolean complete = sourceComplete;
        // 回执合页当前已接纳的编码总长度，每次加项前都检查预算。
        int encoded = CoreLiquidationWorkCodec.emptyWorkLength(continuation);
        for (/* 当前候选编号及对应动作、补亏回执的共同数组下标。 */ int index = 0; index < ids.length; index++) {
            if (ids[index] <= query.afterLiquidationId()) continue;
            // 当前候选的执行动作视图，补亏用途或无有效执行回执时为空。
            var action = actionReceipts[index];
            // 当前候选的保险或 ADL 待办视图，执行用途或无有效补亏回执时为空。
            var resolution = resolutionReceipts[index];
            if (action == null && resolution == null) { cursor = ids[index]; continue; }
            if (actions.size() + resolutions.size() == query.maxItems()) { complete = false; break; }
            // 当前一条有效项增加的编码字节数，加入之前先与页面总上限比较。
            int itemBytes = Math.addExact(action == null ? 0 : CoreLiquidationWorkCodec.actionLength(action),
                    resolution == null ? 0 : CoreLiquidationWorkCodec.resolutionLength(resolution));
            if (Math.addExact(encoded, itemBytes) > query.maxBytes()) { complete = false; break; }
            encoded = Math.addExact(encoded, itemBytes);
            if (action != null) actions.add(action);
            if (resolution != null) resolutions.add(resolution);
            cursor = ids[index];
        }
        return new CoreLiquidationWorkView(productLine, cursor, complete, continuation, actions, resolutions);
    }

    /**
     * 在已有分页结果的补亏项上填入账本推荐覆盖额，缺少推荐时为 0；不改变索赔阶段、动作选择、游标或页面完成标记。
     * @param work 已经选好动作、补亏项及游标的原分页工作结果
     * @param allocations 账本按完整同资产索赔范围计算的推荐覆盖金额映射
     */
    public static CoreLiquidationWorkView insuranceRecommendations(CoreLiquidationWorkView work,
                                                                  java.util.Map<Long, Long> allocations) {
        // 本页补亏待办列表，保险推荐填充方法中则表示替换推荐额后的新列表。
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>(work.resolutions().size());
        for (var /* 当前强平事实或原补亏视图，读取后按本用途筛选或补入推荐额。 */ value : work.resolutions()) resolutions.add(new CoreLiquidationWorkView.Resolution(
                value.liquidationId(), value.userId(), value.instrumentId(), value.asset(), value.marginMode(), value.positionSide(),
                value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(),
                allocations.getOrDefault(value.liquidationId(), 0L), value.purpose()));
        return new CoreLiquidationWorkView(work.productLine(), work.nextCursorLiquidationId(), work.complete(),
                work.riskScanContinuation(), work.actions(), resolutions);
    }

    /**
     * 只在执行用途携带风险续扫信息；先检查币对扫描索引规模，再选下一份未完风险扫描，保留其外部币对、价格序号和最后完成用户。
     * @param runtime 允许读取本批强平、持仓及行情的运行时，调用方负责满足账户与市场读取边界
     * @param identities 外部币对、资产和持仓键与运行时整数身份的映射
     * @param purpose 本次只选择执行、保险补亏还是 ADL 待办
     */
    public static CoreRiskScanContinuation continuation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            CoreLiquidationWorkView.Purpose purpose) {
        if (purpose != CoreLiquidationWorkView.Purpose.EXECUTION) return null;
        // 风险扫描元数据集合，先用它的规模限制查询扫描范围。
        if (runtime.riskScanCount() > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN) {
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
        }
        // 公平调度中下一份未完成的账户风险扫描，若没有就不生成继续信息。
        RiskScanner.State selected = runtime.firstRiskIncompleteScan();
        return selected == null ? null
                : new CoreRiskScanContinuation(identities.instrumentId(selected.symbolId()),
                        selected.priceSequence(), selected.lastUserId());
    }
}

/** 账本资产、资金费及到期结算进度、条件单、算法母单和定时撤单的查询视图组装。各入口按自己的扫描、条数或分页规则限制规模，读取时仍须遵守账户 Lane 与账本的所有权。 构造约定：只提供查询视图转换，不额外持有资金或订单权威状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static final class OperationalQueries {

    /** 普通查询物化实体的统一条数上限一万，具体入口还可能接受更小的分页限制。 */
    public static final int MAX_QUERY_ENTITIES = 10_000;
    /** 单次查询允许扫描的索引规模上限 16384，避免结果少但遍历范围无限大。 */
    public static final int MAX_INDEX_SCAN = 16_384;

    /**
     * 通过运行时的账本静止读取入口获取资产视图，再调用账本数据版本的组装方法。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param identities 资产及币对整数编号到外部业务代码的映射
     */
    public static List<CoreTreasuryAssetView> treasuryAssets(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        return runtime.queryLedgerAtRest(/* 运行时静止读取入口传入的当前资金总账，按它组装资产视图。 */ ledger -> treasuryAssets(ledger, identities));
    }

    /**
     * 先检查总账项规模，再合并手续费、保险、亏空及各类残差中的资产编号，读取每种资产各项余额并按资产代码排序，返回只读列表。
     * @param treasury 当前允许读取的资金总账，包含各资产的手续费、保险和残差记录
     * @param identities 资产及币对整数编号到外部业务代码的映射
     */
    public static List<CoreTreasuryAssetView> treasuryAssets(TreasuryRuntime treasury, RuntimeIdentityRegistry identities) {
        if (treasury.assetLedgerEntryCount() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        // 从各类资金项中去重合并的资产整数编号集合。
        var ids = new org.eclipse.collections.impl.set.mutable.primitive.IntHashSet();
        ids.addAll(treasury.feeBalances().keySet());
        ids.addAll(treasury.insuranceBalances().keySet());
        ids.addAll(treasury.insuranceDeficits().keySet());
        ids.addAll(treasury.liquidationFeeBalances().keySet());
        ids.addAll(treasury.fundingResidualBalances().keySet());
        ids.addAll(treasury.roundingResidualBalances().keySet());
        ids.addAll(treasury.clearingPnlBalances().keySet());
        if (ids.size() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        // 资产编号数组，先按整数排序，再读取对应各项余额。
        int[] sorted = ids.toArray();
        Arrays.sort(sorted);
        // 当前各资产的完整资金视图，返回前再按对外资产代码排序。
        ArrayList<CoreTreasuryAssetView> views = new ArrayList<>(sorted.length);
        for (int /* 当前需要读取手续费、保险、亏空及残差余额的资产整数编号。 */ assetId : sorted) {
            views.add(new CoreTreasuryAssetView(identities.asset(assetId), treasury.fee(assetId),
                    treasury.insurance(assetId), treasury.insuranceDeficit(assetId),
                    treasury.liquidationFee(assetId), treasury.fundingResidual(assetId),
                    treasury.roundingResidual(assetId), treasury.clearingPnl(assetId)));
        }
        views.sort(Comparator.comparing(CoreTreasuryAssetView::asset));
        return List.copyOf(views);
    }

    /**
     * 已知币对有进行中的资金费时返回结算编号和下一用户游标；没有进行中任务则返回最近结算编号和已完成标记，未知币对按无任务处理。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param identities 资产及币对整数编号到外部业务代码的映射
     * @param instrumentId 要查询进度的外部币对编号，条件单过滤中空值或空串表示不限定
     */
    public static CoreFundingProgressView fundingProgress(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId) {
        // 外部币对编号在身份表中的已注册整数编号，未注册时直接返回无进行中任务。
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreFundingProgressView(0, true, 0, 0);
        // 当前运行时中的资金总账，进度记录由该总账维护。
        TreasuryRuntime treasury = runtime.treasury();
        // 当前币对尚未完成的资金费进度，存在时展示下一用户游标。
        TreasuryRuntime.FundingProgressRuntime progress = treasury.fundingProgress(symbolId);
        return progress == null
                ? new CoreFundingProgressView(treasury.fundingSettlement(symbolId), true, 0, 0)
                : new CoreFundingProgressView(progress.settlementId(), false, progress.nextCursorUserId(), 0);
    }

    /**
     * 读取到期结算的订单与用户继续游标、订单阶段完成标记及所需保险金额；没有进行中任务时展示最近结算编号为已完成，未知币对按无任务处理。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param identities 资产及币对整数编号到外部业务代码的映射
     * @param instrumentId 要查询进度的外部币对编号，条件单过滤中空值或空串表示不限定
     */
    public static CoreSettlementProgressView settlementProgress(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId) {
        // 外部币对编号在身份表中的已注册整数编号，未注册时直接返回无进行中任务。
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreSettlementProgressView(0, true, true, 0, 0, 0, 0);
        // 当前运行时中的资金总账，进度记录由该总账维护。
        TreasuryRuntime treasury = runtime.treasury();
        // 当前币对尚未完成的到期结算进度，包含撤单、用户游标和保险需求。
        TreasuryRuntime.LifecycleProgressRuntime progress = treasury.lifecycleProgress(symbolId);
        return progress == null
                ? new CoreSettlementProgressView(treasury.lifecycleSettlement(symbolId), true, true, 0, 0, 0, 0)
                : new CoreSettlementProgressView(progress.settlementId(), false, progress.ordersComplete(),
                progress.nextCursorOrderId(), progress.nextCursorUserId(), 0, 0, progress.requiredInsuranceUnits());
    }

    /**
     * 查询一笔算法母单，在子单扫描上限内汇总退役及仍保留子单的成交量和活动数量；母单不存在时返回 null。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param algoOrderId 要查看的算法母单编号
     */
    public static CoreAlgoOrderView algoOrder(TradingAccountRuntime runtime, long algoOrderId) {
        CoreAlgoOrderState state = runtime.algoOrder(algoOrderId);
        if (state == null) return null;
        if (state.childOrderIds().size() > MAX_INDEX_SCAN) throw new QueryTooLargeException();
        // 母单累计成交数量，先含退役子单进度，再加仍可读取子单的成交量。
        long executed = state.retiredExecutedQuantitySteps();
        // 母单仍处于 OPEN 的子单未成交量合计，单位为数量步数。
        long active = 0;
        // 仍有效的 OPEN 子单数量，与总历史子单数量区分。
        int activeCount = 0;
        for (long /* 母单当前关联的一笔子单编号，用来读取最新成交与剩余数量。 */ childOrderId : state.childOrderIds()) {
            // 子单当前订单状态；已退役且不存在时，其最终成交应已计入母单历史进度。
            Order child = runtime.order(childOrderId);
            if (child == null) continue; // 子单退役前，其最终成交量已经累计到母单。
            executed = Math.addExact(executed, child.executedQuantitySteps());
            if (child.status() == CoreOrderStatus.OPEN) {
                active = Math.addExact(active, child.remainingQuantitySteps());
                activeCount++;
            }
        }
        return new CoreAlgoOrderView(state.algoOrderId(), state.userId(), state.clientAlgoOrderId(),
                state.instrumentId(), state.algoTypeCode(), state.side(), state.priceTicks(), state.quantitySteps(),
                state.childQuantitySteps(), state.intervalSeconds(), state.durationSeconds(), state.marginMode(),
                state.positionSide(), state.reduceOnly(), state.timeInForce(), state.statusCode(),
                state.currentOrderId(), state.rejectReason(), state.startAtEpochMillis(),
                state.nextSliceAtEpochMillis(), state.createdAtEpochMillis(),
                state.updatedAtEpochMillis(), state.revision(), state.childOrderIds(), executed, active,
                activeCount, state.pendingClosedChildId());
    }

    /**
     * 沿给定定时器键读取仍存在的倒计时撤单记录，扫描超限时报错，结果达到一万条就停止并返回只读视图列表。
     * @param runtime 本次查询的运行时，调用方负责在正确账户 Lane 或账本读取范围内调用
     * @param keys 按用户和币对范围定位的定时撤单键集合，空键会跳过
     */
    public static List<CoreCancelAllAfterView> cancelAllAfter(
            TradingAccountRuntime runtime, Iterable<CoreCancelAllAfterKey> keys) {
        // 本次入口逐项组装的协议查询视图，达到其结果上限就停止。
        ArrayList<CoreCancelAllAfterView> result = new ArrayList<>();
        // 本次已检查的非空候选数量，按入口规则超限时停止或抛响应过大异常。
        int scanned = 0;
        for (CoreCancelAllAfterKey /* 本轮需要读取的用户及币对范围定时器键。 */ key : keys) {
            if (key == null) continue;
            if (++scanned > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            // 按该键找到的倒计时撤单状态，存在时转成协议视图。
            CoreCancelAllAfterState state = runtime.cancelAllAfterTimer(key);
            if (state != null) result.add(state.view());
            if (result.size() == MAX_QUERY_ENTITIES) break;
        }
        return List.copyOf(result);
    }

    /**
     * 查询扫描范围或物化规模超过允许上限时使用的内部异常，用来与记录不存在等正常空结果区分。
     */
    public static final class QueryTooLargeException extends IllegalStateException {
    }
}

/** 按查询时的持仓和行情即时计算风险及 ADL 候选，不把后台分批强平扫描的旧风险缓存直接当查询结果。生产查询由 Owner 固定市场输入，再由所属账户 Lane 读取账户；这里只构造查询结果，不改资金、风险缓存或扫描进度。 构造约定：只提供只读查询计算，不创建额外的账户状态持有者。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static final class RiskQueries {

    /** 候选顺序依次为优先分数从大到小、未实现盈利从大到小、用户编号从小到大、币对编号字符串从小到大。 */
    private static final Comparator<CoreAdlCandidateView> ADL_ORDER =
            Comparator.comparingLong(CoreAdlCandidateView::priorityScorePpm).reversed()
                    .thenComparing(Comparator.comparingLong(
                            CoreAdlCandidateView::unrealizedProfitUnits).reversed())
                    .thenComparingLong(CoreAdlCandidateView::userId)
                    .thenComparing(CoreAdlCandidateView::instrumentId);

    /** 比例放大一百万倍后的统一分母，避免用浮点数计算查询风险和 ADL 分数。 */
    private static final long PPM = 1_000_000L;

    /**
     * 生产账户 Lane 的单用户入口，要求正用户编号和 Owner 已固定的市场输入，再按当前持仓计算风险结果。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param identities 币对、资产和持仓业务编号与整数键的映射
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    public static List<CoreRiskSnapshotView> userSnapshots(TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities, long userId, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("risk query Lane inputs are required");
        ArrayList<CoreRiskSnapshotView> result = new ArrayList<>();
        if (runtime.positionCountForUser(userId) > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN)
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
        // 当前用户各非空持仓的临时估值列表，先收集再生成组合口径的风险行。
        var values = new ArrayList<PositionValuation>();
        // 按结算资产归组的本次查询临时累计值，不回写 Core 账户或扫描状态。
        // 仅在本次用户查询中使用的全仓资产汇总，下标 0 为权益变化、1 为维持保证金。
        var cross = new java.util.HashMap<String, long[]>();
        // 当前用户各结算资产的全仓共享钱包缓存，每种资产只计算一次逐仓扣减。
        var wallets = new java.util.HashMap<String, Long>();
        for (long /* 当前用户需要读取的一个持仓整数键。 */ key : runtime.positionKeysForUser(userId)) {
            // 本轮读取的实际持仓或临时估值所关联的持仓，空仓不会成为输出行。
            Position position = runtime.position(key);
            if (position == null || position.signedQuantitySteps() == 0) continue;
            if (values.size() >= CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
            // 本仓在查询准备时捕获的配置。
            CoreInstrument instrument = markets.instrument(position.symbolId());
            // 本仓在查询准备时捕获的行情引用。
            MarkPrice mark = markets.mark(position.symbolId());
            if (instrument == null || mark == null)
                throw new IllegalStateException("risk query source is missing");
            // 本仓带方向的数量步数，分别用于盈亏、维持保证金及市值计算。
            long quantity = position.signedQuantitySteps();
            // 当前持仓的未实现盈亏，查询行独立展示它，不能与期权权益市值混为一项。
            long pnl = CoreContractMath.pnlUnits(instrument, quantity, position.entryPriceTicks(), mark.markPriceTicks());
            // 当前单个持仓的维持保证金要求，先保存到临时估值，再按资产合并全仓金额。
            long maintenance = CoreContractMath.maintenanceMarginUnits(instrument, quantity,
                    mark.markPriceTicks(), mark.indexPriceTicks(), mark.forwardPriceTicks());
            // 本仓实际计入权益的估值变化，期货为浮盈亏，期权为带方向市值。
            long equityDelta = instrument.contractType().isOption()
                    ? OptionContractMath.optionMarketValueUnits(instrument, quantity, mark.markPriceTicks()) : pnl;
            // 本仓按标记价和合约精度算出的名义金额，供风险输出或 ADL 评分使用。
            long notional = CoreContractMath.notionalUnits(instrument, Math.absExact(quantity), mark.markPriceTicks());
            values.add(new PositionValuation(position, instrument, mark, pnl, maintenance, equityDelta, notional));
            wallets.computeIfAbsent(instrument.settleAsset(), /* 当前资产汇总的结算资产代码，或按该代码建立钱包与组合累计值的映射键。 */ asset -> crossWalletBalance(runtime, identities, userId, asset, markets));
            if (position.marginMode() == CoreMarginMode.CROSS) {
                // 同一结算资产的全仓累计值，下标 0 是权益变化合计，下标 1 是维持保证金合计。
                long[] totals = cross.computeIfAbsent(instrument.settleAsset(), /* 当前资产汇总的结算资产代码，或按该代码建立钱包与组合累计值的映射键。 */ asset -> new long[2]);
                totals[0] = Math.addExact(totals[0], equityDelta);
                totals[1] = Math.addExact(totals[1], maintenance);
            }
        }
        for (PositionValuation /* 当前用户已经算好的单持仓估值，结合共享钱包生成最终风险行。 */ value : values) {
            // 本轮读取的实际持仓或临时估值所关联的持仓，空仓不会成为输出行。
            Position position = value.position();
            // 当前资产汇总的结算资产代码，或按该代码建立钱包与组合累计值的映射键。
            String asset = value.instrument().settleAsset();
            // 当前结算资产扣掉逐仓持仓与委托占用后的全仓共享钱包。
            long wallet = wallets.get(asset);
            // 本行风险占比使用的维持保证金，全仓取该资产全仓合计，逐仓取单仓要求；输出行仍单独展示本仓维持保证金。
            // 本行风险占比采用的权益，全仓为共享钱包加组合估值变化，逐仓只看本仓保证金和市值变化。
            long equity, maintenance;
            if (position.marginMode() == CoreMarginMode.CROSS) {
                // 同一结算资产的全仓累计值，下标 0 是权益变化合计，下标 1 是维持保证金合计。
                long[] totals = cross.get(asset);
                equity = Math.addExact(wallet, totals[0]);
                maintenance = totals[1];
            } else {
                equity = Math.addExact(position.positionMarginUnits(), value.equityDelta());
                maintenance = value.maintenance();
            }
            // 维持保证金相对权益的 ppm 风险占比，不需维持时为 0，权益不正时用最大 long。
            long ratio = maintenance == 0 ? 0 : equity <= 0 ? Long.MAX_VALUE
                    : multiplyDivideCapped(maintenance, PPM, equity);
            result.add(new CoreRiskSnapshotView(userId, value.instrument().instrumentId(), position.marginMode(),
                    position.positionSide(), asset, position.signedQuantitySteps(), position.entryPriceTicks(),
                    value.mark().markPriceTicks(), value.notional(), position.positionMarginUnits(),
                    value.mark().priceSequence(), wallet, equity, value.pnl(), value.maintenance(), ratio,
                    RiskMarginCalculator.status(ratio).name()));
        }
        result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                .thenComparing(CoreRiskSnapshotView::instrumentId)
                .thenComparingInt(/* 排序时当前比较的查询结果行，最后按持仓腿的枚举顺序打破同用户同币对的顺序。 */ value -> value.positionSide().ordinal()));
        return List.copyOf(result);
    }

    /**
     * 生产 Lane 按 Owner 准备的整数持仓键读取目标用户仓位，核对路由用户一致，再用固定市场输入计算候选；缺失持仓返回 null。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param identities 币对、资产和持仓业务编号与整数键的映射
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param positionKey Owner 为该用户准备的持仓整数键，0 表示没有定位到持仓
     * @param asset 本次候选或钱包计算限定的结算资产代码，集合查询入口会规范化该代码
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    public static CoreAdlCandidateView adlCandidate(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            long userId, long positionKey, String asset, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("ADL query Lane inputs are required");
        // 本轮读取的实际持仓或临时估值所关联的持仓，空仓不会成为输出行。
        Position position = positionKey == 0 ? null : runtime.position(positionKey);
        if (position == null) return null;
        if (position.userId() != userId) throw new IllegalStateException("ADL query position route differs");
        // 本仓在查询准备时捕获的行情引用。
        MarkPrice mark = markets.mark(position.symbolId());
        // 本仓在查询准备时捕获的配置。
        CoreInstrument instrument = markets.instrument(position.symbolId());
        if (position.signedQuantitySteps() == 0 || !identities.asset(position.assetId()).equals(asset)
                || mark == null || instrument == null
                || !(instrument.contractType().isPerpetual() || instrument.contractType().isDelivery()
                || instrument.contractType().isOption()) || !instrument.settleAsset().equals(asset)) return null;
        // 目标持仓当前正的未实现盈利，非正值不能进入 ADL 候选。
        long profit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        if (profit <= 0) return null;
        // 本仓按标记价和合约精度算出的名义金额，供风险输出或 ADL 评分使用。
        long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        // ADL 杠杆评分使用的保证金基数，逐仓取本仓保证金，全仓取账户该资产总余额。
        long margin = position.marginMode() == CoreMarginMode.ISOLATED
                ? position.positionMarginUnits() : totalBalance(runtime, userId, asset, markets);
        // 盈利相对名义金额的 ppm 比率，参与 ADL 优先分数。
        long profitRate = ratio(profit, notional);
        // 名义金额相对保证金基数的 ppm 杠杆率，基数不正时用最大 long。
        long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
        // 盈利率乘杠杆率后按 ppm 缩放的 ADL 分数，中间乘法溢出时封顶。
        long priority = multiplyDivideCapped(profitRate, leverage, PPM);
        return new CoreAdlCandidateView(userId, instrument.instrumentId(), asset, position.marginMode(),
                position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                mark.markPriceTicks(), mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority);
    }

    /**
     * 按统一 ADL 比较规则二分插入候选，超过条数限制时删除最末项，调用方传入的列表应已按同一规则排序。
     * @param candidates 已经按 ADL_ORDER 排列的当前 Lane 候选列表
     * @param candidate 本次要按优先级插入的有效盈利候选
     * @param limit 最多返回或保留的候选条数，调用方应提供非负上限
     */
    public static void addAdlCandidate(ArrayList<CoreAdlCandidateView> candidates, CoreAdlCandidateView candidate, int limit) {
        // 候选按统一比较器二分查找的插入位置，未命中时从负返回值还原插入点。
        int insertAt = java.util.Collections.binarySearch(candidates, candidate, ADL_ORDER);
        candidates.add(insertAt < 0 ? -insertAt - 1 : insertAt, candidate);
        if (candidates.size() > limit) candidates.removeLast();
    }

    /**
     * 对各 Lane 已排序的有限候选列表做多路归并，每次选最优头项，达到 limit 或全部耗尽后结束；比较器完全相同时，先遍历到的 Lane 优先。
     * @param laneCandidates 各 Lane 已独立排序并限制数量的候选列表，外层下标就是 Lane 顺序
     * @param limit 最多返回或保留的候选条数，调用方应提供非负上限
     */
    public static List<CoreAdlCandidateView> mergeAdlCandidates(List<? extends List<CoreAdlCandidateView>> laneCandidates,
            int limit) {
        // 各 Lane 候选列表已经取走的数量，下次只比较该 Lane 的当前头项。
        int[] cursors = new int[laneCandidates.size()];
        // 已经归并出的最优候选列表，条数不能超过本次限制。
        ArrayList<CoreAdlCandidateView> result = new ArrayList<>(limit);
        while (result.size() < limit) {
            // 本轮最优头项所属 Lane，-1 表示所有列表均已耗尽。
            int bestLane = -1;
            // 本轮从各 Lane 头项中选出的最优候选，尚未找到时为空。
            CoreAdlCandidateView best = null;
            for (/* 本轮初始化或归并候选列表的账户 Lane 下标。 */ int laneId = 0; laneId < laneCandidates.size(); laneId++) {
                // 当前 Lane 已排序的候选列表，只有游标尚未到末尾才参与比较。
                List<CoreAdlCandidateView> lane = laneCandidates.get(laneId);
                if (cursors[laneId] >= lane.size()) continue;
                // 当前可用的 ADL 候选，缺失、空仓、资产不符或不盈利时为空。
                CoreAdlCandidateView candidate = lane.get(cursors[laneId]);
                if (best == null || ADL_ORDER.compare(candidate, best) < 0) {
                    best = candidate;
                    bestLane = laneId;
                }
            }
            if (bestLane < 0) break;
            result.add(best);
            cursors[bestLane]++;
        }
        return List.copyOf(result);
    }

    /**
     * 同一次查询共用的市场输入，由 Owner 捕获配置查询副本、行情引用和资产编号，派发后只读使用。这里不复制用户账户或持仓，查询结束后由调用方释放引用。
     */
    public static final class MarketInputs {
        /** Owner 捕获的本次查询配置映射，账户 Lane 只按整数币对键读取。 */
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments;
        /** Owner 捕获的本次查询行情引用映射，不随之后的行情替换而更换引用。 */
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice> marks;
        /** 本次查询结算资产代码到编号的只读映射，账户 Lane 不需要再扩展全局身份表。 */
        private final java.util.Map<String, Integer> assetIds;

        /**
         * 保存本次查询专用的配置和行情映射，并复制资产编号映射为不可修改 Map；这些输入由捕获方法构造后只读使用。
         * @param instruments 本次查询按币对整数编号保存的配置查询副本映射
         * @param marks 本次查询按币对整数编号保存的行情引用映射
         * @param assetIds 本次查询涉及的结算资产代码到整数编号映射
         */
        private MarketInputs(org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments,
                org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice> marks,
                java.util.Map<String, Integer> assetIds) {
            this.instruments = instruments;
            this.marks = marks;
            this.assetIds = java.util.Map.copyOf(assetIds);
        }

        /**
         * Owner 逐币对捕获当前配置的查询副本与行情引用，跳过未知或重复币对，并记录可用的结算资产编号，让后续账户查询不受 Owner 配置更新影响。
         * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
         * @param identities 币对、资产和持仓业务编号与整数键的映射
         * @param instrumentIds 本次查询需要固定市场输入的外部币对编号集合
         */
        public static MarketInputs capture(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                Iterable<String> instrumentIds) {
            // 本次新建的整数币对配置映射，逐个保存配置查询副本。
            var instruments = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument>();
            // 本次新建的整数币对行情映射，逐个保存捕获时行情引用。
            var marks = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice>();
            // 查询涉及的结算资产编号映射，未知资产不加入。
            var assets = new java.util.HashMap<String, Integer>();
            for (String /* 当前币对的外部编号，用来查配置或捕获市场输入。 */ instrumentId : instrumentIds) {
                // 当前外部币对编号已注册的整数身份，未知或重复身份直接跳过。
                Integer symbolId = identities.findSymbolId(instrumentId);
                if (symbolId == null || instruments.containsKey(symbolId)) continue;
                // Owner 当前持有的币对规范配置，下一步用 captureForQuery 生成本次查询副本。
                CoreInstrument instrument = runtime.instrument(instrumentId);
                instruments.put(symbolId, instrument == null ? null : instrument.captureForQuery());
                marks.put(symbolId, runtime.markPrice(symbolId));
                // 当前配置结算资产的整数身份，缺失时不访问账户余额。
                Integer assetId = instrument == null ? null : identities.findAssetId(instrument.settleAsset());
                if (assetId != null) assets.put(instrument.settleAsset(), assetId);
            }
            return new MarketInputs(instruments, marks, assets);
        }

        /**
         * 按币对整数编号读取这次查询捕获的配置，缺失时返回 null，不再转读后来的全局配置。
         * @param symbolId 本次查询中的币对整数编号
         */
        private CoreInstrument instrument(int symbolId) { return instruments.get(symbolId); }
        /**
         * 按币对整数编号读取这次查询捕获的行情引用，缺失时返回 null。
         * @param symbolId 本次查询中的币对整数编号
         */
        private MarkPrice mark(int symbolId) { return marks.get(symbolId); }
    }

    /**
     * 从用户该资产可用加冻结总余额中，扣掉逐仓持仓保证金和逐仓委托预占，得到全仓可共享的钱包；索引超限或逐仓占用超过总余额时直接报错。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param identities 币对、资产和持仓业务编号与整数键的映射
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param asset 本次候选或钱包计算限定的结算资产代码，集合查询入口会规范化该代码
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    private static long crossWalletBalance(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId, String asset, MarketInputs markets) {
        // 当前结算资产扣掉逐仓持仓与委托占用后的全仓共享钱包。
        long wallet = totalBalance(runtime, userId, asset, markets);
        if (runtime.reservationCountForUser(userId) > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN) {
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
        }
        for (long /* 候选路由对应的整数持仓键，未注册时为空，或在钱包扣减时表示当前持仓键。 */ positionKey : runtime.positionKeysForUser(userId)) {
            // 当前用户该资产的实际持仓，只扣除逐仓保证金，不把其他资产或全仓持仓再次扣出钱包。
            Position position = runtime.position(positionKey);
            if (position != null && position.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(position.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, position.positionMarginUnits());
            }
        }
        for (long /* 当前需要检查是否占用逐仓资金的冻结订单编号。 */ orderId : runtime.reservationIdsForUser(userId)) {
            // 当前订单的资金预占记录，只有属于该资产逐仓委托时才扣出全仓钱包。
            OrderFundsReservation reservation = runtime.reservation(orderId);
            // 当前预占关联的订单，用它的保证金模式区分逐仓和全仓。
            Order order = runtime.order(orderId);
            if (reservation != null && order != null && order.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(reservation.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, reservation.reservedUnits());
            }
        }
        if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
        return wallet;
    }

    /**
     * 按结算资产定位用户余额，缺少资产编号或余额记录时按 0，存在时返回可用与冻结金额之和并检查整数溢出。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param asset 本次候选或钱包计算限定的结算资产代码，集合查询入口会规范化该代码
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    private static long totalBalance(
            TradingAccountRuntime runtime, long userId, String asset, MarketInputs markets) {
        // 使用本次查询捕获的资产编号；缺少编号就不读取余额。
        Integer assetId = markets.assetIds.get(asset);
        // 用户在目标结算资产的余额，缺失时查询按零余额处理。
        AccountBalance balance = assetId == null ? null : runtime.balance(userId, assetId);
        return balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
    }

    /**
     * 把正的分子除以正的分母并放大一百万倍，任一不为正则返回 0，用于 ADL 盈利率和杠杆率。
     * @param numerator 需要换算成 ppm 比例的正分子，非正时比例为 0
     * @param denominator 比例换算的正分母，非正时比例为 0
     */
    private static long ratio(long numerator, long denominator) {
        return numerator <= 0 || denominator <= 0 ? 0 : multiplyDivideCapped(numerator, PPM, denominator);
    }

    /**
     * 先精确整数乘法再做整数除法，发生乘法溢出等算术异常时返回最大 long；按中间运算是否溢出来封顶，不重试大整数计算。
     * @param left 缩放乘法的第一个整数因子
     * @param right 缩放乘法的第二个整数因子
     * @param divisor 乘积缩放所用的正整数除数
     */
    private static long multiplyDivideCapped(long left, long right, long divisor) {
        try {
            return Math.multiplyExact(left, right) / divisor;
        } catch (/* 缩放乘法或除法发生算术异常，返回最大 long 作为饱和上限。 */ ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 单个持仓在本次查询中的临时估值，先算并保留本仓盈亏、维持保证金和市值变化，再在用户内按结算资产合并全仓风险。
     * @param position 当前账户 Lane 的目标持仓，按它的数量、模式与入场信息估值
     * @param instrument 该持仓本次估值采用的配置查询副本或当前有效配置
     * @param mark 该持仓本次估值采用的行情引用
     * @param pnl 本仓在标记价下的未实现盈亏，单位为结算资产最小单位
     * @param maintenance 本仓自身要求的维持保证金，合并全仓时还会按资产累计
     * @param equityDelta 本仓计入权益的变化，期货用未实现盈亏，期权用带方向市值
     * @param notional 本仓按标记价换算的名义金额，单位按产品的结算口径
     */
    private record PositionValuation(Position position, CoreInstrument instrument, MarkPrice mark,
                                     long pnl, long maintenance, long equityDelta, long notional) {
    }
}

/** 账户 Lane 内的用户及订单只读查询。先按用户范围检查物化规模，再把整数身份转换成协议视图，并按固定字段顺序计算状态比较哈希；缺失和响应过大使用独立结果标记。 构造约定：只提供账户视图组装和状态比较计算，不另存账户权威数据。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static final class StateQueries {

    /** 单用户完整查询允许的余额、冻结、持仓及杠杆实体数量合计上限 4096。 */
    public static final int MAX_USER_QUERY_ENTITIES = 4_096;

    /**
     * 在指定 Lane 定位用户，先核对实体数量上限，再整理有序明细；只构造请求的视图或计算请求的哈希。
     * @param runtime 当前查询运行时，账户实体通过显式 Lane 参数读取
     * @param lane 目标用户或订单所属的账户 Lane，调用方应在合法读取范围使用
     * @param identities 账户内资产、币对、客户端及持仓业务身份的映射
     * @param userId 本次用户或客户端订单查询所属的用户编号
     * @param hashOnly true 只计算状态哈希；false 只构造完整用户视图
     */
    public static UserQueryResult userState(
            TradingAccountRuntime runtime,
            AccountPartition lane,
            RuntimeIdentityRegistry identities,
            long userId, boolean hashOnly) {
        // 指定账户 Lane 内的用户记录，缺失时不再读取其他用户实体。
        UserAccount user = runtime.user(lane, userId);
        if (user == null) return UserQueryResult.notFound();

        // 该用户按资产编号组织的余额表，没有余额表时按零条统计。
        var balancesByAsset = runtime.balancesForUser(lane, userId);
        // 当前用户余额记录条数，加入完整视图物化预算。
        int balanceCount = balancesByAsset == null ? 0 : balancesByAsset.size();
        // 当前用户冻结预占记录条数，加入实体总量预算。
        int reservationCount = runtime.reservationCountForUser(lane, userId);
        // 当前用户持仓索引记录条数，先计数量再复制键和视图。
        int positionCount = runtime.positionCountForUser(lane, userId);
        // 该用户现有杠杆配置键，含合约和全仓或逐仓模式。
        var leverageKeys = runtime.leverageKeysForUser(lane, userId);
        // 余额、冻结、持仓及杠杆条数的精确合计，超过上限就返回过大标记。
        int entityCount = Math.addExact(balanceCount,
                Math.addExact(reservationCount, Math.addExact(positionCount, leverageKeys.size())));
        if (entityCount > MAX_USER_QUERY_ENTITIES) return UserQueryResult.oversized();
        // 该用户冻结记录的订单编号数组，按编号排序后读取对应视图。
        long[] reservationIds = runtime.reservationIdsForUser(lane, userId);
        // 该用户的整数持仓键数组，后续转为按业务键排序的持仓项。
        long[] positionKeys = runtime.positionKeysForUser(lane, userId);

        // 该用户资产代码及可用、冻结余额视图，按资产代码排序。
        ArrayList<CoreBalanceView> balances = new ArrayList<>(balanceCount);
        if (balancesByAsset != null) {
            balancesByAsset.forEachValue(/* 当前转换或计算哈希的一项资产余额，保留可用与冻结金额。 */ balance -> balances.add(new CoreBalanceView(
                    identities.asset(balance.assetId()), balance.availableUnits(), balance.lockedUnits())));
            balances.sort(Comparator.comparing(CoreBalanceView::asset));
        }

        Arrays.sort(reservationIds);
        // 按订单编号排列的资金预占视图列表，包含累计预占、释放和消费信息。
        ArrayList<CoreReservationView> reservations = new ArrayList<>(reservationIds.length);
        for (long /* 当前冻结或客户端订单映射对应的普通订单编号。 */ orderId : reservationIds) {
            // 当前资金冻结记录；生成视图时保留字段，算哈希时跳过已用完的记录。
            OrderFundsReservation reservation = runtime.reservation(lane, orderId);
            if (reservation == null) continue;
            reservations.add(new CoreReservationView(reservation.orderId(),
                    identities.instrumentId(reservation.symbolId()), reservation.kind(),
                    identities.asset(reservation.assetId()), reservation.totalReservedUnits(),
                    reservation.releasedUnits(), reservation.consumedUnits(), reservation.orderQuantitySteps()));
        }

        // 带业务排序键的临时持仓项，避免按内部整数分配顺序输出。
        ArrayList<PositionEntry> positionEntries = new ArrayList<>(positionKeys.length);
        for (long /* 当前需要在指定 Lane 读取的整数持仓键。 */ positionKey : positionKeys) {
            // 当前读取或计算哈希的持仓，保证金模式、持仓腿及金额字段都保留。
            Position position = runtime.position(lane, positionKey);
            if (position == null) continue;
            // 当前持仓整数币对编号对应的外部合约编号。
            String instrumentId = identities.instrumentId(position.symbolId());
            // 当前持仓转换成的协议视图，稍后与业务键一起排序。
            CorePositionView view = new CorePositionView(instrumentId, identities.asset(position.assetId()),
                    position.marginMode(), position.positionSide(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                    position.realizedPnlUnits(), position.positionMarginUnits());
            // 当前持仓的业务排序键，净仓和双向腿使用不同写法。
            String key = position.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? instrumentId : instrumentId + ':' + position.positionSide().name();
            positionEntries.add(new PositionEntry(key, view));
        }
        positionEntries.sort(Comparator.comparing(PositionEntry::key));
        // 按持仓业务键排序后提取的最终持仓协议视图列表。
        List<CorePositionView> positions = positionEntries.stream().map(PositionEntry::view).toList();

        // 该用户各合约和保证金模式的杠杆视图，沿原键集合顺序保存。
        ArrayList<CoreLeverageView> leverages = new ArrayList<>(leverageKeys.size());
        for (CoreLeverageKey /* 当前用户一项按合约及保证金模式区分的杠杆配置键。 */ key : leverageKeys) {
            leverages.add(new CoreLeverageView(key.instrumentId(), key.marginMode(), runtime.leverage(lane, key)));
        }
        if (hashOnly) return UserQueryResult.found(null,
                userStateHash(user, balances, reservations, positions, leverages));
        CoreUserStateView view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(),
                user.positionMode(), balances, reservations, positions, leverages);
        return UserQueryResult.found(view, 0);
    }

    /**
     * 在指定 Lane 读取订单，按请求构造视图或计算包括内部撮合价在内的状态哈希；缺失返回未找到。
     * @param runtime 当前查询运行时，账户实体通过显式 Lane 参数读取
     * @param lane 目标用户或订单所属的账户 Lane，调用方应在合法读取范围使用
     * @param identities 账户内资产、币对、客户端及持仓业务身份的映射
     * @param orderId 需要读取的 Core 订单编号
     * @param hashOnly true 只计算状态哈希；false 只构造订单视图
     */
    public static OrderQueryResult orderState(
            TradingAccountRuntime runtime,
            AccountPartition lane,
            RuntimeIdentityRegistry identities,
            long orderId, boolean hashOnly) {
        // 目标订单的当前运行时记录，缺失时直接返回未找到。
        Order order = runtime.order(lane, orderId);
        if (order == null) return OrderQueryResult.notFound();
        if (hashOnly) return OrderQueryResult.found(null,
                orderStateHash(order, identities.instrumentId(order.symbolId())));
        // 目标订单包含委托、成交、费用和提交信息的协议视图。
        CoreOrderStateView view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                identities.instrumentId(order.symbolId()), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(), order.clusterPosition(), order.status().name(), order.revision());
        return OrderQueryResult.found(view, 0);
    }

    /**
     * 先在该 Lane 定位用户的客户端订单身份，再查实际订单编号，任一身份或订单映射不存在时返回未找到，否则复用普通订单查询。
     * @param runtime 当前查询运行时，账户实体通过显式 Lane 参数读取
     * @param lane 目标用户或订单所属的账户 Lane，调用方应在合法读取范围使用
     * @param identities 账户内资产、币对、客户端及持仓业务身份的映射
     * @param userId 本次用户或客户端订单查询所属的用户编号
     * @param clientOrderId 该用户在客户端使用的订单标识，与普通 Core 订单编号分开定位
     */
    public static OrderQueryResult clientOrderState(
            TradingAccountRuntime runtime,
            AccountPartition lane,
            RuntimeIdentityRegistry identities,
            long userId,
            String clientOrderId) {
        // 当前 Lane 中该用户客户端订单标识对应的整数身份，0 表示没有注册。
        long clientKey = identities.findClientKeyValueInLane(lane, userId, clientOrderId);
        if (clientKey == 0) return OrderQueryResult.notFound();
        // 当前冻结或客户端订单映射对应的普通订单编号。
        long orderId = runtime.orderIdByClientValue(lane, userId, clientKey);
        return orderId == 0 ? OrderQueryResult.notFound() : orderState(runtime, lane, identities, orderId, false);
    }

    /**
     * 按用户身份、版本、模式及各视图列表的固定字段顺序混合状态哈希；已全部释放或消费的冻结记录不参与哈希，持仓和杠杆字段继续完整计入。
     * @param user 所属 Lane 当前用户元数据，各有序列表已经按查询顺序准备好
     */
    private static long userStateHash(UserAccount user, List<CoreBalanceView> balances,
            List<CoreReservationView> reservations, List<CorePositionView> positions, List<CoreLeverageView> leverages) {
        // 按固定字段顺序不断混合的业务状态哈希，返回前计入所有要求的版本字段。
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(user.productLine()));
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (CoreBalanceView /* 当前转换或计算哈希的一项资产余额，保留可用与冻结金额。 */ balance : balances) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (CoreReservationView /* 当前资金冻结记录；生成视图时保留字段，算哈希时跳过已用完的记录。 */ reservation : reservations) {
            if (reservation.reservedUnits() - reservation.releasedUnits() - reservation.consumedUnits() == 0) {
                continue;
            }
            hash = CoreStateHash.mix(hash, reservation.orderId());
            hash = CoreStateHash.mix(hash, reservation.instrumentId());
            hash = CoreStateHash.mix(hash, reservation.kind().wireCode());
            hash = CoreStateHash.mix(hash, reservation.asset());
            hash = CoreStateHash.mix(hash, reservation.reservedUnits());
            hash = CoreStateHash.mix(hash, reservation.releasedUnits());
            hash = CoreStateHash.mix(hash, reservation.consumedUnits());
            hash = CoreStateHash.mix(hash, reservation.orderQuantitySteps());
        }
        for (CorePositionView /* 当前读取或计算哈希的持仓，保证金模式、持仓腿及金额字段都保留。 */ position : positions) {
            hash = CoreStateHash.mix(hash, position.instrumentId());
            hash = CoreStateHash.mix(hash, position.marginAsset());
            hash = CoreStateHash.mix(hash, position.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, position.positionSide().wireCode());
            hash = CoreStateHash.mix(hash, position.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, position.entryPriceTicks());
            hash = CoreStateHash.mix(hash, position.entryValueTicks());
            hash = CoreStateHash.mix(hash, position.realizedPnlUnits());
            hash = CoreStateHash.mix(hash, position.positionMarginUnits());
        }
        for (CoreLeverageView /* 本轮加入用户状态哈希的一项杠杆配置，保留合约、保证金模式及 ppm 杠杆值。 */ leverage : leverages) {
            hash = CoreStateHash.mix(hash, leverage.instrumentId());
            hash = CoreStateHash.mix(hash, leverage.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, leverage.leveragePpm());
        }
        return hash;
    }

    /**
     * 按订单身份、委托、成交、费率、费用、时间及状态版本逐字段混合哈希；还包含对外订单视图未单独展示的内部撮合价格。
     * @param order 需要计算状态哈希的运行时订单，包含内部撮合价及完整版本信息
     * @param instrumentId 该订单币对整数身份对应的外部编号
     */
    private static long orderStateHash(Order order, String instrumentId) {
        // 按固定字段顺序不断混合的业务状态哈希，返回前计入所有要求的版本字段。
        long hash = CoreStateHash.mix(CoreStateHash.start(), order.orderId());
        hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(order.productLine()));
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, instrumentId);
        hash = CoreStateHash.mix(hash, order.side().wireCode());
        hash = CoreStateHash.mix(hash, order.priceTicks());
        hash = CoreStateHash.mix(hash, order.matchingPriceTicks());
        hash = CoreStateHash.mix(hash, order.quantitySteps());
        hash = CoreStateHash.mix(hash, order.executedQuantitySteps());
        hash = CoreStateHash.mix(hash, order.remainingQuantitySteps());
        hash = CoreStateHash.mix(hash, order.reduceOnly());
        hash = CoreStateHash.mix(hash, order.marginMode().wireCode());
        hash = CoreStateHash.mix(hash, order.positionSide().wireCode());
        hash = CoreStateHash.mix(hash, order.orderType().wireCode());
        hash = CoreStateHash.mix(hash, order.timeInForce().wireCode());
        hash = CoreStateHash.mix(hash, order.postOnly());
        hash = CoreStateHash.mix(hash, order.clientOrderId());
        hash = CoreStateHash.mix(hash, order.commandId().getMostSignificantBits());
        hash = CoreStateHash.mix(hash, order.commandId().getLeastSignificantBits());
        hash = CoreStateHash.mix(hash, order.makerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.takerFeeRatePpm());
        hash = CoreStateHash.mix(hash, order.cumulativeFeeUnits());
        hash = CoreStateHash.mix(hash, order.executedValueHigh());
        hash = CoreStateHash.mix(hash, order.executedValueLow());
        hash = CoreStateHash.mix(hash, order.createdAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.updatedAtEpochMillis());
        hash = CoreStateHash.mix(hash, order.clusterPosition());
        hash = CoreStateHash.mix(hash, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(order.status()));
        return CoreStateHash.mix(hash, order.revision());
    }

    /**
     * 组装用户视图时的持仓排序项，把实际持仓视图与净仓或双向腿业务键配对，排序后再提取视图。
     * @param key 净仓使用合约编号、双向仓再拼持仓腿的业务排序键
     * @param view 本结果已经组装的协议层只读视图，找到记录时有效
     */
    private record PositionEntry(String key, CorePositionView view) {
    }

    /**
     * 用户查询结果，分别表达找到、未找到或实体规模过大；只有找到时携带完整用户视图和状态哈希。
     * @param found 是否实际找到用户或订单，未找到时视图为空、哈希为 0
     * @param tooLarge 用户实体总数是否超过物化上限，与用户不存在分别表达
     * @param view 本结果已经组装的协议层只读视图，找到记录时有效
     * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
     */
    public record UserQueryResult(boolean found, boolean tooLarge, CoreUserStateView view, long stateHash) {
        /**
         * 封装找到的用户视图和哈希，明确关闭响应过大标记。
         * @param view 本结果已经组装的协议层只读视图，找到记录时有效
         * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
         */
        static UserQueryResult found(CoreUserStateView view, long stateHash) {
            return new UserQueryResult(true, false, view, stateHash);
        }

        /**
         * 封装用户不存在的结果，视图为空、哈希为 0，不能与实体过大混同。
         */
        static UserQueryResult notFound() {
            return new UserQueryResult(false, false, null, 0);
        }

        /**
         * 封装用户实体总量超过物化上限的结果，不返回部分视图。
         */
        static UserQueryResult oversized() {
            return new UserQueryResult(false, true, null, 0);
        }
    }

    /**
     * 订单查询结果，区分找到与不存在；找到时携带协议视图及按运行时订单字段计算的状态哈希。
     * @param found 是否实际找到用户或订单，未找到时视图为空、哈希为 0
     * @param view 本结果已经组装的协议层只读视图，找到记录时有效
     * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
     */
    public record OrderQueryResult(boolean found, CoreOrderStateView view, long stateHash) {
        /**
         * 封装已经找到的订单协议视图和对应运行时状态哈希。
         * @param view 本结果已经组装的协议层只读视图，找到记录时有效
         * @param stateHash 按该查询固定业务字段及顺序计算的状态比较哈希
         */
        static OrderQueryResult found(CoreOrderStateView view, long stateHash) {
            return new OrderQueryResult(true, view, stateHash);
        }

        /**
         * 封装订单不存在的结果，视图为空且哈希为 0。
         */
        static OrderQueryResult notFound() {
            return new OrderQueryResult(false, null, 0);
        }
    }
}
}
