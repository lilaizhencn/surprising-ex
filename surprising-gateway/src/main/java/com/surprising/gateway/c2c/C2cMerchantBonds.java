package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保证金申请、固定原额退款及提交后状态确认；Core I/O 继续由 C2cSettlement 续办。 */
@Service
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cMerchantBonds {
    public record Deposit(UUID requestId,String policyRevision,C2cVerification verification) { }
    public record Policy(String revision,String asset,String amount,String reason) { }
    private final JdbcTemplate jdbc;
    private final C2cFundsRepository funds;
    private final C2cMarketService markets;
    private final C2cAccess access;
    private final C2cAdminAccess admin;
    private final GatewayProductServices products;

    public C2cMerchantBonds(JdbcTemplate jdbc,C2cFundsRepository funds,C2cMarketService markets,C2cAccess access,
                             C2cAdminAccess admin,GatewayProductServices products) {
        this.jdbc=jdbc;this.funds=funds;this.markets=markets;this.access=access;this.admin=admin;this.products=products;
    }

    Map<String,Object> policy() {
        return jdbc.query("SELECT p.*,a.scale_units FROM c2c_merchant_bond_policy p JOIN assets a USING(asset)",(r,n)->
                Map.<String,Object>of("configured",true,"asset",r.getString("asset"),"amountUnits",Long.toString(r.getLong("amount_units")),
                        "amount",C2cViews.quantity(r.getLong("amount_units"),r.getLong("scale_units")),
                        "revision",Long.toString(r.getLong("revision")))).stream().findFirst()
                .orElse(Map.of("configured",false,"revision","0"));
    }

    @Transactional
    Map<String,Object> configure(String authorization,C2cAdminAccess.Request request,byte[] body,Policy input) {
        var actor=admin.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        jdbc.queryForList("SELECT singleton FROM c2c_configuration FOR UPDATE");
        var current=policy();
        if(!current.get("revision").equals(input.revision())) throw new IllegalStateException("质押配置已变更，请刷新");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds WHERE status NOT IN ('RETURNED','FAILED'))",Boolean.class)))
            throw new IllegalStateException("存在未退还质押，不能改变质押币种或金额");
        var scales=jdbc.query("SELECT scale_units FROM assets WHERE asset=? AND listed AND trading_enabled",(r,n)->r.getLong(1),input.asset());
        if(scales.isEmpty()) throw new IllegalArgumentException("质押资产尚未开放");
        long units=C2cMoney.quantityUnits(input.amount(),scales.getFirst());
        if(units<=0) throw new IllegalArgumentException("质押金额必须大于零");
        jdbc.update("""
                INSERT INTO c2c_merchant_bond_policy(singleton,asset,amount_units,updated_by) VALUES(true,?,?,?)
                ON CONFLICT(singleton) DO UPDATE SET asset=EXCLUDED.asset,amount_units=EXCLUDED.amount_units,
                    revision=c2c_merchant_bond_policy.revision+1,updated_by=EXCLUDED.updated_by,updated_at=now()
                """,input.asset(),units,actor.userId());
        admin.audit(actor,request,body);return policy();
    }

    Map<String,Object> mine(long userId) { return mine(userId,null); }
    Map<String,Object> mine(long userId,UUID requestId) {
        var result=new LinkedHashMap<String,Object>();result.put("policy",policy());
        result.put("bond",jdbc.query("""
                SELECT b.*,a.scale_units FROM c2c_merchant_bonds b JOIN assets a USING(asset)
                WHERE user_id=? AND (CAST(? AS uuid) IS NULL OR request_id=?) ORDER BY created_at DESC,bond_id DESC LIMIT 1
                """,(r,n)->view(r),userId,requestId,requestId).stream().findFirst().orElse(null));
        return result;
    }

    @Transactional
    Map<String,Object> deposit(JwtPrincipal user,String authorization,Deposit input) {
        if(input.requestId()==null) throw new IllegalArgumentException("请保留原质押请求号");
        lockUser(user.userId());
        var existing=jdbc.queryForList("SELECT bond_id FROM c2c_merchant_bonds WHERE user_id=? AND request_id=?",user.userId(),input.requestId());
        if(!existing.isEmpty()) return mine(user.userId(),input.requestId());
        products.requireEnabled(ProductLine.SPOT);
        jdbc.queryForList("SELECT singleton FROM c2c_configuration FOR SHARE");
        var policy=policy();
        if(!Boolean.TRUE.equals(policy.get("configured")) || !policy.get("revision").equals(input.policyRevision()))
            throw new IllegalStateException("请刷新当前质押规则");
        var applications=jdbc.queryForList("SELECT status FROM c2c_merchants WHERE user_id=?",user.userId());
        if(applications.isEmpty() || !List.of("PENDING","APPROVED").contains(applications.getFirst().get("status")))
            throw new IllegalStateException("请先提交有效商家申请");
        access.requireCounterparty(user.userId(),markets.markets(true).stream().filter(m->m.asset().equals(policy.get("asset")))
                .findFirst().orElseThrow(()->new IllegalStateException("请先配置可用的质押资产市场")));
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds WHERE user_id=? AND status NOT IN ('RETURNED','FAILED'))",Boolean.class,user.userId())))
            throw new IllegalStateException("已有质押或在途请求，请查询原记录");
        var codes=C2cVerification.orEmpty(input.verification());access.verifyFunds(user,authorization,codes.emailCode(),codes.totpCode());
        UUID id=UUID.randomUUID();String asset=(String)policy.get("asset");long units=Long.parseLong((String)policy.get("amountUnits"));
        jdbc.update("INSERT INTO c2c_merchant_bonds(bond_id,user_id,request_id,asset,amount_units,status) VALUES(?,?,?,?,?,'RESERVING')",
                id,user.userId(),input.requestId(),asset,units);
        funds.createOrGet("MERCHANT",id,"MERCHANT_BOND",user.userId(),markets.configuration().escrowUserId(),asset,units);
        return mine(user.userId(),input.requestId());
    }

    @Transactional
    Map<String,Object> withdraw(JwtPrincipal user,UUID id) {
        lockUser(user.userId());
        var rows=jdbc.queryForList("SELECT * FROM c2c_merchant_bonds WHERE bond_id=? AND user_id=? FOR UPDATE",id,user.userId());
        if(rows.isEmpty()) throw C2cProblem.forbidden("只能退还本人原质押");
        var bond=rows.getFirst();String state=(String)bond.get("status");
        if(List.of("REFUND_PENDING","RETURNED").contains(state)) return mine(user.userId(),(UUID)bond.get("request_id"));
        if(!state.equals("LOCKED")) throw new IllegalStateException("质押尚未确认，请查询原记录");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchants WHERE user_id=? AND status='SUSPENDED')",Boolean.class,user.userId())))
            throw new IllegalStateException("商家已被风控停用，请联系客服处理质押");
        boolean busy=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_ads WHERE maker_user_id=? AND status NOT IN ('CLOSED','REJECTED'))
                OR EXISTS(SELECT 1 FROM c2c_orders WHERE (buyer_user_id=? OR seller_user_id=?)
                    AND status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED'))
                OR EXISTS(SELECT 1 FROM c2c_fund_intents WHERE (source_user_id=? OR target_user_id=?)
                    AND status NOT IN ('APPLIED','REJECTED'))
                """,Boolean.class,user.userId(),user.userId(),user.userId(),user.userId(),user.userId()));
        if(busy) throw new IllegalStateException("请先关闭全部广告并结清订单、申诉与在途资金");
        jdbc.update("UPDATE c2c_merchants SET status='WITHDRAWN',revision=revision+1,updated_at=now() WHERE user_id=?",user.userId());
        funds.createOrGet("MERCHANT",id,"MERCHANT_BOND_REFUND",markets.configuration().escrowUserId(),user.userId(),
                (String)bond.get("asset"),((Number)bond.get("amount_units")).longValue());
        jdbc.update("UPDATE c2c_merchant_bonds SET status='REFUND_PENDING',updated_at=now() WHERE bond_id=?",id);
        return mine(user.userId(),(UUID)bond.get("request_id"));
    }

    @Scheduled(fixedDelayString="${surprising.c2c.settlement-delay-ms:1000}",scheduler="c2cSettlementScheduler")
    public void confirm() {
        // 资金意图先确认提交；每轮有界更新，重启后仍由同一持久意图确认。
        jdbc.update("""
                UPDATE c2c_merchant_bonds b SET status=CASE WHEN b.status='RESERVING' THEN
                    CASE WHEN f.status='APPLIED' THEN 'LOCKED' ELSE 'FAILED' END ELSE 'RETURNED' END,updated_at=now()
                FROM c2c_fund_intents f WHERE b.bond_id=f.owner_id AND f.owner_type='MERCHANT'
                AND ((b.status='RESERVING' AND f.purpose='MERCHANT_BOND' AND f.status IN ('APPLIED','REJECTED'))
                    OR (b.status='REFUND_PENDING' AND f.purpose='MERCHANT_BOND_REFUND' AND f.status='APPLIED'))
                AND b.bond_id IN(SELECT bond_id FROM c2c_merchant_bonds WHERE status IN ('RESERVING','REFUND_PENDING')
                    ORDER BY updated_at,bond_id LIMIT 32)
                """);
    }

    private void lockUser(long userId) {
        if(jdbc.queryForList("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",userId).isEmpty())
            throw new IllegalStateException("账户不存在");
    }
    private Map<String,Object> view(java.sql.ResultSet r) throws java.sql.SQLException {
        return Map.of("bondId",r.getObject("bond_id").toString(),"requestId",r.getObject("request_id").toString(),
                "asset",r.getString("asset"),"amountUnits",Long.toString(r.getLong("amount_units")),
                "amount",C2cViews.quantity(r.getLong("amount_units"),r.getLong("scale_units")),"status",r.getString("status"));
    }
}
