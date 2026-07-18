package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountUserCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotency boundary for commands originated by the account HTTP API.
 */
@Repository
public class AccountCommandSubmissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountCommandSubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean register(AccountUserCommand command, String serializedEnvelope, Instant now) {
        String identityHash = identityHash(command);
        int inserted = jdbcTemplate.update("""
                INSERT INTO account_command_submissions (
                    command_id, product_line, user_id, command_type, source, source_reference,
                    identity_sha256, payload, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (command_id) DO NOTHING
                """, command.commandId(), command.productLine().name(), command.userId(),
                command.commandType().name(), command.source(), command.sourceReference(),
                identityHash, serializedEnvelope, Timestamp.from(now));
        if (inserted == 1) {
            return true;
        }

        boolean identical = jdbcTemplate.query("""
                SELECT product_line, user_id, command_type, source, source_reference, identity_sha256
                  FROM account_command_submissions
                 WHERE command_id = ?
                 FOR UPDATE
                """, (rs, rowNum) ->
                        rs.getString("product_line").equals(command.productLine().name())
                                && rs.getLong("user_id") == command.userId()
                                && rs.getString("command_type").equals(command.commandType().name())
                                && rs.getString("source").equals(command.source())
                                && rs.getString("source_reference").equals(command.sourceReference())
                                && rs.getString("identity_sha256").equals(identityHash),
                command.commandId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate account command submission disappeared"));
        if (!identical) {
            throw new IllegalStateException("idempotency key was already used by a different account command");
        }
        return false;
    }

    private String identityHash(AccountUserCommand command) {
        String value = command.productLine().name() + '\n'
                + command.userId() + '\n'
                + command.commandType().name() + '\n'
                + command.source() + '\n'
                + command.sourceReference() + '\n'
                + String.valueOf(command.dependsOnCommandId()) + '\n'
                + command.payload();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
