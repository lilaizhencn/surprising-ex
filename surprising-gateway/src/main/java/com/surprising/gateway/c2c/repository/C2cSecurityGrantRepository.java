package com.surprising.gateway.c2c.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_security_grants；凭据绑定用户与原登录令牌摘要，不保存令牌或验证码。 */
@Repository
public class C2cSecurityGrantRepository {
    private final JdbcTemplate jdbc;

    public C2cSecurityGrantRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean active(long userId, String tokenHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_security_grants WHERE user_id=? AND token_hash=? AND expires_at>now())
                """, Boolean.class, userId, tokenHash));
    }

    public void save(long userId, String tokenHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO c2c_security_grants(user_id,token_hash,expires_at) VALUES (?,?,?)
                ON CONFLICT(user_id,token_hash) DO UPDATE SET expires_at=EXCLUDED.expires_at
                """, userId, tokenHash, Timestamp.from(expiresAt));
    }
}
