package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountCommandSubmissionRepository;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountCommandSubmissionService {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountCommandSubmissionRepository submissionRepository;
    private final AccountOutboxRepository outboxRepository;

    public AccountCommandSubmissionService(ObjectMapper objectMapper,
                                           AccountProperties properties,
                                           AccountCommandSubmissionRepository submissionRepository,
                                           AccountOutboxRepository outboxRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.submissionRepository = submissionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void submit(AccountUserCommand command) {
        String serializedEnvelope = objectMapper.writeValueAsString(command);
        Instant now = Instant.now();
        if (submissionRepository.register(command, serializedEnvelope, now)) {
            outboxRepository.enqueueUserCommand(properties.getKafka().getUserCommandsTopic(),
                    "ACCOUNT_API_COMMAND", command, now);
        }
    }
}
