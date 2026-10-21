package com.surprising.gateway.c2c.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_profiles，验证设置的共享锁由调用方事务持有。 */
@Repository
public class C2cProfileRepository {
    private final JdbcTemplate jdbc;

    public C2cProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Integer> verificationMinutesWithShareLock(long userId) {
        return jdbc.query("SELECT verification_minutes FROM c2c_profiles WHERE user_id=? FOR SHARE",
                (r, n) -> r.getInt(1), userId).stream().findFirst();
    }
}
