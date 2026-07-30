package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.SpotOrderReservationRepository;
import com.surprising.trading.api.model.OrderSide;
import java.math.BigInteger;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class SpotTradeSettlementService {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final AccountSequenceRepository sequenceRepository;
    private final ProductBalanceRepository productBalanceRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final SpotOrderReservationRepository reservationRepository;

    public SpotTradeSettlementService(AccountSequenceRepository sequenceRepository,
                                      ProductBalanceRepository productBalanceRepository,
                                      ProductLedgerRepository productLedgerRepository,
                                      SpotOrderReservationRepository reservationRepository) {
        this.sequenceRepository = sequenceRepository;
        this.productBalanceRepository = productBalanceRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.reservationRepository = reservationRepository;
    }

    public void settle(long userId,
                       long orderId,
                       long tradeId,
                       String symbol,
                       OrderSide side,
                       long priceTicks,
                       long quantitySteps,
                       SpotInstrumentSpec spec,
                       long feeRatePpm,
                       String feeReason,
                       boolean orderCompleted,
                       Instant now) {
        if (priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("priceTicks and quantitySteps must be positive");
        }
        SpotOrderReservationRepository.SpotReservationRow reservation =
                reservationRepository.lock(orderId, userId, symbol)
                        .orElseThrow(() -> new IllegalStateException(
                                "active spot reservation not found for order " + orderId));
        if (reservation.side() != side) {
            throw new IllegalStateException("spot reservation side mismatch for order " + orderId);
        }
        long baseUnits = multiplyToLong(quantitySteps, spec.quantityStepUnits());
        long quoteUnits = multiplyToLong(priceTicks, quantitySteps, spec.notionalMultiplierUnits());
        long feeUnits = feeUnits(quoteUnits, feeRatePpm);
        long positiveFeeUnits = feeRatePpm > 0 ? feeUnits : 0L;
        long settledUnits = side == OrderSide.BUY
                ? Math.addExact(quoteUnits, positiveFeeUnits)
                : baseUnits;
        long remainingReservationUnits = Math.subtractExact(
                reservation.reservedUnits(),
                Math.addExact(reservation.settledUnits(), reservation.releasedUnits()));
        if (settledUnits > remainingReservationUnits) {
            throw new IllegalStateException("spot reservation is smaller than filled amount for order " + orderId);
        }
        long releaseUnits = orderCompleted
                ? Math.subtractExact(remainingReservationUnits, settledUnits)
                : 0L;
        if (side == OrderSide.BUY) {
            debitLocked(userId, spec.quoteAsset(), quoteUnits, now, tradeId, orderId, "SPOT_BUY_COST");
            if (positiveFeeUnits > 0) {
                debitLocked(userId, spec.quoteAsset(), positiveFeeUnits, now, tradeId, orderId, feeReason);
            } else if (feeUnits > 0) {
                creditAvailable(userId, spec.quoteAsset(), feeUnits, now, tradeId, orderId, feeReason);
            }
            creditAvailable(userId, spec.baseAsset(), baseUnits, now, tradeId, orderId, "SPOT_BUY_FILL");
            releaseLocked(userId, spec.quoteAsset(), releaseUnits, now);
        } else {
            debitLocked(userId, spec.baseAsset(), baseUnits, now, tradeId, orderId, "SPOT_SELL_BASE");
            creditAvailable(userId, spec.quoteAsset(), quoteUnits, now, tradeId, orderId, "SPOT_SELL_PROCEEDS");
            if (positiveFeeUnits > 0) {
                debitAvailable(userId, spec.quoteAsset(), positiveFeeUnits, now, tradeId, orderId, feeReason);
            } else if (feeUnits > 0) {
                creditAvailable(userId, spec.quoteAsset(), feeUnits, now, tradeId, orderId, feeReason);
            }
            releaseLocked(userId, spec.baseAsset(), releaseUnits, now);
        }
        int rows = reservationRepository.settle(orderId, settledUnits, releaseUnits, feeReason, now);
        requireSingleRow(rows, "spot reservation settlement update");
    }

    private void debitLocked(long userId,
                             String asset,
                             long amountUnits,
                             Instant now,
                             long tradeId,
                             long orderId,
                             String reason) {
        if (amountUnits <= 0) {
            return;
        }
        int rows = productBalanceRepository.debitLocked(
                userId, AccountType.SPOT, asset, amountUnits, now);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked spot balance for order " + orderId);
        }
        insertLedger(userId, asset, Math.negateExact(amountUnits), tradeId, orderId, reason, now);
    }

    private void releaseLocked(long userId, String asset, long amountUnits, Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        int rows = productBalanceRepository.moveLockedToAvailable(
                userId, AccountType.SPOT, asset, amountUnits, now);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked spot balance for release");
        }
    }

    private void creditAvailable(long userId,
                                 String asset,
                                 long amountUnits,
                                 Instant now,
                                 long tradeId,
                                 long orderId,
                                 String reason) {
        if (amountUnits <= 0) {
            return;
        }
        int rows = productBalanceRepository.creditAvailable(
                userId, AccountType.SPOT, asset, amountUnits, now);
        requireSingleRow(rows, "spot available credit");
        insertLedger(userId, asset, amountUnits, tradeId, orderId, reason, now);
    }

    private void debitAvailable(long userId,
                                String asset,
                                long amountUnits,
                                Instant now,
                                long tradeId,
                                long orderId,
                                String reason) {
        if (amountUnits <= 0) {
            return;
        }
        int rows = productBalanceRepository.debitAvailable(
                userId, AccountType.SPOT, asset, amountUnits, now);
        if (rows != 1) {
            throw new IllegalStateException("insufficient available spot balance for fee");
        }
        insertLedger(userId, asset, Math.negateExact(amountUnits), tradeId, orderId, reason, now);
    }

    private void insertLedger(long userId,
                              String asset,
                              long amountUnits,
                              long tradeId,
                              long orderId,
                              String reason,
                              Instant now) {
        String referenceId = tradeId + ":" + orderId + ":" + reason;
        int rows = productLedgerRepository.insertSpotTrade(
                sequenceRepository.nextProductLedgerEntryId(),
                userId, asset, amountUnits,
                productBalanceRepository.equity(userId, AccountType.SPOT, asset),
                referenceId, reason, now);
        requireSingleRow(rows, "spot trade ledger insert");
    }

    private static long feeUnits(long quoteUnits, long feeRatePpm) {
        if (feeRatePpm == 0L) {
            return 0L;
        }
        BigInteger numerator = BigInteger.valueOf(quoteUnits)
                .multiply(BigInteger.valueOf(Math.absExact(feeRatePpm)));
        return divideCeiling(numerator, PPM);
    }

    private static long multiplyToLong(long... values) {
        BigInteger product = BigInteger.ONE;
        for (long value : values) {
            if (value <= 0L) {
                throw new IllegalArgumentException("spot settlement inputs must be positive");
            }
            product = product.multiply(BigInteger.valueOf(value));
        }
        return product.longValueExact();
    }

    private static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() < 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("positive numerator and denominator are required");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return (quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE)).longValueExact();
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
