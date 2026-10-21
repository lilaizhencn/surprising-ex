package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.model.C2cAdminRequest;

import com.surprising.gateway.c2c.exception.C2cProblem;

import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.utils.C2cViews;

import com.surprising.gateway.c2c.utils.C2cMoney;

import com.surprising.asset.repository.AssetRepository;
import com.surprising.gateway.c2c.model.*;
import com.surprising.gateway.c2c.repository.*;
import com.surprising.gateway.c2c.service.C2cQueryService;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 配置变更、广告暂停/收款重审与必需审计共同提交；开放前读取原意图和权威账户核对。 */
@Service
public class C2cAdminMarkets {
    private final C2cMarketService markets;
    private final C2cQueryService queries;
    private final C2cAdminAccess access;
    private final GatewayProductServices products;
    private final C2cConfigurationRepository configuration;
    private final C2cMarketRepository marketRows;
    private final AssetRepository assets;
    private final C2cAdsRepository ads;
    private final C2cPaymentMethodRepository methods;
    private final C2cPaymentAccountRepository accounts;
    private final C2cFundsRepository funds;

    public C2cAdminMarkets(C2cMarketService markets,C2cQueryService queries,C2cAdminAccess access,GatewayProductServices products,
                           C2cConfigurationRepository configuration,C2cMarketRepository marketRows,AssetRepository assets,
                           C2cAdsRepository ads,C2cPaymentMethodRepository methods,C2cPaymentAccountRepository accounts,C2cFundsRepository funds) {
        this.markets=markets;this.queries=queries;this.access=access;this.products=products;this.configuration=configuration;
        this.marketRows=marketRows;this.assets=assets;this.ads=ads;this.methods=methods;this.accounts=accounts;this.funds=funds;
    }
    public Map<String,Object> configuration(String authorization) {
        access.read(authorization,false);var config=markets.configuration();
        return Map.of("enabled",config.enabled(),"maxActiveAds",config.maxActiveAds(),"revision",Long.toString(config.revision()),
                "markets",markets.markets(false).stream().map(C2cViews::market).toList(),"paymentMethods",queries.paymentMethods(false));
    }

    @Transactional
    public Map<String,Object> configure(String authorization,C2cAdminRequest request,byte[] body,C2cConfigurationChange input) {
        var administrator=access.write(authorization,request,body);configuration.lockExclusive();var current=markets.configuration();
        if(current.revision()!=input.revision()) throw C2cProblem.conflict("C2C 配置已变更，请刷新");
        if(input.enabled() && !current.enabled() && reconcile().status()!=C2cReconciliation.Status.MATCHED)
            throw C2cProblem.conflict("资金尚未核对一致，请先检查待续办或异常资金意图");
        configuration.update(input.enabled(),input.maxActiveAds(),administrator.userId());
        access.audit(administrator,request,body);return Map.of("enabled",input.enabled(),"maxActiveAds",input.maxActiveAds(),
                "revision",Long.toString(current.revision()+1));
    }

    @Transactional
    public Map<String,Object> market(String authorization,C2cAdminRequest request,byte[] body,C2cMarketChange input) {
        var administrator=access.write(authorization,request,body);configuration.lockExclusive();
        var asset=assets.byAccountingCode(input.asset()).orElseThrow(() -> new IllegalArgumentException("资产尚未配置"));
        long scale=asset.scaleUnits();C2cMoney.quantityUnits("1",scale);
        if(input.enabled() && (!asset.listed() || !asset.tradingEnabled())) throw new IllegalArgumentException("资产未开放交易");
        UUID id=input.marketId()==null?UUID.randomUUID():input.marketId();
        var desired=new C2cMarket(id,input.asset(),scale,input.fiat(),input.fiatDecimals(),input.enabled(),input.minFiat(),input.maxFiat(),
                input.minPrice(),input.maxPrice(),input.buyerFeePpm(),input.paymentWindowSeconds(),input.maxOpenOrders(),input.requireKyc(),input.vipMinCompleted(),input.revision());
        var existing=marketRows.find(id,false);
        if(existing.isEmpty()) {
            if(input.revision()!=0) throw C2cProblem.conflict("新市场版本无效");
            marketRows.insert(desired);
        } else {
            var current=existing.get();
            if(current.revision()!=input.revision()) throw C2cProblem.conflict("市场版本已变更，请刷新");
            if(!current.asset().equals(input.asset()) || !current.fiat().equals(input.fiat()) || current.fiatDecimals()!=input.fiatDecimals())
                throw C2cProblem.conflict("资产、法币和法币精度不能修改，请新建市场");
            // 先等正在下单的事务结束并暂停广告，原订单继续按自己的价格、费用、付款和时限快照履行。
            ads.pauseByMarket(id);marketRows.update(desired);
        }
        access.audit(administrator,request,body);return C2cViews.market(markets.market(id,false));
    }

    @Transactional
    public Map<String,Object> method(String authorization,C2cAdminRequest request,byte[] body,C2cPaymentMethod input) {
        var administrator=access.write(authorization,request,body);configuration.lockExclusive();
        var existing=methods.find(input.code());
        if(existing.isEmpty()) {
            if(input.revision()!=0) throw C2cProblem.conflict("新收款方式版本无效");
            methods.insert(input);
        } else {
            var current=existing.get();
            if(current.revision()!=input.revision()) throw C2cProblem.conflict("收款方式已变更，请刷新");
            ads.pauseByMethod(input.code());
            if((input.requiresBankName() && !current.requiresBankName()) || (!input.supportsQr() && current.supportsQr()))
                accounts.requireReviewByMethod(input.code());
            methods.update(input);
        }
        access.audit(administrator,request,body);
        return C2cViews.paymentMethod(methods.find(input.code()).orElseThrow());
    }

    public Map<String,Object> reconciliation(String authorization) {access.read(authorization,false);return C2cViews.reconciliation(reconcile());}
    private C2cReconciliation reconcile() {
        long pending=funds.pendingCount();
        if(pending>0) return new C2cReconciliation(C2cReconciliation.Status.PENDING,pending,0,null,List.of());
        long failed=funds.failedSettlementCount();
        if(failed>0) return new C2cReconciliation(C2cReconciliation.Status.REVIEW_REQUIRED,0,failed,null,List.of());
        if(!products.enabled().contains(ProductLine.SPOT)) return unavailable("现货资金查询暂不可用");
        var config=markets.configuration();var accountQueries=products.service(ProductLine.SPOT,AccountService.class);
        Map<String,BalanceResponse> escrow=null,fees=null;
        // 两个账户均续租并请求实时同步，冷视图未就绪不能按零放行。
        try {escrow=balances(accountQueries.balances(config.escrowUserId()).balances());}
        catch(com.surprising.realtime.api.exception.ReadViewUnavailableException unavailable) { /* 仍请求手续费账户 */ }
        try {fees=balances(accountQueries.balances(config.feeUserId()).balances());}
        catch(com.surprising.realtime.api.exception.ReadViewUnavailableException unavailable) { /* 未就绪不能放行 */ }
        if(escrow==null || fees==null) return unavailable("资金查询视图暂不可用，请稍后核对");
        var expected=new HashMap<String,C2cFundTotals>();
        for(var row:funds.confirmedTotals(config.escrowUserId(),config.feeUserId())) expected.put(row.asset(),row);
        var allAssets=new TreeSet<String>();allAssets.addAll(expected.keySet());allAssets.addAll(escrow.keySet());allAssets.addAll(fees.keySet());
        var rows=new ArrayList<C2cReconciliation.Asset>();boolean all=true;
        for(String asset:allAssets) {
            var wanted=expected.get(asset);
            var wantedEscrow=wanted==null?BigDecimal.ZERO:wanted.escrow();var wantedFees=wanted==null?BigDecimal.ZERO:wanted.fees();
            var e=escrow.get(asset);var f=fees.get(asset);
            long escrowAvailable=e==null?0:e.availableUnits(),feeAvailable=f==null?0:f.availableUnits();
            long escrowLocked=e==null?0:e.lockedUnits(),feeLocked=f==null?0:f.lockedUnits();
            boolean match=escrowLocked==0 && feeLocked==0 && wantedEscrow.signum()>=0 && wantedFees.signum()>=0
                    && wantedEscrow.compareTo(BigDecimal.valueOf(escrowAvailable))==0 && wantedFees.compareTo(BigDecimal.valueOf(feeAvailable))==0;
            all &= match;
            rows.add(new C2cReconciliation.Asset(asset,wantedEscrow,escrowAvailable,escrowLocked,wantedFees,feeAvailable,feeLocked,match));
        }
        return new C2cReconciliation(all?C2cReconciliation.Status.MATCHED:C2cReconciliation.Status.MISMATCH,0,0,null,rows);
    }
    private static C2cReconciliation unavailable(String reason) {
        return new C2cReconciliation(C2cReconciliation.Status.UNAVAILABLE,0,0,reason,List.of());
    }
    private static Map<String,BalanceResponse> balances(List<BalanceResponse> balances) {
        var result=new HashMap<String,BalanceResponse>();
        for(var balance:balances) if(result.putIfAbsent(balance.asset(),balance)!=null) throw new IllegalStateException("资金查询返回重复资产");
        return result;
    }
    public static long version(String value,boolean zero) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("请提供十进制版本字符串");
        try {long version=Long.parseLong(value);if(!zero && version==0) throw new IllegalArgumentException("请提供当前版本");return version;}
        catch(NumberFormatException failure) {throw new IllegalArgumentException("版本超出范围");}
    }
    public static void reason(String value) {
        if(value==null || value.trim().length()<5 || value.length()>1000) throw new IllegalArgumentException("请填写至少 5 字、最多 1000 字的管理原因");
    }
}
