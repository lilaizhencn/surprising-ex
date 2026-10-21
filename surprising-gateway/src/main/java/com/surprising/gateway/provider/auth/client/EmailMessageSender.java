package com.surprising.gateway.provider.auth.client;

public interface EmailMessageSender {

    public void send(String recipient, String subject, String text);
}
