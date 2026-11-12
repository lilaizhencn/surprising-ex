package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.core.TradingCore;
import java.util.concurrent.CompletableFuture;

/** 一次下单预检的查询任务：由用户所属 Lane 检查订单和所需预留，只返回估算结果，不创建真实订单或冻结。 */
final class PreflightQuery extends CompletableFuture<CoreQueryService.QueryResult> implements AccountLaneCommand,
        java.util.function.Function<AccountPartition, CoreResponse> {
    private final TradingCore owner;
    /** userId：必须存在的预检用户编号，只在其所属 Lane 读取账户。 */
    private final long commandCount, userId;
    private final long openInterest;
    private final com.surprising.aeron.protocol.PlaceOrderCommand command;
    /** Owner 固定的预检币对、标记价、费率及结算状态输入。 */
    private final com.surprising.aeron.service.order.OrderCheckService.Context context;

    PreflightQuery(TradingCore owner, long commandCount, long userId, com.surprising.aeron.protocol.PlaceOrderCommand command,
            com.surprising.aeron.service.order.OrderCheckService.Context context) {
        this.owner = owner;
        this.commandCount = commandCount; this.userId = userId;
        this.command = command; this.context = context;
        if (context.instrument().usesOpenInterestForUserLimit() && !command.reduceOnly()) {
            this.openInterest = owner.accountBook().ledgerOrderCheckOpenInterest(command.instrumentId());
        } else {
            this.openInterest = 0;
        }
    }

    /** 进入所属账户的合法只读范围执行预检，把业务拒绝、算术溢出或非法参数转换为查询响应，然后保存结果并通知 Owner。 */
    @Override public void execute(AccountPartition lane) {
        CoreResponse response;
        try { response = owner.accountBook().readAccountLane(lane, this); }
        catch (com.surprising.aeron.exception.CoreStateRejectedException failure) {
            response = rejected(CoreResultCode.fromRejectionCode(failure.code()));
        } catch (ArithmeticException failure) { response = rejected(CoreResultCode.ARITHMETIC_OVERFLOW); }
        catch (IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
        complete(CoreQueryService.QueryResult.encoded(response));
    }

    /** 按固定输入解析订单规则，在当前 Lane 取得客户及持仓身份，计算所需预留并编码资产和金额。 */
    @Override public CoreResponse apply(AccountPartition lane) {
        var order = com.surprising.aeron.service.order.OrderCheckService.resolve(context, userId, command);
        long reserved = com.surprising.aeron.service.order.OrderCheckService.requiredReservationForQuery(
                owner.accountBook(), lane, userId, order, openInterest, context.symbolId(),
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
