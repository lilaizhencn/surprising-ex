package com.surprising.gateway.provider.auth;

public interface EmailMessageSender {

    void send(String recipient, String subject, String text);
}
