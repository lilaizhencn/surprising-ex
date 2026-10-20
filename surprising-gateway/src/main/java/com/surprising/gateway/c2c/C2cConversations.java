package com.surprising.gateway.c2c;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订单决定参与者；普通咨询服从陌生消息设置。会话权限覆盖发送、补拉和附件。 */
@Service
public class C2cConversations {
    record Conversation(UUID id,long low,long high,UUID orderId) {
        long peer(long user) {return user==low?high:low;}
    }
    private final JdbcTemplate jdbc;
    private final C2cOrdersRepository orders;
    public C2cConversations(JdbcTemplate jdbc,C2cOrdersRepository orders) {this.jdbc=jdbc;this.orders=orders;}

    @Transactional
    Map<String,Object> start(long user,UUID orderId,Long target) {
        if((orderId==null)==(target==null)) throw new IllegalArgumentException("请选择订单或咨询对象");
        long other;
        if(orderId!=null) {
            var order=orders.get(orderId,false);C2cOrders.requireParticipant(user,order);
            other=order.buyerUserId()==user?order.sellerUserId():order.buyerUserId();
        } else {
            other=target;
            if(other<=0 || other==user || !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM gateway_users WHERE user_id=? AND status='NORMAL')",Boolean.class,other)))
                throw new IllegalArgumentException("咨询对象不可用");
            requireDirectAllowed(user,other);
        }
        long low=Math.min(user,other),high=Math.max(user,other);
        // 两个并发入口只能建立一个订单/咨询会话，不借客户端输入指定参与者。
        jdbc.update("""
                INSERT INTO c2c_conversations(conversation_id,low_user_id,high_user_id,order_id)
                VALUES(?,?,?,?) ON CONFLICT DO NOTHING
                """,UUID.randomUUID(),low,high,orderId);
        UUID id=jdbc.queryForObject("""
                SELECT conversation_id FROM c2c_conversations WHERE low_user_id=? AND high_user_id=?
                    AND order_id IS NOT DISTINCT FROM CAST(? AS uuid)
                """,UUID.class,low,high,orderId);
        jdbc.update("""
                INSERT INTO c2c_message_reads(conversation_id,user_id) VALUES(?,?),(?,?) ON CONFLICT DO NOTHING
                """,id,low,id,high);
        return view(user,get(user,id,false));
    }

    Conversation get(long user,UUID id,boolean lock) {
        Conversation conversation=byId(id,lock);
        if(user!=conversation.low() && user!=conversation.high()) throw C2cProblem.forbidden("无权访问此会话");
        return conversation;
    }
    Conversation byId(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM c2c_conversations WHERE conversation_id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Conversation(r.getObject("conversation_id",UUID.class),r.getLong("low_user_id"),
                        r.getLong("high_user_id"),r.getObject("order_id",UUID.class)),id);
        if(rows.isEmpty()) throw new IllegalArgumentException("会话不存在");
        return rows.getFirst();
    }

    void requireSend(long user,Conversation conversation) {
        if(conversation.orderId()==null) {requireDirectAllowed(user,conversation.peer(user));return;}
        var order=orders.get(conversation.orderId(),false);
        C2cOrders.requireParticipant(user,order);
        // 在途订单必须能够沟通履约。结束后，双方拉黑只限制新消息，不删除原有证据。
        if(Set.of(C2cOrderStatus.COMPLETED,C2cOrderStatus.CANCELED,C2cOrderStatus.EXPIRED,C2cOrderStatus.FAILED).contains(order.status()))
            requireNotBlocked(user,conversation.peer(user));
    }
    private void requireDirectAllowed(long sender,long recipient) {
        requireNotBlocked(sender,recipient);
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT COALESCE((SELECT receive_strangers FROM c2c_profiles WHERE user_id=?),false)
                    AND NOT EXISTS(SELECT 1 FROM c2c_relations r WHERE r.user_id=? AND r.target_user_id=? AND r.kind='FOLLOW')
                """,Boolean.class,recipient,recipient,sender))) throw C2cProblem.forbidden("对方暂不接收陌生消息");
    }
    private void requireNotBlocked(long user,long peer) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_relations WHERE kind='BLOCK'
                    AND ((user_id=? AND target_user_id=?) OR (user_id=? AND target_user_id=?)))
                """,Boolean.class,user,peer,peer,user))) throw C2cProblem.forbidden("当前不能向对方发送消息");
    }

    Map<String,Object> list(long user,String search,int limit,int offset) {
        if(limit<1 || limit>50 || offset<0 || offset>10000 || search==null || search.length()>80)
            throw new IllegalArgumentException("会话查询条件无效");
        String pattern="%"+search.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        var values=jdbc.query("""
                SELECT c.conversation_id FROM c2c_conversations c
                LEFT JOIN c2c_profiles p ON p.user_id=CASE WHEN c.low_user_id=? THEN c.high_user_id ELSE c.low_user_id END
                WHERE (c.low_user_id=? OR c.high_user_id=?) AND (COALESCE(p.nickname,'用户') ILIKE ?
                    OR COALESCE(c.order_id::text,'') ILIKE ?)
                ORDER BY GREATEST(c.updated_at,COALESCE((SELECT max(e.created_at) FROM c2c_order_events e
                    WHERE e.order_id=c.order_id),c.updated_at)) DESC,c.conversation_id DESC LIMIT ? OFFSET ?
                """,(r,n)->r.getObject(1,UUID.class),user,user,user,pattern,pattern,limit+1,offset);
        return Map.of("items",values.stream().limit(limit).map(id->view(user,get(user,id,false))).toList(),
                "hasMore",values.size()>limit,"serverTime",Instant.now().toString());
    }

    Map<String,Object> view(long user,Conversation conversation) {
        var result=new LinkedHashMap<String,Object>();
        result.put("conversationId",conversation.id().toString());
        result.put("orderId",conversation.orderId()==null?null:conversation.orderId().toString());
        long peer=conversation.peer(user);
        var names=jdbc.query("SELECT nickname FROM c2c_profiles WHERE user_id=?",(r,n)->r.getString(1),peer);
        result.put("peer",Map.of("userId",Long.toString(peer),"nickname",names.isEmpty()?"用户":names.getFirst()));
        result.put("lastMessage",jdbc.query("""
                SELECT message_id,text,author_kind,attachment_id,created_at FROM c2c_messages
                WHERE conversation_id=? ORDER BY message_id DESC LIMIT 1
                """,(r,n)->Map.<String,Object>of("messageId",Long.toString(r.getLong(1)),"text",r.getString(2),
                        "authorKind",r.getString(3),"hasAttachment",r.getObject(4)!=null,"createdAt",r.getTimestamp(5).toInstant().toString()),
                conversation.id()).stream().findFirst().orElse(null));
        result.put("unreadCount",Long.toString(jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM c2c_messages m WHERE m.conversation_id=? AND m.sender_user_id IS DISTINCT FROM ?
                    AND m.message_id>COALESCE((SELECT last_message_id FROM c2c_message_reads WHERE conversation_id=? AND user_id=?),0))
                    +(SELECT count(*) FROM c2c_order_events e WHERE e.order_id=? AND e.actor_user_id IS DISTINCT FROM ?
                    AND e.event_id>COALESCE((SELECT last_event_id FROM c2c_message_reads WHERE conversation_id=? AND user_id=?),0))
                """,Long.class,conversation.id(),user,conversation.id(),user,conversation.orderId(),user,conversation.id(),user)));
        if(conversation.orderId()!=null) {
            var order=orders.get(conversation.orderId(),false);
            result.put("order",Map.of("orderId",order.id().toString(),"status",order.status().name(),
                    "fiatAmount",order.fiatAmount().toPlainString(),"price",order.price().toPlainString()));
        }
        return result;
    }

    String signal(long user) {
        // 持久消息/事件/已读位置是唯一来源；本地只记通知水位，不另建消息队列或用户状态副本。
        return jdbc.queryForObject("""
                SELECT concat_ws(':',
                    (SELECT COALESCE(max(m.message_id),0) FROM c2c_messages m JOIN c2c_conversations c
                        ON c.conversation_id=m.conversation_id WHERE c.low_user_id=? OR c.high_user_id=?),
                    (SELECT COALESCE(max(e.event_id),0) FROM c2c_order_events e JOIN c2c_orders o ON o.order_id=e.order_id
                        WHERE o.buyer_user_id=? OR o.seller_user_id=?),
                    (SELECT COALESCE(sum(r.revision),0) FROM c2c_message_reads r WHERE r.user_id=?),
                    (SELECT COALESCE(max(c.updated_at)::text,'') FROM c2c_conversations c WHERE c.low_user_id=? OR c.high_user_id=?))
                """,String.class,user,user,user,user,user,user,user);
    }
}
