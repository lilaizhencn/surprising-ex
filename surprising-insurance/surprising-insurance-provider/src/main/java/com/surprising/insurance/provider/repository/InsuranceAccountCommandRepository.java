package com.surprising.insurance.provider.repository;

import com.surprising.account.api.model.AccountCommandStatus;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责读取 {@code account_commands} 表中的命令终态。
 */
@Repository
public class InsuranceAccountCommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsuranceAccountCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, CommandState> findStates(Collection<String> commandIds) {
        List<String> ids = commandIds == null
                ? List.of()
                : commandIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<String, CommandState> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT command_id, status, result_payload::text AS result_payload, error_code, error_message
                  FROM account_commands
                 WHERE command_id IN (%s)
                 FOR UPDATE
                """.formatted(placeholders), rs -> {
            while (rs.next()) {
                result.put(rs.getString("command_id"), new CommandState(
                        AccountCommandStatus.valueOf(rs.getString("status")),
                        rs.getString("result_payload"), rs.getString("error_code"),
                        rs.getString("error_message")));
            }
            return null;
        }, ids.toArray());
        return Map.copyOf(result);
    }

    /** 兼容旧调用方，统一转到单表状态读取方法。 */
    public Map<String, CommandState> findTerminalStates(Collection<String> commandIds) {
        return findStates(commandIds);
    }

    public record CommandState(AccountCommandStatus status,
                               String resultPayload,
                               String errorCode,
                               String errorMessage) {
    }
}
