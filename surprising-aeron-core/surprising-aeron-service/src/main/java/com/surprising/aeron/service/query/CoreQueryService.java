package com.surprising.aeron.service.query;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskStatus;
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
import com.surprising.aeron.service.account.AccountLaneCommand;
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

/** 核心内部查询的异步编排入口。Owner 解析条件并登记编号，账户 Lane、Matcher 或系统账本在各自所有权内读取。结果回到这里供 Owner 消费，不能为查询复制一份在线权威账户。参数 owner：所属交易运行时，提供查询投递、路由、身份和提交水位入口。 */
@RequiredArgsConstructor
public final class CoreQueryService {
    /** 提供业务路由、线程投递和提交水位的所属运行时，查询仍遵守实际状态所有权。 */
    final TradingCoreRuntime owner;

    /** 只有 Owner 访问关联表。工作线程只完成请求自己的 Future。 */
    private final Map<UUID, CompletableFuture<QueryResult>> pendingQueries = new java.util.HashMap<>();

    /** Owner 持有的有界盘口初始化会话，按插入顺序淘汰最早会话，各页共用固定快照。 */
    final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();

    /** 判断协议类型是否属于普通账户、订单、挂单或算法父单查询，用于选择账户 Lane 读取入口。 */
    public static boolean accountQuery(CoreMessageType type) {
        return type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY
                || type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                || type == CoreMessageType.CLIENT_ORDER_STATE_QUERY || type == CoreMessageType.USER_OPEN_ORDERS_QUERY
                || type == CoreMessageType.ALGO_ORDER_QUERY;
    }

    /** 识别风险快照与 ADL 候选两类查询，交给需要先经账本选路由的风险查询任务。 */
    public static boolean riskAccountQuery(CoreMessageType type) {
        return type == CoreMessageType.RISK_STATE_QUERY || type == CoreMessageType.ADL_CANDIDATE_QUERY;
    }

    /** 从 Owner 的待完成划转路由取一页，空页直接返回。 */
    public CoreResponse beginPendingTransferQuery(CoreMessage message, int limit) {
        var page = owner.runtimeState().pendingTransferRoutes(limit);
        if (page.ids().length == 0) {
            owner.runtimeState().completePendingTransferPage(page);
            return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encode(List.of()));
        }
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
        } catch (RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        owner.runtimeState().completePendingTransferPage(page);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 解码下单意图并检查用户和订单身份，固定币对决策输入。 */
    public CoreResponse beginPreflightQuery(CoreMessage message, long timestamp) {
        var command = TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
        long userId = message.header().userId();
        if (userId <= 0) throw new IllegalArgumentException("preflight user is required");
        owner.requireOrderIdentityAvailable(userId, command);
        if (owner.orderIdInUse(command.orderId())) throw new com.surprising.aeron.exception.CoreStateRejectedException(
                "DUPLICATE_ORDER_ID", "orderId already exists");
        var context = com.surprising.aeron.service.order.OrderAdmissionService.queryContext(
                owner.runtimeState(), owner.identities(), command.instrumentId(), timestamp);
        var query = new PreflightQuery(owner.appliedCommandCount(), userId, command, context);
        registerQuery(message, query);
        try {
            if (!owner.runtimeState().dispatchAccountQuery(owner.runtimeState().topology().accountLaneMask(userId), query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 一次下单预检的查询任务：由用户所属 Lane 检查订单和所需预留，只返回估算结果，不创建真实订单或冻结。 */
    private final class PreflightQuery extends CompletableFuture<QueryResult> implements AccountLaneCommand,
            java.util.function.Function<AccountPartition, CoreResponse> {
        /** userId：必须存在的预检用户编号，只在其所属 Lane 读取账户。 */
        private final long commandCount, userId;
        private final long openInterest;
        private final com.surprising.aeron.protocol.PlaceOrderCommand command;
        /** Owner 固定的预检币对、标记价、费率及结算状态输入。 */
        private final com.surprising.aeron.service.order.OrderAdmissionService.Context context;

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

        /** 进入所属账户的合法只读范围执行预检，把业务拒绝、算术溢出或非法参数转换为查询响应，然后保存结果并通知 Owner。 */
        @Override public void execute(AccountPartition lane) {
            CoreResponse response;
            try { response = owner.runtimeState().readAccountLane(lane, this); }
            catch (com.surprising.aeron.exception.CoreStateRejectedException failure) {
                response = rejected(CoreResultCode.fromRejectionCode(failure.code()));
            } catch (ArithmeticException failure) { response = rejected(CoreResultCode.ARITHMETIC_OVERFLOW); }
            catch (IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
            complete(QueryResult.encoded(response));
        }

        /** 按固定输入解析订单规则，在当前 Lane 取得客户及持仓身份，计算所需预留并编码资产和金额。 */
        @Override public CoreResponse apply(AccountPartition lane) {
            var order = com.surprising.aeron.service.order.OrderAdmissionService.resolve(context, userId, command);
            long reserved = com.surprising.aeron.service.order.OrderAdmissionService.requiredReservationForQuery(
                    owner.runtimeState(), lane, userId, order, openInterest, context.symbolId(),
                    owner.identities().findClientKeyValue(userId, command.clientOrderId()),
                    owner.identities().findPositionKeyValueInLane(lane, userId, context.instrument(), command.positionSide()),
                    context.settlementStatus());
            return new CoreResponse(ResponseStatus.OK, commandCount, com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(
                    new com.surprising.aeron.protocol.CoreOrderPreflightView(order.reservationAsset(), reserved)));
        }

        private CoreResponse rejected(CoreResultCode code) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
        }
    }

    /** 创建风险查询对象，登记外部命令与请求完成状态，并投递到相应 Lane。 */
    public CoreResponse beginRiskAccountQuery(CoreMessage message) {
        var query = RiskAccountQuery.prepare(owner, message);
        registerQuery(message, query);
        query.dispatchAccounts();
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 按查询类型解析用户、订单或客户端条件。 */
    public CoreResponse beginAccountQuery(CoreMessage message) {
        var type = message.header().messageType();
        if (type == CoreMessageType.ALGO_ORDER_QUERY) return beginAlgoAccountQuery(message);
        long userId = message.header().userId();
        long orderId = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                ? TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()) : 0;
        String clientName = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                ? CoreStateQueryCodec.decodeClientOrderStateQuery(message.payloadUnsafe()) : null;
        if (type == CoreMessageType.CLIENT_ORDER_STATE_QUERY && userId <= 0)
            throw new IllegalArgumentException("userId must be positive");
        com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds = null;
        if (type == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            var pageQuery = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payloadUnsafe());
            long before = pageQuery.beforeOrderId() == 0 ? Long.MAX_VALUE : pageQuery.beforeOrderId();
            var page = owner.activeOrderIndex().page(userId, pageQuery.instrumentId(), before, pageQuery.limit());
            if (page.orderIds().isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                    CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(List.of())));
            openOrderIds = page.orderIds();
        }
        boolean orderQuery = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY;
        if (!orderQuery && openOrderIds == null && userId <= 0) return missingAccountResponse(type, owner.appliedCommandCount());
        long laneMask;
        if (openOrderIds != null) {
            laneMask = 0;
            for (int index = 0; index < openOrderIds.size(); index++) {
                long selected = openOrderIds.valueAt(index);
                var route = owner.activeOrderIndex().activeOrderRoute(selected);
                if (route == null) throw new IllegalStateException("active order query route is missing");
                laneMask |= owner.runtimeState().topology().accountLaneMask(route.userId());
            }
        } else if (orderQuery) {
            var route = owner.activeOrderIndex().activeOrderRoute(orderId);
            int count = owner.runtimeState().topology().accountLaneCount();
            laneMask = route != null ? owner.runtimeState().topology().accountLaneMask(route.userId())
                    : count == Long.SIZE ? -1L : (1L << count) - 1;
        } else laneMask = owner.runtimeState().topology().accountLaneMask(userId);
        var query = new AccountQuery(owner.appliedCommandCount(), type, userId, orderId, clientName, laneMask, openOrderIds, null);
        return dispatchAccountQuery(message, laneMask, query);
    }

    /** 按过期时间、状态、用户或币对选择触发索引来源，再施加精确编号、游标及活动状态筛选。 */
    public CoreResponse beginTriggerQuery(CoreMessage message) {
        var requested = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payloadUnsafe());
        int requestedInstrument = requested.instrumentId().isEmpty() ? 0
                : com.surprising.product.api.utils.InstrumentIds.parse(requested.instrumentId());
        long before = requested.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : requested.beforeTriggerOrderId();
        Iterable<Long> source = requested.expiresBeforeEpochMillis() > 0
                ? owner.triggerOrderIndex().expired(requested.expiresBeforeEpochMillis(), requested.limit())
                : requested.instrumentId().isEmpty()
                ? requested.status() != null ? owner.triggerOrderIndex().ids(requested.status())
                : message.header().userId() == 0 ? requested.beforeTriggerOrderId() == 0
                        ? owner.triggerOrderIndex().ids() : owner.triggerOrderIndex().idsBefore(before)
                        : owner.triggerOrderIndex().ids(message.header().userId())
                : requested.status() == null ? owner.triggerOrderIndex().ids(requested.instrumentId())
                : owner.triggerOrderIndex().ids(requested.instrumentId(), requested.status());
        var selected = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        var users = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        long laneMask = 0;
        int scanned = 0;
        for (long id : source) {
            if (++scanned > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN)
                throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
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
        var query = new AccountQuery(owner.appliedCommandCount(), message.header().messageType(),
                0, 0, null, laneMask, null, null);
        query.triggerPage = new TriggerQueryPage(selected.toArray(), users.toArray(),
                new com.surprising.aeron.protocol.CoreTriggerOrderStateView[selected.size()]);
        return dispatchAccountQuery(message, laneMask, query);
    }

    /** 按算法索引选父单，或精确选择仍保留的父单编号，固定用户路由及原顺序，再交给所属 Lane 汇总子单进度。 */
    private CoreResponse beginAlgoAccountQuery(CoreMessage message) {
        var requested = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payloadUnsafe());
        var ids = requested.algoOrderId() == 0
                ? owner.algoOrderIndex().query(requested.userId(), requested.instrumentId(), requested.dueAtEpochMillis(),
                        requested.limit())
                : owner.algoOrderIndex().value(requested.algoOrderId()) == null ? List.<Long>of() : List.of(requested.algoOrderId());
        if (ids.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(List.of()));
        long[] selectedIds = new long[ids.size()], users = new long[ids.size()];
        long laneMask = 0;
        for (int index = 0; index < ids.size(); index++) {
            long selected = ids instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList primitive
                    ? primitive.valueAt(index) : ids.get(index);
            var route = owner.algoOrderIndex().value(selected);
            if (route == null) throw new IllegalStateException("algo query route is missing");
            selectedIds[index] = selected; users[index] = route.userId();
            laneMask |= owner.runtimeState().topology().accountLaneMask(route.userId());
        }
        var page = new AlgoQueryPage(selectedIds, users,
                new com.surprising.aeron.protocol.CoreAlgoOrderView[selectedIds.length]);
        var query = new AccountQuery(owner.appliedCommandCount(), CoreMessageType.ALGO_ORDER_QUERY,
                0, 0, null, laneMask, null, page);
        return dispatchAccountQuery(message, laneMask, query);
    }

    private CoreResponse dispatchAccountQuery(CoreMessage message, long laneMask, AccountQuery query) {
        registerQuery(message, query);
        try {
            if (!owner.runtimeState().dispatchAccountQuery(laneMask, query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 本次触发单页的固定编号、用户路由和响应槽。各 Lane 只填自己的下标，全部完成后按原选择顺序编码。 */
    private record TriggerQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreTriggerOrderStateView[] views) { }

    /** 本次算法父单页的固定路由与响应槽，各 Lane 汇总自己的父单及保留子单，最后合并时再检查全局子单扫描量。 */
    private record AlgoQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreAlgoOrderView[] views) { }

    /** 本次待完成划转页的固定编号、用户路由和 DTO 槽，用于各 Lane 原位读取，不保存第二份划转状态。 */
    private record PendingTransferQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.TransferFundsCommand[] views) { }

    private static CoreResponse missingAccountResponse(CoreMessageType type, long commandCount) {
        if (type == CoreMessageType.USER_STATE_HASH_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY)
            return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.ENTITY_NOT_FOUND, commandCount);
    }

    /** 一笔普通账户或业务列表查询的跨 Lane 任务，保存本次响应槽和原子完成位。 */
    private final class AccountQuery extends CompletableFuture<QueryResult> implements AccountLaneCommand,
            java.util.function.Function<AccountPartition, CoreResponse> {
        private final long commandCount, userId, orderId, laneMask;
        private final String clientName;
        private final CoreMessageType type;
        private final CoreResponse[] responses;
        /** 各 Lane 是否命中单实体的标记，合并订单查询时检查只能有一个所有者命中。 */
        private final boolean[] found;
        /** Owner 按挂单索引选出的固定顺序编号数组，非挂单页为 null。 */
        private final com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds;
        private final com.surprising.aeron.protocol.CoreOrderStateView[] openOrders;
        private final AlgoQueryPage algoPage;
        /** 触发单页的固定路由与结果槽，投递前由 Owner 设置。 */
        private TriggerQueryPage triggerPage;
        /** 待完成划转页的固定路由与结果槽，投递前由 Owner 设置。 */
        private PendingTransferQueryPage transferPage;
        private final AtomicLong completedLaneMask = new AtomicLong();

        private AccountQuery(long commandCount, CoreMessageType type, long userId,
                             long orderId, String clientName, long laneMask, com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds, AlgoQueryPage algoPage) {
            this.commandCount = commandCount; this.type = type;
            this.userId = userId; this.orderId = orderId; this.clientName = clientName; this.laneMask = laneMask;
            int count = owner.runtimeState().topology().accountLaneCount();
            responses = new CoreResponse[count]; found = new boolean[count];
            this.openOrderIds = openOrderIds;
            this.algoPage = algoPage;
            openOrders = openOrderIds == null ? null : new com.surprising.aeron.protocol.CoreOrderStateView[openOrderIds.size()];
        }

        /** 在目标 Lane 建立只读范围并记录本 Lane 响应。原子累计完成位并拒绝重复完成，最后一个 Lane 归并和保存结果，通知 Owner。 */
        @Override public void execute(AccountPartition lane) {
            CoreResponse response;
            try { response = owner.runtimeState().readAccountLane(lane, this); }
            catch (CoreQueryService.OperationalQueries.QueryTooLargeException tooLarge) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            }
            catch (IllegalArgumentException invalid) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            responses[lane.laneId()] = response;
            long bit = 1L << lane.laneId();
            long previous, complete;
            do {
                previous = completedLaneMask.get(); complete = previous | bit;
                if ((previous & bit) != 0) throw new IllegalStateException("account query Lane completed twice");
            } while (!completedLaneMask.compareAndSet(previous, complete));
            if (complete != laneMask) return;
            CoreResponse completed;
            try { completed = completedResponse(); }
            catch (IllegalArgumentException invalid) {
                completed = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            complete(QueryResult.encoded(completed));
        }

        /** 在当前 Lane 原位读取所选划转、触发、算法、挂单或单实体详情。 */
        @Override public CoreResponse apply(AccountPartition lane) {
            if (transferPage != null) {
                for (int index = 0; index < transferPage.ids.length; index++) {
                    if (owner.runtimeState().topology().accountLaneId(transferPage.users[index]) != lane.laneId()) continue;
                    var value = owner.runtimeState().pendingTransfer(transferPage.ids[index]);
                    if (value == null || value.sourceUserId() != transferPage.users[index])
                        throw new IllegalArgumentException("pending transfer routing differs from Lane identity");
                    transferPage.views[index] = value;
                }
                return null;
            }
            if (triggerPage != null) {
                for (int index = 0; index < triggerPage.ids.length; index++) {
                    if (owner.runtimeState().topology().accountLaneId(triggerPage.users[index]) != lane.laneId()) continue;
                    var value = owner.runtimeState().triggerOrder(triggerPage.ids[index]);
                    if (value != null) triggerPage.views[index] = value.view();
                }
                return null;
            }
            if (algoPage != null) {
                for (int index = 0; index < algoPage.ids.length; index++) {
                    if (owner.runtimeState().topology().accountLaneId(algoPage.users[index]) != lane.laneId()) continue;
                    algoPage.views[index] = CoreQueryService.OperationalQueries.algoOrder(
                            owner.runtimeState(), algoPage.ids[index]);
                }
                return null;
            }
            if (openOrderIds != null) {
                for (int index = 0; index < openOrderIds.size(); index++) {
                    var query = CoreQueryService.StateQueries.orderState(owner.runtimeState(), lane, owner.identities(), openOrderIds.valueAt(index), false);
                    if (query.found() && "OPEN".equals(query.view().status())) openOrders[index] = query.view();
                }
                return null;
            }
            if (type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY) {
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
            var query = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                    ? CoreQueryService.StateQueries.clientOrderState(owner.runtimeState(), lane, owner.identities(), userId, clientName)
                    : orderId == 0 ? null : CoreQueryService.StateQueries.orderState(owner.runtimeState(), lane, owner.identities(), orderId,
                            type == CoreMessageType.ORDER_STATE_HASH_QUERY);
            found[lane.laneId()] = query != null && query.found();
            if (!found[lane.laneId()]) return missingAccountResponse(type, commandCount);
            byte[] encoded = type == CoreMessageType.ORDER_STATE_HASH_QUERY
                    ? CoreStateQueryCodec.encodeStateHash(query.stateHash()) : CoreStateQueryCodec.encodeOrderState(query.view());
            return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
        }

        /** 先检查所选 Lane 的参数或超限失败，再按原索引顺序合并列表。算法父单额外核对全局子单条数，单订单必须只有一个 Lane 命中。 */
        private CoreResponse completedResponse() {
            long pending = laneMask;
            while (pending != 0) {
                int laneId = Long.numberOfTrailingZeros(pending); pending &= pending - 1;
                var response = responses[laneId];
                if (response != null && (response.resultCode() == CoreResultCode.INVALID_COMMAND
                        || response.resultCode() == CoreResultCode.QUERY_RESPONSE_TOO_LARGE)) return response;
            }
            if (transferPage != null) return new CoreResponse(ResponseStatus.OK, commandCount,
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encode(java.util.Arrays.asList(transferPage.views)));
            if (triggerPage != null) {
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreTriggerOrderStateView>(triggerPage.views.length);
                for (var view : triggerPage.views) if (view != null) selected.add(view);
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(selected));
            }
            if (algoPage != null) {
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreAlgoOrderView>(algoPage.views.length);
                int children = 0;
                for (var view : algoPage.views) if (view != null) {
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
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreOrderStateView>(openOrders.length);
                for (var order : openOrders) if (order != null) selected.add(order);
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(selected)));
            }
            CoreResponse selected = null;
            long lanes = laneMask;
            while (lanes != 0) {
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

    /** 选择强平工作候选，保险页保留游标外计划以计算全局分配。 */
    public CoreResponse beginLiquidationWorkQuery(CoreMessage message, CoreLiquidationWorkView.Query requested) {
        if (deferredLiquidationWorkQuery != null) return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        var candidates = requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                ? owner.liquidationIndex().activeIds()
                : owner.liquidationIndex().activeIds().tailSet(requested.afterLiquidationId(), false);
        if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                && candidates.size() > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN)
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
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

    /** 一次强平执行、保险或 ADL 工作页的交接任务。Owner 固定候选和市场输入，Lane 读取计划事实，等待所有 Lane 后计算保险建议并编码有界页，不实际执行强平或赔付。 */
    private final class LiquidationWorkQuery extends CompletableFuture<QueryResult> implements AccountLaneCommand {
        private final long commandCount, laneMask;
        private final CoreLiquidationWorkView.Query requested;
        /** 按强平编号顺序选择的本次扫描候选，保险分支包括最终返回页以外的相关计划。 */
        private final long[] ids;
        private final int[] lanes, assetIds;
        /** Owner 为各候选固定的规范币对对象，Lane 读取计划时核对身份。 */
        private final com.surprising.aeron.service.instrument.CoreInstrument[] instruments;
        private final CoreLiquidationWorkView.Resolution[] receipts;
        private final com.surprising.aeron.protocol.CoreLiquidationActionView[] actions;
        /** Owner 为各候选固定的标记价输入，执行页用它核对触发价格序号。 */
        private final com.surprising.aeron.service.market.MarkPrice[] marks;
        /** Owner 固定的风险续扫信息，与本次工作页一起返回。 */
        private final com.surprising.aeron.protocol.CoreRiskScanContinuation continuation;
        private final boolean sourceComplete;
        /** 各账户 Lane 的失败码，账本先检查失败再编码工作页，避免返回部分成功。 */
        private final CoreResultCode[] failures;
        private final InsuranceAllocationPolicy.AllocationInputs[] claims;
        private final java.util.concurrent.atomic.AtomicLong completed = new java.util.concurrent.atomic.AtomicLong();
        /** 按扫描上限固定候选编号、所属 Lane、币对、结算资产和标记价，并记录是否已选完来源及风险续扫信息。 */
        LiquidationWorkQuery(long commandCount, CoreLiquidationWorkView.Query requested, java.util.NavigableSet<Long> candidates) {
            this.commandCount = commandCount; this.requested = requested;
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
            long mask = 0;
            int index = 0;
            for (long liquidationId : candidates) {
                if (index == count) break;
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
        /** 当前 Lane 读取其候选计划，按工作类型和固定市场输入筛选执行动作或缺口回执。保险页还准备本 Lane 的全部相关请求，记录错误后发布完成位并通知 Owner。 */
        @Override public void execute(AccountPartition lane) {
            try { owner.runtimeState().readAccountLane(lane, owned -> {
                for (int index = 0; index < ids.length; index++) {
                    if (lanes[index] != owned.laneId()) continue;
                    var value = owned.liquidation(ids[index]);
                    if (value == null) continue;
                    var purpose = requested.purpose();
                    if (purpose == CoreLiquidationWorkView.Purpose.EXECUTION) {
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
                    var required = purpose == CoreLiquidationWorkView.Purpose.INSURANCE
                            ? com.surprising.aeron.model.CoreLiquidationState.Status.INSURANCE_REQUIRED
                            : com.surprising.aeron.model.CoreLiquidationState.Status.ADL_REQUIRED;
                    if (value.status() != required) continue;
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
            catch (CoreQueryService.OperationalQueries.QueryTooLargeException tooLarge) {
                failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
            }
            completed.getAndUpdate(mask -> mask | (1L << lane.laneId()));
        }
        /** 只有所有目标 Lane 的完成位齐全，才可读取回执。 */
        public boolean ready() { return completed.get() == laneMask; }
        /** 等待 Lane 已完成后，先返回失败或组装有界工作页。保险页依据全部请求和原基金余额计算建议金额，编码结果并通知 Owner，不执行资金支付。 */
        public void apply(TreasuryRuntime ledger) {
            CoreResponse response;
            try {
                for (CoreResultCode failure : failures) {
                    if (failure == null) continue;
                    complete(QueryResult.encoded(new CoreResponse(
                            ResponseStatus.REJECTED, ResponseStatus.REJECTED, failure, commandCount)));
                    return;
                }
                var result = CoreQueryService.LiquidationQueries.workPage(owner.productLine(), requested, ids, actions,
                        receipts, continuation, sourceComplete);
                if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE) {
                    var allocations = InsuranceAllocationPolicy.allocations(ledger, claims);
                    result = CoreQueryService.LiquidationQueries.insuranceRecommendations(result, allocations);
                }
                response = new CoreResponse(ResponseStatus.OK, commandCount, CoreLiquidationWorkCodec.encodeWork(result));
            } catch (CoreQueryService.OperationalQueries.QueryTooLargeException tooLarge) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            } catch (IllegalArgumentException | ArithmeticException invalid) {
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
        CoreResponse response;
        try {
            byte[] encoded;
            if (type == CoreMessageType.OPEN_INTEREST_QUERY) {
                var totals = owner.openInterestIndex().totals();
                if (totals.size() > CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                    throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
                var views = new ArrayList<com.surprising.aeron.protocol.CoreOpenInterestView>(totals.size());
                for (var entry : totals.entrySet()) {
                    views.add(new com.surprising.aeron.protocol.CoreOpenInterestView(
                            entry.getKey(), entry.getValue().longQuantity(), entry.getValue().shortQuantity()));
                }
                encoded = com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views);
            } else if (type == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
                var users = new ArrayList<Long>(page.limit());
                boolean more = false;
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
        } catch (CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
        } catch (IllegalArgumentException failure) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, commandCount);
        }
        return response;
    }

    /** 解码单币对盘口查询，在对应 Matcher 提交栅栏之后读取层级。异步完成或失败分别登记，Owner 先返回待完成。 */
    public CoreResponse beginBookQuery(CoreMessage message) {
        if (message.payloadLength() == 0) {
            throw new IllegalArgumentException("single-instrumentId book query payload is required");
        }
        var query = CoreStateQueryCodec.decodeOrderBookQuery(message.payloadUnsafe());
        var result = new CompletableFuture<QueryResult>();
        registerQuery(message, result);
        try {
            owner.matcherPipeline().readAtSubmissionFence(owner.matchingAdapter().matcherShardId(query.instrumentId()),
                    () -> owner.matchingAdapter().orderBookLevels(query.instrumentId(), query.depth()))
                    .whenComplete((levels, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else result.complete(QueryResult.single(levels));
                    });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING,
                owner.appliedCommandCount());
    }

    /** 已有快照编号时校验会话和深度并续页。新请求异步读取一个或多个 Matcher 的初始化快照，合并后交 Owner 建立有界分页会话。 */
    public CoreResponse beginBookBootstrapQuery(CoreMessage message) {
        CoreOrderBookBootstrapQuery query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(message.payloadUnsafe());
        if (!query.snapshotId().isEmpty()) {
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
            var snapshot = owner.matchingAdapter().topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline().readAtSubmissionFence(0,
                    () -> owner.matchingAdapter().orderBookBootstrapShard(0, query.depth()))
                    : owner.matcherPipeline().readEachAsync(
 shardId -> owner.matchingAdapter().orderBookBootstrapShard(shardId, query.depth()))
                    .thenApply(owner.matchingAdapter()::mergeBookBootstrapShards);
            snapshot.whenComplete((value, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(QueryResult.bootstrap(
                        snapshotId, query, value));
            });
        } catch (RuntimeException failure) {
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
        long exportSequence = owner.commits.publication.publishedSequence();
        if (completed.bootstrapSnapshot() == null) {
            var view = new com.surprising.aeron.protocol.CoreOrderBookView(exportSequence, completed.levels());
            byte[] encoded = CoreStateQueryCodec.encodeOrderBookView(view);
            return boundedBookResponse(completed.levels().size(), encoded);
        }
        BookBootstrapSession session = BookBootstrapSession.create(completed.snapshotId(), exportSequence,
                completed.bootstrapQuery().depth(), completed.bootstrapSnapshot());
        while (bookBootstrapSessions.size() >= TradingCoreRuntime.Limits.MAX_BOOK_BOOTSTRAP_SNAPSHOTS) {
            bookBootstrapSessions.remove(bookBootstrapSessions.keySet().iterator().next());
        }
        bookBootstrapSessions.put(session.snapshotId(), session);
        return bootstrapPageResponse(session, completed.bootstrapQuery());
    }

    /** 校验币对游标属于固定会话，按币对文本顺序选择其后最多 limit 个币对并汇总层级，计算续页游标，编码后检查条数与字节上限。 */
    CoreResponse bootstrapPageResponse(BookBootstrapSession session, CoreOrderBookBootstrapQuery query) {
        if (!query.symbolCursor().isEmpty() && !session.symbols().containsKey(query.symbolCursor())) {
            return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
        }
        var tailSymbols = session.symbols().tailMap(query.symbolCursor(), false).keySet();
        List<String> symbols = new ArrayList<>(Math.min(query.limit(), tailSymbols.size()));
        for (String symbol : tailSymbols) {
            symbols.add(symbol);
            if (symbols.size() >= query.limit()) break;
        }
        int expectedLevels = 0;
        for (String instrumentId : symbols) {
            expectedLevels = Math.addExact(expectedLevels, session.symbols().get(instrumentId).size());
        }
        List<com.surprising.aeron.protocol.CoreBookLevelView> levels = new ArrayList<>(expectedLevels);
        for (String instrumentId : symbols) levels.addAll(session.symbols().get(instrumentId));
        boolean complete = symbols.isEmpty()
                || session.symbols().higherKey(symbols.getLast()) == null;
        String nextCursor = complete ? "" : symbols.getLast();
        CoreOrderBookBootstrapPage page = new CoreOrderBookBootstrapPage(session.snapshotId(),
                session.exportSequence(), nextCursor, complete, levels);
        byte[] encoded = CoreStateQueryCodec.encodeOrderBookBootstrapPage(page);
        return boundedBookResponse(levels.size(), encoded);
    }

    /** 统一检查盘口层级数量和编码字节上限，超限返回专用错误，否则按当前已应用命令计数构造成功响应。 */
    CoreResponse boundedBookResponse(int levelCount, byte[] encoded) {
        if (levelCount > TradingCoreRuntime.Limits.MAX_BOOK_RESPONSE_LEVELS || encoded.length > TradingCoreRuntime.Limits.MAX_BOOK_RESPONSE_BYTES) {
            return owner.rejected(CoreResultCode.BOOK_QUERY_RESPONSE_TOO_LARGE);
        }
        return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(), encoded);
    }

    /** 已完成查询的交接结果：可直接携带已编码响应，也可携带单币对盘口层级或多币对初始化快照，Owner 消费时选择对应协议。 */
    record QueryResult(
            List<com.surprising.aeron.protocol.CoreBookLevelView> levels,
            String snapshotId,
            CoreOrderBookBootstrapQuery bootstrapQuery,
            OrderBookDepthSnapshot bootstrapSnapshot,
            CoreResponse encodedResponse) {

        /** 保存尚待 Owner 包装的盘口查询结果，已编码响应分支设为 null。 */
        QueryResult(List<com.surprising.aeron.protocol.CoreBookLevelView> levels, String snapshotId,
                           CoreOrderBookBootstrapQuery bootstrapQuery, OrderBookDepthSnapshot bootstrapSnapshot) {
            this(levels, snapshotId, bootstrapQuery, bootstrapSnapshot, null);
        }

        /** 把所属读取线程已经编码的响应包装为可一次消费的完成结果，无须 Owner 再读实体。 */
        static QueryResult encoded(CoreResponse response) { return new QueryResult(List.of(), "", null, null, response); }

        /** 复制单币对盘口层级列表作为不可修改的查询交接内容，Owner 稍后补导出水位并编码。 */
        static QueryResult single(
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels) {
            return new QueryResult(List.copyOf(levels), "", null, null);
        }

        /** 保留多币对初始化查询条件及 Matcher 快照，Owner 消费后创建分页会话。 */
        static QueryResult bootstrap(
                String snapshotId,
                CoreOrderBookBootstrapQuery query,
                OrderBookDepthSnapshot snapshot) {
            return new QueryResult(List.of(), snapshotId, query, snapshot);
        }
    }

    /** 多币对盘口初始化的分页会话，固定快照编号、导出水位、深度和按币对文本排序的只读层级映射，后续页不能混入另一份快照。 */
    record BookBootstrapSession(
            String snapshotId,
            long exportSequence,
            int depth,
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> symbols) {

        /** 按币对文本建立有序分组，把层级归入已有币对并拒绝未知引用。 */
        static BookBootstrapSession create(
                String snapshotId,
                long exportSequence,
                int depth,
                OrderBookDepthSnapshot snapshot) {
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> grouped = new TreeMap<>();
            for (String instrumentId : snapshot.symbols()) grouped.put(instrumentId, new ArrayList<>());
            for (com.surprising.aeron.protocol.CoreBookLevelView level : snapshot.levels()) {
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels = grouped.get(level.instrumentId());
                if (levels == null) throw new IllegalStateException("bootstrap level references unknown instrumentId");
                levels.add(level);
            }
            grouped.replaceAll((instrumentId, levels) -> List.copyOf(levels));
            return new BookBootstrapSession(snapshotId, exportSequence, depth,
                    Collections.unmodifiableNavigableMap(grouped));
        }
    }

/** 一次风险快照或 ADL 候选查询的线程交接对象：Owner 固定市场输入，Ledger 选择有界路由，各账户 Lane 读取自己的状态，最后完成的 Lane 合并并编码响应。 */
static final class RiskAccountQuery extends java.util.concurrent.CompletableFuture<CoreQueryService.QueryResult> implements AccountLaneCommand, Function<AccountPartition, Void> {
    /** 提供账本、账户投递及查询结果登记的所属运行时，不代表任意线程都能直接访问其账户。 */
    private final TradingCoreRuntime owner;
    private final long commandCount, requestedUser;
    private long laneMask;
    private long[] users, positionKeys;
    private boolean accountsDispatched;
    private final String asset;
    private final int limit;
    private final CoreQueryService.RiskQueries.MarketInputs markets;
    private final List<ArrayList<CoreRiskSnapshotView>> riskResults;
    private final List<ArrayList<CoreAdlCandidateView>> adlResults;
    private final CoreResultCode[] failures;
    private final AtomicLong completedLaneMask = new AtomicLong();

    /** 在 Owner 解码查询类型及 ADL 条件，固定当前命令计数和市场输入，创建稍后交给 Ledger 选择账户路由的查询对象。 */
    static RiskAccountQuery prepare(TradingCoreRuntime owner, CoreMessage message) {
        String asset = null;
        int limit = 0;
        if (message.header().messageType() != CoreMessageType.RISK_STATE_QUERY) {
            var query = CoreAdlQueryCodec.decodeQuery(message.payloadUnsafe());
            asset = AssetBalance.normalizeAsset(query.asset());
            limit = query.limit();
        }
        return new RiskAccountQuery(owner, owner.appliedCommandCount(), message.header().userId(), asset, limit,
                CoreQueryService.RiskQueries.MarketInputs.capture(owner.runtimeState(), owner.identities(), owner.runtimeState().instrumentIdsForQuery()));
    }

    private RiskAccountQuery(TradingCoreRuntime owner, long commandCount,
            long requestedUser, String asset, int limit, CoreQueryService.RiskQueries.MarketInputs markets) {
        this.owner = owner; this.commandCount = commandCount; this.requestedUser = requestedUser;
        this.asset = asset; this.limit = limit; this.markets = markets;
        int count = owner.runtimeState().topology().accountLaneCount();
        failures = new CoreResultCode[count];
        riskResults = new ArrayList<>(count); adlResults = new ArrayList<>(count);
        for (int lane = 0; lane < count; lane++) {
            riskResults.add(new ArrayList<>());
            adlResults.add(new ArrayList<>(Math.min(limit, 64)));
        }
        resolveRoutes();
    }

    private void resolveRoutes() {
        try {
            if (asset == null) {
                users = owner.positionUserIndex().riskQueryUserIds(requestedUser,
                        CoreQueryService.OperationalQueries.MAX_INDEX_SCAN);
            } else {
                var routes = owner.adlPositionIndex().positions(asset, CoreQueryService.OperationalQueries.MAX_INDEX_SCAN);
                users = new long[routes.size()]; positionKeys = new long[routes.size()];
                int index = 0;
                for (var route : routes) {
                    users[index] = route.userId();
                    positionKeys[index++] = com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry.positionIdentityKey(
                            route.userId(), route.instrumentId(), route.positionSide());
                }
            }
            for (long user : users) laneMask |= owner.runtimeState().topology().accountLaneMask(user);
            if (laneMask == 0) {
                complete(CoreQueryService.QueryResult.encoded(completedResponse()));
            }
        } catch (CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            failRoutes(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (IllegalArgumentException failure) { failRoutes(CoreResultCode.INVALID_COMMAND); }
    }

    /** 保存带业务错误码的已编码响应，让 Owner 按普通查询结果消费。 */
    private void failRoutes(CoreResultCode code) {
        complete(CoreQueryService.QueryResult.encoded(rejected(code)));
    }

    /** 由 Owner 把查询投到所选账户 Lane。已直接回复则无需投递，邮箱暂不能接受时返回 false 供后续继续尝试。 */
    boolean dispatchAccounts() {
        if (accountsDispatched) return true;
        accountsDispatched = isDone() || owner.runtimeState().dispatchAccountQuery(laneMask, this);
        return accountsDispatched;
    }

    /** 在所属 Lane 的合法只读范围执行查询，把可预期的参数或规模错误记入本 Lane 槽。原子标记完成，只有最后完成的 Lane 合并编码并通知 Owner，重复完成视为内部错误。 */
    @Override public void execute(AccountPartition lane) {
        try { owner.runtimeState().readAccountLane(lane, this); }
        catch (CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
        } catch (IllegalArgumentException failure) {
            failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
        }
        long bit = 1L << lane.laneId();
        long previous, complete;
        do {
            previous = completedLaneMask.get(); complete = previous | bit;
            if ((previous & bit) != 0) throw new IllegalStateException("risk query Lane completed twice");
        } while (!completedLaneMask.compareAndSet(previous, complete));
        if (complete != laneMask) return;
        CoreResponse response;
        try { response = completedResponse(); }
        catch (CoreQueryService.OperationalQueries.QueryTooLargeException failure) {
            response = rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
        complete(CoreQueryService.QueryResult.encoded(response));
    }

    /** 在账户只读范围内过滤属于当前 Lane 的用户或持仓：风险查询重算用户快照，ADL 查询估值并保留有界候选。 */
    @Override public Void apply(AccountPartition lane) {
        var topology = owner.runtimeState().topology();
        if (positionKeys == null) {
            var result = riskResults.get(lane.laneId());
            for (long user : users) {
                if (topology.accountLaneId(user) != lane.laneId()) continue;
                var rows = CoreQueryService.RiskQueries.userSnapshots(owner.runtimeState(), owner.identities(), user, markets);
                if (result.size() + rows.size() > CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                    throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
                result.addAll(rows);
            }
        } else {
            var result = adlResults.get(lane.laneId());
            for (int index = 0; index < users.length; index++) {
                if (topology.accountLaneId(users[index]) != lane.laneId()) continue;
                var candidate = CoreQueryService.RiskQueries.adlCandidate(owner.runtimeState(), owner.identities(),
                        users[index], positionKeys[index], asset, markets);
                if (candidate != null) CoreQueryService.RiskQueries.addAdlCandidate(result, candidate, limit);
            }
        }
        return null;
    }

    /** 按 Lane 编号优先返回已有失败。否则风险快照先检查合并数量，再按用户、币对文本及持仓腿排序编码，ADL 则归并各 Lane 的有界候选。 */
    CoreResponse completedResponse() {
        for (CoreResultCode failure : failures) if (failure != null) return rejected(failure);
        byte[] encoded;
        if (positionKeys == null) {
            int count = 0;
            for (var rows : riskResults) count = Math.addExact(count, rows.size());
            if (count > CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
            var result = new ArrayList<CoreRiskSnapshotView>(count);
            for (var rows : riskResults) result.addAll(rows);
            result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                    .thenComparing(CoreRiskSnapshotView::instrumentId)
                    // 用户及币对相同时用于比较持仓腿枚举顺序的风险快照项。
                    .thenComparingInt(value -> value.positionSide().ordinal()));
            encoded = CoreRiskQueryCodec.encode(result);
        } else encoded = CoreAdlQueryCodec.encodeCandidates(CoreQueryService.RiskQueries.mergeAdlCandidates(adlResults, limit));
        return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
    }

    private CoreResponse rejected(CoreResultCode code) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
    }
}

/** 强平待办工作查询。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static final class LiquidationQueries {

    /** 把账户 Lane 已捕获的只读动作和补亏回执归并成一页。 */
    public static CoreLiquidationWorkView workPage(ProductLine productLine,
            CoreLiquidationWorkView.Query query, long[] ids, CoreLiquidationActionView[] actionReceipts,
            CoreLiquidationWorkView.Resolution[] resolutionReceipts, CoreRiskScanContinuation continuation,
            boolean sourceComplete) {
        ArrayList<CoreLiquidationActionView> actions = new ArrayList<>();
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>();
        long cursor = query.afterLiquidationId();
        boolean complete = sourceComplete;
        int encoded = CoreLiquidationWorkCodec.emptyWorkLength(continuation);
        for (int index = 0; index < ids.length; index++) {
            if (ids[index] <= query.afterLiquidationId()) continue;
            var action = actionReceipts[index];
            var resolution = resolutionReceipts[index];
            if (action == null && resolution == null) { cursor = ids[index]; continue; }
            if (actions.size() + resolutions.size() == query.maxItems()) { complete = false; break; }
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

    /** 在已有分页结果的补亏项上填入账本推荐覆盖额，缺少推荐时为 0。 */
    public static CoreLiquidationWorkView insuranceRecommendations(CoreLiquidationWorkView work,
                                                                  java.util.Map<Long, Long> allocations) {
        ArrayList<CoreLiquidationWorkView.Resolution> resolutions = new ArrayList<>(work.resolutions().size());
        for (var value : work.resolutions()) resolutions.add(new CoreLiquidationWorkView.Resolution(
                value.liquidationId(), value.userId(), value.instrumentId(), value.asset(), value.marginMode(), value.positionSide(),
                value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(),
                allocations.getOrDefault(value.liquidationId(), 0L), value.purpose()));
        return new CoreLiquidationWorkView(work.productLine(), work.nextCursorLiquidationId(), work.complete(),
                work.riskScanContinuation(), work.actions(), resolutions);
    }

    /** 只在执行用途携带风险续扫信息。 */
    public static CoreRiskScanContinuation continuation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            CoreLiquidationWorkView.Purpose purpose) {
        if (purpose != CoreLiquidationWorkView.Purpose.EXECUTION) return null;
        if (runtime.riskScanCount() > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN) {
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
        }
        RiskScanner.State selected = runtime.firstRiskIncompleteScan();
        return selected == null ? null
                : new CoreRiskScanContinuation(identities.instrumentId(selected.symbolId()),
                        selected.priceSequence(), selected.lastUserId());
    }
}

/** 账本资产、资金费及到期结算进度、条件单、算法母单和定时撤单的查询视图组装。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static final class OperationalQueries {

    public static final int MAX_QUERY_ENTITIES = 10_000;
    /** 单次查询允许扫描的索引规模上限 16384，避免结果少但遍历范围无限大。 */
    public static final int MAX_INDEX_SCAN = 16_384;

    /** 通过运行时的账本静止读取入口获取资产视图，再调用账本数据版本的组装方法。 */
    public static List<CoreTreasuryAssetView> treasuryAssets(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        return runtime.queryLedgerAtRest(ledger -> treasuryAssets(ledger, identities));
    }

    /** 先检查总账项规模，再合并手续费、保险、亏空及各类残差中的资产编号，读取每种资产各项余额并按资产代码排序，返回只读列表。 */
    public static List<CoreTreasuryAssetView> treasuryAssets(TreasuryRuntime treasury, RuntimeIdentityRegistry identities) {
        if (treasury.assetLedgerEntryCount() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        var ids = new org.eclipse.collections.impl.set.mutable.primitive.IntHashSet();
        ids.addAll(treasury.feeBalances().keySet());
        ids.addAll(treasury.insuranceBalances().keySet());
        ids.addAll(treasury.insuranceDeficits().keySet());
        ids.addAll(treasury.liquidationFeeBalances().keySet());
        ids.addAll(treasury.fundingResidualBalances().keySet());
        ids.addAll(treasury.roundingResidualBalances().keySet());
        ids.addAll(treasury.clearingPnlBalances().keySet());
        if (ids.size() > MAX_QUERY_ENTITIES) throw new QueryTooLargeException();
        int[] sorted = ids.toArray();
        Arrays.sort(sorted);
        ArrayList<CoreTreasuryAssetView> views = new ArrayList<>(sorted.length);
        for (int assetId : sorted) {
            views.add(new CoreTreasuryAssetView(identities.asset(assetId), treasury.fee(assetId),
                    treasury.insurance(assetId), treasury.insuranceDeficit(assetId),
                    treasury.liquidationFee(assetId), treasury.fundingResidual(assetId),
                    treasury.roundingResidual(assetId), treasury.clearingPnl(assetId)));
        }
        views.sort(Comparator.comparing(CoreTreasuryAssetView::asset));
        return List.copyOf(views);
    }

    /** 已知币对有进行中的资金费时返回结算编号和下一用户游标。 */
    public static CoreFundingProgressView fundingProgress(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreFundingProgressView(0, true, 0, 0);
        TreasuryRuntime treasury = runtime.treasury();
        TreasuryRuntime.FundingProgressRuntime progress = treasury.fundingProgress(symbolId);
        return progress == null
                ? new CoreFundingProgressView(treasury.fundingSettlement(symbolId), true, 0, 0)
                : new CoreFundingProgressView(progress.settlementId(), false, progress.nextCursorUserId(), 0);
    }

    /** 读取到期结算的订单与用户继续游标、订单阶段完成标记及所需保险金额。 */
    public static CoreSettlementProgressView settlementProgress(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        if (symbolId == null) return new CoreSettlementProgressView(0, true, true, 0, 0, 0, 0);
        TreasuryRuntime treasury = runtime.treasury();
        TreasuryRuntime.LifecycleProgressRuntime progress = treasury.lifecycleProgress(symbolId);
        return progress == null
                ? new CoreSettlementProgressView(treasury.lifecycleSettlement(symbolId), true, true, 0, 0, 0, 0)
                : new CoreSettlementProgressView(progress.settlementId(), false, progress.ordersComplete(),
                progress.nextCursorOrderId(), progress.nextCursorUserId(), 0, 0, progress.requiredInsuranceUnits());
    }

    /** 查询一笔算法母单，在子单扫描上限内汇总退役及仍保留子单的成交量和活动数量。 */
    public static CoreAlgoOrderView algoOrder(TradingAccountRuntime runtime, long algoOrderId) {
        CoreAlgoOrderState state = runtime.algoOrder(algoOrderId);
        if (state == null) return null;
        if (state.childOrderIds().size() > MAX_INDEX_SCAN) throw new QueryTooLargeException();
        long executed = state.retiredExecutedQuantitySteps();
        long active = 0;
        int activeCount = 0;
        for (long childOrderId : state.childOrderIds()) {
            Order child = runtime.order(childOrderId);
            if (child == null) continue;
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

    /** 沿给定定时器键读取仍存在的倒计时撤单记录，扫描超限时报错，结果达到一万条就停止并返回只读视图列表。 */
    public static List<CoreCancelAllAfterView> cancelAllAfter(
            TradingAccountRuntime runtime, Iterable<CoreCancelAllAfterKey> keys) {
        ArrayList<CoreCancelAllAfterView> result = new ArrayList<>();
        int scanned = 0;
        for (CoreCancelAllAfterKey key : keys) {
            if (key == null) continue;
            if (++scanned > MAX_INDEX_SCAN) throw new QueryTooLargeException();
            CoreCancelAllAfterState state = runtime.cancelAllAfterTimer(key);
            if (state != null) result.add(state.view());
            if (result.size() == MAX_QUERY_ENTITIES) break;
        }
        return List.copyOf(result);
    }

    /** 查询扫描范围或物化规模超过允许上限时使用的内部异常，用来与记录不存在等正常空结果区分。 */
    public static final class QueryTooLargeException extends IllegalStateException {
    }
}

/** 按查询时的持仓和行情即时计算风险及 ADL 候选，不把后台分批强平扫描的旧风险缓存直接当查询结果。生产查询由 Owner 固定市场输入，再由所属账户 Lane 读取账户。 */
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

    /** 生产账户 Lane 的单用户入口，要求正用户编号和 Owner 已固定的市场输入，再按当前持仓计算风险结果。 */
    public static List<CoreRiskSnapshotView> userSnapshots(TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities, long userId, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("risk query Lane inputs are required");
        ArrayList<CoreRiskSnapshotView> result = new ArrayList<>();
        if (runtime.positionCountForUser(userId) > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN)
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
        var values = new ArrayList<PositionValuation>();
        var cross = new java.util.HashMap<String, long[]>();
        var wallets = new java.util.HashMap<String, Long>();
        for (long key : runtime.positionKeysForUser(userId)) {
            Position position = runtime.position(key);
            if (position == null || position.signedQuantitySteps() == 0) continue;
            if (values.size() >= CoreQueryService.OperationalQueries.MAX_QUERY_ENTITIES)
                throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
            CoreInstrument instrument = markets.instrument(position.symbolId());
            MarkPrice mark = markets.mark(position.symbolId());
            if (instrument == null || mark == null)
                throw new IllegalStateException("risk query source is missing");
            long quantity = position.signedQuantitySteps();
            long pnl = CoreContractMath.pnlUnits(instrument, quantity, position.entryPriceTicks(), mark.markPriceTicks());
            long maintenance = CoreContractMath.maintenanceMarginUnits(instrument, quantity,
                    mark.markPriceTicks(), mark.indexPriceTicks(), mark.forwardPriceTicks());
            long equityDelta = instrument.contractType().isOption()
                    ? OptionContractMath.optionMarketValueUnits(instrument, quantity, mark.markPriceTicks()) : pnl;
            long notional = CoreContractMath.notionalUnits(instrument, Math.absExact(quantity), mark.markPriceTicks());
            values.add(new PositionValuation(position, instrument, mark, pnl, maintenance, equityDelta, notional));
            wallets.computeIfAbsent(instrument.settleAsset(), asset -> crossWalletBalance(runtime, identities, userId, asset, markets));
            if (position.marginMode() == CoreMarginMode.CROSS) {
                long[] totals = cross.computeIfAbsent(instrument.settleAsset(), asset -> new long[2]);
                totals[0] = Math.addExact(totals[0], equityDelta);
                totals[1] = Math.addExact(totals[1], maintenance);
            }
        }
        for (PositionValuation value : values) {
            Position position = value.position();
            String asset = value.instrument().settleAsset();
            long wallet = wallets.get(asset);
            long equity, maintenance;
            if (position.marginMode() == CoreMarginMode.CROSS) {
                long[] totals = cross.get(asset);
                equity = Math.addExact(wallet, totals[0]);
                maintenance = totals[1];
            } else {
                equity = Math.addExact(position.positionMarginUnits(), value.equityDelta());
                maintenance = value.maintenance();
            }
            long ratio = maintenance == 0 ? 0 : equity <= 0 ? Long.MAX_VALUE
                    : multiplyDivideCapped(maintenance, PPM, equity);
            result.add(new CoreRiskSnapshotView(userId, value.instrument().instrumentId(), position.marginMode(),
                    position.positionSide(), asset, position.signedQuantitySteps(), position.entryPriceTicks(),
                    value.mark().markPriceTicks(), value.notional(), position.positionMarginUnits(),
                    value.mark().priceSequence(), wallet, equity, value.pnl(), value.maintenance(), ratio,
                    CoreRiskStatus.of(ratio).name()));
        }
        result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                .thenComparing(CoreRiskSnapshotView::instrumentId)
                // 排序时当前比较的查询结果行，最后按持仓腿的枚举顺序打破同用户同币对的顺序。
                .thenComparingInt(value -> value.positionSide().ordinal()));
        return List.copyOf(result);
    }

    /** 生产 Lane 按 Owner 准备的整数持仓键读取目标用户仓位，核对路由用户一致，再用固定市场输入计算候选。 */
    public static CoreAdlCandidateView adlCandidate(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            long userId, long positionKey, String asset, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("ADL query Lane inputs are required");
        Position position = positionKey == 0 ? null : runtime.position(positionKey);
        if (position == null) return null;
        if (position.userId() != userId) throw new IllegalStateException("ADL query position route differs");
        MarkPrice mark = markets.mark(position.symbolId());
        CoreInstrument instrument = markets.instrument(position.symbolId());
        if (position.signedQuantitySteps() == 0 || !identities.asset(position.assetId()).equals(asset)
                || mark == null || instrument == null
                || !(instrument.contractType().isPerpetual() || instrument.contractType().isDelivery()
                || instrument.contractType().isOption()) || !instrument.settleAsset().equals(asset)) return null;
        long profit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        if (profit <= 0) return null;
        long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long margin = position.marginMode() == CoreMarginMode.ISOLATED
                ? position.positionMarginUnits() : totalBalance(runtime, userId, asset, markets);
        long profitRate = ratio(profit, notional);
        long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
        long priority = multiplyDivideCapped(profitRate, leverage, PPM);
        return new CoreAdlCandidateView(userId, instrument.instrumentId(), asset, position.marginMode(),
                position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                mark.markPriceTicks(), mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority);
    }

    /** 按统一 ADL 比较规则二分插入候选，超过条数限制时删除最末项，调用方传入的列表应已按同一规则排序。 */
    public static void addAdlCandidate(ArrayList<CoreAdlCandidateView> candidates, CoreAdlCandidateView candidate, int limit) {
        int insertAt = java.util.Collections.binarySearch(candidates, candidate, ADL_ORDER);
        candidates.add(insertAt < 0 ? -insertAt - 1 : insertAt, candidate);
        if (candidates.size() > limit) candidates.removeLast();
    }

    /** 对各 Lane 已排序的有限候选列表做多路归并，每次选最优头项，达到 limit 或全部耗尽后结束。 */
    public static List<CoreAdlCandidateView> mergeAdlCandidates(List<? extends List<CoreAdlCandidateView>> laneCandidates,
            int limit) {
        int[] cursors = new int[laneCandidates.size()];
        ArrayList<CoreAdlCandidateView> result = new ArrayList<>(limit);
        while (result.size() < limit) {
            int bestLane = -1;
            CoreAdlCandidateView best = null;
            for (int laneId = 0; laneId < laneCandidates.size(); laneId++) {
                List<CoreAdlCandidateView> lane = laneCandidates.get(laneId);
                if (cursors[laneId] >= lane.size()) continue;
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

    /** 同一次查询共用的市场输入，由 Owner 捕获配置查询副本、行情引用和资产编号，派发后只读使用。 */
    public static final class MarketInputs {
        /** Owner 捕获的本次查询配置映射，账户 Lane 只按整数币对键读取。 */
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments;
        /** Owner 捕获的本次查询行情引用映射，不随之后的行情替换而更换引用。 */
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice> marks;
        private final java.util.Map<String, Integer> assetIds;

        /** 保存本次查询专用的配置和行情映射，并复制资产编号映射为不可修改 Map。 */
        private MarketInputs(org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments,
                org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice> marks,
                java.util.Map<String, Integer> assetIds) {
            this.instruments = instruments;
            this.marks = marks;
            this.assetIds = java.util.Map.copyOf(assetIds);
        }

        /** Owner 逐币对捕获当前配置的查询副本与行情引用，跳过未知或重复币对，并记录可用的结算资产编号，让后续账户查询不受 Owner 配置更新影响。 */
        public static MarketInputs capture(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                Iterable<String> instrumentIds) {
            var instruments = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument>();
            var marks = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice>();
            var assets = new java.util.HashMap<String, Integer>();
            for (String instrumentId : instrumentIds) {
                Integer symbolId = identities.findSymbolId(instrumentId);
                if (symbolId == null || instruments.containsKey(symbolId)) continue;
                CoreInstrument instrument = runtime.instrument(instrumentId);
                instruments.put(symbolId, instrument == null ? null : instrument.captureForQuery());
                marks.put(symbolId, runtime.markPrice(symbolId));
                Integer assetId = instrument == null ? null : identities.findAssetId(instrument.settleAsset());
                if (assetId != null) assets.put(instrument.settleAsset(), assetId);
            }
            return new MarketInputs(instruments, marks, assets);
        }

        private CoreInstrument instrument(int symbolId) { return instruments.get(symbolId); }
        private MarkPrice mark(int symbolId) { return marks.get(symbolId); }
    }

    private static long crossWalletBalance(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId, String asset, MarketInputs markets) {
        long wallet = totalBalance(runtime, userId, asset, markets);
        if (runtime.reservationCountForUser(userId) > CoreQueryService.OperationalQueries.MAX_INDEX_SCAN) {
            throw new CoreQueryService.OperationalQueries.QueryTooLargeException();
        }
        for (long positionKey : runtime.positionKeysForUser(userId)) {
            Position position = runtime.position(positionKey);
            if (position != null && position.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(position.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, position.positionMarginUnits());
            }
        }
        for (long orderId : runtime.reservationIdsForUser(userId)) {
            OrderFundsReservation reservation = runtime.reservation(orderId);
            Order order = runtime.order(orderId);
            if (reservation != null && order != null && order.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(reservation.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, reservation.reservedUnits());
            }
        }
        if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
        return wallet;
    }

    /** 按结算资产定位用户余额，缺少资产编号或余额记录时按 0，存在时返回可用与冻结金额之和并检查整数溢出。 */
    private static long totalBalance(
            TradingAccountRuntime runtime, long userId, String asset, MarketInputs markets) {
        Integer assetId = markets.assetIds.get(asset);
        AccountBalance balance = assetId == null ? null : runtime.balance(userId, assetId);
        return balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
    }

    private static long ratio(long numerator, long denominator) {
        return numerator <= 0 || denominator <= 0 ? 0 : multiplyDivideCapped(numerator, PPM, denominator);
    }

    /** 先精确整数乘法再做整数除法，发生乘法溢出等算术异常时返回最大 long。按中间运算是否溢出来封顶，不重试大整数计算。 */
    private static long multiplyDivideCapped(long left, long right, long divisor) {
        try {
            return Math.multiplyExact(left, right) / divisor;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /** 单个持仓在本次查询中的临时估值，先算并保留本仓盈亏、维持保证金和市值变化，再在用户内按结算资产合并全仓风险。 */
    private record PositionValuation(Position position, CoreInstrument instrument, MarkPrice mark,
                                     long pnl, long maintenance, long equityDelta, long notional) {
    }
}

/** 账户 Lane 内的用户及订单只读查询。先按用户范围检查物化规模，再把整数身份转换成协议视图，并按固定字段顺序计算状态比较哈希。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static final class StateQueries {

    public static final int MAX_USER_QUERY_ENTITIES = 4_096;

    /** 在指定 Lane 定位用户，先核对实体数量上限，再整理有序明细。 */
    public static UserQueryResult userState(
            TradingAccountRuntime runtime,
            AccountPartition lane,
            RuntimeIdentityRegistry identities,
            long userId, boolean hashOnly) {
        UserAccount user = runtime.user(lane, userId);
        if (user == null) return UserQueryResult.notFound();

        var balancesByAsset = runtime.balancesForUser(lane, userId);
        int balanceCount = balancesByAsset == null ? 0 : balancesByAsset.size();
        int reservationCount = runtime.reservationCountForUser(lane, userId);
        int positionCount = runtime.positionCountForUser(lane, userId);
        var leverageKeys = runtime.leverageKeysForUser(lane, userId);
        int entityCount = Math.addExact(balanceCount,
                Math.addExact(reservationCount, Math.addExact(positionCount, leverageKeys.size())));
        if (entityCount > MAX_USER_QUERY_ENTITIES) return UserQueryResult.oversized();
        long[] reservationIds = runtime.reservationIdsForUser(lane, userId);
        long[] positionKeys = runtime.positionKeysForUser(lane, userId);

        ArrayList<CoreBalanceView> balances = new ArrayList<>(balanceCount);
        if (balancesByAsset != null) {
            balancesByAsset.forEachValue(balance -> balances.add(new CoreBalanceView(
                    identities.asset(balance.assetId()), balance.availableUnits(), balance.lockedUnits())));
            balances.sort(Comparator.comparing(CoreBalanceView::asset));
        }

        Arrays.sort(reservationIds);
        ArrayList<CoreReservationView> reservations = new ArrayList<>(reservationIds.length);
        for (long orderId : reservationIds) {
            OrderFundsReservation reservation = runtime.reservation(lane, orderId);
            if (reservation == null) continue;
            reservations.add(new CoreReservationView(reservation.orderId(),
                    identities.instrumentId(reservation.symbolId()), reservation.kind(),
                    identities.asset(reservation.assetId()), reservation.totalReservedUnits(),
                    reservation.releasedUnits(), reservation.consumedUnits(), reservation.orderQuantitySteps()));
        }

        ArrayList<PositionEntry> positionEntries = new ArrayList<>(positionKeys.length);
        for (long positionKey : positionKeys) {
            Position position = runtime.position(lane, positionKey);
            if (position == null) continue;
            String instrumentId = identities.instrumentId(position.symbolId());
            CorePositionView view = new CorePositionView(instrumentId, identities.asset(position.assetId()),
                    position.marginMode(), position.positionSide(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                    position.realizedPnlUnits(), position.positionMarginUnits());
            String key = position.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? instrumentId : instrumentId + ':' + position.positionSide().name();
            positionEntries.add(new PositionEntry(key, view));
        }
        positionEntries.sort(Comparator.comparing(PositionEntry::key));
        List<CorePositionView> positions = positionEntries.stream().map(PositionEntry::view).toList();

        ArrayList<CoreLeverageView> leverages = new ArrayList<>(leverageKeys.size());
        for (CoreLeverageKey key : leverageKeys) {
            leverages.add(new CoreLeverageView(key.instrumentId(), key.marginMode(), runtime.leverage(lane, key)));
        }
        if (hashOnly) return UserQueryResult.found(null,
                userStateHash(user, balances, reservations, positions, leverages));
        CoreUserStateView view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(),
                user.positionMode(), balances, reservations, positions, leverages);
        return UserQueryResult.found(view, 0);
    }

    /** 在指定 Lane 读取订单，按请求构造视图或计算包括内部撮合价在内的状态哈希。 */
    public static OrderQueryResult orderState(
            TradingAccountRuntime runtime,
            AccountPartition lane,
            RuntimeIdentityRegistry identities,
            long orderId, boolean hashOnly) {
        Order order = runtime.order(lane, orderId);
        if (order == null) return OrderQueryResult.notFound();
        if (hashOnly) return OrderQueryResult.found(null,
                orderStateHash(order, identities.instrumentId(order.symbolId())));
        CoreOrderStateView view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                identities.instrumentId(order.symbolId()), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.executedValueHigh(), order.executedValueLow(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(), order.clusterPosition(), order.status().name(), order.revision());
        return OrderQueryResult.found(view, 0);
    }

    /** 先在该 Lane 定位用户的客户端订单身份，再查实际订单编号，任一身份或订单映射不存在时返回未找到，否则复用普通订单查询。 */
    public static OrderQueryResult clientOrderState(
            TradingAccountRuntime runtime,
            AccountPartition lane,
            RuntimeIdentityRegistry identities,
            long userId,
            String clientOrderId) {
        long clientKey = identities.findClientKeyValueInLane(lane, userId, clientOrderId);
        if (clientKey == 0) return OrderQueryResult.notFound();
        long orderId = runtime.orderIdByClientValue(lane, userId, clientKey);
        return orderId == 0 ? OrderQueryResult.notFound() : orderState(runtime, lane, identities, orderId, false);
    }

    /** 按用户身份、版本、模式及各视图列表的固定字段顺序混合状态哈希。 */
    private static long userStateHash(UserAccount user, List<CoreBalanceView> balances,
            List<CoreReservationView> reservations, List<CorePositionView> positions, List<CoreLeverageView> leverages) {
        long hash = CoreStateHash.mix(CoreStateHash.start(), com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(user.productLine()));
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        hash = CoreStateHash.mix(hash, user.positionMode().wireCode());
        for (CoreBalanceView balance : balances) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (CoreReservationView reservation : reservations) {
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
        for (CorePositionView position : positions) {
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
        for (CoreLeverageView leverage : leverages) {
            hash = CoreStateHash.mix(hash, leverage.instrumentId());
            hash = CoreStateHash.mix(hash, leverage.marginMode().wireCode());
            hash = CoreStateHash.mix(hash, leverage.leveragePpm());
        }
        return hash;
    }

    private static long orderStateHash(Order order, String instrumentId) {
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

    /** 组装用户视图时的持仓排序项，把实际持仓视图与净仓或双向腿业务键配对，排序后再提取视图。 */
    private record PositionEntry(String key, CorePositionView view) {
    }

    /** 用户查询结果，分别表达找到、未找到或实体规模过大。 */
    public record UserQueryResult(boolean found, boolean tooLarge, CoreUserStateView view, long stateHash) {
        /** 封装找到的用户视图和哈希，明确关闭响应过大标记。 */
        static UserQueryResult found(CoreUserStateView view, long stateHash) {
            return new UserQueryResult(true, false, view, stateHash);
        }

        /** 封装用户不存在的结果，视图为空、哈希为 0，不能与实体过大混同。 */
        static UserQueryResult notFound() {
            return new UserQueryResult(false, false, null, 0);
        }

        /** 封装用户实体总量超过物化上限的结果，不返回部分视图。 */
        static UserQueryResult oversized() {
            return new UserQueryResult(false, true, null, 0);
        }
    }

    /** 订单查询结果，区分找到与不存在。 */
    public record OrderQueryResult(boolean found, CoreOrderStateView view, long stateHash) {
        /** 封装已经找到的订单协议视图和对应运行时状态哈希。 */
        static OrderQueryResult found(CoreOrderStateView view, long stateHash) {
            return new OrderQueryResult(true, view, stateHash);
        }

        /** 封装订单不存在的结果，视图为空且哈希为 0。 */
        static OrderQueryResult notFound() {
            return new OrderQueryResult(false, null, 0);
        }
    }
}
}
