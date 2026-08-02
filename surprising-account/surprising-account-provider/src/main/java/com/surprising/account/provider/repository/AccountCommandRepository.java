package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账户命令异步审计投影仓储。
 *
 * <p>账户用户分区 WAL 和本地 reducer 才是命令的唯一事实源。本仓储只把已经写入 WAL
 * 的命令以及本地结果库中的终态投影到 {@code account_commands}，不参与依赖裁决、顺序
 * 分配、余额计算或重试调度，也不使用行锁。</p>
 */
@Repository
public class AccountCommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 将 WAL 命令登记为处理中；重复投影只校验不可变命令指纹。 */
    public void projectCommand(AccountUserCommand command, String serializedEnvelope, Instant projectedAt) {
        if (command == null || serializedEnvelope == null || serializedEnvelope.isBlank()) {
            throw new IllegalArgumentException("账户命令审计投影参数不能为空");
        }
        Instant now = projectedAt == null ? Instant.now() : projectedAt;
        String payloadHash = sha256(serializedEnvelope);
        int inserted = jdbcTemplate.update("""
                INSERT INTO account_commands (
                    command_id, product_line, user_id, command_type, source, source_reference,
                    depends_on_command_id, payload, payload_sha256, status, occurred_at,
                    started_at, updated_at, trace_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PROCESSING', ?, ?, ?, ?)
                ON CONFLICT (command_id) DO NOTHING
                """, command.commandId(), command.productLine().name(), command.userId(),
                command.commandType().name(), command.source(), command.sourceReference(),
                command.dependsOnCommandId(), serializedEnvelope, payloadHash,
                Timestamp.from(command.occurredAt()), Timestamp.from(now), Timestamp.from(now), command.traceId());
        if (inserted == 0) {
            ExistingCommand existing = jdbcTemplate.query("""
                    SELECT product_line, user_id, command_type, source, source_reference,
                           payload_sha256, depends_on_command_id
                      FROM account_commands
                     WHERE command_id = ?
                    """, (rs, rowNum) -> new ExistingCommand(
                    rs.getString("product_line"), rs.getLong("user_id"),
                    rs.getString("command_type"), rs.getString("source"),
                    rs.getString("source_reference"), rs.getString("payload_sha256"),
                    rs.getString("depends_on_command_id")), command.commandId())
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("账户命令审计投影记录丢失: " + command.commandId()));
            requireSameCommand(existing, command, payloadHash);
        }
    }

    /** 只供审计终态幂等校验使用，不能暴露给账户命令执行器作为事实来源。 */
    private Optional<AccountCommandTerminalResult> terminalResult(String commandId) {
        requireCommandId(commandId);
        return jdbcTemplate.query("""
                SELECT status, result_payload::text AS result_payload, error_code, error_message
                  FROM account_commands
                 WHERE command_id = ?
                   AND status IN ('APPLIED', 'REJECTED')
                """, (rs, rowNum) -> new AccountCommandTerminalResult(
                AccountCommandStatus.valueOf(rs.getString("status")),
                rs.getString("result_payload"), rs.getString("error_code"),
                rs.getString("error_message")), commandId).stream().findFirst();
    }

    /** 将本地结果库中的成功终态异步投影到审计表，重复投影必须幂等。 */
    public void markApplied(String commandId, String resultPayload, Instant projectedAt) {
        markTerminal(commandId, AccountCommandStatus.APPLIED, resultPayload, null, null, projectedAt);
    }

    /** 将本地结果库中的拒绝终态异步投影到审计表，重复投影必须幂等。 */
    public void markRejected(String commandId,
                             String resultPayload,
                             String errorCode,
                             String errorMessage,
                             Instant projectedAt) {
        markTerminal(commandId, AccountCommandStatus.REJECTED, resultPayload, errorCode, errorMessage, projectedAt);
    }

    private void markTerminal(String commandId,
                              AccountCommandStatus status,
                              String resultPayload,
                              String errorCode,
                              String errorMessage,
                              Instant projectedAt) {
        requireCommandId(commandId);
        if (status != AccountCommandStatus.APPLIED && status != AccountCommandStatus.REJECTED) {
            throw new IllegalArgumentException("审计终态必须为 APPLIED 或 REJECTED");
        }
        Instant now = projectedAt == null ? Instant.now() : projectedAt;
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
        if (rows == 1) {
            return;
        }
        AccountCommandTerminalResult existing = terminalResult(commandId).orElseThrow(
                () -> new IllegalStateException("账户命令审计终态记录不存在: " + commandId));
        if (existing.status() != status
                || !Objects.equals(existing.resultPayload(), resultPayload)
                || !Objects.equals(existing.errorCode(), errorCode)
                || !Objects.equals(existing.errorMessage(), truncate(errorMessage))) {
            throw new IllegalStateException("账户命令审计终态幂等冲突: " + commandId);
        }
    }

    private void requireSameCommand(ExistingCommand existing,
                                    AccountUserCommand command,
                                    String payloadHash) {
        if (!existing.productLine().equals(command.productLine().name())
                || existing.userId() != command.userId()
                || !existing.commandType().equals(command.commandType().name())
                || !existing.source().equals(command.source())
                || !existing.sourceReference().equals(command.sourceReference())
                || !existing.payloadHash().equals(payloadHash)
                || !Objects.equals(existing.dependsOnCommandId(), command.dependsOnCommandId())) {
            throw new IllegalStateException("账户命令审计投影发生幂等冲突: " + command.commandId());
        }
    }

    private void requireCommandId(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("账户命令编号不能为空");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private record ExistingCommand(String productLine,
                                   long userId,
                                   String commandType,
                                   String source,
                                   String sourceReference,
                                   String payloadHash,
                                   String dependsOnCommandId) {
    }
}
