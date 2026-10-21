package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cBondPolicy;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只写c2c_merchant_bond_policy；关联唯一资产只读取权威精度以展示政策金额，不复制资产配置。 */
@Repository
public class C2cMerchantBondPolicyRepository {
    private final JdbcTemplate jdbc;
    public C2cMerchantBondPolicyRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Optional<C2cBondPolicy> find() {
        return jdbc.query("SELECT p.*,a.scale_units FROM c2c_merchant_bond_policy p JOIN assets a USING(asset)",
                (r,n)->new C2cBondPolicy(r.getString("asset"),r.getLong("amount_units"),r.getLong("scale_units"),r.getLong("revision"))).stream().findFirst();
    }
    public void save(String asset,long units,long actor) {
        jdbc.update("""
                INSERT INTO c2c_merchant_bond_policy(singleton,asset,amount_units,updated_by) VALUES(true,?,?,?)
                ON CONFLICT(singleton) DO UPDATE SET asset=EXCLUDED.asset,amount_units=EXCLUDED.amount_units,
                    revision=c2c_merchant_bond_policy.revision+1,updated_by=EXCLUDED.updated_by,updated_at=now()
                """,asset,units,actor);
    }
}
