package com.surprising.account.provider.service;

import com.surprising.account.api.model.FundingSettlementAccountCommand;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.model.FundingBalanceState;
import com.surprising.account.provider.repository.AccountFundingBalanceRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionMarginRepository.PositionMarginRow;
import com.surprising.account.provider.repository.ProductFundingBalanceRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资金费支付的权威写入服务。该服务必须位于账户模块，确保资金费计算节点不能修改账户余额、
 * 亏空、保证金或流水。
 */
@Service
public class FundingSettlementService {

    private final AccountSequenceRepository sequenceRepository;
    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final AccountFundingBalanceRepository accountFundingBalanceRepository;
    private final ProductFundingBalanceRepository productFundingBalanceRepository;
    private final PositionMarginRepository positionMarginRepository;

    public FundingSettlementService(AccountSequenceRepository sequenceRepository,
                                    AccountLedgerRepository accountLedgerRepository,
                                    ProductLedgerRepository productLedgerRepository,
                                    AccountFundingBalanceRepository accountFundingBalanceRepository,
                                    ProductFundingBalanceRepository productFundingBalanceRepository,
                                    PositionMarginRepository positionMarginRepository) {
        this.sequenceRepository = sequenceRepository;
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.accountFundingBalanceRepository = accountFundingBalanceRepository;
        this.productFundingBalanceRepository = productFundingBalanceRepository;
        this.positionMarginRepository = positionMarginRepository;
    }

    @Transactional
    public long apply(ProductLine productLine,
                      long userId,
                      String commandId,
                      FundingSettlementAccountCommand payment,
                      Instant now) {
        if (!productLine.isFundingProduct()) {
            throw new IllegalArgumentException("funding settlement requires a perpetual product line");
        }
        return usesProductAccount(productLine)
                ? applyProductAccount(productLine, userId, commandId, payment, now)
                : applyLegacyAccount(productLine, userId, commandId, payment, now);
    }

    private long applyLegacyAccount(ProductLine productLine,
                                    long userId,
                                    String commandId,
                                    FundingSettlementAccountCommand payment,
                                    Instant now) {
        int ledgerRows = accountLedgerRepository.insertFunding(
                sequenceRepository.nextLedgerEntryId(), userId, payment.asset(), payment.amountUnits(),
                commandId, reason(payment.amountUnits()), now);
        requireSingleRow(ledgerRows, "funding account ledger insert");
        long balanceAfter = applyBalance(productLine, userId, payment, now);
        int ledgerUpdateRows = accountLedgerRepository.updateFundingBalance(
                userId, payment.asset(), commandId, balanceAfter);
        requireSingleRow(ledgerUpdateRows, "funding account ledger balance update");
        return balanceAfter;
    }

    private long applyProductAccount(ProductLine productLine,
                                     long userId,
                                     String commandId,
                                     FundingSettlementAccountCommand payment,
                                     Instant now) {
        AccountType accountType = accountType(productLine);
        int ledgerRows = productLedgerRepository.insertFunding(
                sequenceRepository.nextProductLedgerEntryId(), accountType, userId, payment.asset(),
                payment.amountUnits(), commandId, reason(payment.amountUnits()), now);
        requireSingleRow(ledgerRows, "funding product account ledger insert");
        long balanceAfter = applyProductBalance(productLine, userId, payment, now);
        int ledgerUpdateRows = productLedgerRepository.updateFundingBalance(
                userId, accountType, payment.asset(), commandId, balanceAfter);
        requireSingleRow(ledgerUpdateRows, "funding product account ledger balance update");
        return balanceAfter;
    }

    private long applyBalance(ProductLine productLine,
                              long userId,
                              FundingSettlementAccountCommand payment,
                              Instant now) {
        List<PositionMarginRow> lockedMargins = lockDebitMargins(productLine, userId, payment);
        long maxLockedDebit = sumMargins(lockedMargins);
        FundingBalanceState current = accountFundingBalanceRepository.lockOrCreate(
                userId, payment.asset(), now);
        FundingBalanceState next = applyPayment(
                current, payment.marginMode(), payment.amountUnits(), maxLockedDebit);
        reducePositionMargins(productLine, userId, payment.asset(),
                Math.subtractExact(current.lockedUnits(), next.lockedUnits()), lockedMargins, now);
        accountFundingBalanceRepository.update(userId, payment.asset(), next, now);
        return netBalance(next);
    }

    private long applyProductBalance(ProductLine productLine,
                                     long userId,
                                     FundingSettlementAccountCommand payment,
                                     Instant now) {
        AccountType accountType = accountType(productLine);
        List<PositionMarginRow> lockedMargins = lockDebitMargins(productLine, userId, payment);
        long maxLockedDebit = sumMargins(lockedMargins);
        FundingBalanceState current = productFundingBalanceRepository.lockOrCreate(
                accountType, userId, payment.asset(), now);
        FundingBalanceState next = applyPayment(
                current, payment.marginMode(), payment.amountUnits(), maxLockedDebit);
        reducePositionMargins(productLine, userId, payment.asset(),
                Math.subtractExact(current.lockedUnits(), next.lockedUnits()), lockedMargins, now);
        productFundingBalanceRepository.update(accountType, userId, payment.asset(), next, now);
        return netBalance(next);
    }

    private List<PositionMarginRow> lockDebitMargins(ProductLine productLine,
                                                     long userId,
                                                     FundingSettlementAccountCommand payment) {
        if (payment.amountUnits() >= 0) {
            return List.of();
        }
        return positionMarginRepository.lockForFunding(productLine, userId, payment.symbol(), payment.asset(),
                payment.marginMode(), payment.positionSide());
    }

    private FundingBalanceState applyPayment(FundingBalanceState current,
                                             MarginMode marginMode,
                                             long amountUnits,
                                             long maxLockedDebitUnits) {
        if (amountUnits > 0) {
            long availableDeficit = Math.subtractExact(
                    current.deficitUnits(), current.reservedDeficitUnits());
            long deficitOffset = Math.min(availableDeficit, amountUnits);
            return new FundingBalanceState(
                    Math.addExact(current.availableUnits(), Math.subtractExact(amountUnits, deficitOffset)),
                    current.lockedUnits(),
                    Math.subtractExact(current.deficitUnits(), deficitOffset),
                    current.reservedDeficitUnits());
        }
        long availableInput = marginMode == MarginMode.ISOLATED ? 0L : current.availableUnits();
        long charge = Math.negateExact(amountUnits);
        long fromAvailable = Math.min(availableInput, charge);
        long remaining = Math.subtractExact(charge, fromAvailable);
        long fromLocked = Math.min(Math.min(current.lockedUnits(), Math.max(0L, maxLockedDebitUnits)), remaining);
        FundingBalanceState calculated = new FundingBalanceState(
                Math.subtractExact(availableInput, fromAvailable),
                Math.subtractExact(current.lockedUnits(), fromLocked),
                Math.addExact(current.deficitUnits(), Math.subtractExact(remaining, fromLocked)),
                current.reservedDeficitUnits());
        return marginMode == MarginMode.ISOLATED
                ? new FundingBalanceState(current.availableUnits(), calculated.lockedUnits(), calculated.deficitUnits(),
                        calculated.reservedDeficitUnits())
                : calculated;
    }

    private void reducePositionMargins(ProductLine productLine,
                                       long userId,
                                       String asset,
                                       long debitUnits,
                                       List<PositionMarginRow> lockedMargins,
                                       Instant now) {
        long remaining = debitUnits;
        for (PositionMarginRow margin : lockedMargins) {
            if (remaining <= 0) {
                break;
            }
            long debit = Math.min(margin.marginUnits(), remaining);
            requireSingleRow(positionMarginRepository.subtract(
                    productLine, userId, margin.symbol(), asset,
                    margin.marginMode(), margin.positionSide(), debit, now), "funding position margin debit");
            positionMarginRepository.deleteZero(
                    productLine, userId, margin.symbol(), asset,
                    margin.marginMode(), margin.positionSide());
            remaining = Math.subtractExact(remaining, debit);
        }
        if (remaining != 0) {
            throw new IllegalStateException("insufficient position margin for funding locked debit");
        }
    }

    private long sumMargins(List<PositionMarginRow> margins) {
        return margins.stream().mapToLong(PositionMarginRow::marginUnits).reduce(0L, Math::addExact);
    }

    private long netBalance(FundingBalanceState state) {
        return Math.subtractExact(Math.addExact(state.availableUnits(), state.lockedUnits()), state.deficitUnits());
    }

    private String reason(long amountUnits) {
        return amountUnits >= 0 ? "FUNDING_RECEIVED" : "FUNDING_PAID";
    }

    private boolean usesProductAccount(ProductLine productLine) {
        return productLine != ProductLine.LINEAR_PERPETUAL;
    }

    private AccountType accountType(ProductLine productLine) {
        return AccountType.valueOf(productLine.accountTypeCode());
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

}
