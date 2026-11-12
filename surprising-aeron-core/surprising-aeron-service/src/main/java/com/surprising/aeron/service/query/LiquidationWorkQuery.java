package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.treasury.InsuranceAllocationPolicy;
import com.surprising.aeron.service.treasury.SystemFunds;
import java.util.concurrent.CompletableFuture;

/** 一次强平执行、保险或 ADL 工作页的交接任务。Owner 固定候选和市场输入，Lane 读取计划事实，等待所有 Lane 后计算保险建议并编码有界页，不实际执行强平或赔付。 */
final class LiquidationWorkQuery extends CompletableFuture<CoreQueryService.QueryResult> implements AccountLaneCommand {
    private final TradingCore owner;
    private final long commandCount;
    final long laneMask;
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
    LiquidationWorkQuery(TradingCore owner, long commandCount, CoreLiquidationWorkView.Query requested, java.util.NavigableSet<Long> candidates) {
        this.owner = owner;
        this.commandCount = commandCount; this.requested = requested;
        int count = Math.min(candidates.size(), OperationalQueries.MAX_INDEX_SCAN);
        sourceComplete = count == candidates.size();
        continuation = LiquidationQueries.continuation(owner.accountBook(), owner.identities(), requested.purpose());
        ids = new long[count]; lanes = new int[ids.length]; assetIds = new int[ids.length];
        instruments = new com.surprising.aeron.service.instrument.CoreInstrument[ids.length];
        receipts = new CoreLiquidationWorkView.Resolution[ids.length];
        actions = new com.surprising.aeron.protocol.CoreLiquidationActionView[ids.length];
        marks = new com.surprising.aeron.service.market.MarkPrice[ids.length];
        failures = new CoreResultCode[owner.accountBook().topology().accountLaneCount()];
        claims = new InsuranceAllocationPolicy.AllocationInputs[owner.accountBook().topology().accountLaneCount()];
        long mask = 0;
        int index = 0;
        for (long liquidationId : candidates) {
            if (index == count) break;
            var route = owner.liquidationIndex().route(liquidationId);
            if (route == null) throw new IllegalStateException("liquidation query route is missing");
            instruments[index] = owner.accountBook().instrument(route.instrumentId());
            assetIds[index] = owner.identities().assetId(instruments[index].settleAsset());
            marks[index] = owner.accountBook().markPrice(owner.identities().findSymbolId(route.instrumentId()));
            ids[index] = liquidationId; lanes[index] = owner.accountBook().topology().accountLaneId(route.userId());
            mask |= 1L << lanes[index++];
        }
        laneMask = mask;
    }
    /** 当前 Lane 读取其候选计划，按工作类型和固定市场输入筛选执行动作或缺口回执。保险页还准备本 Lane 的全部相关请求，记录错误后发布完成位并通知 Owner。 */
    @Override public void execute(AccountPartition lane) {
        try { owner.accountBook().readAccountLane(lane, owned -> {
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
                claims[owned.laneId()] = InsuranceAllocationPolicy.prepareLaneAllocations(owner.accountBook(), ids, assetIds, instruments);
            return null;
        }); }
        catch (QueryTooLargeException tooLarge) {
            failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
        }
        completed.getAndUpdate(mask -> mask | (1L << lane.laneId()));
    }
    /** 只有所有目标 Lane 的完成位齐全，才可读取回执。 */
    public boolean ready() { return completed.get() == laneMask; }
    /** 等待 Lane 已完成后，先返回失败或组装有界工作页。保险页依据全部请求和原基金余额计算建议金额，编码结果并通知 Owner，不执行资金支付。 */
    public void apply(SystemFunds ledger) {
        CoreResponse response;
        try {
            for (CoreResultCode failure : failures) {
                if (failure == null) continue;
                complete(CoreQueryService.QueryResult.encoded(new CoreResponse(
                        ResponseStatus.REJECTED, ResponseStatus.REJECTED, failure, commandCount)));
                return;
            }
            var result = LiquidationQueries.workPage(owner.productLine(), requested, ids, actions,
                    receipts, continuation, sourceComplete);
            if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE) {
                var allocations = InsuranceAllocationPolicy.allocations(ledger, claims);
                result = LiquidationQueries.insuranceRecommendations(result, allocations);
            }
            response = new CoreResponse(ResponseStatus.OK, commandCount, CoreLiquidationWorkCodec.encodeWork(result));
        } catch (QueryTooLargeException tooLarge) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.INVALID_COMMAND, commandCount);
        }
        complete(CoreQueryService.QueryResult.encoded(response));
    }
}
