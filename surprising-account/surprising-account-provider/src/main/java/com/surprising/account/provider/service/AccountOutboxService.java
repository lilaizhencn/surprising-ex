package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.model.OpenInterestShardSnapshot;
import com.surprising.account.api.model.OpenInterestShardUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 编排账户事件、序列号和 outbox 单表写入。
 *
 * <p>所有事件都在调用方现有事务内完成构造和持久化，Repository 只负责
 * {@code account_outbox_events} 表。</p>
 */
@Service
public class AccountOutboxService {

    private final AccountOutboxRepository outboxRepository;
    private final AccountSequenceRepository sequenceRepository;
    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final PositionCacheProjectionService positionCacheProjectionService;

    public AccountOutboxService(AccountOutboxRepository outboxRepository,
                                AccountSequenceRepository sequenceRepository,
                                ObjectMapper objectMapper,
                                AccountProperties properties,
                                PositionCacheProjectionService positionCacheProjectionService) {
        this.outboxRepository = outboxRepository;
        this.sequenceRepository = sequenceRepository;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new AccountProperties() : properties;
        this.positionCacheProjectionService = positionCacheProjectionService;
    }

    public PositionUpdatedEvent enqueuePositionUpdated(String topic,
                                                       long tradeId,
                                                       PositionResponse position,
                                                       Instant now,
                                                       String traceId) {
        requireCurrentProductTopic(topic);
        ProductLine productLine = currentProductLine();
        PositionCacheEvent snapshot = positionCacheProjectionService.captureFinalSnapshot(
                productLine, position.userId(), position.symbol(), position.marginMode(),
                position.positionSide());
        long eventId = sequenceRepository.nextPositionEventId();
        PositionUpdatedEvent event = new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                tradeId,
                snapshot.productLine(),
                snapshot.revision(),
                snapshot.userId(),
                snapshot.symbol(),
                snapshot.instrumentVersion(),
                snapshot.marginMode(),
                snapshot.positionSide(),
                snapshot.signedQuantitySteps(),
                snapshot.entryPriceTicks(),
                snapshot.entryValueTicks(),
                snapshot.realizedPnlUnits(),
                snapshot.marginAsset(),
                snapshot.marginUnits(),
                snapshot.positionUpdatedAt(),
                snapshot.marginUpdatedAt(),
                now,
                traceId);
        outboxRepository.insert(productLine.name(), "POSITION", eventId, topic, event.partitionKey(),
                "POSITION_UPDATED", objectMapper.writeValueAsString(event), now);
        return event;
    }

    public LiquidationFeeSettledEvent enqueueLiquidationFeeSettled(String topic,
                                                                   long tradeId,
                                                                   long orderId,
                                                                   long liquidationOrderId,
                                                                   long candidateId,
                                                                   long userId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   String accountType,
                                                                   String asset,
                                                                   long amountUnits,
                                                                   long feeRatePpm,
                                                                   Instant now,
                                                                   String traceId) {
        requireCurrentProductTopic(topic);
        long eventId = sequenceRepository.nextLiquidationFeeEventId();
        LiquidationFeeSettledEvent event = new LiquidationFeeSettledEvent(eventId, tradeId, orderId,
                liquidationOrderId, candidateId, userId, symbol, marginMode, accountType, asset, amountUnits,
                feeRatePpm, now, traceId);
        outboxRepository.insert(currentProductLine().name(), "LIQUIDATION_FEE", eventId, topic, asset,
                "LIQUIDATION_FEE_SETTLED", objectMapper.writeValueAsString(event), now);
        return event;
    }

    /** 在仓位和未平仓量分片同一事务提交前写入 Kafka outbox。 */
    public OpenInterestShardUpdatedEvent enqueueOpenInterestUpdated(String topic,
                                                                     OpenInterestShardSnapshot shard,
                                                                     Instant now) {
        requireCurrentProductTopic(topic);
        OpenInterestShardUpdatedEvent event = new OpenInterestShardUpdatedEvent(
                OpenInterestShardUpdatedEvent.CURRENT_SCHEMA_VERSION,
                sequenceRepository.nextOpenInterestEventId(),
                shard.productLine(), shard.symbol(), shard.shardId(),
                shard.longQuantitySteps(), shard.shortQuantitySteps(), shard.revision(), now);
        outboxRepository.insert(shard.productLine().name(), "OPEN_INTEREST", event.eventId(), topic,
                event.partitionKey(), "OPEN_INTEREST_UPDATED", objectMapper.writeValueAsString(event), now);
        return event;
    }

    /** 使用当前账户产品线配置发布未平仓量增量，避免调用方重复组装 Topic。 */
    public OpenInterestShardUpdatedEvent enqueueOpenInterestUpdated(OpenInterestShardSnapshot shard,
                                                                     Instant now) {
        return enqueueOpenInterestUpdated(properties.getKafka().getOpenInterestEventsTopic(), shard, now);
    }

    public AccountCommandResultEvent enqueueCommandResult(String topic,
                                                          AccountUserCommand command,
                                                          AccountCommandStatus status,
                                                          String resultPayload,
                                                          String errorCode,
                                                          String errorMessage,
                                                          Instant now) {
        requireCurrentProductTopic(topic);
        long eventId = sequenceRepository.nextCommandResultEventId();
        AccountCommandResultEvent event = new AccountCommandResultEvent(
                eventId, command.commandId(), command.productLine(), command.userId(), command.commandType(),
                status, command.source(), command.sourceReference(), resultPayload, errorCode, errorMessage,
                now, command.traceId());
        outboxRepository.insert(currentProductLine().name(), "ACCOUNT_COMMAND_RESULT", eventId, topic,
                command.partitionKey(), status.name(), objectMapper.writeValueAsString(event), now);
        return event;
    }

    public void enqueueUserCommandRetry(String topic,
                                        String partitionKey,
                                        String serializedCommand,
                                        Instant now) {
        requireCurrentProductTopic(topic);
        long aggregateId = sequenceRepository.nextCommandRetryEventId();
        outboxRepository.insert(currentProductLine().name(), "ACCOUNT_COMMAND_RETRY", aggregateId, topic,
                partitionKey, "DEPENDENCY_READY", serializedCommand, now);
    }

    public void enqueueUserCommand(String topic,
                                   String aggregateType,
                                   AccountUserCommand command,
                                   Instant now) {
        requireCurrentProductTopic(topic);
        long aggregateId = sequenceRepository.nextUserCommandOutboxEventId();
        outboxRepository.insert(command.productLine().name(), aggregateType, aggregateId, topic,
                command.partitionKey(), command.commandType().name(), objectMapper.writeValueAsString(command), now);
    }

    private void requireCurrentProductTopic(String topic) {
        AccountProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String positionEventsTopic = kafka.getPositionEventsTopic();
        String openInterestEventsTopic = kafka.getOpenInterestEventsTopic();
        String liquidationFeeEventsTopic = kafka.getLiquidationFeeEventsTopic();
        String riskWalletEventsTopic = kafka.getRiskWalletEventsTopic();
        String commandResultsTopic = kafka.getCommandResultsTopic();
        String userCommandsTopic = kafka.getUserCommandsTopic();
        if (!positionEventsTopic.equals(topic)
                && !openInterestEventsTopic.equals(topic)
                && !liquidationFeeEventsTopic.equals(topic)
                && !riskWalletEventsTopic.equals(topic)
                && !commandResultsTopic.equals(topic)
                && !userCommandsTopic.equals(topic)) {
            throw new IllegalStateException("account outbox topic must match current product line: expected one of ["
                    + positionEventsTopic + ", " + openInterestEventsTopic + ", " + liquidationFeeEventsTopic
                    + ", " + riskWalletEventsTopic
                    + ", " + commandResultsTopic
                    + ", " + userCommandsTopic + "] actual=" + topic);
        }
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
    }
}
