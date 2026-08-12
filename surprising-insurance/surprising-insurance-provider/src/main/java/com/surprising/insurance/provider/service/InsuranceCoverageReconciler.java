package com.surprising.insurance.provider.service;

import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.model.InsurancePendingCoverage;
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceAccountCommandRepository;
import com.surprising.insurance.provider.repository.InsuranceFundBalanceRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import com.surprising.insurance.provider.repository.InsurancePendingCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceSequenceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InsuranceCoverageReconciler {

    private final InsuranceProperties properties;
    private final InsurancePendingCoverageRepository pendingCoverageRepository;
    private final InsuranceCoverageRepository coverageRepository;
    private final InsuranceFundBalanceRepository balanceRepository;
    private final InsuranceFundLedgerRepository ledgerRepository;
    private final InsuranceSequenceRepository sequenceRepository;
    private final InsuranceAccountCommandRepository accountCommandRepository;
    private final ObjectMapper objectMapper;

    public InsuranceCoverageReconciler(InsuranceProperties properties,
                                       InsurancePendingCoverageRepository pendingCoverageRepository,
                                       InsuranceCoverageRepository coverageRepository,
                                       InsuranceFundBalanceRepository balanceRepository,
                                       InsuranceFundLedgerRepository ledgerRepository,
                                       InsuranceSequenceRepository sequenceRepository,
                                       ObjectMapper objectMapper) {
        this(properties, pendingCoverageRepository, coverageRepository, balanceRepository, ledgerRepository,
                sequenceRepository, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InsuranceCoverageReconciler(InsuranceProperties properties,
                                       InsurancePendingCoverageRepository pendingCoverageRepository,
                                       InsuranceCoverageRepository coverageRepository,
                                       InsuranceFundBalanceRepository balanceRepository,
                                       InsuranceFundLedgerRepository ledgerRepository,
                                       InsuranceSequenceRepository sequenceRepository,
                                       ObjectMapper objectMapper,
                                       InsuranceAccountCommandRepository accountCommandRepository) {
        this.properties = properties;
        this.pendingCoverageRepository = pendingCoverageRepository;
        this.coverageRepository = coverageRepository;
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
        this.sequenceRepository = sequenceRepository;
        this.objectMapper = objectMapper;
        this.accountCommandRepository = accountCommandRepository;
    }

    /**
     * 账户命令表中的终态是权威结果，基金扣减、预留释放和覆盖完成不依赖结果主题的消息顺序。
     */
    @Transactional
    public void reconcile() {
        Instant now = Instant.now();
        List<InsurancePendingCoverage> pending = pendingCoverageRepository.lock(accountType(), 500);
        Map<String, InsuranceAccountCommandRepository.CommandState> commands = accountCommandRepository == null
                ? Map.of()
                : accountCommandRepository.findStates(commandIds(pending));
        for (InsurancePendingCoverage row : pending) {
            InsurancePendingCoverage coverage = mergeCommandStates(row, commands);
            if (coverage.reserveRejected()) {
                balanceRepository.release(
                        coverage.accountType(), coverage.asset(), coverage.coveredUnits(), now);
                coverageRepository.markFailed(coverage, now);
            } else if (coverage.finalizeApplied()) {
                long balance = balanceRepository.consumeReservation(
                        coverage.accountType(), coverage.asset(), coverage.coveredUnits(), now);
                boolean inserted = ledgerRepository.insert(sequenceRepository.next("insurance-ledger"),
                        coverage.accountType(), coverage.asset(), Math.negateExact(coverage.coveredUnits()),
                        balance, "DEFICIT_COVERAGE", Long.toString(coverage.coverageId()),
                        "COVER_ACCOUNT_DEFICIT", now);
                if (!inserted) {
                    throw new IllegalStateException("failed to write insurance fund ledger insert");
                }
                coverageRepository.markCompleted(
                        coverage.coverageId(), remainingDeficit(coverage.finalizeResult()), now);
            } else if (coverage.reserveApplied()
                    && "PENDING_RESERVE".equals(coverage.coverageStatus())) {
                coverageRepository.markPendingFinalize(coverage.coverageId(), now);
            }
        }
    }

    private Collection<String> commandIds(List<InsurancePendingCoverage> pending) {
        List<String> ids = new ArrayList<>();
        for (InsurancePendingCoverage coverage : pending) {
            ids.add(coverage.reserveCommandId());
            ids.add(coverage.finalizeCommandId());
        }
        return ids;
    }

    private InsurancePendingCoverage mergeCommandStates(
            InsurancePendingCoverage coverage,
            Map<String, InsuranceAccountCommandRepository.CommandState> commands) {
        var reserve = command(commands, coverage.reserveCommandId());
        var finalize = command(commands, coverage.finalizeCommandId());
        String errorCode = firstNonBlank(reserve == null ? null : reserve.errorCode(),
                finalize == null ? null : finalize.errorCode());
        String errorMessage = firstNonBlank(reserve == null ? null : reserve.errorMessage(),
                finalize == null ? null : finalize.errorMessage());
        return new InsurancePendingCoverage(
                coverage.coverageId(), coverage.accountType(), coverage.userId(), coverage.asset(),
                coverage.coveredUnits(), coverage.reserveCommandId(), coverage.finalizeCommandId(),
                coverage.coverageStatus(), reserve == null ? coverage.reserveStatus() : reserve.status().name(),
                finalize == null ? coverage.finalizeStatus() : finalize.status().name(),
                finalize == null ? coverage.finalizeResult() : finalize.resultPayload(),
                errorCode == null ? coverage.errorCode() : errorCode,
                errorMessage == null ? coverage.errorMessage() : errorMessage);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private InsuranceAccountCommandRepository.CommandState command(
            Map<String, InsuranceAccountCommandRepository.CommandState> commands, String commandId) {
        return commandId == null || commandId.isBlank() ? null : commands.get(commandId);
    }

    private String accountType() {
        String accountType = properties.getKafka().getAccountType();
        return accountType == null || accountType.isBlank()
                ? "USDT_PERPETUAL"
                : accountType.trim().toUpperCase();
    }

    private long remainingDeficit(String resultPayload) {
        if (resultPayload == null || resultPayload.isBlank()) {
            throw new IllegalStateException("insurance finalize result payload is missing");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resultPayload, Map.class);
        Object value = result.get("remainingDeficitUnits");
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("insurance finalize result remaining deficit is missing");
        }
        return number.longValue();
    }
}
