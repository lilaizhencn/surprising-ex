package com.surprising.gateway.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class UserDepositAddressRepositoryTest {

    @Test
    void findExecutesExpectedSqlWithUserIdNetworkAndVersion() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserDepositAddressRepository repository = new UserDepositAddressRepository(jdbc);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(101L);
        when(rs.getLong("user_id")).thenReturn(42L);
        when(rs.getString("network_code")).thenReturn("ETH");
        when(rs.getString("address_family")).thenReturn("evm");
        when(rs.getString("address")).thenReturn("0x123abc");
        when(rs.getString("memo")).thenReturn("");
        when(rs.getLong("address_version")).thenReturn(1L);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.EPOCH));

        when(jdbc.query(contains("FROM gateway_user_deposit_addresses"), any(RowMapper.class), eq(42L), eq("ETH"), eq(1L)))
                .thenAnswer(invocation -> {
                    RowMapper<UserDepositAddressRecord> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var result = repository.find(42L, "ETH", 1L);
        assertThat(result).isPresent();
        assertThat(result.get().address()).isEqualTo("0x123abc");
        assertThat(result.get().addressFamily()).isEqualTo("evm");
    }

    @Test
    void saveExecutesUpsertWithReturning() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserDepositAddressRepository repository = new UserDepositAddressRepository(jdbc);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(202L);
        when(rs.getLong("user_id")).thenReturn(42L);
        when(rs.getString("network_code")).thenReturn("BASE");
        when(rs.getString("address_family")).thenReturn("evm");
        when(rs.getString("address")).thenReturn("0x123abc");
        when(rs.getString("memo")).thenReturn("");
        when(rs.getLong("address_version")).thenReturn(1L);

        when(jdbc.queryForObject(contains("INSERT INTO gateway_user_deposit_addresses"),
                any(RowMapper.class),
                eq(42L), eq("BASE"), eq("evm"), eq("0x123abc"), eq(""), eq(1L), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<UserDepositAddressRecord> mapper = invocation.getArgument(1);
                    return mapper.mapRow(rs, 0);
                });

        var record = repository.save(42L, "BASE", "evm", "0x123abc", "", 1L);
        assertThat(record.id()).isEqualTo(202L);
        assertThat(record.networkCode()).isEqualTo("BASE");
        assertThat(record.addressFamily()).isEqualTo("evm");
        assertThat(record.address()).isEqualTo("0x123abc");
    }
}
