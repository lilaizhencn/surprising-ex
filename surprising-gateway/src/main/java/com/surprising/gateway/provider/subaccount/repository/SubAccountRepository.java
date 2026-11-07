package com.surprising.gateway.provider.subaccount.repository;

import com.surprising.gateway.provider.subaccount.model.SubAccountModels.SubAccountRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SubAccountRepository {
    private final JdbcTemplate jdbc;

    public SubAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SubAccountRecord createRelation(long parentUserId, long subUserId, String subAccountName, String permissions) {
        String sql = """
                INSERT INTO gateway_sub_account_relations(parent_user_id, sub_user_id, sub_account_name, permissions, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                RETURNING id, parent_user_id, sub_user_id, sub_account_name, permissions, status, created_at
                """;
        return jdbc.queryForObject(sql, this::mapRow, parentUserId, subUserId, subAccountName, permissions);
    }

    public List<SubAccountRecord> listByParent(long parentUserId) {
        String sql = """
                SELECT id, parent_user_id, sub_user_id, sub_account_name, permissions, status, created_at
                FROM gateway_sub_account_relations
                WHERE parent_user_id = ?
                ORDER BY created_at DESC
                """;
        return jdbc.query(sql, this::mapRow, parentUserId);
    }

    public Optional<SubAccountRecord> findRelation(long parentUserId, long subUserId) {
        String sql = """
                SELECT id, parent_user_id, sub_user_id, sub_account_name, permissions, status, created_at
                FROM gateway_sub_account_relations
                WHERE parent_user_id = ? AND sub_user_id = ?
                """;
        List<SubAccountRecord> list = jdbc.query(sql, this::mapRow, parentUserId, subUserId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public boolean updateStatus(long parentUserId, long subUserId, String status) {
        String sql = """
                UPDATE gateway_sub_account_relations
                SET status = ?, updated_at = now()
                WHERE parent_user_id = ? AND sub_user_id = ?
                """;
        return jdbc.update(sql, status, parentUserId, subUserId) > 0;
    }

    private SubAccountRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SubAccountRecord(
                rs.getLong("id"),
                rs.getLong("parent_user_id"),
                rs.getLong("sub_user_id"),
                rs.getString("sub_account_name"),
                rs.getString("permissions"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
