package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 订单准入决策门面：统一委托给 {@link RuntimeOrderAdmission}。保留本门面以保证已有调用点、单测与工具契约平滑兼容。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreOrderDecisionResolver {

    public record Context(CoreInstrument instrument, int symbolId, MarkPriceRuntime mark,
                          FeePolicyBook policies, long clusterTimestamp,
                          com.surprising.aeron.service.treasury.TreasuryRuntime.SettlementStatus settlementStatus) {
        public boolean lifecycleSettled() { return settlementStatus.lifecycleSettled(); }
        public boolean fundingInProgress() { return settlementStatus.fundingInProgress(); }
    }

    public static ResolvedPlaceOrder resolve(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                             long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        return RuntimeOrderAdmission.resolve(runtime, identities, userId, intent, clusterTimestamp);
    }

    public static void prepare(PlaceAdmissionEvent target, TradingAccountRuntime runtime,
                               RuntimeIdentityRegistry identities, long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        RuntimeOrderAdmission.prepare(target, runtime, identities, userId, intent, clusterTimestamp);
    }

    public static void resolvePrepared(PlaceAdmissionEvent target, MarkPriceRuntime mark,
                                       FeePolicyBook policies, long userId, long clusterTimestamp, boolean lifecycleSettled) {
        RuntimeOrderAdmission.resolvePrepared(target, mark, policies, userId, clusterTimestamp, lifecycleSettled);
    }

    public static <T extends ResolvedPlaceOrder> T resolveInto(T target, Context context, long userId, PlaceOrderCommand intent) {
        return RuntimeOrderAdmission.resolveInto(target, context, userId, intent);
    }

    public static ResolvedPlaceOrder resolve(Context context, long userId, PlaceOrderCommand intent) {
        return RuntimeOrderAdmission.resolve(context, userId, intent);
    }

    public static Context context(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId, long clusterTimestamp) {
        return RuntimeOrderAdmission.context(runtime, identities, instrumentId, clusterTimestamp);
    }

    public static Context context(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, int instrumentId, long clusterTimestamp) {
        return RuntimeOrderAdmission.context(runtime, identities, instrumentId, clusterTimestamp);
    }

    public static Context queryContext(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId, long clusterTimestamp) {
        return RuntimeOrderAdmission.queryContext(runtime, identities, instrumentId, clusterTimestamp);
    }

    public static long scalePpm(long value, long factor, boolean ceiling) {
        return RuntimeOrderAdmission.scalePpm(value, factor, ceiling);
    }
}
