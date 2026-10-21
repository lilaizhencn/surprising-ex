package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cReadPosition;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问c2c_message_reads；以数据库GREATEST保证已读水位单调，归属及游标有效性由Service验证。 */
@Repository
public class C2cMessageReadRepository {
    private final JdbcTemplate jdbc;
    public C2cMessageReadRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public void ensureParticipants(UUID id,long low,long high) {
        jdbc.update("INSERT INTO c2c_message_reads(conversation_id,user_id) VALUES(?,?),(?,?) ON CONFLICT DO NOTHING",id,low,id,high);
    }
    public List<C2cReadPosition> forConversation(UUID id) {
        return jdbc.query("SELECT * FROM c2c_message_reads WHERE conversation_id=? ORDER BY user_id",(r,n)->new C2cReadPosition(
                r.getLong("user_id"),r.getLong("last_message_id"),r.getLong("last_event_id"),r.getLong("revision")),id);
    }
    public void advance(UUID id,long user,long message,long event) {
        jdbc.update("""
                INSERT INTO c2c_message_reads(conversation_id,user_id,last_message_id,last_event_id,revision)
                VALUES(?,?,?,?,1) ON CONFLICT(conversation_id,user_id) DO UPDATE SET
                    last_message_id=GREATEST(c2c_message_reads.last_message_id,EXCLUDED.last_message_id),
                    last_event_id=GREATEST(c2c_message_reads.last_event_id,EXCLUDED.last_event_id),revision=c2c_message_reads.revision+1
                    WHERE c2c_message_reads.last_message_id<EXCLUDED.last_message_id OR c2c_message_reads.last_event_id<EXCLUDED.last_event_id
                """,id,user,message,event);
    }
}
