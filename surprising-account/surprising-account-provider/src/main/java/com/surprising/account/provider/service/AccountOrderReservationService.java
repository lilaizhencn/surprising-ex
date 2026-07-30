package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountCommandRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.SpotOrderReservationRepository;
import com.surprising.account.provider.repository.TradeSettlementSideRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AccountOrderReservationService {

    private final AccountSequenceRepository sequenceRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final ProductBalanceRepository productBalanceRepository;
    private final SpotOrderReservationRepository spotReservationRepository;
    private final TradeSettlementSideRepository tradeSettlementSideRepository;
    private final AccountCommandRepository accountCommandRepository;

    public AccountOrderReservationService(AccountSequenceRepository sequenceRepository,
                                          AccountBalanceRepository accountBalanceRepository,
                                          ProductBalanceRepository productBalanceRepository,
                                          SpotOrderReservationRepository spotReservationRepository,
                                          TradeSettlementSideRepository tradeSettlementSideRepository,
                                          AccountCommandRepository accountCommandRepository) {
        this.sequenceRepository = sequenceRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.productBalanceRepository = productBalanceRepository;
        this.spotReservationRepository = spotReservationRepository;
        this.tradeSettlementSideRepository = tradeSettlementSideRepository;
        this.accountCommandRepository = accountCommandRepository;
    }

    public boolean reserve(ProductLine productLine,
                           long userId,
                           OrderReserveAccountCommand command,
                           Instant now) {
        requireAccountScope(productLine, command.accountType());
        if (command.reservationKind() == OrderReservationKind.SPOT_ASSET) {
            if (command.accountType() != AccountType.SPOT) {
                throw new IllegalStateException("spot reservation requires SPOT account");
            }
            return reserveSpot(userId, command, now);
        }
        if (command.accountType() == AccountType.SPOT) {
            throw new IllegalStateException("derivative reservation requires margin account");
        }
        return reserveDerivative(userId, command, now);
    }

    public long release(ProductLine productLine,
                        long userId,
                        long orderId,
                        boolean releaseAll,
                        long quantitySteps,
                        long remainingQuantitySteps,
                        boolean reservationExpected,
                        AccountType reservationAccountType,
                        String reservationAsset,
                        long reservedUnits,
                        String reason,
                        Instant now) {
        if (reservationAccountType == AccountType.SPOT) {
            return releaseSpot(productLine, userId, orderId, releaseAll, quantitySteps,
                    remainingQuantitySteps, reservationExpected, reason, now);
        }
        if (reservationAccountType == null || reservationAsset == null || reservationAsset.isBlank()
                || reservedUnits <= 0L) {
            if (reservationExpected) {
                throw new IllegalStateException("missing derivative reservation snapshot for order " + orderId);
            }
            return 0L;
        }
        requireReservationScope(productLine, userId, userId, reservationAccountType, orderId);
        TradeSettlementSideRepository.MarginUsage tradeUsage =
                tradeSettlementSideRepository.marginUsage(orderId);
        long commandReleased = accountCommandRepository.releasedOrderMargin(orderId);
        long releasedUnits = Math.addExact(tradeUsage.releasedUnits(), commandReleased);
        long unavailable = Math.addExact(tradeUsage.consumedUnits(), releasedUnits);
        if (unavailable > reservedUnits) {
            throw new IllegalStateException("order margin usage exceeds reservation for order " + orderId);
        }
        long amountUnits = releaseAll
                ? Math.subtractExact(reservedUnits, unavailable)
                : AccountMarginReleaseMath.releaseForExecuted(
                        reservedUnits, releasedUnits, tradeUsage.consumedUnits(),
                        quantitySteps, remainingQuantitySteps);
        if (amountUnits <= 0L) {
            return 0L;
        }
        releaseBalance(reservationAccountType, userId, reservationAsset, amountUnits, now);
        return amountUnits;
    }

    private boolean reserveDerivative(long userId, OrderReserveAccountCommand command, Instant now) {
        if (usesProductBalance(command.accountType())) {
            return productBalanceRepository.moveAvailableToLocked(
                    userId, command.accountType(), command.asset(), command.reservedUnits(), now);
        }
        return accountBalanceRepository.moveAvailableToLocked(
                userId, command.asset(), command.reservedUnits(), now);
    }

    private boolean reserveSpot(long userId, OrderReserveAccountCommand command, Instant now) {
        boolean reserved = productBalanceRepository.moveAvailableToLocked(
                userId, AccountType.SPOT, command.asset(), command.reservedUnits(), now);
        if (!reserved) {
            return false;
        }
        int rows = spotReservationRepository.insert(
                sequenceRepository.nextSpotReservationId(),
                command.orderId(), userId, command.symbol(), command.side(),
                command.asset(), command.reservedUnits(), now);
        requireSingleRow(rows, "account spot reservation insert");
        return true;
    }

    private long releaseSpot(ProductLine productLine,
                             long userId,
                             long orderId,
                             boolean releaseAll,
                             long quantitySteps,
                             long remainingQuantitySteps,
                             boolean reservationExpected,
                             String reason,
                             Instant now) {
        SpotOrderReservationRepository.SpotReservationRow reservation =
                spotReservationRepository.lock(orderId).orElse(null);
        if (reservation == null) {
            if (reservationExpected) {
                throw new IllegalStateException("missing spot reservation for order " + orderId);
            }
            return 0L;
        }
        requireReservationScope(productLine, userId, reservation.userId(), AccountType.SPOT, orderId);
        long amountUnits = releaseAll
                ? Math.subtractExact(reservation.reservedUnits(),
                        Math.addExact(reservation.releasedUnits(), reservation.settledUnits()))
                : AccountMarginReleaseMath.releaseForExecuted(
                        reservation.reservedUnits(), reservation.releasedUnits(), reservation.settledUnits(),
                        quantitySteps, remainingQuantitySteps);
        if (amountUnits <= 0L) {
            return 0L;
        }
        int balanceRows = productBalanceRepository.moveLockedToAvailable(
                reservation.userId(), AccountType.SPOT, reservation.asset(), amountUnits, now);
        requireSingleRow(balanceRows, "account spot balance release");
        int reservationRows = spotReservationRepository.release(orderId, amountUnits, reason, now);
        requireSingleRow(reservationRows, "account spot reservation release");
        return amountUnits;
    }

    private void releaseBalance(AccountType accountType,
                                long userId,
                                String asset,
                                long amountUnits,
                                Instant now) {
        int rows = usesProductBalance(accountType)
                ? productBalanceRepository.moveLockedToAvailable(userId, accountType, asset, amountUnits, now)
                : accountBalanceRepository.moveLockedToAvailable(userId, asset, amountUnits, now);
        requireSingleRow(rows, "account locked balance release");
    }

    private static void requireAccountScope(ProductLine productLine, AccountType accountType) {
        ProductLine expected = accountType.productLine()
                .orElseThrow(() -> new IllegalStateException("order reservation requires a product account"));
        if (productLine != expected) {
            throw new IllegalStateException("order reservation account does not match product line");
        }
    }

    private static void requireReservationScope(ProductLine productLine,
                                                long commandUserId,
                                                long reservationUserId,
                                                AccountType accountType,
                                                long orderId) {
        if (reservationUserId != commandUserId) {
            throw new IllegalStateException("account reservation user mismatch " + orderId);
        }
        requireAccountScope(productLine, accountType);
    }

    private static boolean usesProductBalance(AccountType accountType) {
        return accountType != AccountType.USDT_PERPETUAL;
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
