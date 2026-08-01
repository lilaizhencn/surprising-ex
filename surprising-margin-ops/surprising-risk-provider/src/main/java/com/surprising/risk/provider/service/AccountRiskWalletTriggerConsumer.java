package com.surprising.risk.provider.service;

import com.surprising.account.api.model.AccountRiskWalletUpdatedEvent;
import com.surprising.risk.provider.config.RiskProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 消费账户单写者发布的风险钱包快照，失败时保留 Kafka 重试，不允许回查账户数据库兜底。 */
@Service
public class AccountRiskWalletTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountRiskWalletTriggerConsumer.class);

    private final ObjectMapper objectMapper;
    private final RiskService riskService;
    private final RiskProperties properties;

    public AccountRiskWalletTriggerConsumer(ObjectMapper objectMapper,
                                            RiskService riskService,
                                            RiskProperties properties) {
        this.objectMapper = objectMapper;
        this.riskService = riskService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.accountRiskWalletEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "riskAccountWalletKafkaListenerContainerFactory")
    public void onAccountRiskWalletUpdated(List<ConsumerRecord<String, String>> records) {
        try {
            if (records == null || records.isEmpty()) {
                return;
            }
            List<AccountRiskWalletUpdatedEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                requireCurrentProductTopic(record.topic());
                AccountRiskWalletUpdatedEvent event = objectMapper.readValue(
                        record.value(), AccountRiskWalletUpdatedEvent.class);
                if (event.productLine() != properties.getKafka().getProductLine()) {
                    throw new IllegalArgumentException("风险钱包事件产品线不一致");
                }
                if (!event.partitionKey().equals(record.key())) {
                    throw new IllegalArgumentException("风险钱包事件 Kafka key 不匹配: " + event.partitionKey());
                }
                events.add(event);
            }
            riskService.scanAccountWalletUpdates(events);
        } catch (Exception ex) {
            log.error("Failed to process account risk wallet records={}: {}",
                    records == null ? 0 : records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to process account risk wallet batch", ex);
        }
    }

    public String accountRiskWalletEventsTopic() {
        return properties.getKafka().getAccountRiskWalletEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getAccountRiskWalletGroupId();
    }

    private void requireCurrentProductTopic(String topic) {
        RiskProperties.Kafka kafka = properties.getKafka();
        if (kafka.isProductTopicsEnabled() && !kafka.getAccountRiskWalletEventsTopic().equals(topic)) {
            throw new IllegalArgumentException("风险钱包事件 Topic 不属于当前产品线: expected="
                    + kafka.getAccountRiskWalletEventsTopic() + " actual=" + topic);
        }
    }
}
