package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 报价固定交易事实，SQL 锁分配库存；实际托管、放币和退款均等待原 Core 指令。 */
@Service
public class C2cOrders {
    private final C2cOrdersRepository orders;
    private final C2cAdsRepository ads;
    private final C2cMarkets markets;
    private final C2cPaymentAccounts payments;
    private final C2cAccess access;
    private final C2cFundsRepository funds;
    private final JdbcTemplate jdbc;
    private final C2cSubmissions submissions;
    public C2cOrders(C2cOrdersRepository orders,C2cAdsRepository ads,C2cMarkets markets,C2cPaymentAccounts payments,
                      C2cAccess access,C2cFundsRepository funds,JdbcTemplate jdbc,C2cSubmissions submissions) {
        this.submissions=submissions;
        this.orders=orders;this.ads=ads;this.markets=markets;this.payments=payments;this.access=access;this.funds=funds;this.jdbc=jdbc;
    }

    @Transactional
    C2cOrdersRepository.Quote quote(JwtPrincipal user,UUID adId,String amountType,String amount,UUID paymentAccountId) {
        if(!markets.configuration(true).enabled()) throw new IllegalStateException("C2C 暂未开放");
        var ad=ads.get(adId,true);var market=markets.market(ad.marketId(),true);
        requireOffer(user,ad,market);
        if(amountType==null || paymentAccountId==null) throw new IllegalArgumentException("报价与收款方式不完整");
        long quantity=switch(amountType) {
            case "FIAT" -> C2cMoney.unitsForFiat(amount,ad.price(),market.assetScale(),market.fiatDecimals());
            case "CRYPTO" -> C2cMoney.quantityUnits(amount,market.assetScale());
            default -> throw new IllegalArgumentException("请选择按金额或数量报价");
        };
        var fiat=C2cMoney.fiatAmount(quantity,ad.price(),market.assetScale(),market.fiatDecimals());
        requireAmount(ad,market,quantity,fiat);
        var account=paymentAccount(user.userId(),ad,market,paymentAccountId);
        return orders.createQuote(user.userId(),ad,market,quantity,C2cMoney.buyerFee(quantity,market.buyerFeePpm()),fiat,account);
    }

    @Transactional
    C2cOrdersRepository.Order create(JwtPrincipal user,String authorization,String key,UUID quoteId,String email,String totp) {
        C2cAds.requireKey(key);
        if(quoteId==null) throw new IllegalArgumentException("请选择有效报价");
        String hash=C2cAccess.digest(quoteId.toString());
        var receipt=submissions.begin(user.userId(),key,"ORDER_CREATE",hash);
        if(receipt.status().equals("SUCCEEDED")) {
            var accepted = orders.get(receipt.resultId(),false);
            if (accepted.creatorUserId() != user.userId() || !accepted.quoteId().equals(quoteId))
                throw new IllegalStateException("原请求订单归属不匹配");
            return accepted;
        }
        var config=markets.configuration(true);
        if(!config.enabled()) throw new IllegalStateException("C2C 暂未开放");
        // 同一用户的新订单限额不能被多个并发吃单请求同时绕过。
        if(jdbc.query("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",(r,n)->r.getLong(1),user.userId()).isEmpty())
            throw new IllegalStateException("账户不可用");
        var initial=orders.quote(quoteId,false);
        var ad=ads.get(initial.adId(),true);var quote=orders.quote(quoteId,true);
        var market=markets.market(ad.marketId(),true);
        if(quote.userId()!=user.userId()) throw new IllegalStateException("只能使用本人报价");
        if(!quote.expiresAt().isAfter(Instant.now()) || quote.adRevision()!=ad.revision() || quote.marketRevision()!=market.revision())
            throw new IllegalStateException("报价已失效，请重新获取");
        if(orders.byQuote(quoteId)!=null) throw new IllegalStateException("报价已使用，请查看原订单");
        requireOffer(user,ad,market);requireAmount(ad,market,quote.quantityUnits(),quote.fiatAmount());
        var count=jdbc.queryForObject("""
                SELECT count(*) FROM c2c_orders WHERE market_id=? AND (buyer_user_id=? OR seller_user_id=?)
                    AND status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED')
                """,Long.class,market.id(),user.userId(),user.userId());
        if(count!=null && count>=market.maxOpenOrders()) throw new IllegalStateException("请先处理进行中的订单");
        var account=paymentAccount(user.userId(),ad,market,quote.paymentAccountId());
        if(account.revision()!=quote.accountRevision()) throw new IllegalStateException("收款账号已变更，请重新报价");
        payments.details(account); // 不能在资金锁定后才发现收款快照不可用。
        ads.allocate(ad,quote.quantityUnits());
        var order=orders.create(user.userId(),quote,ad,account);
        if(ad.side().equals("BUY")) {
            access.verifyFunds(user,authorization,email,totp);
            funds.createOrGet("ORDER",order.id(),"ORDER_RESERVE",order.sellerUserId(),config.escrowUserId(),market.asset(),order.quantityUnits());
        }
        submissions.succeed(user.userId(),key,order.id());
        return order;
    }

    @Transactional
    C2cOrdersRepository.Order paid(JwtPrincipal user,UUID id,UUID proofId) {
        var order=lockForParticipant(user.userId(),id);
        if(order.buyerUserId()!=user.userId()) throw new IllegalStateException("只有买方可以确认已付款");
        if(order.paidAt()!=null) return order;
        order.status().requirePayment();
        if(order.paymentExpiresAt()==null || !order.paymentExpiresAt().isAfter(Instant.now())) throw new IllegalStateException("付款时间已结束，请勿继续转账");
        if(proofId!=null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_attachments WHERE attachment_id=? AND user_id=? AND order_id=? AND purpose='PROOF' AND status='READY')
                """,Boolean.class,proofId,user.userId(),id))) throw new IllegalArgumentException("付款证明归属不匹配");
        orders.markPaid(order,proofId);
        return orders.get(id,false);
    }

    @Transactional
    C2cOrdersRepository.Order release(JwtPrincipal user,String authorization,UUID id,String email,String totp) {
        var order=lockForParticipant(user.userId(),id);
        if(order.sellerUserId()!=user.userId()) throw new IllegalStateException("只有卖方核对到账后可以放币");
        if(order.status()==C2cOrderStatus.RELEASE_PENDING || order.status()==C2cOrderStatus.COMPLETED) return order;
        order.status().requireRelease();
        var market=markets.market(order.marketId(),false);
        access.requireTrading(user,market);
        access.verifyFunds(user,authorization,email,totp);
        prepareRelease(order,market,user.userId(),"SELLER_CONFIRMED_RECEIPT");
        return orders.get(id,false);
    }

    @Transactional
    C2cOrdersRepository.Order cancel(JwtPrincipal user,UUID id,String reason) {
        var order=lockForParticipant(user.userId(),id);
        if(order.buyerUserId()!=user.userId()) throw new IllegalStateException("卖方不能主动取消，未付款请等待到期或发起申诉");
        if(order.status()==C2cOrderStatus.REFUND_PENDING || order.status()==C2cOrderStatus.CANCELED || order.status()==C2cOrderStatus.EXPIRED) return order;
        order.status().requireCancellation();
        if(reason==null || reason.isBlank() || reason.length()>1000) throw new IllegalArgumentException("请选择或填写取消原因");
        prepareCancellation(order,C2cOrderStatus.CANCELED,reason.trim(),user.userId(),false);
        return orders.get(id,false);
    }

    @Transactional
    void expire(UUID id) {
        var initial=orders.get(id,false);ads.get(initial.adId(),true);var order=orders.get(id,true);
        if(order.status()!=C2cOrderStatus.PAYMENT_PENDING || order.paymentExpiresAt()==null || order.paymentExpiresAt().isAfter(Instant.now())) return;
        prepareCancellation(order,C2cOrderStatus.EXPIRED,"付款窗口已结束",null,false);
    }

    @Transactional
    void finishFunding(UUID id) {
        var initial=orders.get(id,false);var ad=ads.get(initial.adId(),true);var order=orders.get(id,true);
        if(order.status()==C2cOrderStatus.RESERVING) {
            var intent=funds.find("ORDER",id,"ORDER_RESERVE");
            if(intent!=null && intent.status().equals("APPLIED")) orders.awaitingPayment(order);
            else if(intent!=null && intent.status().equals("REJECTED")) {
                ads.finishOrder(ad,order.quantityUnits(),false,canRestore(ad));
                orders.terminal(order,C2cOrderStatus.FAILED,"托管未成功，订单未成立");
            }
        } else if(order.status()==C2cOrderStatus.RELEASE_PENDING) {
            if(applied(id,"ORDER_RELEASE") && (order.feeUnits()==0 || applied(id,"BUYER_FEE"))) {
                ads.finishOrder(ad,order.quantityUnits(),true,false);
                orders.terminal(order,C2cOrderStatus.COMPLETED,null);
            }
        } else if(order.status()==C2cOrderStatus.REFUND_PENDING && applied(id,"ORDER_REFUND")) {
            // SELL 广告的退款已经实际回到卖家，不能再次恢复为广告可售库存。
            ads.finishOrder(ad,order.quantityUnits(),false,ad.side().equals("BUY") && canRestore(ad));
            orders.terminal(order,C2cOrderStatus.valueOf(order.requestedTerminal()),order.cancelReason());
        }
    }

    @Transactional
    UUID appeal(JwtPrincipal user,UUID id,String reason) {
        var order=lockForParticipant(user.userId(),id);
        var existing=jdbc.query("SELECT appeal_id FROM c2c_appeals WHERE order_id=? AND status IN ('OPEN','REVIEWING')",(r,n)->r.getObject(1,UUID.class),id);
        if(!existing.isEmpty()) return existing.getFirst();
        order.status().requireAppeal();
        if(reason==null || reason.isBlank() || reason.length()>2000) throw new IllegalArgumentException("请填写申诉原因");
        UUID appeal=UUID.randomUUID();
        jdbc.update("INSERT INTO c2c_appeals(appeal_id,order_id,opener_user_id,previous_order_status,reason) VALUES(?,?,?,?,?)",
                appeal,id,user.userId(),order.status().name(),reason.trim());
        orders.state(order,C2cOrderStatus.APPEALED,null,null,user.userId(),"APPEAL_OPENED");
        return appeal;
    }

    // 仅由已消费双人审批的 C2C 管理入口在同一事务内调用，不能接受任意金额或收款人。
    @Transactional
    void resolveAppeal(UUID appealId,String decision,String reason,long administrator,long approvalId) {
        var ids=jdbc.query("SELECT order_id FROM c2c_appeals WHERE appeal_id=?",(r,n)->r.getObject(1,UUID.class),appealId);
        if(ids.isEmpty()) throw new IllegalArgumentException("申诉不存在");
        var initial=orders.get(ids.getFirst(),false);ads.get(initial.adId(),true);var order=orders.get(initial.id(),true);
        var appeal=jdbc.queryForMap("SELECT * FROM c2c_appeals WHERE appeal_id=? FOR UPDATE",appealId);
        if(!List.of("OPEN","REVIEWING").contains(appeal.get("status")) || order.status()!=C2cOrderStatus.APPEALED)
            throw new IllegalStateException("申诉已处理，请刷新订单");
        if(administrator==order.buyerUserId() || administrator==order.sellerUserId()) throw new IllegalStateException("不能裁决本人交易");
        if(reason==null || reason.isBlank() || reason.length()>2000) throw new IllegalArgumentException("请填写证据核验与裁决理由");
        switch(decision) {
            case "RELEASE" -> prepareRelease(order,markets.market(order.marketId(),false),administrator,"APPEAL_RELEASE_APPROVED");
            case "REFUND" -> prepareCancellation(order,C2cOrderStatus.CANCELED,"申诉裁决退款",administrator,true);
            default -> throw new IllegalArgumentException("申诉裁决无效");
        }
        jdbc.update("""
                UPDATE c2c_appeals SET status='RESOLVED',decision=?,decision_reason=?,decided_by=?,approval_id=?,resolved_at=now()
                WHERE appeal_id=?
                """,decision,reason.trim(),administrator,approvalId,appealId);
    }

    private C2cOrdersRepository.Order lockForParticipant(long userId,UUID id) {
        var initial=orders.get(id,false);
        requireParticipant(userId,initial);
        ads.get(initial.adId(),true);
        var order=orders.get(id,true);requireParticipant(userId,order);return order;
    }
    static void requireParticipant(long userId,C2cOrdersRepository.Order order) {
        if(userId!=order.buyerUserId() && userId!=order.sellerUserId()) throw C2cProblem.forbidden("只能查看和操作本人订单");
    }
    private void prepareRelease(C2cOrdersRepository.Order order,C2cMarkets.Market market,Long actor,String event) {
        var config=markets.configuration();
        funds.createOrGet("ORDER",order.id(),"ORDER_RELEASE",config.escrowUserId(),order.buyerUserId(),market.asset(),order.quantityUnits()-order.feeUnits());
        if(order.feeUnits()>0) funds.createOrGet("ORDER",order.id(),"BUYER_FEE",config.escrowUserId(),config.feeUserId(),market.asset(),order.feeUnits());
        orders.state(order,C2cOrderStatus.RELEASE_PENDING,null,null,actor,event);
    }
    private void prepareCancellation(C2cOrdersRepository.Order order,C2cOrderStatus terminal,String reason,Long actor,boolean adjudicated) {
        var ad=ads.get(order.adId(),false);
        if(!adjudicated && ad.side().equals("SELL") && canRestore(ad)) {
            ads.finishOrder(ad,order.quantityUnits(),false,true);
            orders.terminal(order,terminal,reason);
        } else {
            var config=markets.configuration();var market=markets.market(order.marketId(),false);
            funds.createOrGet("ORDER",order.id(),"ORDER_REFUND",config.escrowUserId(),order.sellerUserId(),market.asset(),order.quantityUnits());
            orders.state(order,C2cOrderStatus.REFUND_PENDING,terminal.name(),reason,actor,adjudicated?"APPEAL_REFUND_APPROVED":"REFUND_REQUESTED");
        }
    }
    private boolean applied(UUID order,String purpose) {var intent=funds.find("ORDER",order,purpose);return intent!=null && intent.status().equals("APPLIED");}
    private static boolean canRestore(C2cAdsRepository.Ad ad) {return List.of("ACTIVE","PAUSED").contains(ad.status());}
    private C2cPaymentAccounts.Account paymentAccount(long userId,C2cAdsRepository.Ad ad,C2cMarkets.Market market,UUID id) {
        long seller=ad.side().equals("SELL")?ad.makerUserId():userId;
        var account=payments.get(seller,id,true);
        if(!account.fiat().equals(market.fiat()) || !ad.methods().contains(account.method())
                || (ad.side().equals("SELL") && !ad.accounts().contains(account.id()))) throw new IllegalArgumentException("收款账号不属于本次报价");
        return account;
    }
    private void requireOffer(JwtPrincipal user,C2cAdsRepository.Ad ad,C2cMarkets.Market market) {
        if(!ad.status().equals("ACTIVE") || ad.availableUnits()<=0) throw new IllegalStateException("广告暂不可交易");
        if(ad.makerUserId()==user.userId()) throw new IllegalArgumentException("不能与本人广告成交");
        access.requireTrading(user,market);access.requireCounterparty(ad.makerUserId(),market);
        markets.requireMerchantBond(ad.makerUserId());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchants WHERE user_id=? AND status='APPROVED')",Boolean.class,ad.makerUserId())))
            throw new IllegalStateException("商家当前不可交易");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_relations WHERE kind='BLOCK' AND ((user_id=? AND target_user_id=?) OR (user_id=? AND target_user_id=?)))
                """,Boolean.class,user.userId(),ad.makerUserId(),ad.makerUserId(),user.userId()))) throw new IllegalStateException("暂不能与该交易对手交易");
        Long completed=jdbc.queryForObject("SELECT count(*) FROM c2c_orders WHERE status='COMPLETED' AND (buyer_user_id=? OR seller_user_id=?)",Long.class,user.userId(),user.userId());
        if(completed!=null && completed<ad.minCompleted()) throw new IllegalStateException("尚未满足商家成交笔数要求");
    }
    private static void requireAmount(C2cAdsRepository.Ad ad,C2cMarkets.Market market,long units,BigDecimal fiat) {
        if(units<=0 || units>ad.availableUnits() || fiat.compareTo(ad.minFiat())<0 || fiat.compareTo(ad.maxFiat())>0
                || fiat.compareTo(market.minFiat())<0 || fiat.compareTo(market.maxFiat())>0) throw new IllegalArgumentException("交易金额或数量不在当前广告限额内");
    }
}
