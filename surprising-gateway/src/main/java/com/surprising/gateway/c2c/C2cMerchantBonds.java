package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.utils.C2cMoney;

import com.surprising.asset.repository.AssetRepository;
import com.surprising.gateway.c2c.model.C2cBondDeposit;
import com.surprising.gateway.c2c.model.C2cBondPolicy;
import com.surprising.gateway.c2c.model.C2cBondPolicyChange;
import com.surprising.gateway.c2c.model.C2cMerchantBond;
import com.surprising.gateway.c2c.repository.C2cConfigurationRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantBondPolicyRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantBondRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantRepository;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 质押申请、原额退款及资金确认后的终态；Core I/O继续由原资金意图续办。 */
@Service
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cMerchantBonds {
    private final C2cFundsRepository funds;
    private final C2cMarketService markets;
    private final C2cAccess access;
    private final C2cAdminAccess admin;
    private final GatewayProductServices products;
    private final GatewayUserRepository users;
    private final AssetRepository assets;
    private final C2cConfigurationRepository configuration;
    private final C2cMerchantBondPolicyRepository policies;
    private final C2cMerchantBondRepository bonds;
    private final C2cMerchantRepository merchants;
    private final C2cAdsRepository ads;
    private final C2cOrdersRepository orders;

    public C2cMerchantBonds(C2cFundsRepository funds,C2cMarketService markets,C2cAccess access,C2cAdminAccess admin,
                             GatewayProductServices products,GatewayUserRepository users,AssetRepository assets,
                             C2cConfigurationRepository configuration,C2cMerchantBondPolicyRepository policies,
                             C2cMerchantBondRepository bonds,C2cMerchantRepository merchants,C2cAdsRepository ads,C2cOrdersRepository orders) {
        this.funds=funds;this.markets=markets;this.access=access;this.admin=admin;this.products=products;this.users=users;
        this.assets=assets;this.configuration=configuration;this.policies=policies;this.bonds=bonds;
        this.merchants=merchants;this.ads=ads;this.orders=orders;
    }

    Map<String,Object> policy() {
        return policies.find().map(this::policyView).orElse(Map.of("configured",false,"revision","0"));
    }
    private Map<String,Object> policyView(C2cBondPolicy policy) {
        return Map.of("configured",true,"asset",policy.asset(),"amountUnits",Long.toString(policy.amountUnits()),
                "amount",C2cViews.quantity(policy.amountUnits(),policy.assetScale()),"revision",Long.toString(policy.revision()));
    }

    @Transactional
    Map<String,Object> configure(String authorization,C2cAdminAccess.Request request,byte[] body,C2cBondPolicyChange input) {
        var actor=admin.write(authorization,request,body);configuration.lockExclusive();
        long revision=policies.find().map(C2cBondPolicy::revision).orElse(0L);
        if(revision!=input.revision()) throw new IllegalStateException("质押配置已变更，请刷新");
        if(bonds.anyOutstanding()) throw new IllegalStateException("存在未退还质押，不能改变质押币种或金额");
        var asset=assets.byAccountingCode(input.asset()).filter(value -> value.listed() && value.tradingEnabled())
                .orElseThrow(() -> new IllegalArgumentException("质押资产尚未开放"));
        long units=C2cMoney.quantityUnits(input.amount().toPlainString(),asset.scaleUnits());
        policies.save(input.asset(),units,actor.userId());admin.audit(actor,request,body);return policy();
    }

    Map<String,Object> mine(long userId) {return mine(userId,null);}
    Map<String,Object> mine(long userId,UUID requestId) {
        var result=new LinkedHashMap<String,Object>();result.put("policy",policy());
        result.put("bond",bonds.latest(userId,requestId).map(this::view).orElse(null));return result;
    }

    @Transactional
    Map<String,Object> deposit(JwtPrincipal user,String authorization,C2cBondDeposit input) {
        lockUser(user.userId());
        if(bonds.requestExists(user.userId(),input.requestId())) return mine(user.userId(),input.requestId());
        products.requireEnabled(ProductLine.SPOT);configuration.get(true);
        var policy=policies.find().filter(value -> value.revision()==input.policyRevision())
                .orElseThrow(() -> new IllegalStateException("请刷新当前质押规则"));
        if(merchants.find(user.userId(),false).filter(value -> List.of("PENDING","APPROVED").contains(value.status())).isEmpty())
            throw new IllegalStateException("请先提交有效商家申请");
        access.requireCounterparty(user.userId(),markets.markets(true).stream().filter(value -> value.asset().equals(policy.asset()))
                .findFirst().orElseThrow(() -> new IllegalStateException("请先配置可用的质押资产市场")));
        if(bonds.outstanding(user.userId())) throw new IllegalStateException("已有质押或在途请求，请查询原记录");
        access.verifyFunds(user,authorization,input.emailCode(),input.totpCode());
        UUID id=UUID.randomUUID();bonds.insert(id,user.userId(),input.requestId(),policy.asset(),policy.amountUnits());
        funds.createOrGet("MERCHANT",id,"MERCHANT_BOND",user.userId(),markets.configuration().escrowUserId(),policy.asset(),policy.amountUnits());
        return mine(user.userId(),input.requestId());
    }

    @Transactional
    Map<String,Object> withdraw(JwtPrincipal user,UUID id) {
        lockUser(user.userId());
        var bond=bonds.findOwned(user.userId(),id,true).orElseThrow(() -> C2cProblem.forbidden("只能退还本人原质押"));
        if(List.of("REFUND_PENDING","RETURNED").contains(bond.status())) return mine(user.userId(),bond.requestId());
        if(!bond.status().equals("LOCKED")) throw new IllegalStateException("质押尚未确认，请查询原记录");
        if(merchants.find(user.userId(),false).filter(value -> value.status().equals("SUSPENDED")).isPresent())
            throw new IllegalStateException("商家已被风控停用，请联系客服处理质押");
        // 用户锁阻止本人新建广告/订单/质押；广告全部终止后，其他用户也无法再从原广告创建订单。
        // 分别读取单表事实，不以某次查询结果缓存或推断待确认资金已完成。
        if(ads.activeCount(user.userId())>0 || orders.hasOpenOrders(user.userId()) || funds.hasPendingForUser(user.userId()))
            throw new IllegalStateException("请先关闭全部广告并结清订单、申诉与在途资金");
        merchants.withdraw(user.userId());
        funds.createOrGet("MERCHANT",id,"MERCHANT_BOND_REFUND",markets.configuration().escrowUserId(),user.userId(),bond.asset(),bond.amountUnits());
        bonds.updateStatus(id,"REFUND_PENDING");return mine(user.userId(),bond.requestId());
    }

    @Transactional
    public void confirm() {
        for(var confirmation:bonds.lockConfirmable(32)) {
            var bond=confirmation.bond();
            String next;
            if(bond.status().equals("RESERVING")) next=confirmation.intentStatus().equals("APPLIED")?"LOCKED":"FAILED";
            else next="RETURNED";
            bonds.updateStatus(bond.id(),next);
        }
    }
    private void lockUser(long userId) {
        if(!users.lockForNoKeyUpdate(userId)) throw new IllegalStateException("账户不存在");
    }
    private Map<String,Object> view(C2cMerchantBond bond) {
        return Map.of("bondId",bond.id().toString(),"requestId",bond.requestId().toString(),"asset",bond.asset(),
                "amountUnits",Long.toString(bond.amountUnits()),"amount",C2cViews.quantity(bond.amountUnits(),bond.assetScale()),"status",bond.status());
    }
}
