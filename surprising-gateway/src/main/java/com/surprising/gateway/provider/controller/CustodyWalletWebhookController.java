package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.CustodyWalletWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CustodyWalletWebhookController {

    private final CustodyWalletWebhookService service;

    public CustodyWalletWebhookController(CustodyWalletWebhookService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/internal/wallet/webhooks/custody")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestHeader("X-Custody-Event-Id") String eventId,
                        @RequestHeader("X-Custody-Event-Type") String eventType,
                        @RequestHeader("X-Custody-Timestamp") String timestamp,
                        @RequestHeader("X-Custody-Signature") String signature,
                        @RequestBody byte[] body) {
        try {
            service.handle(eventId, eventType, timestamp, signature, body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }
}
