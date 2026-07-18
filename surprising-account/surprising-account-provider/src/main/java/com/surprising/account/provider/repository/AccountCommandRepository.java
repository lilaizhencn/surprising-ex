package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.model.AccountCommandRegistration;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.model.PendingAccountCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountCommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AccountCommandRegistration register(AccountUserCommand command,
                                               String serializedEnvelope,
                                               Instant now) {
        String payloadHash = sha256(serializedEnvelope);
        AccountCommandStatus dependencyStatus = dependencyStatus(command.dependsOnCommandId());
        boolean dependencyReady = command.dependsOnCommandId() == null
                || dependencyStatus == AccountCommandStatus.APPLIED;
        boolean dependencyRejected = dependencyStatus == AccountCommandStatus.REJECTED;
        String initialStatus = dependencyReady || dependencyRejected
                ? AccountCommandStatus.PROCESSING.name()
                : AccountCommandStatus.WAITING_DEPENDENCY.name();
        int inserted = jdbcTemplate.update("""
                INSERT INTO account_commands (
                    command_id, product_line, user_id, command_type, source, source_reference,
                    depends_on_command_id, payload, payload_sha256, status, occurred_at,
                    started_at, updated_at, trace_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (command_id) DO NOTHING
                """, command.commandId(), command.productLine().name(), command.userId(),
                command.commandType().name(), command.source(), command.sourceReference(),
                command.dependsOnCommandId(), serializedEnvelope, payloadHash, initialStatus,
                Timestamp.from(command.occurredAt()), Timestamp.from(now), Timestamp.from(now), command.traceId());
        if (inserted == 1) {
            if (dependencyRejected) {
                return AccountCommandRegistration.DEPENDENCY_REJECTED;
            }
            return dependencyReady ? AccountCommandRegistration.READY
                    : AccountCommandRegistration.WAITING_DEPENDENCY;
        }

        ExistingCommand existing = jdbcTemplate.query("""
                SELECT product_line, user_id, command_type, source, source_reference,
                       payload_sha256, status, depends_on_command_id
                  FROM account_commands
                 WHERE command_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new ExistingCommand(
                rs.getString("product_line"),
                rs.getLong("user_id"),
                rs.getString("command_type"),
                rs.getString("source"),
                rs.getString("source_reference"),
                rs.getString("payload_sha256"),
                AccountCommandStatus.valueOf(rs.getString("status")),
                rs.getString("depends_on_command_id")), command.commandId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate account command disappeared"));
        requireSameCommand(existing, command, payloadHash);
        if (existing.status() == AccountCommandStatus.APPLIED
                || existing.status() == AccountCommandStatus.REJECTED) {
            return AccountCommandRegistration.ALREADY_TERMINAL;
        }
        AccountCommandStatus existingDependencyStatus = dependencyStatus(existing.dependsOnCommandId());
        if (existing.status() == AccountCommandStatus.WAITING_DEPENDENCY
                && (existingDependencyStatus == AccountCommandStatus.APPLIED
                || existingDependencyStatus == AccountCommandStatus.REJECTED)) {
            int rows = jdbcTemplate.update("""
                    UPDATE account_commands
                       SET status = 'PROCESSING',
                           started_at = ?,
                           updated_at = ?
                     WHERE command_id = ?
                       AND status = 'WAITING_DEPENDENCY'
                    """, Timestamp.from(now), Timestamp.from(now), command.commandId());
            if (rows == 1) {
                return existingDependencyStatus == AccountCommandStatus.REJECTED
                        ? AccountCommandRegistration.DEPENDENCY_REJECTED
                        : AccountCommandRegistration.READY;
            }
        }
        return AccountCommandRegistration.WAITING_DEPENDENCY;
    }

    public void markApplied(String commandId, String resultPayload, Instant now) {
        markTerminal(commandId, AccountCommandStatus.APPLIED, resultPayload, null, null, now);
    }

    public void markRejected(String commandId,
                             String resultPayload,
                             String errorCode,
                             String errorMessage,
                             Instant now) {
        markTerminal(commandId, AccountCommandStatus.REJECTED, resultPayload, errorCode, errorMessage, now);
    }

    public List<PendingAccountCommand> waitingDependents(String completedCommandId) {
        return jdbcTemplate.query("""
                SELECT command_id, product_line, user_id, payload::text AS payload
                  FROM account_commands
                 WHERE depends_on_command_id = ?
                   AND status = 'WAITING_DEPENDENCY'
                 ORDER BY started_at ASC, command_id ASC
                """, (rs, rowNum) -> new PendingAccountCommand(
                rs.getString("command_id"),
                AccountUserCommand.partitionKey(
                        com.surprising.product.api.ProductLine.valueOf(rs.getString("product_line")),
                        rs.getLong("user_id")),
                rs.getString("payload")), completedCommandId);
    }

    public Optional<AccountCommandTerminalResult> terminalResult(String commandId) {
        return jdbcTemplate.query("""
                SELECT status, result_payload::text AS result_payload, error_code, error_message
                  FROM account_commands
                 WHERE command_id = ?
                   AND status IN ('APPLIED', 'REJECTED')
                """, (rs, rowNum) -> new AccountCommandTerminalResult(
                AccountCommandStatus.valueOf(rs.getString("status")),
                rs.getString("result_payload"),
                rs.getString("error_code"),
                rs.getString("error_message")), commandId).stream().findFirst();
    }

    public Map<String, AccountCommandTerminalResult> terminalResults(List<String> commandIds) {
        if (commandIds == null || commandIds.isEmpty()) {
            return Map.of();
        }
        Map<String, AccountCommandTerminalResult> results = new LinkedHashMap<>();
        int batchSize = 500;
        for (int start = 0; start < commandIds.size(); start += batchSize) {
            List<String> batch = commandIds.subList(start, Math.min(start + batchSize, commandIds.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("""
                    SELECT command_id, status, result_payload::text AS result_payload,
                           error_code, error_message
                      FROM account_commands
                     WHERE command_id IN (%s)
                       AND status IN ('APPLIED', 'REJECTED')
                    """.formatted(placeholders), rs -> {
                while (rs.next()) {
                    results.put(rs.getString("command_id"), new AccountCommandTerminalResult(
                            AccountCommandStatus.valueOf(rs.getString("status")),
                            rs.getString("result_payload"),
                            rs.getString("error_code"),
                            rs.getString("error_message")));
                }
                return null;
            }, batch.toArray());
        }
        return results;
    }

    private void markTerminal(String commandId,
                              AccountCommandStatus status,
                              String resultPayload,
                              String errorCode,
                              String errorMessage,
                              Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_commands
                   SET status = ?,
                       result_payload = CASE WHEN CAST(? AS text) IS NULL THEN NULL ELSE ?::jsonb END,
                       error_code = ?,
                       error_message = ?,
                       completed_at = ?,
                       updated_at = ?
                 WHERE command_id = ?
                   AND status = 'PROCESSING'
                """, status.name(), resultPayload, resultPayload, errorCode, truncate(errorMessage),
                Timestamp.from(now), Timestamp.from(now), commandId);
        if (rows != 1) {
            throw new IllegalStateException("failed to complete account command " + commandId);
        }
    }

    private AccountCommandStatus dependencyStatus(String commandId) {
        if (commandId == null) {
            return AccountCommandStatus.APPLIED;
        }
        return jdbcTemplate.query("""
                SELECT status
                  FROM account_commands
                 WHERE command_id = ?
                """, (rs, rowNum) -> AccountCommandStatus.valueOf(rs.getString("status")), commandId)
                .stream().findFirst().orElse(null);
    }

    private void requireSameCommand(ExistingCommand existing, AccountUserCommand command, String payloadHash) {
        if (!existing.productLine().equals(command.productLine().name())
                || existing.userId() != command.userId()
                || !existing.commandType().equals(command.commandType().name())
                || !existing.source().equals(command.source())
                || !existing.sourceReference().equals(command.sourceReference())
                || !existing.payloadHash().equals(payloadHash)
                || !java.util.Objects.equals(existing.dependsOnCommandId(), command.dependsOnCommandId())) {
            throw new IllegalStateException("conflicting duplicate account command " + command.commandId());
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private record ExistingCommand(
            String productLine,
            long userId,
            String commandType,
            String source,
            String sourceReference,
            String payloadHash,
            AccountCommandStatus status,
            String dependsOnCommandId) {
    }
}
