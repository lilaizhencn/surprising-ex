package com.surprising.instrument.provider.service;

import com.surprising.instrument.provider.repository.InstrumentOutboxRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class InstrumentOutboxService {

    private final ObjectMapper objectMapper;
    private final InstrumentOutboxRepository outboxRepository;

    public InstrumentOutboxService(ObjectMapper objectMapper,
                                   InstrumentOutboxRepository outboxRepository) {
        this.objectMapper = objectMapper;
        this.outboxRepository = outboxRepository;
    }

    public long enqueue(String aggregateType,
                        long aggregateId,
                        String topic,
                        String eventKey,
                        String eventType,
                        Object payload,
                        Instant now) {
        try {
            return outboxRepository.enqueue(aggregateType, aggregateId, topic, eventKey,
                    eventType, objectMapper.writeValueAsString(payload), now);
        } catch (JacksonException ex) {
            throw new IllegalStateException("instrument 事件序列化失败: " + eventType, ex);
        }
    }
}
