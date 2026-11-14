package com.surprising.gateway.provider.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 用户充值地址本地持久化仓储。
 * 实现 0 外部穿透读取，同时保持与 Custody 托管端的确定性公链映射一致。
 */
@Repository
public class UserDepositAddressRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserDepositAddressRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserDepositAddressRecord> find(long userId, String networkCode, long addressVersion) {
        List<UserDepositAddressRecord> rows = jdbcTemplate.query("""
                SELECT id, user_id, network_code, address_family, address, memo, address_version, created_at, updated_at
                  FROM gateway_user_deposit_addresses
                 WHERE user_id = ? AND network_code = ? AND address_version = ?
                """, this::toRecord, userId, networkCode, addressVersion);
        return rows.stream().findFirst();
    }

    public Optional<UserDepositAddressRecord> findByFamily(long userId, String addressFamily, long addressVersion) {
        List<UserDepositAddressRecord> rows = jdbcTemplate.query("""
                SELECT id, user_id, network_code, address_family, address, memo, address_version, created_at, updated_at
                  FROM gateway_user_deposit_addresses
                 WHERE user_id = ? AND address_family = ? AND address_version = ?
                 ORDER BY id ASC
                 LIMIT 1
                """, this::toRecord, userId, addressFamily, addressVersion);
        return rows.stream().findFirst();
    }

    public List<UserDepositAddressRecord> listByUser(long userId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, network_code, address_family, address, memo, address_version, created_at, updated_at
                  FROM gateway_user_deposit_addresses
                 WHERE user_id = ?
                 ORDER BY id ASC
                """, this::toRecord, userId);
    }

    public UserDepositAddressRecord save(long userId, String networkCode, String addressFamily,
                                         String address, String memo, long addressVersion) {
        Instant now = Instant.now();
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_deposit_addresses (
                    user_id, network_code, address_family, address, memo, address_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, network_code, address_version)
                DO UPDATE SET
                    address = EXCLUDED.address,
                    memo = EXCLUDED.memo,
                    address_family = EXCLUDED.address_family,
                    updated_at = EXCLUDED.updated_at
                RETURNING id, user_id, network_code, address_family, address, memo, address_version, created_at, updated_at
                """, this::toRecord, userId, networkCode, addressFamily, address, memo == null ? "" : memo,
                addressVersion, Timestamp.from(now), Timestamp.from(now));
    }

    private UserDepositAddressRecord toRecord(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new UserDepositAddressRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("network_code"),
                rs.getString("address_family"),
                rs.getString("address"),
                rs.getString("memo"),
                rs.getLong("address_version"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }
}
