package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cConversation;
import com.surprising.gateway.c2c.model.C2cChatRows.Inbox;
import com.surprising.gateway.c2c.model.C2cChatRows.LastMessage;
import com.surprising.gateway.c2c.model.C2cChatRows.OrderSummary;
import com.surprising.gateway.c2c.model.C2cOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只写c2c_conversations；本人收件箱/通知信号的必要只读关联见各方法，不维护第二份消息状态。 */
@Repository
public class C2cConversationRepository {
    private final JdbcTemplate jdbc;
    public C2cConversationRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public UUID ensure(UUID id,long low,long high,UUID order) {
        jdbc.update("INSERT INTO c2c_conversations(conversation_id,low_user_id,high_user_id,order_id) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",id,low,high,order);
        return jdbc.queryForObject("SELECT conversation_id FROM c2c_conversations WHERE low_user_id=? AND high_user_id=? AND order_id IS NOT DISTINCT FROM CAST(? AS uuid)",UUID.class,low,high,order);
    }
    public Optional<C2cConversation> find(UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM c2c_conversations WHERE conversation_id=?"+(lock?" FOR UPDATE":""),this::read,id).stream().findFirst();
    }
    public Optional<UUID> forOrder(UUID order) {
        return jdbc.query("SELECT conversation_id FROM c2c_conversations WHERE order_id=?",(r,n)->r.getObject(1,UUID.class),order).stream().findFirst();
    }
    public void touch(UUID id) {jdbc.update("UPDATE c2c_conversations SET updated_at=now() WHERE conversation_id=?",id);}

    public List<Inbox> page(long user,String search,int count,int offset) {return inbox(user,null,search,count,offset);}
    public Optional<Inbox> inbox(long user,UUID id) {return inbox(user,id,"",1,0).stream().findFirst();}

    /**
     * 本人会话为稳定分页根，CTE先限定有界页；唯一档案/订单提供脱敏摘要，LATERAL只取最后消息及聚合未读数。
     * 消息/事件/已读位置均来自原表，不扩大根行数；不读取付款快照或文件私有key，不逐会话再查询。
     */
    private List<Inbox> inbox(long user,UUID id,String search,int count,int offset) {
        String pattern="%"+search.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        return jdbc.query("""
                WITH page AS (
                    SELECT c.*,COALESCE(p.nickname,'用户') AS peer_nickname,
                        GREATEST(c.updated_at,COALESCE((SELECT max(e.created_at) FROM c2c_order_events e
                            WHERE e.order_id=c.order_id),c.updated_at)) AS inbox_updated
                    FROM c2c_conversations c LEFT JOIN c2c_profiles p
                        ON p.user_id=CASE WHEN c.low_user_id=? THEN c.high_user_id ELSE c.low_user_id END
                    WHERE (c.low_user_id=? OR c.high_user_id=?) AND (CAST(? AS uuid) IS NULL OR c.conversation_id=?)
                        AND (COALESCE(p.nickname,'用户') ILIKE ? OR COALESCE(c.order_id::text,'') ILIKE ?)
                    ORDER BY inbox_updated DESC,c.conversation_id DESC LIMIT ? OFFSET ?
                )
                SELECT page.*,last.message_id AS last_message_id,last.text AS last_text,last.author_kind AS last_kind,
                    last.attachment_id IS NOT NULL AS last_attachment,last.created_at AS last_created_at,
                    message_unread.total+event_unread.total AS unread_count,
                    o.order_id AS summary_order_id,o.status AS order_status,o.fiat_amount,o.price
                FROM page
                LEFT JOIN LATERAL(SELECT message_id,text,author_kind,attachment_id,created_at FROM c2c_messages
                    WHERE conversation_id=page.conversation_id ORDER BY message_id DESC LIMIT 1) last ON true
                LEFT JOIN c2c_orders o ON o.order_id=page.order_id
                LEFT JOIN c2c_message_reads r ON r.conversation_id=page.conversation_id AND r.user_id=?
                LEFT JOIN LATERAL(SELECT count(*) AS total FROM c2c_messages m WHERE m.conversation_id=page.conversation_id
                    AND m.sender_user_id IS DISTINCT FROM ? AND m.message_id>COALESCE(r.last_message_id,0)) message_unread ON true
                LEFT JOIN LATERAL(SELECT count(*) AS total FROM c2c_order_events e WHERE e.order_id=page.order_id
                    AND e.actor_user_id IS DISTINCT FROM ? AND e.event_id>COALESCE(r.last_event_id,0)) event_unread ON true
                ORDER BY page.inbox_updated DESC,page.conversation_id DESC
                """,this::readInbox,user,user,user,id,id,pattern,pattern,count,offset,user,user,user);
    }

    /**
     * 本人持久会话为消息关联根，事件按本人订单关联，已读按本人过滤；只生成一次只读通知水位。
     * 它仅用于唤醒补拉，不作为业务/送达权威，也不写入任何关联表或另建用户消息队列。
     */
    public String signal(long user) {
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
    private C2cConversation read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new C2cConversation(r.getObject("conversation_id",UUID.class),r.getLong("low_user_id"),r.getLong("high_user_id"),r.getObject("order_id",UUID.class));
    }
    private Inbox readInbox(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        var last=r.getObject("last_message_id")==null?null:new LastMessage(r.getLong("last_message_id"),r.getString("last_text"),
                r.getString("last_kind"),r.getBoolean("last_attachment"),r.getTimestamp("last_created_at").toInstant());
        var order=r.getObject("summary_order_id")==null?null:new OrderSummary(r.getObject("summary_order_id",UUID.class),
                C2cOrderStatus.valueOf(r.getString("order_status")),r.getBigDecimal("fiat_amount"),r.getBigDecimal("price"));
        return new Inbox(read(r,n),r.getString("peer_nickname"),last,r.getLong("unread_count"),order);
    }
}
