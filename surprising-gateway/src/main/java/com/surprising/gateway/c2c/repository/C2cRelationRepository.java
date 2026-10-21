package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cProfileRows.Relation;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只写c2c_relations；本人列表的只读关联用途见对应方法。 */
@Repository
public class C2cRelationRepository {
    private final JdbcTemplate jdbc;

    public C2cRelationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean blockedEitherDirection(long userId, long otherUserId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_relations WHERE kind='BLOCK' AND ((user_id=? AND target_user_id=?) OR (user_id=? AND target_user_id=?)))
                """, Boolean.class, userId, otherUserId, otherUserId, userId));
    }
    /** 本人关系为稳定分页根；档案昵称和唯一商家状态仅补充显示，不扩大关系行数。 */
    public List<Relation> byUser(long userId,String kind,int count,int offset) {
        return jdbc.query("""
                SELECT r.target_user_id,r.kind,COALESCE(p.nickname,'用户'||right(r.target_user_id::text,6)) AS nickname,
                    c.status AS merchant_status,c.tier
                FROM c2c_relations r LEFT JOIN c2c_profiles p ON p.user_id=r.target_user_id
                LEFT JOIN c2c_merchants c ON c.user_id=r.target_user_id WHERE r.user_id=? AND r.kind=?
                ORDER BY r.created_at DESC,r.target_user_id DESC LIMIT ? OFFSET ?
                """,(r,n)->new Relation(r.getLong("target_user_id"),r.getString("nickname"),r.getString("kind"),
                        r.getString("merchant_status"),r.getString("tier")),userId,kind,count,offset);
    }
    public void insert(long userId,long target,String kind) {
        jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(?,?,?) ON CONFLICT DO NOTHING",userId,target,kind);
    }
    public void delete(long userId,long target,String kind) {
        jdbc.update("DELETE FROM c2c_relations WHERE user_id=? AND target_user_id=? AND kind=?",userId,target,kind);
    }

}
