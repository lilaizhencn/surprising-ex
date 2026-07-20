package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AdlTargetSettlementAccountCommand;
import com.surprising.account.api.model.BalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.DeficitReservationAccountCommand;
import com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand;
import com.surprising.account.api.model.FundingSettlementAccountCommand;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountCommandRegistration;
import com.surprising.account.provider.repository.AccountCommandRepository;
import com.surprising.account.provider.repository.AccountAdlTargetSettlementRepository;
import com.surprising.account.provider.repository.AccountDeficitSettlementRepository;
import com.surprising.account.provider.repository.AccountFundingSettlementRepository;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountOrderReservationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountUserCommandProcessor {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountCommandRepository commandRepository;
    private final AccountOutboxRepository outboxRepository;
    private final AccountAdlTargetSettlementRepository adlTargetSettlementRepository;
    private final AccountDeficitSettlementRepository deficitSettlementRepository;
    private final AccountFundingSettlementRepository fundingSettlementRepository;
    private final AccountRepository accountRepository;
    private final AccountOrderReservationRepository orderReservationRepository;
    private final AccountService accountService;
    private final PositionCacheAfterCommitSynchronizer positionCacheSynchronizer;

    public AccountUserCommandProcessor(ObjectMapper objectMapper,
                                       AccountProperties properties,
                                       AccountCommandRepository commandRepository,
                                       AccountOutboxRepository outboxRepository,
                                       AccountAdlTargetSettlementRepository adlTargetSettlementRepository,
                                       AccountDeficitSettlementRepository deficitSettlementRepository,
                                       AccountFundingSettlementRepository fundingSettlementRepository,
                                       AccountRepository accountRepository,
                                       AccountOrderReservationRepository orderReservationRepository,
                                       AccountService accountService,
                                       PositionCacheAfterCommitSynchronizer positionCacheSynchronizer) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.commandRepository = commandRepository;
        this.outboxRepository = outboxRepository;
        this.adlTargetSettlementRepository = adlTargetSettlementRepository;
        this.deficitSettlementRepository = deficitSettlementRepository;
        this.fundingSettlementRepository = fundingSettlementRepository;
        this.accountRepository = accountRepository;
        this.orderReservationRepository = orderReservationRepository;
        this.accountService = accountService;
        this.positionCacheSynchronizer = positionCacheSynchronizer;
    }

    @Transactional
    public List<ProcessingOutcome> processBatch(List<CommandEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        accountRepository.lockOpenInterestShards(openInterestLockRequests(envelopes), Instant.now());
        List<ProcessingOutcome> outcomes = new ArrayList<>(envelopes.size());
        for (CommandEnvelope envelope : envelopes) {
            if (envelope == null || envelope.command() == null
                    || envelope.serializedEnvelope() == null || envelope.serializedEnvelope().isBlank()) {
                throw new AccountCommandPoisonPillException("invalid account command batch envelope");
            }
            outcomes.add(processOne(envelope.command(), envelope.serializedEnvelope()));
        }
        return List.copyOf(outcomes);
    }

    private List<AccountRepository.OpenInterestLockRequest> openInterestLockRequests(
            List<CommandEnvelope> envelopes) {
        List<AccountRepository.OpenInterestLockRequest> requests = new ArrayList<>();
        for (CommandEnvelope envelope : envelopes) {
            if (envelope == null || envelope.command() == null) {
                continue;
            }
            AccountUserCommand command = envelope.command();
            String symbol = switch (command.commandType()) {
                case TRADE_SIDE_SETTLE -> readPayload(command, TradeSideSettlementCommand.class).trade().symbol();
                case ADL_TARGET_SETTLE -> readPayload(command, AdlTargetSettlementAccountCommand.class).symbol();
                case DELIVERY_SETTLE, OPTION_EXERCISE ->
                        readPayload(command, ExpiringPositionSettlementAccountCommand.class).symbol();
                default -> null;
            };
            if (symbol != null) {
                requests.add(new AccountRepository.OpenInterestLockRequest(
                        command.productLine(), command.userId(), symbol));
            }
        }
        return requests;
    }

    private ProcessingOutcome processOne(AccountUserCommand command, String serializedEnvelope) {
        Instant now = Instant.now();
        AccountCommandRegistration registration = commandRepository.register(command, serializedEnvelope, now);
        if (registration == AccountCommandRegistration.ALREADY_TERMINAL) {
            return ProcessingOutcome.DUPLICATE;
        }
        if (registration == AccountCommandRegistration.WAITING_DEPENDENCY) {
            return ProcessingOutcome.WAITING_DEPENDENCY;
        }
        if (registration == AccountCommandRegistration.DEPENDENCY_REJECTED) {
            Instant completedAt = Instant.now();
            String message = "dependency command was rejected: " + command.dependsOnCommandId();
            commandRepository.markRejected(command.commandId(), null, "DEPENDENCY_REJECTED", message, completedAt);
            enqueueCommandResultIfObserved(command,
                    AccountCommandStatus.REJECTED, null, "DEPENDENCY_REJECTED", message, completedAt);
            requeueWaitingDependents(command.commandId(), completedAt);
            return ProcessingOutcome.REJECTED;
        }

        String resultPayload;
        try {
            resultPayload = dispatch(command);
            Instant completedAt = Instant.now();
            commandRepository.markApplied(command.commandId(), resultPayload, completedAt);
            enqueueCommandResultIfObserved(command,
                    AccountCommandStatus.APPLIED, resultPayload, null, null, completedAt);
            requeueWaitingDependents(command.commandId(), completedAt);
            return ProcessingOutcome.APPLIED;
        } catch (AccountCommandRejectedException ex) {
            Instant completedAt = Instant.now();
            commandRepository.markRejected(command.commandId(), ex.resultPayload(), ex.errorCode(),
                    ex.getMessage(), completedAt);
            enqueueCommandResultIfObserved(command,
                    AccountCommandStatus.REJECTED, ex.resultPayload(), ex.errorCode(), ex.getMessage(), completedAt);
            requeueWaitingDependents(command.commandId(), completedAt);
            return ProcessingOutcome.REJECTED;
        }
    }

    private String dispatch(AccountUserCommand command) {
        return switch (command.commandType()) {
            case ORDER_RESERVE -> {
                OrderReserveAccountCommand reserve = readPayload(command, OrderReserveAccountCommand.class);
                boolean reserved = orderReservationRepository.reserve(command.productLine(), command.userId(),
                        reserve, Instant.now());
                if (!reserved) {
                    throw new AccountCommandRejectedException(
                            "INSUFFICIENT_AVAILABLE_BALANCE", "insufficient available balance for order reservation");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("orderId", reserve.orderId());
                result.put("reservedUnits", reserve.reservedUnits());
                result.put("asset", reserve.asset());
                result.put("reservationKind", reserve.reservationKind().name());
                yield objectMapper.writeValueAsString(result);
            }
            case ORDER_RELEASE -> {
                OrderReleaseAccountCommand release = readPayload(command, OrderReleaseAccountCommand.class);
                long releasedUnits = orderReservationRepository.release(
                        command.productLine(), command.userId(), release.orderId(),
                        release.releaseAll(), release.quantitySteps(), release.remainingQuantitySteps(),
                        release.reservationExpected(), release.reservationAccountType(), release.reservationAsset(),
                        release.reservedUnits(), release.reason(), release.effectiveAt());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("orderId", release.orderId());
                result.put("released", true);
                result.put("releasedUnits", releasedUnits);
                yield objectMapper.writeValueAsString(result);
            }
            case TRADE_SIDE_SETTLE -> {
                TradeSideSettlementCommand side = readPayload(command, TradeSideSettlementCommand.class);
                if (side.userId() != command.userId()) {
                    throw new AccountCommandPoisonPillException(
                            "trade side user does not match account command user");
                }
                accountService.processTradeSide(command.productLine(), command.commandId(), side);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("tradeId", side.trade().tradeId());
                result.put("orderId", side.orderId());
                result.put("participantRole", side.participantRole().name());
                yield objectMapper.writeValueAsString(result);
            }
            case FUNDING_SETTLE -> {
                FundingSettlementAccountCommand funding =
                        readPayload(command, FundingSettlementAccountCommand.class);
                long balanceAfter = fundingSettlementRepository.apply(command.productLine(), command.userId(),
                        command.commandId(), funding, Instant.now());
                accountRepository.positions(command.productLine(), command.userId(), funding.positionSide())
                        .stream()
                        .filter(position -> position.symbol().equals(funding.symbol()))
                        .filter(position -> position.marginMode() == funding.marginMode())
                        .forEach(position -> {
                            var event = outboxRepository.enqueuePositionUpdated(
                                    properties.getKafka().getPositionEventsTopic(), funding.paymentId(), position,
                                    Instant.now(), command.traceId());
                            positionCacheSynchronizer.schedule(event.cacheEvent());
                        });
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("settlementId", funding.settlementId());
                result.put("paymentId", funding.paymentId());
                result.put("asset", funding.asset());
                result.put("amountUnits", funding.amountUnits());
                result.put("balanceAfterUnits", balanceAfter);
                yield objectMapper.writeValueAsString(result);
            }
            case ADL_DEFICIT_RESERVE, INSURANCE_DEFICIT_RESERVE -> {
                DeficitReservationAccountCommand reserve =
                        readPayload(command, DeficitReservationAccountCommand.class);
                if (!deficitSettlementRepository.reserve(command.productLine(), command.userId(),
                        reserve.asset(), reserve.amountUnits(), Instant.now())) {
                    throw new AccountCommandRejectedException("DEFICIT_NOT_AVAILABLE",
                            "requested deficit is already reserved or no longer exists");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("asset", reserve.asset());
                result.put("reservedUnits", reserve.amountUnits());
                yield objectMapper.writeValueAsString(result);
            }
            case ADL_DEFICIT_FINALIZE, INSURANCE_DEFICIT_FINALIZE -> {
                DeficitReservationAccountCommand finalize =
                        readPayload(command, DeficitReservationAccountCommand.class);
                boolean adl = command.commandType()
                        == com.surprising.account.api.model.AccountUserCommandType.ADL_DEFICIT_FINALIZE;
                long remaining = deficitSettlementRepository.finalizeReservation(
                        command.productLine(), command.userId(), finalize.asset(), finalize.amountUnits(),
                        command.commandId(), adl ? "ADL_COVERAGE" : "INSURANCE_COVERAGE",
                        adl ? "ADL_DEFICIT_COVERAGE" : "COVER_ACCOUNT_DEFICIT", Instant.now());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("asset", finalize.asset());
                result.put("coveredUnits", finalize.amountUnits());
                result.put("remainingDeficitUnits", remaining);
                yield objectMapper.writeValueAsString(result);
            }
            case ADL_DEFICIT_RELEASE, INSURANCE_DEFICIT_RELEASE -> {
                DeficitReservationAccountCommand release =
                        readPayload(command, DeficitReservationAccountCommand.class);
                long available = deficitSettlementRepository.releaseReservation(
                        command.productLine(), command.userId(), release.asset(), release.amountUnits(), Instant.now());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("asset", release.asset());
                result.put("releasedUnits", release.amountUnits());
                result.put("availableDeficitUnits", available);
                yield objectMapper.writeValueAsString(result);
            }
            case ADL_TARGET_SETTLE -> {
                AdlTargetSettlementAccountCommand adl =
                        readPayload(command, AdlTargetSettlementAccountCommand.class);
                var settled = adlTargetSettlementRepository.settle(
                        command.productLine(), command.userId(), command.commandId(), adl, Instant.now());
                if (!settled.applied()) {
                    throw new AccountCommandRejectedException(
                            "STALE_ADL_TARGET", "ADL target position changed before settlement");
                }
                accountRepository.position(command.productLine(), command.userId(), adl.symbol(),
                                adl.marginMode(), adl.positionSide())
                        .ifPresent(position -> {
                            var event = outboxRepository.enqueuePositionUpdated(
                                    properties.getKafka().getPositionEventsTopic(), adl.executionId(), position,
                                    Instant.now(), command.traceId());
                            positionCacheSynchronizer.schedule(event.cacheEvent());
                        });
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("executionId", adl.executionId());
                result.put("realizedProfitUnits", settled.realizedProfitUnits());
                result.put("coveredUnits", settled.coveredUnits());
                result.put("nextSignedQuantitySteps", settled.nextSignedQuantitySteps());
                yield objectMapper.writeValueAsString(result);
            }
            case DELIVERY_SETTLE, OPTION_EXERCISE -> {
                ExpiringPositionSettlementAccountCommand settlement =
                        readPayload(command, ExpiringPositionSettlementAccountCommand.class);
                var position = accountService.processExpiringPosition(
                        command.productLine(), command.userId(), command.commandId(), settlement);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("symbol", settlement.symbol());
                result.put("instrumentVersion", settlement.instrumentVersion());
                result.put("positionSettled", position.isPresent());
                position.ifPresent(value -> result.put(
                        "nextSignedQuantitySteps", value.signedQuantitySteps()));
                yield objectMapper.writeValueAsString(result);
            }
            case BALANCE_ADJUST -> {
                BalanceAdjustmentAccountCommand adjustment =
                        readPayload(command, BalanceAdjustmentAccountCommand.class);
                requireCommandUser(command, adjustment.request().userId());
                try {
                    var response = adjustment.adminOperation()
                            ? accountService.adminAdjustBalance(
                                    adjustment.adminUserId(), adjustment.adminUsername(), adjustment.request())
                            : accountService.adjustBalance(adjustment.request());
                    yield objectMapper.writeValueAsString(response);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    throw rejectedMutation(ex);
                }
            }
            case PRODUCT_BALANCE_ADJUST -> {
                ProductBalanceAdjustmentAccountCommand adjustment =
                        readPayload(command, ProductBalanceAdjustmentAccountCommand.class);
                requireCommandUser(command, adjustment.request().userId());
                try {
                    var response = adjustment.adminOperation()
                            ? accountService.adminAdjustProductBalance(
                                    adjustment.adminUserId(), adjustment.adminUsername(), adjustment.request())
                            : accountService.adjustProductBalance(adjustment.request());
                    yield objectMapper.writeValueAsString(response);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    throw rejectedMutation(ex);
                }
            }
            case PRODUCT_TRANSFER -> {
                ProductTransferRequest transfer = readPayload(command, ProductTransferRequest.class);
                requireCommandUser(command, transfer.userId());
                try {
                    yield objectMapper.writeValueAsString(accountService.transfer(transfer));
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    throw rejectedMutation(ex);
                }
            }
            case POSITION_MARGIN_ADJUST -> {
                PositionMarginAdjustmentRequest adjustment =
                        readPayload(command, PositionMarginAdjustmentRequest.class);
                requireCommandUser(command, adjustment.userId());
                try {
                    yield objectMapper.writeValueAsString(accountService.adjustPositionMargin(adjustment));
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    throw rejectedMutation(ex);
                }
            }
            case POSITION_MODE_UPDATE -> {
                PositionModeUpdateRequest update = readPayload(command, PositionModeUpdateRequest.class);
                requireCommandUser(command, update.userId());
                if (update.productLine() != command.productLine()) {
                    throw new AccountCommandRejectedException(
                            "PRODUCT_LINE_MISMATCH", "position mode product line does not match command");
                }
                try {
                    yield objectMapper.writeValueAsString(accountService.updatePositionMode(update));
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    throw rejectedMutation(ex);
                }
            }
            default -> throw new AccountCommandRejectedException(
                    "UNSUPPORTED_COMMAND_TYPE", "unsupported account command type " + command.commandType());
        };
    }

    private void requireCommandUser(AccountUserCommand command, long payloadUserId) {
        if (payloadUserId != command.userId()) {
            throw new AccountCommandPoisonPillException(
                    "account command payload user does not match envelope user");
        }
    }

    private AccountCommandRejectedException rejectedMutation(RuntimeException ex) {
        return new AccountCommandRejectedException("ACCOUNT_MUTATION_REJECTED", ex.getMessage());
    }

    private <T> T readPayload(AccountUserCommand command, Class<T> type) {
        try {
            return objectMapper.readValue(command.payload(), type);
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException(
                    "invalid payload for account command " + command.commandId(), ex);
        }
    }

    private void requeueWaitingDependents(String completedCommandId, Instant now) {
        for (var dependent : commandRepository.waitingDependents(completedCommandId)) {
            outboxRepository.enqueueUserCommandRetry(properties.getKafka().getUserCommandsTopic(),
                    dependent.partitionKey(), dependent.serializedEnvelope(), now);
        }
    }

    private void enqueueCommandResultIfObserved(AccountUserCommand command,
                                                AccountCommandStatus status,
                                                String resultPayload,
                                                String errorCode,
                                                String errorMessage,
                                                Instant completedAt) {
        if (command.commandType()
                == com.surprising.account.api.model.AccountUserCommandType.TRADE_SIDE_SETTLE) {
            return;
        }
        outboxRepository.enqueueCommandResult(properties.getKafka().getCommandResultsTopic(), command,
                status, resultPayload, errorCode, errorMessage, completedAt);
    }

    public enum ProcessingOutcome {
        APPLIED,
        REJECTED,
        WAITING_DEPENDENCY,
        DUPLICATE
    }

    public record CommandEnvelope(AccountUserCommand command, String serializedEnvelope) {
    }
}
