package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** 一笔普通账户或业务列表查询的跨 Lane 任务，保存本次响应槽和原子完成位。 */
final class AccountQuery extends CompletableFuture<CoreQueryService.QueryResult> implements AccountLaneCommand,
        java.util.function.Function<AccountPartition, CoreResponse> {
    private final TradingCoreRuntime owner;
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
    TriggerQueryPage triggerPage;
    /** 待完成划转页的固定路由与结果槽，投递前由 Owner 设置。 */
    PendingTransferQueryPage transferPage;
    private final AtomicLong completedLaneMask = new AtomicLong();

    AccountQuery(TradingCoreRuntime owner, long commandCount, CoreMessageType type, long userId,
                         long orderId, String clientName, long laneMask, com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds, AlgoQueryPage algoPage) {
        this.owner = owner;
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
        catch (QueryTooLargeException tooLarge) {
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
        complete(CoreQueryService.QueryResult.encoded(completed));
    }

    /** 在当前 Lane 原位读取所选划转、触发、算法、挂单或单实体详情。 */
    @Override public CoreResponse apply(AccountPartition lane) {
        if (transferPage != null) {
            for (int index = 0; index < transferPage.ids().length; index++) {
                if (owner.runtimeState().topology().accountLaneId(transferPage.users()[index]) != lane.laneId()) continue;
                var value = owner.runtimeState().pendingTransfer(transferPage.ids()[index]);
                if (value == null || value.sourceUserId() != transferPage.users()[index])
                    throw new IllegalArgumentException("pending transfer routing differs from Lane identity");
                transferPage.views()[index] = value;
            }
            return null;
        }
        if (triggerPage != null) {
            for (int index = 0; index < triggerPage.ids().length; index++) {
                if (owner.runtimeState().topology().accountLaneId(triggerPage.users()[index]) != lane.laneId()) continue;
                var value = owner.runtimeState().triggerOrder(triggerPage.ids()[index]);
                if (value != null) triggerPage.views()[index] = value.view();
            }
            return null;
        }
        if (algoPage != null) {
            for (int index = 0; index < algoPage.ids().length; index++) {
                if (owner.runtimeState().topology().accountLaneId(algoPage.users()[index]) != lane.laneId()) continue;
                algoPage.views()[index] = OperationalQueries.algoOrder(
                        owner.runtimeState(), algoPage.ids()[index]);
            }
            return null;
        }
        if (openOrderIds != null) {
            for (int index = 0; index < openOrderIds.size(); index++) {
                var query = StateQueries.orderState(owner.runtimeState(), lane, owner.identities(), openOrderIds.valueAt(index), false);
                if (query.found() && "OPEN".equals(query.view().status())) openOrders[index] = query.view();
            }
            return null;
        }
        if (type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY) {
            var query = StateQueries.userState(owner.runtimeState(), lane, owner.identities(), userId,
                    type == CoreMessageType.USER_STATE_HASH_QUERY);
            if (query.tooLarge()) return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            found[lane.laneId()] = query.found();
            if (type == CoreMessageType.USER_STATE_HASH_QUERY)
                return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(query.stateHash()));
            return query.found() ? new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeUserState(query.view()))
                    : CoreQueryService.missingAccountResponse(type, commandCount);
        }
        var query = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                ? StateQueries.clientOrderState(owner.runtimeState(), lane, owner.identities(), userId, clientName)
                : orderId == 0 ? null : StateQueries.orderState(owner.runtimeState(), lane, owner.identities(), orderId,
                        type == CoreMessageType.ORDER_STATE_HASH_QUERY);
        found[lane.laneId()] = query != null && query.found();
        if (!found[lane.laneId()]) return CoreQueryService.missingAccountResponse(type, commandCount);
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
                com.surprising.aeron.protocol.CorePendingTransferCodec.encode(java.util.Arrays.asList(transferPage.views())));
        if (triggerPage != null) {
            var selected = new ArrayList<com.surprising.aeron.protocol.CoreTriggerOrderStateView>(triggerPage.views().length);
            for (var view : triggerPage.views()) if (view != null) selected.add(view);
            return new CoreResponse(ResponseStatus.OK, commandCount,
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(selected));
        }
        if (algoPage != null) {
            var selected = new ArrayList<com.surprising.aeron.protocol.CoreAlgoOrderView>(algoPage.views().length);
            int children = 0;
            for (var view : algoPage.views()) if (view != null) {
                children = Math.addExact(children, view.childOrderIds().size());
                if (children > OperationalQueries.MAX_INDEX_SCAN)
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
