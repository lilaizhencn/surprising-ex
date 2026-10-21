package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.C2cFundsRepository;
import com.surprising.gateway.c2c.C2cFunds;
import com.surprising.gateway.c2c.C2cAdsRepository;
import com.surprising.gateway.c2c.C2cAds;
import com.surprising.gateway.c2c.C2cOrdersRepository;
import com.surprising.gateway.c2c.C2cOrders;
import com.surprising.gateway.c2c.C2cSubmissions;

import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 单独线程续办小批量原指令，Core I/O 不占用行情、交易或共享定时线程。 */
@Service
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cSettlement {
    private final C2cFundsRepository intents;
    private final C2cFunds funds;
    private final C2cAdsRepository adRows;
    private final C2cAds ads;
    private final C2cOrdersRepository orderRows;
    private final C2cOrders orders;
    private final C2cSubmissions submissions;
    private final GatewayProductServices products;

    public C2cSettlement(C2cFundsRepository intents,C2cFunds funds,C2cAdsRepository adRows,C2cAds ads,
                         C2cOrdersRepository orderRows,C2cOrders orders,C2cSubmissions submissions,GatewayProductServices products) {
        this.intents=intents;this.funds=funds;this.adRows=adRows;this.ads=ads;
        this.orderRows=orderRows;this.orders=orders;this.submissions=submissions;this.products=products;
    }

    public void settle() {
        // 关闭市场只停止新订单，已收款订单与在途资金仍须续办。
        if(products.enabled().contains(ProductLine.SPOT)) {
            for(var intent:intents.due(8)) attempt("FUNDS",intent.id(),()->funds.resume(intent.id()));
        }
        for(var ad:adRows.progress(32)) attempt("AD",ad.id(),()->ads.finishFunding(ad.id()));
        for(var id:orderRows.fundingReady(32)) attempt("ORDER",id,()->orders.finishFunding(id));
        for(var id:orderRows.expired(32)) attempt("EXPIRY",id,()->orders.expire(id));
        submissions.expireUnstarted(32);
    }

    private void attempt(String stage,UUID id,Runnable action) {
        try { action.run(); }
        catch(RuntimeException failure) {
            // 不输出收款资料或验证码；异常不伪装为成功，也不创建替代资金命令。
            LoggerFactory.getLogger(getClass()).warn("C2C continuation stage={} id={} error={}",stage,id,failure.getClass().getSimpleName());
        }
    }
}
