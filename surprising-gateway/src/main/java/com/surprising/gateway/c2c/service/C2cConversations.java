package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;

import com.surprising.gateway.c2c.model.C2cConversation;
import com.surprising.gateway.c2c.model.C2cChatRows.Inbox;
import com.surprising.gateway.c2c.model.C2cOrderStatus;
import com.surprising.gateway.c2c.repository.C2cConversationRepository;
import com.surprising.gateway.c2c.repository.C2cMessageReadRepository;
import com.surprising.gateway.c2c.repository.C2cProfileRepository;
import com.surprising.gateway.c2c.repository.C2cRelationRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合订单参与者、陌生消息和屏蔽规则；原消息/事件/已读事实提供收件箱，不维护第二份状态。 */
@Service
public class C2cConversations {
    private final C2cConversationRepository conversations;
    private final C2cMessageReadRepository reads;
    private final C2cOrdersRepository orders;
    private final C2cProfileRepository profiles;
    private final C2cRelationRepository relations;
    private final GatewayUserRepository users;
    public C2cConversations(C2cConversationRepository conversations,C2cMessageReadRepository reads,C2cOrdersRepository orders,
                             C2cProfileRepository profiles,C2cRelationRepository relations,GatewayUserRepository users) {
        this.conversations=conversations;this.reads=reads;this.orders=orders;this.profiles=profiles;this.relations=relations;this.users=users;
    }

    @Transactional
    public Map<String,Object> start(long user,UUID orderId,Long target) {
        long other;
        if(orderId!=null) {
            var order=orders.get(orderId,false);C2cOrders.requireParticipant(user,order);
            other=order.buyerUserId()==user?order.sellerUserId():order.buyerUserId();
        } else {
            other=target;
            if(other==user || users.find(other).filter(value -> "NORMAL".equals(value.status())).isEmpty()) throw new IllegalArgumentException("咨询对象不可用");
            requireDirectAllowed(user,other);
        }
        return view(user,byId(ensure(Math.min(user,other),Math.max(user,other),orderId),false));
    }
    public C2cConversation forOrder(UUID orderId) {
        var order=orders.get(orderId,false);
        return byId(ensure(Math.min(order.buyerUserId(),order.sellerUserId()),Math.max(order.buyerUserId(),order.sellerUserId()),orderId),false);
    }
    private UUID ensure(long low,long high,UUID order) {
        UUID id=conversations.ensure(UUID.randomUUID(),low,high,order);
        reads.ensureParticipants(id,low,high);return id;
    }
    public C2cConversation get(long user,UUID id,boolean lock) {
        var conversation=byId(id,lock);requireParticipant(user,conversation);return conversation;
    }
    public C2cConversation byId(UUID id,boolean lock) {
        return conversations.find(id,lock).orElseThrow(() -> new IllegalArgumentException("会话不存在"));
    }
    public void requireSend(long user,C2cConversation conversation) {
        if(conversation.orderId()==null) {requireDirectAllowed(user,conversation.peer(user));return;}
        var order=orders.get(conversation.orderId(),false);C2cOrders.requireParticipant(user,order);
        // 在途订单仍须沟通履约；终态后的屏蔽只限制新消息，不删除历史证据。
        if(Set.of(C2cOrderStatus.COMPLETED,C2cOrderStatus.CANCELED,C2cOrderStatus.EXPIRED,C2cOrderStatus.FAILED).contains(order.status()))
            requireNotBlocked(user,conversation.peer(user));
    }
    private void requireDirectAllowed(long sender,long recipient) {
        requireNotBlocked(sender,recipient);
        boolean strangers=profiles.find(recipient,false).map(value -> value.strangers()).orElse(false);
        if(!strangers && !relations.follows(recipient,sender)) throw C2cProblem.forbidden("对方暂不接收陌生消息");
    }
    private void requireNotBlocked(long user,long peer) {
        if(relations.blockedEitherDirection(user,peer)) throw C2cProblem.forbidden("当前不能向对方发送消息");
    }
    public Map<String,Object> list(long user,String search,int limit,int offset) {
        var values=conversations.page(user,search,limit+1,offset);
        return Map.of("items",values.stream().limit(limit).map(value -> view(user,value)).toList(),
                "hasMore",values.size()>limit,"serverTime",Instant.now().toString());
    }
    public Map<String,Object> view(long user,C2cConversation conversation) {
        requireParticipant(user,conversation);
        return view(user,conversations.inbox(user,conversation.id()).orElseThrow(() -> new IllegalArgumentException("会话不存在")));
    }
    private Map<String,Object> view(long user,Inbox inbox) {
        var conversation=inbox.conversation();var result=new LinkedHashMap<String,Object>();
        result.put("conversationId",conversation.id().toString());result.put("orderId",conversation.orderId()==null?null:conversation.orderId().toString());
        result.put("peer",Map.of("userId",Long.toString(conversation.peer(user)),"nickname",inbox.peerNickname()));
        var last=inbox.lastMessage();
        result.put("lastMessage",last==null?null:Map.of("messageId",Long.toString(last.id()),"text",last.text(),"authorKind",last.kind(),
                "hasAttachment",last.attachment(),"createdAt",last.createdAt().toString()));
        result.put("unreadCount",Long.toString(inbox.unread()));
        var order=inbox.order();
        if(order!=null) result.put("order",Map.of("orderId",order.id().toString(),"status",order.status().name(),
                "fiatAmount",order.fiatAmount().toPlainString(),"price",order.price().toPlainString()));
        return result;
    }
    private static void requireParticipant(long user,C2cConversation conversation) {
        if(user!=conversation.low() && user!=conversation.high()) throw C2cProblem.forbidden("无权访问此会话");
    }
    public String signal(long user) {return conversations.signal(user);}
}
