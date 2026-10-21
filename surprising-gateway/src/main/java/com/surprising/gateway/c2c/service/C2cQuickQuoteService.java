package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cOrders;
import com.surprising.gateway.c2c.model.C2cOfferFilter;
import com.surprising.gateway.c2c.model.C2cQuickQuoteSpec;
import com.surprising.gateway.c2c.model.C2cQuote;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import org.springframework.stereotype.Service;

/** 快捷报价按当前价格顺序尝试原生候选，不分配库存、不调用资金Core。 */
@Service
public class C2cQuickQuoteService {
    private final C2cQueryService queries;
    private final C2cMarketService markets;
    private final C2cAccess access;
    private final C2cOrders orders;

    public C2cQuickQuoteService(C2cQueryService queries,C2cMarketService markets,C2cAccess access,C2cOrders orders) {
        this.queries=queries;this.markets=markets;this.access=access;this.orders=orders;
    }

    public C2cQuote quote(JwtPrincipal user,C2cQuickQuoteSpec request) {
        access.requireTrading(user,markets.market(request.marketId(),true));
        var filter=new C2cOfferFilter(request.marketId(),request.side(),request.amount(),request.methodCode(),
                null,false,0,0,3600,C2cOfferFilter.Sort.PRICE,20,0);
        for(var candidate:queries.candidates(filter,user)) {
            var ad=candidate.ad();
            var accountId=request.paymentAccountId();
            if(request.side()==C2cOfferFilter.Side.BUY) {
                var options=queries.paymentOptions(ad).stream().filter(option ->
                        request.methodCode()==null || request.methodCode().equals(option.methodCode())).toList();
                if(options.isEmpty()) continue;
                accountId=options.getFirst().accountId();
            }
            try {
                // 本方法不能包裹事务：每次经独立的Orders代理进入报价事务，候选失败先回滚再尝试下一条。
                return orders.quote(user,ad.id(),"FIAT",request.amount().toPlainString(),accountId);
            } catch(IllegalArgumentException | IllegalStateException unavailable) {
                // 候选仍执行完整准入；其他异常直接上抛，不隐藏基础设施故障。
            }
        }
        throw new IllegalStateException("暂无满足金额、收款方式及交易资格的报价");
    }
}
