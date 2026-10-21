package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_configuration；共享锁随调用方业务事务释放。 */
@Repository
public class C2cConfigurationRepository {
    private final JdbcTemplate jdbc;

    public C2cConfigurationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public C2cConfiguration get(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM c2c_configuration WHERE singleton" + (lock ? " FOR SHARE" : ""),
                (row, n) -> new C2cConfiguration(row.getBoolean("enabled"), row.getLong("escrow_user_id"),
                        row.getLong("fee_user_id"), row.getInt("max_active_ads"), row.getLong("revision")));
    }
}
