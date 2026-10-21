package com.surprising.gateway.c2c.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 负责 c2c_merchant_bonds；此处不读取或修改 Core 账户余额。 */
@Repository
public class C2cMerchantBondRepository {
    private final JdbcTemplate jdbc;

    public C2cMerchantBondRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean matches(UUID bondId, String asset, long amount, long owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds WHERE bond_id=? AND asset=? AND amount_units=? AND user_id=?)
                """, Boolean.class, bondId, asset, amount, owner));
    }

    /** 关联唯一质押政策，原子读取已锁定质押是否覆盖当前政策；只读，不更新政策表。 */
    public boolean coversCurrentPolicy(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds b JOIN c2c_merchant_bond_policy p
                    ON p.asset=b.asset AND b.amount_units>=p.amount_units
                    WHERE b.user_id=? AND b.status='LOCKED')
                """, Boolean.class, userId));
    }
}
