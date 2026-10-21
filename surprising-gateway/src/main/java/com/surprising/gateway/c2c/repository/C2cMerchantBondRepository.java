package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cMerchantBond;
import com.surprising.gateway.c2c.model.C2cBondConfirmation;
import java.util.List;
import java.util.Optional;
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
    public boolean anyOutstanding() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds WHERE status NOT IN ('RETURNED','FAILED'))",Boolean.class));
    }
    public boolean outstanding(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds WHERE user_id=? AND status NOT IN ('RETURNED','FAILED'))",Boolean.class,userId));
    }
    public boolean requestExists(long userId,UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds WHERE user_id=? AND request_id=?)",Boolean.class,userId,requestId));
    }
    /** 质押为根，关联资产仅补充原币种精度用于本人原记录展示；必要锁仅锁质押行。 */
    public Optional<C2cMerchantBond> findOwned(long userId,UUID id,boolean lock) {
        return jdbc.query("SELECT b.*,a.scale_units FROM c2c_merchant_bonds b JOIN assets a USING(asset) WHERE b.bond_id=? AND b.user_id=?"
                +(lock?" FOR UPDATE OF b":""),this::read,id,userId).stream().findFirst();
    }
    /** 同上；原请求号可选过滤确保历史回执不会漂移到下一轮质押。 */
    public Optional<C2cMerchantBond> latest(long userId,UUID requestId) {
        return jdbc.query("""
                SELECT b.*,a.scale_units FROM c2c_merchant_bonds b JOIN assets a USING(asset)
                WHERE user_id=? AND (CAST(? AS uuid) IS NULL OR request_id=?) ORDER BY created_at DESC,bond_id DESC LIMIT 1
                """,this::read,userId,requestId,requestId).stream().findFirst();
    }
    public void insert(UUID id,long userId,UUID requestId,String asset,long units) {
        jdbc.update("INSERT INTO c2c_merchant_bonds(bond_id,user_id,request_id,asset,amount_units,status) VALUES(?,?,?,?,?,'RESERVING')",
                id,userId,requestId,asset,units);
    }
    public void updateStatus(UUID id,String status) {
        jdbc.update("UPDATE c2c_merchant_bonds SET status=?,updated_at=now() WHERE bond_id=?",status,id);
    }
    /**
     * 当前最早32条在途质押为批次根；只读原资金意图终态以交给Service决定质押终态，不修改资金表。
     * 原owner/purpose唯一键保证一对一；锁仅由业务事务持有质押行，跳过其他实例正在处理的行。
     */
    public List<C2cBondConfirmation> lockConfirmable(int limit) {
        return jdbc.query("""
                SELECT b.*,a.scale_units,f.status AS intent_status FROM c2c_merchant_bonds b
                JOIN assets a USING(asset)
                JOIN c2c_fund_intents f ON b.bond_id=f.owner_id AND f.owner_type='MERCHANT'
                WHERE ((b.status='RESERVING' AND f.purpose='MERCHANT_BOND' AND f.status IN ('APPLIED','REJECTED'))
                    OR (b.status='REFUND_PENDING' AND f.purpose='MERCHANT_BOND_REFUND' AND f.status='APPLIED'))
                AND b.bond_id IN(SELECT bond_id FROM c2c_merchant_bonds WHERE status IN ('RESERVING','REFUND_PENDING')
                    ORDER BY updated_at,bond_id LIMIT ?)
                ORDER BY b.updated_at,b.bond_id FOR UPDATE OF b SKIP LOCKED
                """,(r,n)->new C2cBondConfirmation(read(r,n),r.getString("intent_status")),limit);
    }
    private C2cMerchantBond read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new C2cMerchantBond(r.getObject("bond_id",UUID.class),r.getLong("user_id"),r.getObject("request_id",UUID.class),
                r.getString("asset"),r.getLong("amount_units"),r.getString("status"),r.getLong("scale_units"));
    }

}
