package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.model.LoginVerificationRows;
import com.surprising.gateway.provider.auth.model.LoginVerificationRows.Factor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问gateway_login_factors；联系方式更新属于用户表Repository。 */
@Repository
public class LoginFactorRepository {
    private final JdbcTemplate jdbc;
    public LoginFactorRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Factor> factors(long userId) {
        return jdbc.query(
                "SELECT method,destination,enabled,verified_at,updated_at FROM"
                    + " gateway_login_factors WHERE user_id=? ORDER BY method",
                (r, n) ->
                        new Factor(
                                r.getString(1),
                                r.getString(2),
                                r.getBoolean(3),
                                r.getTimestamp(4).toInstant(),
                                r.getTimestamp(5).toInstant()),
                userId);
    }
    public void saveFactor(
            long userId, String method, String destination, boolean enabled, Instant now) {
        jdbc.update(
                """
                INSERT INTO gateway_login_factors(user_id,method,destination,enabled,verified_at,updated_at)
                VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,method) DO UPDATE
                SET destination=EXCLUDED.destination,enabled=EXCLUDED.enabled,verified_at=EXCLUDED.verified_at,updated_at=EXCLUDED.updated_at
                """,
                userId,
                method,
                destination,
                enabled,
                Timestamp.from(now),
                Timestamp.from(now));
    }
}
