package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.AccountTradeSettlementSideRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 将用户分区命令终态中的余额变更异步投影到产品账本。
 *
 * <p>本服务只消费本地 reducer 已经固化的终态，不参与余额计算、顺序裁决或命令重试。数据库
 * 不可用时投影事务失败，下一轮从 WAL 和结果库重放；在线资金状态不受影响。</p>
 */
@Service
public class AccountLedgerProjectionService {

    private final ProductLedgerRepository productLedgerRepository;
    private final AccountSequenceRepository sequenceRepository;
    private final AccountTradeSettlementSideRepository settlementSideRepository;
    private final ObjectMapper objectMapper;

    public AccountLedgerProjectionService(ProductLedgerRepository productLedgerRepository,
                                          AccountSequenceRepository sequenceRepository) {
        this(productLedgerRepository, sequenceRepository, null, null);
    }

    @Autowired
    public AccountLedgerProjectionService(ProductLedgerRepository productLedgerRepository,
                                          AccountSequenceRepository sequenceRepository,
                                          AccountTradeSettlementSideRepository settlementSideRepository,
                                          ObjectMapper objectMapper) {
        this.productLedgerRepository = productLedgerRepository;
        this.sequenceRepository = sequenceRepository;
        this.settlementSideRepository = settlementSideRepository;
        this.objectMapper = objectMapper;
    }

    /** 一个命令的全部账本明细在同一数据库事务中提交。 */
    @Transactional
    public void project(AccountUserCommand command,
                        AccountCommandTerminalResult terminal,
                        Instant projectedAt) {
        if (command == null || terminal == null || terminal.status() != AccountCommandStatus.APPLIED) {
            return;
        }
        AccountType accountType = AccountType.valueOf(command.productLine().accountTypeCode());
        Instant now = projectedAt == null ? Instant.now() : projectedAt;
        if (command.commandType() == AccountUserCommandType.TRADE_SIDE_SETTLE
                && settlementSideRepository != null && objectMapper != null) {
            projectSettlementSide(command, terminal, now);
        }
        if (terminal.ledgerDeltas().isEmpty()) {
            return;
        }
        for (AccountCommandTerminalResult.LedgerDelta delta : terminal.ledgerDeltas()) {
            productLedgerRepository.projectCommandDelta(
                    sequenceRepository.nextProductLedgerEntryId(), command.userId(), accountType,
                    delta.asset(), delta.amountUnits(), delta.balanceAfterUnits(), delta.referenceType(),
                    delta.referenceId(), delta.reason(), delta.symbol(), now);
        }
    }

    private void projectSettlementSide(AccountUserCommand command,
                                       AccountCommandTerminalResult terminal,
                                       Instant projectedAt) {
        try {
            TradeSideSettlementCommand sideCommand = objectMapper.readValue(
                    command.payload(), TradeSideSettlementCommand.class);
            JsonNode result = objectMapper.readTree(terminal.resultPayload());
            long consumedUnits = nonNegative(result, "orderMarginConsumedUnits");
            long releasedUnits = nonNegative(result, "orderMarginReleasedUnits");
            settlementSideRepository.project(command, sideCommand, consumedUnits, releasedUnits, projectedAt);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("成交结算侧异步投影失败 commandId=" + command.commandId(), ex);
        }
    }

    private long nonNegative(JsonNode result, String field) {
        long value = result == null ? 0L : result.path(field).asLong(0L);
        if (value < 0L) {
            throw new IllegalStateException(field + " must be non-negative");
        }
        return value;
    }
}
