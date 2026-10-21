package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cProfileRows.QuickReply;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问c2c_quick_replies；用户串行锁与配额由Service持有。 */
@Repository
public class C2cQuickReplyRepository {
    private final JdbcTemplate jdbc;
    public C2cQuickReplyRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public List<QuickReply> byUser(long userId) {
        return jdbc.query("SELECT * FROM c2c_quick_replies WHERE user_id=? ORDER BY created_at,reply_id",
                (r,n)->new QuickReply(r.getObject("reply_id",UUID.class),r.getLong("user_id"),r.getString("text")),userId);
    }
    public Optional<Long> owner(UUID id) {
        return jdbc.query("SELECT user_id FROM c2c_quick_replies WHERE reply_id=?",(r,n)->r.getLong(1),id).stream().findFirst();
    }
    public long count(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM c2c_quick_replies WHERE user_id=?",Long.class,userId);
    }
    public void save(UUID id,long userId,String text) {
        jdbc.update("""
                INSERT INTO c2c_quick_replies(reply_id,user_id,text) VALUES(?,?,?)
                ON CONFLICT(reply_id) DO UPDATE SET text=EXCLUDED.text WHERE c2c_quick_replies.user_id=EXCLUDED.user_id
                """,id,userId,text);
    }
    public void delete(long userId,UUID id) {
        jdbc.update("DELETE FROM c2c_quick_replies WHERE user_id=? AND reply_id=?",userId,id);
    }
}
