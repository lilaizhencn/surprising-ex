package com.surprising.gateway.provider.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅保留注销/踢人的操作记录；没有到期时间、令牌或任何可以恢复登录的资料。 */
@Repository
public class GatewaySessionAuditRepository {
    private final JdbcTemplate jdbc;
    public GatewaySessionAuditRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void append(long userId,String operation,List<Long> sessionIds,Instant now) {
        jdbc.update("""
                INSERT INTO gateway_session_operation_logs (user_id,operation,session_ids,created_at)
                VALUES (?,?,?,?)
                """,userId,operation,sessionIds.toString(),Timestamp.from(now));
    }
}
