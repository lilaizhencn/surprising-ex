package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cMessage;
import com.surprising.gateway.c2c.model.C2cChatRows.Activity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问c2c_messages；发送权限、配额阈值及原请求匹配由Service聚合。 */
@Repository
public class C2cMessageRepository {
    private final JdbcTemplate jdbc;
    public C2cMessageRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Optional<C2cMessage> byRequest(UUID conversation,UUID request) {
        return jdbc.query("SELECT * FROM c2c_messages WHERE conversation_id=? AND request_id=?",this::read,conversation,request).stream().findFirst();
    }
    public void insert(UUID conversation,long user,UUID request,String text,UUID attachment,String kind) {
        jdbc.update("INSERT INTO c2c_messages(conversation_id,sender_user_id,request_id,text,attachment_id,author_kind) VALUES(?,?,?,?,?,?)",
                conversation,user,request,text,attachment,kind);
    }
    public Activity activity(long userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER(WHERE created_at>now()-interval '1 minute') AS minute,count(*) AS day
                FROM c2c_messages WHERE sender_user_id=? AND created_at>now()-interval '24 hours'
                """,(r,n)->new Activity(r.getLong("minute"),r.getLong("day")),userId);
    }
    public List<C2cMessage> page(UUID conversation,long boundary,boolean older,boolean latest,int count) {
        return jdbc.query("SELECT * FROM c2c_messages WHERE conversation_id=? AND message_id "+(latest?"<=":older?"<":">")
                +" ? ORDER BY message_id "+(older?"DESC":"ASC")+" LIMIT ?",this::read,conversation,boundary,count);
    }
    public boolean exists(UUID conversation,long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_messages WHERE conversation_id=? AND message_id=?)",Boolean.class,conversation,id));
    }
    public boolean referencesAttachment(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_messages WHERE attachment_id=?)",Boolean.class,id));
    }
    private C2cMessage read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new C2cMessage(r.getLong("message_id"),r.getObject("conversation_id",UUID.class),r.getObject("sender_user_id",Long.class),
                r.getObject("request_id",UUID.class),r.getString("text"),r.getObject("attachment_id",UUID.class),r.getString("author_kind"),r.getTimestamp("created_at").toInstant());
    }
}
