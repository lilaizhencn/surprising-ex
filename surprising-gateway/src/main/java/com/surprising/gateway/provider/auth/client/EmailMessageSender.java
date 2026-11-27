package com.surprising.gateway.provider.auth.client;

public interface EmailMessageSender {

    void send(String recipient, String subject, String text);
}
