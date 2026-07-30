package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountCommandSubmissionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountCommandSubmissionService {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountCommandSubmissionRepository submissionRepository;
    private final AccountOutboxService outboxService;

    public AccountCommandSubmissionService(ObjectMapper objectMapper,
                                           AccountProperties properties,
                                           AccountCommandSubmissionRepository submissionRepository,
                                           AccountOutboxService outboxService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.submissionRepository = submissionRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public void submit(AccountUserCommand command) {
        String serializedEnvelope = objectMapper.writeValueAsString(command);
        Instant now = Instant.now();
        if (submissionRepository.register(command, serializedEnvelope, now)) {
            outboxService.enqueueUserCommand(properties.getKafka().getUserCommandsTopic(),
                    "ACCOUNT_API_COMMAND", command, now);
        }
    }
}
