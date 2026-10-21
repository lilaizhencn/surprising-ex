package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.service.C2cQueryService;

import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.provider.service.AccountRequestService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 市场配置变更与审批、审计共同提交；开放市场前核对内部账户与资金意图。 */
@Service
public class C2cAdminMarkets {
    public record ConfigurationInput(String revision,boolean enabled,int maxActiveAds,String reason) { }
    public record MarketInput(UUID marketId,String revision,String asset,String fiat,int fiatDecimals,boolean enabled,
                              String minFiat,String maxFiat,String minPrice,String maxPrice,int buyerFeePpm,
                              int paymentWindowSeconds,int maxOpenOrders,boolean requireKyc,int vipMinCompleted,String reason) { }
    public record MethodInput(String code,String revision,String label,boolean enabled,List<String> currencies,
                              boolean requiresBankName,boolean supportsQr,String reason) { }
    private final JdbcTemplate jdbc;
    private final C2cMarketService markets;
    private final C2cQueryService queries;
    private final C2cAdminAccess access;
    private final GatewayProductServices products;

    public C2cAdminMarkets(JdbcTemplate jdbc,C2cMarketService markets,C2cQueryService queries,C2cAdminAccess access,GatewayProductServices products) {
        this.jdbc=jdbc;this.markets=markets;this.queries=queries;this.access=access;this.products=products;
    }
    Map<String,Object> configuration(String authorization) {
        access.read(authorization,false);var config=markets.configuration();
        return Map.of("enabled",config.enabled(),"maxActiveAds",config.maxActiveAds(),"revision",Long.toString(config.revision()),
                "markets",markets.markets(false).stream().map(C2cViews::market).toList(),"paymentMethods",queries.paymentMethods(false));
    }

    @Transactional
    Map<String,Object> configure(String authorization,C2cAdminAccess.Request request,byte[] body,ConfigurationInput input) {
        var administrator=access.write(authorization,request,body);reason(input.reason());lockConfiguration();var current=markets.configuration();
        if(current.revision()!=version(input.revision(),false)) throw new IllegalStateException("C2C 配置已变更，请刷新");
        if(input.maxActiveAds()<1 || input.maxActiveAds()>100) throw new IllegalArgumentException("广告限额应为 1 至 100");
        if(input.enabled() && !current.enabled()) {
            var reconciliation=reconciliation();
            if(!"MATCHED".equals(reconciliation.get("status"))) throw new IllegalStateException("资金尚未核对一致，请先检查待续办或异常资金意图");
        }
        jdbc.update("UPDATE c2c_configuration SET enabled=?,max_active_ads=?,revision=revision+1,updated_by=?,updated_at=now() WHERE singleton",
                input.enabled(),input.maxActiveAds(),administrator.userId());
        access.audit(administrator,request,body);return Map.of("enabled",input.enabled(),"maxActiveAds",input.maxActiveAds(),
                "revision",Long.toString(current.revision()+1));
    }

    @Transactional
    Map<String,Object> market(String authorization,C2cAdminAccess.Request request,byte[] body,MarketInput input) {
        var administrator=access.write(authorization,request,body);reason(input.reason());lockConfiguration();
        if(input.asset()==null || !input.asset().matches("[A-Z0-9]{2,20}") || input.fiat()==null || !input.fiat().matches("[A-Z]{3,8}")
                || input.fiatDecimals()<0 || input.fiatDecimals()>8 || input.buyerFeePpm()<0 || input.buyerFeePpm()>=1000000
                || input.paymentWindowSeconds()<60 || input.paymentWindowSeconds()>3600 || input.maxOpenOrders()<1 || input.maxOpenOrders()>20
                || input.vipMinCompleted()<0 || input.vipMinCompleted()>1000000) throw new IllegalArgumentException("市场准入或费率配置无效");
        var assets=jdbc.queryForList("SELECT scale_units,listed,trading_enabled FROM assets WHERE asset=?",input.asset());
        if(assets.isEmpty()) throw new IllegalArgumentException("资产尚未配置");
        long scale=((Number)assets.getFirst().get("scale_units")).longValue();C2cMoney.quantityUnits("1",scale);
        if(input.enabled() && (!Boolean.TRUE.equals(assets.getFirst().get("listed")) || !Boolean.TRUE.equals(assets.getFirst().get("trading_enabled"))))
            throw new IllegalArgumentException("资产未开放交易");
        var lowFiat=C2cMoney.decimal(input.minFiat());var highFiat=C2cMoney.decimal(input.maxFiat());
        var lowPrice=C2cMoney.decimal(input.minPrice());var highPrice=C2cMoney.decimal(input.maxPrice());
        if(lowFiat.compareTo(highFiat)>0 || lowFiat.stripTrailingZeros().scale()>input.fiatDecimals()
                || highFiat.stripTrailingZeros().scale()>input.fiatDecimals() || integerDigits(highFiat)>30
                || lowPrice.compareTo(highPrice)>0 || highPrice.scale()>18 || lowPrice.scale()>18 || integerDigits(highPrice)>20)
            throw new IllegalArgumentException("价格或法币限额范围、精度无效");
        UUID id=input.marketId()==null?UUID.randomUUID():input.marketId();long revision=version(input.revision(),true);
        var existing=jdbc.queryForList("SELECT revision FROM c2c_markets WHERE market_id=?",id);
        if(existing.isEmpty()) {
            if(revision!=0) throw new IllegalStateException("新市场版本无效");
            jdbc.update("""
                    INSERT INTO c2c_markets(market_id,asset,fiat,fiat_decimals,enabled,min_fiat,max_fiat,min_price,max_price,buyer_fee_ppm,
                        payment_window_seconds,max_open_orders,require_kyc,vip_min_completed)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,input.asset(),input.fiat(),input.fiatDecimals(),input.enabled(),lowFiat,highFiat,lowPrice,highPrice,
                    input.buyerFeePpm(),input.paymentWindowSeconds(),input.maxOpenOrders(),input.requireKyc(),input.vipMinCompleted());
        } else {
            var current=markets.market(id,false);
            if(current.revision()!=revision) throw new IllegalStateException("市场版本已变更，请刷新");
            if(!current.asset().equals(input.asset()) || !current.fiat().equals(input.fiat()) || current.fiatDecimals()!=input.fiatDecimals())
                throw new IllegalStateException("资产、法币和法币精度不能修改，请新建市场");
            // 等待正在下单的事务结束，再暂停全部广告。原订单仍按自己的收款、费用和时间快照履行。
            jdbc.update("UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now() WHERE market_id=? AND status='ACTIVE'",id);
            jdbc.update("""
                    UPDATE c2c_markets SET enabled=?,min_fiat=?,max_fiat=?,min_price=?,max_price=?,buyer_fee_ppm=?,payment_window_seconds=?,
                        max_open_orders=?,require_kyc=?,vip_min_completed=?,revision=revision+1,updated_at=now() WHERE market_id=?
                    """,input.enabled(),lowFiat,highFiat,lowPrice,highPrice,input.buyerFeePpm(),input.paymentWindowSeconds(),
                    input.maxOpenOrders(),input.requireKyc(),input.vipMinCompleted(),id);
        }
        access.audit(administrator,request,body);return C2cViews.market(markets.market(id,false));
    }

    @Transactional
    Map<String,Object> method(String authorization,C2cAdminAccess.Request request,byte[] body,MethodInput input) {
        var administrator=access.write(authorization,request,body);reason(input.reason());lockConfiguration();
        if(input.code()==null || !input.code().matches("[A-Z][A-Z0-9_]{0,31}") || input.label()==null || input.label().isBlank()
                || input.label().length()>80 || input.currencies()==null || input.currencies().size()>50
                || input.currencies().stream().anyMatch(value->value==null || !value.matches("[A-Z]{3,8}")))
            throw new IllegalArgumentException("收款方式资料无效");
        if(input.enabled() && input.currencies().isEmpty()) throw new IllegalArgumentException("请配置适用法币");
        var currencies=input.currencies().stream().distinct().sorted().toList();
        String array="{"+String.join(",",currencies)+"}";long revision=version(input.revision(),true);
        var existing=jdbc.queryForList("SELECT * FROM c2c_payment_methods WHERE code=?",input.code());
        if(existing.isEmpty()) {
            if(revision!=0) throw new IllegalStateException("新收款方式版本无效");
            jdbc.update("""
                    INSERT INTO c2c_payment_methods(code,label,enabled,currencies,requires_bank_name,supports_qr) VALUES(?,?,?,?::text[],?,?)
                    """,input.code(),input.label().trim(),input.enabled(),array,input.requiresBankName(),input.supportsQr());
        } else {
            if(((Number)existing.getFirst().get("revision")).longValue()!=revision) throw new IllegalStateException("收款方式已变更，请刷新");
            jdbc.update("UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now() WHERE status='ACTIVE' AND ?=ANY(method_codes)",input.code());
            var old=existing.getFirst();
            if((input.requiresBankName() && !Boolean.TRUE.equals(old.get("requires_bank_name")))
                    || (!input.supportsQr() && Boolean.TRUE.equals(old.get("supports_qr"))))
                jdbc.update("""
                        UPDATE c2c_payment_accounts SET status='PENDING',revision=revision+1,reviewed_by=NULL,reviewed_at=NULL,
                            review_reason=NULL,updated_at=now() WHERE method_code=? AND status='APPROVED'
                        """,input.code());
            jdbc.update("""
                    UPDATE c2c_payment_methods SET label=?,enabled=?,currencies=?::text[],requires_bank_name=?,supports_qr=?,revision=revision+1 WHERE code=?
                    """,input.label().trim(),input.enabled(),array,input.requiresBankName(),input.supportsQr(),input.code());
        }
        access.audit(administrator,request,body);
        return queries.paymentMethods(false).stream().filter(row->input.code().equals(row.get("code"))).findFirst().orElseThrow();
    }

    Map<String,Object> reconciliation(String authorization) {access.read(authorization,false);return reconciliation();}
    private Map<String,Object> reconciliation() {
        long pending=jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE status NOT IN ('APPLIED','REJECTED')",Long.class);
        if(pending>0) return Map.of("status","PENDING","pendingIntentCount",Long.toString(pending),"assets",List.of());
        long failed=jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE status='REJECTED' AND purpose NOT IN ('AD_RESERVE','ORDER_RESERVE','MERCHANT_BOND')",Long.class);
        if(failed>0) return Map.of("status","REVIEW_REQUIRED","failedSettlementCount",Long.toString(failed),"assets",List.of());
        if(!products.enabled().contains(ProductLine.SPOT)) return Map.of("status","UNAVAILABLE","reason","现货资金查询暂不可用","assets",List.of());
        var config=markets.configuration();var accountQueries=products.service(ProductLine.SPOT,AccountRequestService.class);
        Map<String,BalanceResponse> escrow=null,fees=null;
        // 两个账户都续租并请求 realtime 同步；冷视图不能因首个异常一直阻塞另一个账户。
        try { escrow=balances(accountQueries.balances(config.escrowUserId()).balances()); }
        catch(org.springframework.web.server.ResponseStatusException unavailable) { /* 仍请求手续费账户 */ }
        try { fees=balances(accountQueries.balances(config.feeUserId()).balances()); }
        catch(org.springframework.web.server.ResponseStatusException unavailable) { /* 未就绪不能按零放行 */ }
        if(escrow==null || fees==null)
            return Map.of("status","UNAVAILABLE","reason","资金查询视图暂不可用，请稍后核对","assets",List.of());
        var expected=jdbc.query("""
                SELECT asset,COALESCE(sum(CASE WHEN target_user_id=? THEN amount_units::numeric WHEN source_user_id=? THEN -amount_units::numeric ELSE 0 END),0) AS escrow,
                    COALESCE(sum(CASE WHEN target_user_id=? THEN amount_units::numeric WHEN source_user_id=? THEN -amount_units::numeric ELSE 0 END),0) AS fees
                FROM c2c_fund_intents WHERE status='APPLIED' GROUP BY asset
                """,(r,n)->Map.<String,Object>of("asset",r.getString("asset"),"escrow",r.getBigDecimal("escrow"),"fees",r.getBigDecimal("fees")),
                config.escrowUserId(),config.escrowUserId(),config.feeUserId(),config.feeUserId());
        var byAsset=new HashMap<String,Map<String,Object>>();
        for(var row:expected) byAsset.put((String)row.get("asset"),row);
        var assets=new TreeSet<String>();assets.addAll(byAsset.keySet());assets.addAll(escrow.keySet());assets.addAll(fees.keySet());
        var rows=new ArrayList<Map<String,Object>>();boolean all=true;
        for(String asset:assets) {
            var expectedRow=byAsset.get(asset);
            var wantedEscrow=expectedRow==null?BigDecimal.ZERO:(BigDecimal)expectedRow.get("escrow");
            var wantedFees=expectedRow==null?BigDecimal.ZERO:(BigDecimal)expectedRow.get("fees");
            var e=escrow.get(asset);var f=fees.get(asset);
            long escrowAvailable=e==null?0:e.availableUnits(),feeAvailable=f==null?0:f.availableUnits();
            long escrowLocked=e==null?0:e.lockedUnits(),feeLocked=f==null?0:f.lockedUnits();
            boolean match=escrowLocked==0 && feeLocked==0 && wantedEscrow.signum()>=0 && wantedFees.signum()>=0
                    && wantedEscrow.compareTo(BigDecimal.valueOf(escrowAvailable))==0 && wantedFees.compareTo(BigDecimal.valueOf(feeAvailable))==0;
            all &= match;
            rows.add(Map.of("asset",asset,"expectedEscrowUnits",wantedEscrow.toBigIntegerExact().toString(),"actualEscrowUnits",Long.toString(escrowAvailable),
                    "escrowLockedUnits",Long.toString(escrowLocked),"expectedFeeUnits",wantedFees.toBigIntegerExact().toString(),
                    "actualFeeUnits",Long.toString(feeAvailable),"feeLockedUnits",Long.toString(feeLocked),"matches",match));
        }
        return Map.of("status",all?"MATCHED":"MISMATCH","pendingIntentCount","0","assets",rows);
    }
    private static Map<String,BalanceResponse> balances(List<BalanceResponse> balances) {
        var result=new HashMap<String,BalanceResponse>();
        for(var balance:balances) if(result.putIfAbsent(balance.asset(),balance)!=null) throw new IllegalStateException("资金查询返回重复资产");
        return result;
    }
    private void lockConfiguration() {jdbc.queryForObject("SELECT singleton FROM c2c_configuration WHERE singleton FOR UPDATE",Boolean.class);}
    static long version(String value,boolean zero) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("请提供十进制版本字符串");
        try {long version=Long.parseLong(value);if(!zero && version==0) throw new IllegalArgumentException("请提供当前版本");return version;}
        catch(NumberFormatException failure) {throw new IllegalArgumentException("版本超出范围");}
    }
    static void reason(String value) {
        if(value==null || value.trim().length()<5 || value.length()>1000) throw new IllegalArgumentException("请填写至少 5 字、最多 1000 字的管理原因");
    }
    private static int integerDigits(BigDecimal value) {return value.precision()-value.scale();}
}
