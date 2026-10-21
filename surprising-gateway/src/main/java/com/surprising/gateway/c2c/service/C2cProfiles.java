package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;

import com.surprising.gateway.c2c.model.C2cOfferFilter;
import com.surprising.gateway.c2c.model.C2cProfileRows.Profile;
import com.surprising.gateway.c2c.repository.*;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合本人设置、关系、举报与通知；用户锁保护配额，资金展示只派生已确认意图。 */
@Service
public class C2cProfiles {
    private final C2cAccess access;
    private final ComplianceService compliance;
    private final GatewayUserRepository users;
    private final C2cOrdersRepository orders;
    private final C2cMarketService markets;
    private final C2cProfileRepository profiles;
    private final C2cSecurityGrantRepository grants;
    private final C2cMerchantRepository merchants;
    private final C2cQuickReplyRepository replies;
    private final C2cRelationRepository relations;
    private final C2cReportRepository reports;
    private final C2cPriceAlertRepository alerts;
    private final C2cNotificationRepository notifications;
    private final C2cFundsRepository funds;

    public C2cProfiles(C2cAccess access,ComplianceService compliance,GatewayUserRepository users,C2cOrdersRepository orders,
                       C2cMarketService markets,C2cProfileRepository profiles,C2cSecurityGrantRepository grants,
                       C2cMerchantRepository merchants,C2cQuickReplyRepository replies,C2cRelationRepository relations,
                       C2cReportRepository reports,C2cPriceAlertRepository alerts,C2cNotificationRepository notifications,C2cFundsRepository funds) {
        this.access=access;this.compliance=compliance;this.users=users;this.orders=orders;this.markets=markets;
        this.profiles=profiles;this.grants=grants;this.merchants=merchants;this.replies=replies;this.relations=relations;
        this.reports=reports;this.alerts=alerts;this.notifications=notifications;this.funds=funds;
    }

    public Map<String,Object> me(JwtPrincipal user) {
        var view=profileView(profile(user.userId(),false),user.userId());
        view.put("accountStatus",user.status());
        var kyc=compliance.kyc(user.userId());
        view.put("kycStatus",kyc==null?"UNVERIFIED":kyc.status());view.put("kycLevel",kyc==null?"NONE":kyc.kycLevel());
        view.put("kycVerified",kyc!=null && "VERIFIED".equals(kyc.status()) && (kyc.expiresAt()==null || kyc.expiresAt().isAfter(Instant.now())));
        view.put("merchant",merchant(user.userId()));
        var stats=orders.userStats(user.userId());
        view.put("statistics",Map.of("completed",Long.toString(stats.completed()),"canceled",Long.toString(stats.canceled()),
                "ongoing",Long.toString(stats.ongoing()),"appealed",Long.toString(stats.appealed())));
        view.put("escrow",escrow(user.userId()));view.put("serverTime",Instant.now().toString());return view;
    }

    @Transactional
    public Map<String,Object> update(JwtPrincipal user,long revision,String nickname,boolean strangers,int minutes,C2cVerification verification) {
        lockUser(user.userId());var current=profile(user.userId(),true);
        if(current.revision()!=revision) throw new IllegalStateException("个人设置已变更，请刷新");
        if(current.verificationMinutes()!=minutes) {
            var codes=C2cVerification.orEmpty(verification);access.verifySettings(user,codes.emailCode(),codes.totpCode());
        }
        if(current.revision()==0) profiles.insert(user.userId(),nickname,strangers,minutes);
        else profiles.update(user.userId(),nickname,strangers,minutes);
        if(current.verificationMinutes()!=minutes) grants.deleteByUser(user.userId());
        return profileView(profile(user.userId(),false),user.userId());
    }

    @Transactional
    public Map<String,Object> applyMerchant(JwtPrincipal user,long revision,String application) {
        if(!"NORMAL".equals(user.status())) throw new IllegalStateException("账户当前不能申请商家");
        var kyc=compliance.kyc(user.userId());
        if(kyc==null || !"VERIFIED".equals(kyc.status()) || (kyc.expiresAt()!=null && !kyc.expiresAt().isAfter(Instant.now())))
            throw new IllegalStateException("请先完成有效身份认证");
        lockUser(user.userId());
        var existing=merchants.find(user.userId(),true);
        if(existing.isEmpty()) {
            if(revision!=0) throw new IllegalStateException("商家申请状态已变更");
            merchants.apply(user.userId(),application);
        } else {
            var current=existing.get();
            if(current.revision()!=revision) throw new IllegalStateException("商家申请状态已变更");
            if(!List.of("PENDING","REJECTED","WITHDRAWN").contains(current.status())) throw new IllegalStateException("请联系客服处理当前商家状态");
            merchants.reapply(user.userId(),application);
        }
        return merchant(user.userId());
    }

    public List<Map<String,Object>> quickReplies(long userId) {
        return replies.byUser(userId).stream().map(reply -> Map.<String,Object>of("replyId",reply.id().toString(),"text",reply.text())).toList();
    }
    @Transactional
    public Map<String,Object> saveReply(long userId,UUID id,String content) {
        lockUser(userId);if(id==null) id=UUID.randomUUID();
        var owner=replies.owner(id);
        if(owner.isPresent() && owner.get()!=userId) throw C2cProblem.forbidden("只能修改本人快捷回复");
        if(owner.isEmpty() && replies.count(userId)>=30) throw new IllegalStateException("最多保存 30 条快捷回复");
        replies.save(id,userId,content);return Map.of("replyId",id.toString(),"text",content);
    }
    @Transactional
    public void deleteReply(long userId,UUID id) {replies.delete(userId,id);}

    public Map<String,Object> relations(long userId,String kind,int limit,int offset) {
        var rows=relations.byUser(userId,kind,limit+1,offset);
        var items=rows.stream().limit(limit).map(row -> {
            var view=new LinkedHashMap<String,Object>();view.put("userId",Long.toString(row.targetUserId()));
            view.put("nickname",row.nickname());view.put("kind",row.kind());view.put("merchantStatus",row.merchantStatus());view.put("tier",row.tier());return view;
        }).toList();
        return Map.of("items",items,"hasMore",rows.size()>limit,"offset",offset);
    }
    @Transactional
    public void relation(long userId,long target,String kind,boolean enabled) {
        if(target==userId || users.find(target).isEmpty()) throw new IllegalArgumentException("关系对象无效");
        if(enabled) relations.insert(userId,target,kind);else relations.delete(userId,target,kind);
    }

    @Transactional
    public UUID report(long userId,UUID id,long target,UUID orderId,String reason) {
        if(userId==target || users.find(target).isEmpty()) throw new IllegalArgumentException("举报对象无效");
        if(orderId!=null) {
            var order=orders.get(orderId,false);C2cOrders.requireParticipant(userId,order);
            if(order.buyerUserId()!=target && order.sellerUserId()!=target) throw new IllegalArgumentException("举报对象不属于当前订单");
        }
        lockUser(userId);if(id==null) id=UUID.randomUUID();
        var existing=reports.find(id,false);
        if(existing.isPresent()) {
            var current=existing.get();
            if(current.userId()!=userId || current.targetUserId()!=target || !Objects.equals(orderId,current.orderId()) || !reason.equals(current.reason()))
                throw new IllegalStateException("原举报内容不匹配");
            return id;
        }
        if(reports.recentCount(userId)>=20) throw new IllegalStateException("今日举报较多，请等待客服处理");
        reports.insert(id,userId,target,orderId,reason);return id;
    }
    public Map<String,Object> reports(long userId,int limit,int offset) {
        var rows=reports.byUser(userId,limit+1,offset);
        var items=rows.stream().limit(limit).map(row -> {
            var view=new LinkedHashMap<String,Object>();view.put("reportId",row.reportId().toString());view.put("targetUserId",Long.toString(row.targetUserId()));
            view.put("orderId",row.orderId()==null?null:row.orderId().toString());view.put("reason",row.reason());view.put("status",row.status());
            view.put("reviewReason",row.reviewReason());view.put("createdAt",row.createdAt().toString());return view;
        }).toList();
        return Map.of("items",items,"hasMore",rows.size()>limit,"offset",offset);
    }

    public List<Map<String,Object>> alerts(long userId) {
        return alerts.byUser(userId).stream().<Map<String,Object>>map(row -> {
            var view=new LinkedHashMap<String,Object>();view.put("alertId",row.id().toString());view.put("marketId",row.marketId().toString());
            view.put("side",row.side());view.put("targetPrice",row.targetPrice().toPlainString());view.put("enabled",row.enabled());
            view.put("revision",Long.toString(row.revision()));view.put("triggeredAt",row.triggeredAt()==null?null:row.triggeredAt().toString());return view;
        }).toList();
    }
    @Transactional
    public UUID saveAlert(long userId,UUID id,long revision,UUID market,C2cOfferFilter.Side side,BigDecimal target,boolean enabled) {
        var config=markets.market(market,false);
        if(target.compareTo(config.minPrice())<0 || target.compareTo(config.maxPrice())>0)
            throw new IllegalArgumentException("预警价格超出市场范围");
        lockUser(userId);
        if(id==null && revision!=0) throw new IllegalStateException("预警版本无效");
        if(enabled && alerts.enabledCountExcept(userId,id)>=20) throw new IllegalStateException("最多启用 20 条价格预警");
        if(id==null) {
            id=UUID.randomUUID();alerts.insert(id,userId,market,side.name(),target,enabled);
        } else if(alerts.replace(id,userId,revision,market,side.name(),target,enabled)!=1) throw new IllegalStateException("价格预警已变更，请刷新");
        return id;
    }
    @Transactional
    public void deleteAlert(long userId,UUID id) {alerts.delete(userId,id);}

    public Map<String,Object> notifications(long userId,Long before,int limit) {
        var rows=notifications.byUser(userId,before,limit+1);
        var visible=rows.stream().limit(limit).toList();
        var items=visible.stream().map(row -> Map.<String,Object>of("notificationId",Long.toString(row.id()),"kind",row.kind(),
                "entityId",row.entityId().toString(),"details",row.details(),"read",row.read(),"createdAt",row.createdAt().toString())).toList();
        var view=new LinkedHashMap<String,Object>();view.put("items",items);view.put("hasMore",rows.size()>limit);
        view.put("nextBeforeId",rows.size()>limit?Long.toString(visible.getLast().id()):null);
        view.put("unreadCount",Long.toString(notifications.unreadCount(userId)));return view;
    }
    @Transactional
    public void readNotifications(long userId,long through) {notifications.readThrough(userId,through);}

    private List<Map<String,Object>> escrow(long userId) {
        return funds.escrowByOwner(userId).stream().<Map<String,Object>>map(row -> {
            boolean known=row.pending()==0 && row.failed()==0 && row.held().signum()>=0 && row.held().compareTo(BigDecimal.valueOf(Long.MAX_VALUE))<=0;
            var view=new LinkedHashMap<String,Object>();view.put("asset",row.asset());view.put("assetScale",Long.toString(row.assetScale()));
            view.put("confirmedEscrowUnits",known?row.held().toBigIntegerExact().toString():null);view.put("pendingIntentCount",Long.toString(row.pending()));
            view.put("requiresReview",row.failed()>0 || row.held().signum()<0);return view;
        }).toList();
    }
    private Profile profile(long userId,boolean lock) {
        return profiles.find(userId,lock).orElseGet(() -> {
            String id=Long.toString(userId);return new Profile("用户"+id.substring(Math.max(0,id.length()-6)),false,0,0);
        });
    }
    private Map<String,Object> profileView(Profile profile,long userId) {
        var view=new LinkedHashMap<String,Object>();view.put("userId",Long.toString(userId));view.put("nickname",profile.nickname());
        view.put("receiveStrangers",profile.strangers());view.put("verificationMinutes",profile.verificationMinutes());
        view.put("revision",Long.toString(profile.revision()));return view;
    }
    private Map<String,Object> merchant(long userId) {
        return merchants.find(userId,false).<Map<String,Object>>map(row -> {
            var view=new LinkedHashMap<String,Object>();view.put("status",row.status());view.put("tier",row.tier());
            view.put("application",row.application());view.put("reviewReason",row.reviewReason());view.put("revision",Long.toString(row.revision()));return view;
        }).orElse(null);
    }
    private void lockUser(long userId) {
        if(!users.lockForNoKeyUpdate(userId)) throw new IllegalStateException("账户不可用");
    }
}
