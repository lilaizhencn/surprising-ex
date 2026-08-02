package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public AccountLedgerProjectionService(ProductLedgerRepository productLedgerRepository,
                                          AccountSequenceRepository sequenceRepository) {
        this.productLedgerRepository = productLedgerRepository;
        this.sequenceRepository = sequenceRepository;
    }

    /** 一个命令的全部账本明细在同一数据库事务中提交。 */
    @Transactional
    public void project(AccountUserCommand command,
                        AccountCommandTerminalResult terminal,
                        Instant projectedAt) {
        if (command == null || terminal == null || terminal.status() != AccountCommandStatus.APPLIED
                || terminal.ledgerDeltas().isEmpty()) {
            return;
        }
        AccountType accountType = AccountType.valueOf(command.productLine().accountTypeCode());
        Instant now = projectedAt == null ? Instant.now() : projectedAt;
        for (AccountCommandTerminalResult.LedgerDelta delta : terminal.ledgerDeltas()) {
            productLedgerRepository.projectCommandDelta(
                    sequenceRepository.nextProductLedgerEntryId(), command.userId(), accountType,
                    delta.asset(), delta.amountUnits(), delta.balanceAfterUnits(), delta.referenceType(),
                    delta.referenceId(), delta.reason(), delta.symbol(), now);
        }
    }
}
