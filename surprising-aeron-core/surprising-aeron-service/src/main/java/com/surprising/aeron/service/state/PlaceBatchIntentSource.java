package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.PlaceOrderCommand;

/** Owner 固定命令上下文后交接给账户 Lane；完成前输入不能复用。 */
public interface PlaceBatchIntentSource {
    PlaceOrderCommand intent(int index);
    Decision decision(int index);
    /** 同币对共享的只读行情、费率和全局准入视图，资产身份由 Owner 预先解析。 */
    record Decision(CoreOrderDecisionResolver.Context context, long openInterestSteps,
                    int baseAssetId, int quoteAssetId, int settleAssetId) { }
}
