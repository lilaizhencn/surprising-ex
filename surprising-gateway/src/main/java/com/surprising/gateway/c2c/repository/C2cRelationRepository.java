package com.surprising.gateway.c2c.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_relations。 */
@Repository
public class C2cRelationRepository {
    private final JdbcTemplate jdbc;

    public C2cRelationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean blockedEitherDirection(long userId, long otherUserId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_relations WHERE kind='BLOCK' AND ((user_id=? AND target_user_id=?) OR (user_id=? AND target_user_id=?)))
                """, Boolean.class, userId, otherUserId, otherUserId, userId));
    }
}
